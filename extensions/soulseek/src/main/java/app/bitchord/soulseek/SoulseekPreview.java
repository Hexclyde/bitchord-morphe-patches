package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, expiring preview capabilities. Ordinary Soulseek source URIs remain download-only. */
public final class SoulseekPreview {
  private static final String PREFS = "bitchord_streamseek_preview";
  static final String EXTRA = "streamseek.preview.token";
  private static final Map<String, Offer> offers = new LinkedHashMap<>();
  private static volatile Session active;

  private SoulseekPreview() {}

  /** Set only by the optional Morphe patch. */
  public static boolean installed() {
    return false;
  }

  public static boolean enabled(Context context) {
    return installed() && context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false);
  }

  public static void setEnabled(Context context, boolean on) throws IOException {
    if (!installed()) throw new IOException("Install Streamseek previews in Morphe first");
    if (!context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", on).commit())
      throw new IOException("Could not save preview setting");
    if (!on) stopAll();
  }

  public static boolean available(Context context) {
    return enabled(context) && DownloadSettings.enabled(context, DownloadSettings.SOULSEEK);
  }

  static boolean supported(SoulseekTrack track) {
    return "mp3".equals(track.advertisedExtension) || "flac".equals(track.advertisedExtension);
  }

  static void open(Activity activity, SoulseekTrack track, String session) throws IOException {
    if (!available(activity))
      throw new IOException("Enable Streamseek previews and Soulseek first");
    if (!supported(track)) throw new IOException("Preview currently supports MP3 and FLAC files");
    SoulseekBridge bridge = SoulseekBridge.get();
    if (!session.equals(bridge.sessionTag)) throw new IOException("Soulseek result expired");
    String token = UUID.randomUUID().toString();
    synchronized (offers) {
      prune();
      if (offers.size() >= 8) offers.remove(offers.keySet().iterator().next());
      offers.put(token, new Offer(track, session, SystemClock.elapsedRealtime() + 600_000));
    }
    activity.startActivity(
        new Intent(activity, SoulseekPreviewActivity.class).putExtra(EXTRA, token));
  }

  private static void prune() {
    long now = SystemClock.elapsedRealtime();
    offers.values().removeIf(o -> o.expires <= now);
  }

  static Offer offer(String token, Context context) throws IOException {
    if (!available(context)) throw new IOException("Streamseek previews are disabled");
    synchronized (offers) {
      prune();
      Offer offer = offers.get(token);
      if (offer == null || !offer.session.equals(SoulseekBridge.get().sessionTag))
        throw new IOException("Preview expired. Select the file again.");
      return offer;
    }
  }

  static void forget(String token) {
    synchronized (offers) {
      offers.remove(token);
    }
  }

  static final class Offer {
    final SoulseekTrack track;
    final String session;
    final long expires;

    Offer(SoulseekTrack track, String session, long expires) {
      this.track = track;
      this.session = session;
      this.expires = expires;
    }
  }

  static Session start(Context context, Offer offer) throws IOException {
    if (!available(context)) throw new IOException("Streamseek previews are disabled");
    BulkTransferService.ensureRunning(context);
    BulkTransferService.awaitRunning();
    synchronized (SoulseekPreview.class) {
      if (active != null && !active.closed.get())
        throw new IOException("Close the other preview first");
      active = new Session(context, offer, SoulseekBridge.get());
      return active;
    }
  }

  public static int activeCount() {
    Session s = active;
    return s != null && !s.closed.get() ? 1 : 0;
  }

  public static void stopAll() {
    Session s = active;
    if (s != null) s.close();
  }

  static final class Session implements Closeable {
    final Context context;
    final Offer offer;
    final SoulseekBridge bridge;
    final String uri;
    final ProgressiveReader hold;
    final AtomicBoolean closed = new AtomicBoolean();
    private final Set<ProgressiveReader> readers = new HashSet<>();
    final long expires = SystemClock.elapsedRealtime() + 300_000;

    Session(Context context, Offer offer, SoulseekBridge bridge) throws IOException {
      this.context = context.getApplicationContext();
      this.offer = offer;
      this.bridge = bridge;
      uri = "soulseek-preview://" + UUID.randomUUID() + "/audio." + offer.track.advertisedExtension;
      hold = bridge.holdPreview(offer.track, offer.session);
    }

    void check() throws IOException {
      if (closed.get() || !available(context)) throw new IOException("Preview stopped");
      if (!offer.session.equals(bridge.sessionTag))
        throw new IOException("Soulseek session changed");
      if (SystemClock.elapsedRealtime() > expires) throw new IOException("Preview session expired");
      if (Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("Preview cancelled");
    }

    synchronized ProgressiveReader reader(long position, long length) throws IOException {
      check();
      ProgressiveReader reader = bridge.openSelected(offer.track, offer.session, position, length);
      reader.cancellation =
          () -> {
            try {
              check();
            } catch (IOException e) {
              throw new PreviewStopped(e);
            }
          };
      readers.add(reader);
      return reader;
    }

    synchronized void release(ProgressiveReader reader) {
      readers.remove(reader);
      reader.close();
    }

    ProgressiveReader.Snapshot snapshot() throws IOException {
      check();
      return bridge.previewSnapshot(offer.track, offer.session);
    }

    boolean keep(boolean now) throws IOException {
      check();
      // Queue ownership is taken before preview ownership can be released.
      Closeable retained = bridge.openSelected(offer.track, offer.session, 0, 0);
      return SoulseekSelectedDownloads.enqueuePreview(
          context, offer.track, offer.session, now, retained);
    }

    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (this) {
        for (ProgressiveReader reader : readers) reader.close();
        readers.clear();
        hold.close();
      }
    }
  }

  static final class PreviewStopped extends RuntimeException {
    PreviewStopped(IOException cause) {
      super(cause);
    }
  }
}
