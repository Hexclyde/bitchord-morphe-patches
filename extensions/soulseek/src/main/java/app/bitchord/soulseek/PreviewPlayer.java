package app.bitchord.soulseek;

import android.content.Context;

import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;

/** Isolated Media3 preview. Every reflection signature is checked against the patched APK. */
final class PreviewPlayer {
    private final Object actual;
    final Player player;
    private final PreviewExtractors extractors;

    PreviewPlayer(Context context, SoulseekPreview.Session session) throws Exception {
        Class<?> builderType = Class.forName("androidx.media3.exoplayer.ExoPlayer$Builder");
        Object builder =
                builderType
                        .getConstructor(Context.class)
                        .newInstance(context.getApplicationContext());
        Class<?> renderersType = Class.forName("androidx.media3.exoplayer.DefaultRenderersFactory");
        Object renderers =
                renderersType
                        .getConstructor(Context.class)
                        .newInstance(context.getApplicationContext());
        java.lang.reflect.Field floatOutput = renderersType.getDeclaredField("enableFloatOutput");
        floatOutput.setAccessible(true);
        floatOutput.setBoolean(renderers, true);
        Class<?> supplierType = Class.forName("com.google.common.base.Supplier");
        Object supplier =
                java.lang.reflect.Proxy.newProxyInstance(
                        supplierType.getClassLoader(),
                        new Class<?>[] {supplierType},
                        (proxy, method, args) -> {
                            if (method.getName().equals("get")) return renderers;
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                            return "Streamseek renderers";
                        });
        builderType.getField("renderersFactorySupplier").set(builder, supplier);
        extractors = new PreviewExtractors(session);
        Object factory =
                Class.forName("androidx.media3.exoplayer.source.ProgressiveMediaSource$Factory")
                        .getConstructor(
                                DataSource.Factory.class,
                                Class.forName("androidx.media3.extractor.ExtractorsFactory"))
                        .newInstance(
                                (DataSource.Factory) () -> new PreviewDataSource(session),
                                extractors.factory());
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

    String format() {
        return extractors.description;
    }

    String error() {
        try {
            Object error = actual.getClass().getMethod("getPlayerError").invoke(actual);
            return error == null
                    ? ""
                    : "Preview could not play. The peer may be unavailable or the file"
                            + " unsupported.";
        } catch (Exception ignored) {
            return "";
        }
    }

    void close() {
        try {
            Class.forName("androidx.media3.exoplayer.ExoPlayer")
                    .getMethod("release")
                    .invoke(actual);
        } catch (Exception | LinkageError ignored) {
        }
    }
}
