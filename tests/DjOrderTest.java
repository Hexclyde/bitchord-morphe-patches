package app.bitchord.telegram;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class DjOrderTest {
    static int checks;

    static DjMode.Info song(String key, double bpm) {
        return new DjMode.Info(key, bpm, "test");
    }

    static List<Integer> identity(int size) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) out.add(i);
        return out;
    }

    static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        DjMode.Info now = song("8A", 120);
        List<DjMode.Info> songs =
                Arrays.asList(song("2B", 160), song("9A", 122), song("8A", 121), song("10A", 124));
        List<Boolean> free = Collections.nCopies(songs.size(), false);
        List<Integer> ordered = DjOrder.order(now, songs, free, DjOrder.STEADY);
        check(
                ordered.equals(Arrays.asList(2, 1, 3, 0)),
                "Whole queue follows adjacent measured keys and tempo");
        List<DjMode.Info> direction =
                Arrays.asList(song("8A", 118), song("8A", 122), song("8A", 124));
        check(
                DjOrder.order(now, direction, Collections.nCopies(3, false), DjOrder.BUILD_UP)
                                .get(0)
                        == 1,
                "Build up chooses a nearby higher tempo");
        check(
                DjOrder.order(now, direction, Collections.nCopies(3, false), DjOrder.WIND_DOWN)
                                .get(0)
                        == 0,
                "Wind down chooses a nearby lower tempo");
        List<DjMode.Info> unknown = Arrays.asList(null, song("9A", 122), null, song("8A", 121));
        ordered = DjOrder.order(now, unknown, Collections.nCopies(4, false), DjOrder.STEADY);
        check(
                ordered.equals(Arrays.asList(3, 1, 0, 2)),
                "Unknown measurements stay stable without invented keys");
        ordered =
                DjOrder.order(now, songs, Arrays.asList(false, true, false, false), DjOrder.STEADY);
        check(ordered.get(1) == 1, "Explicit Play next anchor keeps its occurrence");
        ordered =
                DjOrder.order(
                        now,
                        Arrays.asList(now, now, now),
                        Collections.nCopies(3, false),
                        DjOrder.STEADY);
        check(
                ordered.equals(Arrays.asList(0, 1, 2)) && new HashSet<>(ordered).size() == 3,
                "Duplicate tracks preserve every occurrence and stable ties");
        check(
                DjOrder.order(
                                null,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                DjOrder.STEADY)
                        .isEmpty(),
                "Empty queue is valid");
        check(
                DjOrder.blend(now, song("9A", 122), 8, 30) > 8,
                "Adjacent measured keys receive a longer blend");
        check(
                DjOrder.blend(now, song("2B", 122), 8, 30) == 8,
                "Incompatible keys keep the normal blend");
        check(DjOrder.blend(now, null, 8, 30) == 8, "Unknown key does not trigger a long blend");
        check(
                DjOrder.blend(now, song("9A", 122), 8, 9) <= 9,
                "Long blends fit the real available audio");
        check(
                DjOrder.blend(now, song("9A", 180), 8, 30) == 8,
                "Large tempo mismatch does not trigger a long blend");
        List<Boolean> anchors = Arrays.asList(false, false, true, false, false, true);
        List<DjMode.Info> tail =
                Arrays.asList(
                        song("2B", 160),
                        song("9A", 122),
                        song("8A", 120),
                        song("8A", 121),
                        song("3B", 90),
                        song("8A", 119));
        ordered = DjOrder.order(now, tail, anchors, DjOrder.STEADY);
        check(ordered.get(2) == 2 && ordered.get(5) == 5, "Anchors keep their index");
        check(
                new HashSet<>(ordered.subList(0, 2)).equals(new HashSet<>(Arrays.asList(0, 1))),
                "Nothing crosses the first anchor");
        check(
                new HashSet<>(ordered.subList(3, 5)).equals(new HashSet<>(Arrays.asList(3, 4))),
                "Nothing crosses the second anchor");
        List<Boolean> adjacent = Arrays.asList(true, true, false, true);
        ordered = DjOrder.order(now, tail.subList(0, 4), adjacent, DjOrder.WIND_DOWN);
        check(
                ordered.equals(Arrays.asList(0, 1, 2, 3)),
                "Adjacent anchors and a lone song between them stay put");
        ordered =
                DjOrder.order(
                        null,
                        Arrays.asList(song("9A", 122), null, song("8A", 121)),
                        Collections.nCopies(3, false),
                        DjOrder.STEADY);
        check(
                ordered.equals(Arrays.asList(0, 2, 1)),
                "Unknown current song keeps measured songs first and in order");
        java.util.Random random = new java.util.Random(19);
        String[] keys = {"1A", "2B", "8A", "8B", "9A", "12B", null};
        for (int round = 0; round < 300; round++) {
            int size = random.nextInt(25);
            List<DjMode.Info> queue = new java.util.ArrayList<>();
            List<Boolean> pins = new java.util.ArrayList<>();
            for (int i = 0; i < size; i++) {
                queue.add(
                        random.nextInt(5) == 0
                                ? null
                                : song(
                                        keys[random.nextInt(keys.length)],
                                        random.nextInt(4) == 0 ? 0 : 60 + random.nextInt(120)));
                pins.add(random.nextInt(6) == 0);
            }
            DjMode.Info start =
                    random.nextBoolean()
                            ? null
                            : song(keys[random.nextInt(keys.length - 1)], 100 + random.nextInt(60));
            int mode = random.nextInt(3);
            List<Integer> result = DjOrder.order(start, queue, pins, mode);
            List<Integer> sorted = new java.util.ArrayList<>(result);
            Collections.sort(sorted);
            boolean permutation = sorted.size() == size;
            for (int i = 0; i < size && permutation; i++) permutation = sorted.get(i) == i;
            check(permutation, "Every occurrence appears exactly once");
            boolean anchored = true;
            for (int i = 0, from = 0; i < size; i++) {
                int to = i;
                while (to < size && !pins.get(to)) to++;
                if (pins.get(i)) {
                    anchored &= result.get(i) == i;
                    from = i + 1;
                } else anchored &= result.get(i) >= from && result.get(i) < to;
            }
            check(anchored, "Anchors stay and free songs stay between their anchors");
            List<DjMode.Info> again = new java.util.ArrayList<>();
            for (int at : result) again.add(queue.get(at));
            List<Integer> second = DjOrder.order(start, again, pins, mode);
            check(second.equals(identity(size)), "An ordered queue is not reordered");
        }
        check(
                DjOrder.blend(song("8A", 120), song("8A", 120), 8, 30) == 16,
                "Thirty-two beats at 120 BPM last sixteen seconds");
        check(
                DjOrder.blend(song("8A", 90), song("8A", 90), 8, 30) == 32 * 60 / 90.0,
                "A slower tempo gives a longer blend");
        check(
                DjOrder.blend(song("8A", 120), song("8A", 120), 8, 12) == 12,
                "The available audio caps the blend");
        check(
                DjOrder.blend(song("8A", 120), song("8A", 120), 20, 30) == 20,
                "A longer normal blend is never shortened");
        check(
                DjOrder.blend(song("8A", 120), song("8A", 120), 8, 5) <= 8,
                "No room means no more than the normal blend");
        check(
                DjOrder.blend(song("8A", 120), song("8A", 60), 8, 30) == 16,
                "Half-time counts as the same tempo");
        check(
                DjOrder.blend(song("8A", 120), song("8A", 240), 8, 30) == 16,
                "Double-time counts as the same tempo");
        check(
                DjOrder.blend(song("8A", 120), song("8A", 124), 8, 30) == 8,
                "A tempo gap past four percent keeps the normal blend");
        check(
                DjOrder.blend(song("8A", 120), song("9B", 120), 8, 30) == 8,
                "A neighbour in the other mode keeps the normal blend");
        check(
                DjOrder.blend(song("8A", 0), song("8A", 120), 8, 30) == 8,
                "A missing tempo keeps the normal blend");
        check(
                DjOrder.blend(song("8A", Double.NaN), song("8A", 120), 8, 30) == 8,
                "An unusable tempo keeps the normal blend");
        try {
            DjOrder.order(now, songs, Collections.nCopies(songs.size() - 1, false), DjOrder.STEADY);
            check(false, "Mismatched anchors are refused");
        } catch (IllegalArgumentException expected) {
            check(true, "Mismatched anchors are refused");
        }
        System.out.println("PASS: " + checks + " DJ journey ordering and blend checks");
    }
}
