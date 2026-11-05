package app.bitchord.telegram;

import androidx.media3.common.*;

import java.lang.reflect.Proxy;

/** Controllable private-deck clock; the listening player remains a different object. */
class AutoMixTestDeck implements TransitionPreview.Deck {
    long position;
    boolean playing, closed, prepared, shuffled;
    int state = Player.STATE_READY, repeats, plays, releases;
    float volume;
    MediaItem item;
    PlaybackParameters parameters;
    PlaybackException error;
    TransitionEdit effects;
    int side;
    double progress;
    final Player player =
            (Player)
                    Proxy.newProxyInstance(
                            Player.class.getClassLoader(),
                            new Class<?>[] {Player.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "getCurrentPosition":
                                        return position;
                                    case "getCurrentMediaItem":
                                        return item;
                                    case "getMediaItemAt":
                                        return item;
                                    case "getMediaItemCount":
                                        return item == null ? 0 : 1;
                                    case "getCurrentMediaItemIndex":
                                        return 0;
                                    case "getNextMediaItemIndex":
                                        return -1;
                                    case "setMediaItem":
                                        item = (MediaItem) args[0];
                                        return null;
                                    case "setRepeatMode":
                                        repeats = (Integer) args[0];
                                        return null;
                                    case "setShuffleModeEnabled":
                                        shuffled = (Boolean) args[0];
                                        return null;
                                    case "seekTo":
                                        if ((Integer) args[0] != 0)
                                            throw new AssertionError(
                                                    "Audition must contain a single track");
                                        position = (Long) args[1];
                                        return null;
                                    case "getPlayWhenReady":
                                    case "isPlaying":
                                        return playing;
                                    case "getPlaybackState":
                                        return state;
                                    case "getPlayerError":
                                        return error;
                                    case "getVolume":
                                        return volume;
                                    case "setVolume":
                                        volume = (Float) args[0];
                                        return null;
                                    case "setPlaybackParameters":
                                        parameters = (PlaybackParameters) args[0];
                                        return null;
                                    case "getPlaybackParameters":
                                        return parameters;
                                    case "prepare":
                                        prepared = true;
                                        return null;
                                    case "play":
                                        playing = true;
                                        plays++;
                                        return null;
                                    case "pause":
                                        playing = false;
                                        return null;
                                    case "setPlayWhenReady":
                                        playing = (Boolean) args[0];
                                        return null;
                                    case "equals":
                                        return proxy == args[0];
                                    case "hashCode":
                                        return System.identityHashCode(proxy);
                                    case "toString":
                                        return "Private audition deck";
                                }
                                if (method.getReturnType() == boolean.class) return false;
                                if (method.getReturnType() == int.class) return 0;
                                if (method.getReturnType() == long.class) return 0L;
                                if (method.getReturnType() == float.class) return 0f;
                                return null;
                            });

    public Player player() {
        return player;
    }

    public void effects(TransitionEdit edit, int side, double progress) {
        effects = edit.copy();
        this.side = side;
        this.progress = progress;
    }

    float trim = 1f;
    final java.util.List<Float> trims = new java.util.ArrayList<>();

    public void trim(float gain) {
        trim = gain;
        trims.add(gain);
    }

    public void close() {
        if (!closed) {
            closed = true;
            playing = false;
            releases++;
        }
    }
}
