package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headphone correction: a parametric equaliser that flattens a headphone's own sound, from a
 * profile measured for that model. Profiles come from AutoEq by Jaakko Pasanen, which publishes
 * them for thousands of headphones, or can be pasted in the same text form from anywhere else. Each
 * output has its own profile, so wired earphones and a Bluetooth headset each get theirs.
 *
 * <p>The filters are the usual peaking and shelving biquads, after Robert Bristow-Johnson's Audio
 * EQ Cookbook. Prism by Yigder has headphone correction too; this one is written for BitChord.
 */
final class HeadphoneEq {
    private HeadphoneEq() {}

    static final String INDEX_URL =
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md";
    static final String RESULTS_URL =
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/";
    static final long INDEX_FRESH_MILLIS = 14L * 24 * 3600 * 1000;
    static final int MAX_FILTERS = 20;

    // ── One filter ───────────────────────────────────────────────────────────

    static final class Filter {
        static final int PEAK = 0, LOW_SHELF = 1, HIGH_SHELF = 2;
        final int kind;
        final double frequency, gainDb, q;

        Filter(int kind, double frequency, double gainDb, double q) {
            this.kind = kind;
            this.frequency = frequency;
            this.gainDb = gainDb;
            this.q = q;
        }
    }

    /** A parsed profile: a preamp and up to {@link #MAX_FILTERS} filters. */
    static final class Profile {
        final String name;
        final double preampDb;
        final List<Filter> filters;

        Profile(String name, double preampDb, List<Filter> filters) {
            this.name = name;
            this.preampDb = preampDb;
            this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
        }

        String summary() {
            return Strings.f(
                    "{0} filters, preamp {1} dB",
                    String.valueOf(filters.size()),
                    String.format(Locale.ROOT, "%.1f", preampDb).replace('-', '−'));
        }
    }

    private static final Pattern PREAMP = Pattern.compile("(?i)preamp\\s*:\\s*(-?[0-9.]+)\\s*dB");
    private static final Pattern FILTER =
            Pattern.compile(
                    "(?i)filter\\s*\\d*\\s*:\\s*(ON|OFF)\\s+(PK|LSC?|HSC?|PEQ|LS|HS)\\s+Fc\\s+(-?[0-9.]+)\\s*Hz\\s+Gain\\s+(-?[0-9.]+)\\s*dB(?:\\s+Q\\s+(-?[0-9.]+))?");

    /**
     * Reads the text form AutoEq and Equalizer APO use: a {@code Preamp:} line and {@code Filter N:
     * ON PK Fc 105 Hz Gain -3.2 dB Q 0.70} lines. Null when there's no filter in it.
     */
    static Profile parse(String name, String text) {
        if (text == null) return null;
        double preamp = 0;
        Matcher p = PREAMP.matcher(text);
        if (p.find()) preamp = number(p.group(1), 0);
        List<Filter> filters = new ArrayList<>();
        Matcher m = FILTER.matcher(text);
        while (m.find() && filters.size() < MAX_FILTERS) {
            if (!m.group(1).equalsIgnoreCase("ON")) continue;
            String type = m.group(2).toUpperCase(Locale.ROOT);
            int kind =
                    type.startsWith("LS")
                            ? Filter.LOW_SHELF
                            : type.startsWith("HS") ? Filter.HIGH_SHELF : Filter.PEAK;
            double fc = number(m.group(3), 0), gain = number(m.group(4), 0);
            double q = m.group(5) == null ? 0.707 : number(m.group(5), 0.707);
            if (fc < 10 || fc > 24_000 || Math.abs(gain) > 30 || q <= 0.05 || q > 30) continue;
            filters.add(new Filter(kind, fc, gain, q));
        }
        if (filters.isEmpty()) return null;
        if (preamp > 0) preamp = 0;
        if (preamp < -30) preamp = -30;
        return new Profile(name, preamp, filters);
    }

