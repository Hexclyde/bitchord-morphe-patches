package app.bitchord.telegram;

public final class TelegramMatchTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    private static com.music.bitchord.data.model.Song song(String id, String title, String length)
            throws Exception {
        return (com.music.bitchord.data.model.Song)
                MixedSongCodec.decode(
                        new org.json.JSONObject()
                                .put("videoId", id)
                                .put("title", title)
                                .put("artist", "Artist")
                                .put("durationText", length));
    }

    private static void heldBack() throws Exception {
        java.lang.reflect.Constructor<com.music.bitchord.data.sources.TrackMatcher.Target> make =
                com.music.bitchord.data.sources.TrackMatcher.Target.class.getDeclaredConstructor();
        make.setAccessible(true);
        com.music.bitchord.data.sources.TrackMatcher.Target target = make.newInstance();
        java.lang.reflect.Field title = target.getClass().getField("title");
        title.setAccessible(true);
        title.set(target, "Numb");
        java.lang.reflect.Field seconds = target.getClass().getField("durationSec");
        seconds.setAccessible(true);
        seconds.set(target, 187);
        java.util.List<com.music.bitchord.data.model.Song> all =
                java.util.Arrays.asList(
                        song("src:other::1", "Numb", "3:07"), song("dxp9w9Ggehc", "Numb", "3:07"));
        java.util.List<String> asked = new java.util.ArrayList<>();
        TelegramMatch.held =
                (key, id) -> {
                    asked.add(key);
                    return id.startsWith("src:");
                };
        try {
            java.util.List<com.music.bitchord.data.model.Song> kept =
                    TelegramMatch.filter(all, target);
            check(
                    kept.size() == 1 && kept.get(0).videoId.equals("dxp9w9Ggehc"),
                    "A held candidate sits the search out, and only it");
            check(asked.get(0).equals("Numb|187"), "Asked by song: " + asked);
        } finally {
            TelegramMatch.held = null;
        }
        check(TelegramMatch.filter(all, target).size() == 2, "Nothing held, nothing dropped");
        TelegramMatch.held = (key, id) -> true;
        try {
            java.util.List<com.music.bitchord.data.model.Song> fallback =
                    TelegramMatch.filter(all, target);
            check(
                    fallback.size() == 1 && fallback.get(0).videoId.equals("dxp9w9Ggehc"),
                    "Preparation holds can never remove the YouTube fallback");
        } finally {
            TelegramMatch.held = null;
        }
    }

    public static void main(String[] args) throws Exception {
        heldBack();
        check(
                !TelegramMatch.accepts("L-O-V-E", 156, "How I'd Love To Love You", 139),
                "A title containing the word isn't the song (the reported mix-up)");
        check(
                !TelegramMatch.accepts("L-O-V-E", 156, "How I'd Love To Love You", 156),
                "Not even at the same length");
        check(TelegramMatch.accepts("L-O-V-E", 156, "L-O-V-E", 158), "Same title");
        check(TelegramMatch.accepts("L-O-V-E", 156, "LOVE", 156), "Same letters");
        check(
                TelegramMatch.accepts("Crawling", 209, "Crawling (Remastered 2020)", 209),
                "Brackets aside, same title");
        check(TelegramMatch.accepts("Crawling", 209, "crawling", 218), "Within 10 seconds");
        check(!TelegramMatch.accepts("Crawling", 209, "Crawling", 225), "Too far apart");
        check(TelegramMatch.accepts("Crawling", null, "Crawling", 225), "Unknown length");
        check(
                TelegramMatch.accepts("Numb", 187, "Numb Encore", 189),
                "Containing title, almost the same length");
        check(
                !TelegramMatch.accepts("Numb", 187, "Numb Encore", 205),
                "Containing title, different length");
        check(
                !TelegramMatch.accepts("Numb", null, "Numb Encore", 205),
                "Containing title needs a length");
        check(!TelegramMatch.accepts("", 100, "Anything", 100), "Blank title");
        check(TelegramMatch.core("Song [Live] (2009 Remaster)").equals("Song"), "Core title");
        System.out.println("PASS: " + checks + " Telegram substitution match checks.");
    }
}
