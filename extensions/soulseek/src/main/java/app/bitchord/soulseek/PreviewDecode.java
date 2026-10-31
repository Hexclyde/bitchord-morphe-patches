package app.bitchord.soulseek;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.IOException;
import java.nio.*;

/** Bounded independent decoding for comparison; playback EQ/pitch never enters the analysis. */
final class PreviewDecode {
  interface Check {
    void check() throws IOException;
  }

  static final class Pcm {
    final float[] samples;
    final int length, rate;

    Pcm(float[] samples, int length, int rate) {
      this.samples = samples;
      this.length = length;
      this.rate = rate;
    }
  }

  static Pcm local(Context context, Uri uri, Check cancellation) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(context, uri, null);
      return decode(extractor, 45, cancellation);
    } finally {
      extractor.release();
    }
  }

  static Pcm candidate(SoulseekPreview.Session session, Check cancellation) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    try (Source source = new Source(session)) {
      extractor.setDataSource(source);
      return decode(extractor, 25, cancellation);
    } finally {
      extractor.release();
    }
  }

  private static Pcm decode(MediaExtractor extractor, int seconds, Check cancellation)
      throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 90_000;
    MediaCodec codec = null;
    try {
      int track = -1;
      MediaFormat format = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat candidate = extractor.getTrackFormat(i);
        String mime = candidate.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          track = i;
          format = candidate;
          break;
        }
      }
      if (track < 0) throw new IOException("No decodable audio in this file");
      extractor.selectTrack(track);
      int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
          channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
          encoding = 2;
      validateFormat(rate, channels);
      float[] samples = new float[rate * seconds];
      int length = 0;
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
      codec.configure(format, null, null, 0);
      codec.start();
      boolean inputDone = false;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (length < samples.length) {
        cancellation.check();
        if (android.os.SystemClock.elapsedRealtime() > deadline)
          throw new IOException("Audio comparison timed out");
        if (!inputDone) {
          int in = codec.dequeueInputBuffer(10_000);
          if (in >= 0) {
            ByteBuffer buffer = codec.getInputBuffer(in);
            if (buffer == null) throw new IOException("Audio decoder input unavailable");
            int count = extractor.readSampleData(buffer, 0);
            if (count < 0) {
              codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              codec.queueInputBuffer(in, 0, count, extractor.getSampleTime(), 0);
              extractor.advance();
            }
          }
        }
        int out = codec.dequeueOutputBuffer(info, 10_000);
        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat next = codec.getOutputFormat();
          int nextRate = next.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = next.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          validateFormat(nextRate, channels);
          if (nextRate != rate) {
            if (length > 0) throw new IOException("Audio format changed during comparison");
            rate = nextRate;
            samples = new float[rate * seconds];
          }
          if (next.containsKey("pcm-encoding")) encoding = next.getInteger("pcm-encoding");
        } else if (out >= 0) {
          try {
            ByteBuffer buffer = codec.getOutputBuffer(out);
            if (buffer != null && info.size > 0) {
              ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
              pcm.position(info.offset);
              pcm.limit(info.offset + info.size);
              int width = bytesPerSample(encoding);
              if (width == 0) throw new IOException("Unsupported decoded PCM format");
              while (pcm.remaining() >= width * channels && length < samples.length) {
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += sample(pcm, encoding);
                samples[length++] = sum / channels;
              }
            }
          } finally {
            codec.releaseOutputBuffer(out, false);
          }
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
      }
      cancellation.check();
      if (length < (long) rate * 10) throw new IOException("Too little audio for comparison");
      return new Pcm(samples, length, rate);
    } finally {
      if (codec != null) {
        try {
          codec.stop();
        } catch (RuntimeException ignored) {
        } finally {
          codec.release();
        }
      }
    }
  }

  private static void validateFormat(int rate, int channels) throws IOException {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 8)
      throw new IOException("Audio format exceeds preview analysis limits");
  }

  static int bytesPerSample(int encoding) {
    switch (encoding) {
      case 2:
        return 2;
      case 3:
        return 1;
      case 4:
      case 0x30000000:
        return 4;
      case 0x20000000:
        return 3;
      default:
        return 0;
    }
  }

  static float sample(ByteBuffer pcm, int encoding) throws IOException {
    switch (encoding) {
      case 2:
        return pcm.getShort() / 32768f;
      case 3:
        return ((pcm.get() & 255) - 128) / 128f;
      case 4:
        float f = pcm.getFloat();
        return Float.isFinite(f) ? Math.max(-1f, Math.min(1f, f)) : 0;
      case 0x20000000:
        int value = (pcm.get() & 255) | ((pcm.get() & 255) << 8) | (pcm.get() << 16);
        return value / 8388608f;
      case 0x30000000:
        return (float) (pcm.getInt() / 2147483648.0);
      default:
        throw new IOException("Unsupported decoded PCM format");
    }
  }

  static final class Source extends MediaDataSource {
    final SoulseekPreview.Session session;
    ProgressiveReader reader;
    long position;

    Source(SoulseekPreview.Session session) {
      this.session = session;
    }

    @Override
    public synchronized int readAt(long at, byte[] buffer, int offset, int length)
        throws IOException {
      session.check();
      if (at >= getSize()) return -1;
      if (reader == null || position != at) {
        if (reader != null) session.release(reader);
        reader = session.reader(at, -1);
        position = at;
      }
      try {
        int count = reader.read(buffer, offset, length);
        if (count > 0) position += count;
        return count;
      } catch (SoulseekPreview.PreviewStopped stopped) {
        throw (IOException) stopped.getCause();
      }
    }

    @Override
    public long getSize() {
      return session.offer.track.size;
    }

    @Override
    public synchronized void close() {
      if (reader != null) session.release(reader);
      reader = null;
    }
  }
}
