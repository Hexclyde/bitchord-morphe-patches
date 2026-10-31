package app.bitchord.soulseek;

import android.content.Context;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;

/** Isolated Media3 preview. Every reflection signature is checked against the patched APK. */
final class PreviewPlayer {
  private final Object actual;
  final Player player;

  PreviewPlayer(Context context, SoulseekPreview.Session session) throws Exception {
    Class<?> builderType = Class.forName("androidx.media3.exoplayer.ExoPlayer$Builder");
    Object builder =
        builderType.getConstructor(Context.class).newInstance(context.getApplicationContext());
    Object extractors =
        Class.forName("androidx.media3.extractor.DefaultExtractorsFactory")
            .getConstructor()
            .newInstance();
    Object factory =
        Class.forName("androidx.media3.exoplayer.source.ProgressiveMediaSource$Factory")
            .getConstructor(
                DataSource.Factory.class,
                Class.forName("androidx.media3.extractor.ExtractorsFactory"))
            .newInstance((DataSource.Factory) () -> new PreviewDataSource(session), extractors);
    builderType
        .getMethod(
            "setMediaSourceFactory",
            Class.forName("androidx.media3.exoplayer.source.MediaSource$Factory"))
        .invoke(builder, factory);
    builderType.getField("handleAudioFocus").setBoolean(builder, true);
    builderType.getField("handleAudioBecomingNoisy").setBoolean(builder, true);
    actual = builderType.getMethod("build").invoke(builder);
    player = (Player) actual;
    try {
      Class<?> mediaItem = Class.forName("androidx.media3.common.MediaItem");
      Object item = mediaItem.getMethod("fromUri", String.class).invoke(null, session.uri);
      Player.class.getMethod("setMediaItem", mediaItem).invoke(player, item);
      player.prepare();
      play();
    } catch (Exception | LinkageError failed) {
      close();
      throw failed;
    }
  }

  void play() throws Exception {
    Player.class.getMethod("setPlayWhenReady", boolean.class).invoke(player, true);
  }

  String error() {
    try {
      Object error = actual.getClass().getMethod("getPlayerError").invoke(actual);
      return error == null
          ? ""
          : "Preview could not play. The peer may be unavailable or the file unsupported.";
    } catch (Exception ignored) {
      return "";
    }
  }

  void close() {
    try {
      Class.forName("androidx.media3.exoplayer.ExoPlayer").getMethod("release").invoke(actual);
    } catch (Exception | LinkageError ignored) {
    }
  }
}
