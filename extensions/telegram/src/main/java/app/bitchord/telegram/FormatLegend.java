package app.bitchord.telegram;

import com.music.bitchord.data.NerdStats;
import com.music.bitchord.data.model.Song;
import com.music.bitchord.data.sources.StreamFormat;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The small "FLAC 24bit" legend after a song's title. Telegram files are labelled from their own
 * header; anything else from the format BitChord was told when the stream was chosen. Lossy formats
 * get no legend.
 */
public final class FormatLegend {
    private FormatLegend() {}

    static volatile boolean enabled = true;
    static final String TELEGRAM = "src:" + NativeSource.ID + "::";

    /** Called after the title in BitChord's song title row. Never throws; "" means no legend. */
    public static String text(Song song) {
        try {
            if (!enabled || song == null) return "";
            String label = label(song.videoId);
            return label.isEmpty() ? "" : "  " + label;
        } catch (Throwable e) {
            return "";
        }
    }

    static String label(String id) {
        if (id == null || id.isEmpty()) return "";
        if (id.startsWith(TELEGRAM)) {
            TelegramBridge bridge = TelegramBridge.instance;
            JSONObject header =
                    bridge == null ? null : bridge.formats.get(id.substring(TELEGRAM.length()));
            if (header != null) return AudioHeader.label(header);
        }
        StreamFormat format = NerdStats.declaredFormat(id);
        return format == null ? "" : label(format.codec, format.bitDepth);
    }

    /** "FLAC 16bit" for a lossless codec, "" for anything else. */
    static String label(String codec, Integer bits) {
        if (codec == null) return "";
        String name;
        switch (codec.toLowerCase(Locale.ROOT)) {
            case "flac":
                name = "FLAC";
                break;
            case "alac":
                name = "ALAC";
                break;
            case "wav":
            case "wave":
            case "pcm":
                name = "WAV";
                break;
            case "aif":
            case "aiff":
                name = "AIFF";
                break;
            default:
                return "";
        }
        return bits != null && bits > 0 ? name + " " + bits + "bit" : name;
    }
}