    private static double number(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ── The equaliser itself ─────────────────────────────────────────────────

    /** A cascade of biquads for one stream, at one sample rate and strength. */
    static final class Chain {
        final Profile profile;
        final int sampleRate;
        final float strength;
        final float preamp;
        // Coefficients per filter: b0 b1 b2 a1 a2.
        private final double[] c;
        // Two state values per filter per channel (direct form II transposed).
        private final double[] state;
        private final int channels;

        Chain(Profile profile, int sampleRate, float strength, int channels) {
            this.profile = profile;
            this.sampleRate = Math.max(8_000, sampleRate);
            this.strength = Math.max(0f, Math.min(1f, strength));
            this.channels = Math.max(1, channels);
            int n = profile.filters.size();
            c = new double[n * 5];
            state = new double[n * this.channels * 2];
            for (int i = 0; i < n; i++) coefficients(profile.filters.get(i), i * 5);
            preamp = (float) Math.pow(10, profile.preampDb * this.strength / 20);
        }

        private void coefficients(Filter f, int at) {
            double a = Math.pow(10, f.gainDb * strength / 40);
            double w0 = 2 * Math.PI * Math.min(f.frequency, sampleRate * 0.49) / sampleRate;
            double cos = Math.cos(w0), sin = Math.sin(w0);
            double b0, b1, b2, a0, a1, a2;
            if (f.kind == Filter.PEAK) {
                double alpha = sin / (2 * f.q);
                b0 = 1 + alpha * a;
                b1 = -2 * cos;
                b2 = 1 - alpha * a;
                a0 = 1 + alpha / a;
                a1 = -2 * cos;
                a2 = 1 - alpha / a;
            } else {
                double alpha = sin / 2 * Math.sqrt((a + 1 / a) * (1 / f.q - 1) + 2);
                double root = 2 * Math.sqrt(a) * alpha;
                if (f.kind == Filter.LOW_SHELF) {
                    b0 = a * ((a + 1) - (a - 1) * cos + root);
                    b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                    b2 = a * ((a + 1) - (a - 1) * cos - root);
                    a0 = (a + 1) + (a - 1) * cos + root;
                    a1 = -2 * ((a - 1) + (a + 1) * cos);
                    a2 = (a + 1) + (a - 1) * cos - root;
                } else {
                    b0 = a * ((a + 1) + (a - 1) * cos + root);
                    b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                    b2 = a * ((a + 1) + (a - 1) * cos - root);
                    a0 = (a + 1) - (a - 1) * cos + root;
                    a1 = 2 * ((a - 1) - (a + 1) * cos);
                    a2 = (a + 1) - (a - 1) * cos - root;
                }
            }
            c[at] = b0 / a0;
            c[at + 1] = b1 / a0;
            c[at + 2] = b2 / a0;
            c[at + 3] = a1 / a0;
            c[at + 4] = a2 / a0;
        }

        /** Filters {@code count} interleaved samples in place. */
        void process(float[] samples, int count) {
            int n = profile.filters.size();
            int frames = count / channels;
            for (int ch = 0; ch < channels; ch++) {
                for (int f = 0; f < n; f++) {
                    int k = f * 5, s = (f * channels + ch) * 2;
                    double b0 = c[k], b1 = c[k + 1], b2 = c[k + 2], a1 = c[k + 3], a2 = c[k + 4];
                    double z1 = state[s], z2 = state[s + 1];
                    for (int i = ch; i < frames * channels; i += channels) {
                        double x = samples[i];
                        double y = b0 * x + z1;
                        z1 = b1 * x - a1 * y + z2;
                        z2 = b2 * x - a2 * y;
                        samples[i] = (float) y;
                    }
                    state[s] = z1;
                    state[s + 1] = z2;
                }
            }
            if (preamp != 1f) for (int i = 0; i < frames * channels; i++) samples[i] *= preamp;
        }

        void reset() {
            java.util.Arrays.fill(state, 0);
        }

        /** The chain's gain at {@code hz} in dB, for tests and the page's curve. */
        double gainDb(double hz) {
            double w = 2 * Math.PI * hz / sampleRate;
            double re = 1, im = 0;
            for (int f = 0; f < profile.filters.size(); f++) {
                int k = f * 5;
                // H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2) at z = e^{jw}
                double nr = c[k] + c[k + 1] * Math.cos(w) + c[k + 2] * Math.cos(2 * w);
                double ni = -c[k + 1] * Math.sin(w) - c[k + 2] * Math.sin(2 * w);
                double dr = 1 + c[k + 3] * Math.cos(w) + c[k + 4] * Math.cos(2 * w);
                double di = -c[k + 3] * Math.sin(w) - c[k + 4] * Math.sin(2 * w);
                double d = dr * dr + di * di;
                double r = (nr * dr + ni * di) / d, i = (ni * dr - nr * di) / d;
                double nre = re * r - im * i, nim = re * i + im * r;
                re = nre;
                im = nim;
            }
            return 20 * Math.log10(Math.hypot(re, im) * preamp);
        }
    }

    // ── Settings: a profile per output ───────────────────────────────────────

    static final String PREFERENCES = "bitchord_headphone_eq";

    private static volatile SharedPreferences preferences;
    private static volatile File folder;
    static volatile int strength = 100;

    /** The page's switch; off leaves the sound alone while keeping the profiles. */
    static volatile boolean enabled = false;

    /** The profile for the current output, or null; replaced whole, never edited. */
    static volatile Profile active;

    private static volatile String activeOutput = "";

    static void init(Context context) {
        if (preferences != null || context == null) return;
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        folder = new File(app.getNoBackupFilesDir(), "headphones");
        strength = Math.max(0, Math.min(100, preferences.getInt("strength", 100)));
        enabled = preferences.getBoolean("enabled", false);
        Outputs.listen(HeadphoneEq::outputChanged);
        outputChanged();
    }

    static void setEnabled(boolean on) {
        enabled = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("enabled", on).apply();
        chains.clear();
    }

    static void setStrength(int percent) {
        strength = Math.max(0, Math.min(100, percent));
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putInt("strength", strength).apply();
        chains.clear();
    }

    /** The saved profile for an output: {@code name} and {@code file} keys, or null. */
    static String assignedName(String outputKey) {
        SharedPreferences saved = preferences;
        return saved == null ? null : saved.getString("name." + outputKey, null);
    }

    /** Uses {@code profile} whenever {@code outputKey} is the output; saved to disk. */
    static void assign(String outputKey, Profile profile, String text) {
        SharedPreferences saved = preferences;
        File dir = folder;
        if (saved == null || dir == null) return;
        try {
            dir.mkdirs();
            File file = new File(dir, fileName(outputKey));
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            saved.edit().putString("name." + outputKey, profile.name).apply();
            HostLog.d("Headphone correction: " + profile.name + " for " + outputKey);
        } catch (IOException e) {
            HostLog.d("Headphone correction couldn't save the profile: " + e);
        }
        outputChanged();
    }

    static void clear(String outputKey) {
        SharedPreferences saved = preferences;
        if (saved == null) return;
        saved.edit().remove("name." + outputKey).apply();
        File dir = folder;
        if (dir != null) new File(dir, fileName(outputKey)).delete();
        outputChanged();
    }

    private static String fileName(String outputKey) {
        return Integer.toHexString(outputKey.hashCode()) + ".txt";
    }

    /** The output changed: load its profile, or none. */
    static void outputChanged() {
        Outputs.Output output = Outputs.current();
        SharedPreferences saved = preferences;
        File dir = folder;
        if (saved == null || dir == null) return;
        String name = saved.getString("name." + output.key, null);
        Profile next = null;
        if (name != null)
            try {
                File file = new File(dir, fileName(output.key));
                next =
                        parse(
                                name,
                                new String(
                                        Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (IOException e) {
                next = null;
            }
        Profile before = active;
        active = next;
        activeOutput = output.key;
        if (before != next) chains.clear();
    }

    /** Whether correction runs on the current output. */
    static boolean active() {
        return enabled && active != null && strength > 0;
    }

    // ── Processing ───────────────────────────────────────────────────────────

    private static final java.util.Map<Object, Chain> chains =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Filters a block in place; nothing happens without a profile for this output. */
    static void process(Object key, float[] samples, int count, int channels, int sampleRate) {
        Profile profile = active;
        if (!enabled || profile == null || strength <= 0 || channels < 1 || channels > 2) return;
        Chain chain = chains.get(key);
        if (chain == null
                || chain.profile != profile
                || chain.sampleRate != Math.max(8_000, sampleRate)
                || chain.strength != strength / 100f
                || chain.channels != channels) {
            chain = new Chain(profile, sampleRate, strength / 100f, channels);
            chains.put(key, chain);
        }
        chain.process(samples, count);
    }

    static void flush(Object key) {
        Chain chain = chains.get(key);
        if (chain != null) chain.reset();
    }

    // ── AutoEq's index ───────────────────────────────────────────────────────

    /** One headphone in AutoEq's index. */
    static final class Entry {
        final String name, path, source;

        Entry(String name, String path, String source) {
            this.name = name;
            this.path = path;
            this.source = source;
        }

        String label() {
            return source.isEmpty() ? name : name + " · " + source;
        }
    }

    private static final Pattern INDEX_LINE =
            Pattern.compile("^- \\[(.+?)\\]\\(\\./(.+?)\\)(?: by (.+?))?(?: on .*)?$");
    private static final String[] PREFERRED_SOURCES = {
        "oratory1990", "crinacle", "Rtings", "HypetheSonics", "Innerfidelity", "Headphone.com"
    };

    /** Reads AutoEq's INDEX.md into entries. Pure, for tests. */
    static List<Entry> parseIndex(String markdown) {
        List<Entry> out = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            Matcher m = INDEX_LINE.matcher(line.trim());
            if (!m.matches()) continue;
            String source = m.group(3) == null ? "" : m.group(3).trim();
            out.add(new Entry(m.group(1).trim(), m.group(2).trim(), source));
        }
        return out;
    }

    private static volatile List<Entry> index;

    /** The index, from disk or the network; empty when neither has it. Not for the main thread. */
    static List<Entry> index(boolean refresh) throws IOException {
        List<Entry> have = index;
        if (have != null && !refresh) return have;
        File dir = folder;
        if (dir == null) return Collections.emptyList();
        File file = new File(dir, "index.md");
        boolean stale =
                !file.isFile()
                        || System.currentTimeMillis() - file.lastModified() > INDEX_FRESH_MILLIS;
        if (stale || refresh) {
            String text = fetch(INDEX_URL);
            List<Entry> fresh = parseIndex(text);
            if (fresh.size() > 100) {
                dir.mkdirs();
                Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
                index = fresh;
                return fresh;
            }
            if (!file.isFile()) throw new IOException("AutoEq's index couldn't be read");
        }
        List<Entry> loaded =
                parseIndex(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        index = loaded;
        return loaded;
    }

    /** Entries whose name has every word of {@code query}, best-measured sources first. */
    static List<Entry> search(List<Entry> entries, String query, int limit) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return Collections.emptyList();
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries) {
            String name = entry.name.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) all &= name.contains(word);
            if (all) found.add(entry);
        }
        found.sort(
                (a, b) -> {
                    int ra = rank(a.source), rb = rank(b.source);
                    if (ra != rb) return Integer.compare(ra, rb);
                    if (a.name.length() != b.name.length())
                        return Integer.compare(a.name.length(), b.name.length());
                    return a.name.compareToIgnoreCase(b.name);
                });
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private static int rank(String source) {
        for (int i = 0; i < PREFERRED_SOURCES.length; i++)
            if (PREFERRED_SOURCES[i].equalsIgnoreCase(source)) return i;
        return PREFERRED_SOURCES.length;
    }

    /** Where an entry's parametric EQ file is. */
    static String profileUrl(Entry entry) {
        return RESULTS_URL + entry.path + "/" + encode(entry.name + " ParametricEQ.txt");
    }

    static String encode(String part) {
        try {
            return URLEncoder.encode(part, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return part.replace(" ", "%20");
        }
    }

    /** Downloads an entry's profile text. Not for the main thread. */
    static String download(Entry entry) throws IOException {
        return fetch(profileUrl(entry));
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "BitChord patches");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = connection.getInputStream();
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[16_384];
                int read;
                while ((read = reader.read(buffer)) > 0) {
                    text.append(buffer, 0, read);
                    if (text.length() > 4_000_000) throw new IOException("Too large: " + url);
                }
                return text.toString();
            }
        } finally {
            connection.disconnect();
        }
    }
}
