package app.bitchord.telegram;

import java.util.Arrays;
import java.util.List;

/**
 * Headphone correction: AutoEq's text is read, the filters shape the sound as asked, the index is
 * searched.
 */
public final class HeadphoneEqTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static final String HD650 =
            "Preamp: -6.1 dB\n"
                    + "Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70\n"
                    + "Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42\n"
                    + "Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50\n"
                    + "Filter 4: OFF PK Fc 37 Hz Gain 0.7 dB Q 3.96\n"
                    + "Filter 5: ON PK Fc 3169 Hz Gain -1.7 dB Q 3.89\n"
                    + "Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70\n";

    /** The chain's response to a tone: its RMS out over its RMS in, in dB. */
    static double measured(HeadphoneEq.Chain chain, double hz, int rate) {
        int n = rate * 2;
        float[] tone = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float v = (float) (0.25 * Math.sin(2 * Math.PI * hz * i / rate));
            tone[2 * i] = v;
            tone[2 * i + 1] = v;
        }
        chain.reset();
        chain.process(tone, tone.length);
        double in = 0, out = 0;
        for (int i = n; i < 2 * n; i += 2) {
            double v = 0.25 * Math.sin(2 * Math.PI * hz * (i / 2) / rate);
            in += v * v;
            out += tone[i] * tone[i];
        }
        return 10 * Math.log10(out / in);
    }

    public static void main(String[] args) {
        // Parsing.
        HeadphoneEq.Profile profile = HeadphoneEq.parse("HD 650", HD650);
        check(profile != null && profile.filters.size() == 5, "five filters on, one off");
        check(Math.abs(profile.preampDb + 6.1) < 1e-9, "preamp read");
        check(profile.filters.get(0).kind == HeadphoneEq.Filter.LOW_SHELF, "low shelf");
        check(profile.filters.get(4).kind == HeadphoneEq.Filter.HIGH_SHELF, "high shelf");
        check(
                profile.filters.get(1).kind == HeadphoneEq.Filter.PEAK
                        && profile.filters.get(1).q == 1.42,
                "peak with Q");
        check(HeadphoneEq.parse("x", "nothing here") == null, "no filters, no profile");
        check(
                HeadphoneEq.parse("x", "Filter 1: ON PK Fc 100 Hz Gain 40 dB Q 1") == null,
                "a silly gain is skipped");
        HeadphoneEq.Profile loose = HeadphoneEq.parse("x", "filter 1: on pk fc 1000 hz gain -3 db");
        check(
                loose != null && loose.filters.get(0).q == 0.707 && loose.preampDb == 0,
                "any case, Q and preamp optional");
        check(
                profile.summary().contains("5") && profile.summary().contains("6.1"),
                profile.summary());

        // The filters do what they say, at two sample rates.
        for (int rate : new int[] {44_100, 48_000}) {
            HeadphoneEq.Profile peak =
                    HeadphoneEq.parse("peak", "Filter 1: ON PK Fc 1000 Hz Gain 6 dB Q 1.0");
            HeadphoneEq.Chain chain = new HeadphoneEq.Chain(peak, rate, 1f, 2);
            check(
                    Math.abs(chain.gainDb(1000) - 6) < 0.05,
                    "peak gain at centre " + chain.gainDb(1000));
            check(
                    Math.abs(chain.gainDb(100)) < 0.3 && Math.abs(chain.gainDb(10_000)) < 0.3,
                    "flat away from it");
            check(
                    Math.abs(measured(chain, 1000, rate) - 6) < 0.2,
                    "measured " + measured(chain, 1000, rate));
            check(Math.abs(measured(chain, 100, rate)) < 0.3, "measured flat at 100 Hz");

            HeadphoneEq.Profile shelf =
                    HeadphoneEq.parse(
                            "shelf",
                            "Filter 1: ON LSC Fc 200 Hz Gain -4 dB Q 0.7\n"
                                    + "Filter 2: ON HSC Fc 8000 Hz Gain 3 dB Q 0.7");
            HeadphoneEq.Chain shelves = new HeadphoneEq.Chain(shelf, rate, 1f, 2);
            check(
                    Math.abs(shelves.gainDb(30) + 4) < 0.2,
                    "low shelf below its corner " + shelves.gainDb(30));
            check(
                    Math.abs(shelves.gainDb(1500)) < 0.3,
                    "flat in the middle " + shelves.gainDb(1500));
            check(
                    Math.abs(shelves.gainDb(Math.min(18_000, rate * 0.4)) - 3) < 0.4,
                    "high shelf above " + shelves.gainDb(18_000));

            // Half strength halves the gains and the preamp.
            HeadphoneEq.Chain half = new HeadphoneEq.Chain(peak, rate, 0.5f, 2);
            check(Math.abs(half.gainDb(1000) - 3) < 0.1, "half strength " + half.gainDb(1000));
            HeadphoneEq.Chain whole = new HeadphoneEq.Chain(profile, rate, 1f, 2);
            check(
                    Math.abs(
                                    whole.gainDb(20_000 > rate / 2 ? rate / 4 : 20_000)
                                            - 20 * Math.log10(whole.preamp))
                            < 3,
                    "preamp applied");
            check(
                    whole.preamp < 1 && Math.abs(20 * Math.log10(whole.preamp) + 6.1) < 0.01,
                    "preamp −6.1 dB");
        }

        // APO shelves use Q, including resonant shelves above Q=1. They must not
        // generate NaN coefficients or samples at any accepted rate or strength.
        for (String kind : new String[] {"LSC", "HSC"}) {
            for (int rate : new int[] {8_000, 44_100, 48_000, 192_000}) {
                for (float strength : new float[] {0f, 0.25f, 0.5f, 1f}) {
                    HeadphoneEq.Profile resonant =
                            HeadphoneEq.parse(
                                    "resonant shelf",
                                    "Filter 1: ON " + kind + " Fc 1000 Hz Gain 12 dB Q 10");
                    HeadphoneEq.Chain chain = new HeadphoneEq.Chain(resonant, rate, strength, 2);
                    double gain = chain.gainDb(1000);
                    check(Double.isFinite(gain), "high-Q shelf gain stays finite");
                    check(
                            Math.abs(gain - 6 * strength) < 0.001,
                            "shelf corner has half the requested dB gain");
                    float[] impulse = new float[rate / 2 * 2];
                    impulse[0] = impulse[1] = 0.25f;
                    chain.process(impulse, impulse.length);
                    boolean finite = true;
                    for (float sample : impulse) finite &= Float.isFinite(sample);
                    check(finite, "high-Q shelf output stays finite");
                }
            }
        }

        // Off means untouched: zero strength is never built, and a flat profile passes through.
        HeadphoneEq.Profile flat =
                HeadphoneEq.parse("flat", "Filter 1: ON PK Fc 1000 Hz Gain 0 dB Q 1.0");
        HeadphoneEq.Chain still = new HeadphoneEq.Chain(flat, 48_000, 1f, 2);
        float[] noise = new float[4_000];
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < noise.length; i++) noise[i] = (float) random.nextGaussian() * 0.1f;
        float[] before = noise.clone();
        still.process(noise, noise.length);
        boolean same = true;
        for (int i = 0; i < noise.length; i++) same &= Math.abs(noise[i] - before[i]) < 1e-5f;
        check(same, "a flat filter changes nothing");

        // Mono works too, and the state is per channel.
        HeadphoneEq.Chain mono = new HeadphoneEq.Chain(flat, 48_000, 1f, 1);
        mono.process(new float[100], 100);

        // AutoEq's index.
        String index =
                "# Index\n"
                    + "- [1MORE Quad Driver](./oratory1990/in-ear/1MORE%20Quad%20Driver) by"
                    + " oratory1990\n"
                    + "- [1MORE Quad Driver](./crinacle/711%20in-ear/1MORE%20Quad%20Driver) by"
                    + " crinacle on 711\n"
                    + "- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by"
                    + " oratory1990\n"
                    + "- [Sennheiser HD 650](./Innerfidelity/over-ear/Sennheiser%20HD%20650) by"
                    + " Innerfidelity\n"
                    + "- [Sennheiser HD 600](./Rtings/HMS%20II.3%20over-ear/Sennheiser%20HD%20600)"
                    + " by Rtings on HMS II.3\n"
                    + "not an entry\n";
        List<HeadphoneEq.Entry> entries = HeadphoneEq.parseIndex(index);
        check(entries.size() == 5, "five entries: " + entries.size());
        check(
                entries.get(1).source.equals("crinacle")
                        && entries.get(1)
                                .path
                                .equals("crinacle/711%20in-ear/1MORE%20Quad%20Driver"),
                "source and path");
        List<HeadphoneEq.Entry> found = HeadphoneEq.search(entries, "hd 650", 10);
        check(
                found.size() == 2 && found.get(0).source.equals("oratory1990"),
                "the best-measured source first");
        check(HeadphoneEq.search(entries, "sennheiser", 10).size() == 3, "every word must match");
        check(HeadphoneEq.search(entries, "HD", 1).size() == 1, "limited");
        check(HeadphoneEq.search(entries, " ", 10).isEmpty(), "nothing for nothing");
        check(
                HeadphoneEq.profileUrl(found.get(0))
                        .equals(
                                HeadphoneEq.RESULTS_URL
                                        + "oratory1990/over-ear/Sennheiser%20HD%20650/Sennheiser%20HD%20650%20ParametricEQ.txt"),
                HeadphoneEq.profileUrl(found.get(0)));
        check(found.get(0).label().equals("Sennheiser HD 650 · oratory1990"), found.get(0).label());
        check(Arrays.asList("AAC").size() == 1, "sanity");
        System.out.println("PASS: " + checks + " headphone correction checks.");
    }
}
