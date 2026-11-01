package app.bitchord.telegram;

import app.bitchord.telegram.LocalPlaylistEngine.Entry;
import app.bitchord.telegram.LocalPlaylistEngine.Index;
import app.bitchord.telegram.LocalPlaylistEngine.Pick;
import app.bitchord.telegram.LocalPlaylistEngine.Query;
import app.bitchord.telegram.LocalPlaylistEngine.Ranking;
import app.bitchord.telegram.LocalPlaylistEngine.Tempo;
import app.bitchord.telegram.LocalPlaylistEngine.Why;
import app.bitchord.telegram.LocalPlaylistLibrary.Host;
import app.bitchord.telegram.LocalPlaylistLibrary.Listened;
import app.bitchord.telegram.LocalPlaylistLibrary.MediaTags;
import app.bitchord.telegram.LocalPlaylistLibrary.Shelf;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local playlist generation against a catalogue fixture: pure ranking, combining and tags. */
public final class LocalPlaylistTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static Entry song(String title, String artist) {
        String identity = "stream:" + artist + "/" + title;
        return new Entry(identity, identity, title, artist, "");
    }

    static Entry song(String title, String artist, String genre, Integer year) {
        return song(title, artist).genre(genre).year(year);
    }

    static List<String> titles(List<Pick> picks) {
        List<String> titles = new ArrayList<>();
        for (Pick pick : picks) titles.add(pick.entry.title);
        return titles;
    }

    static Ranking rank(String prompt, int count, boolean familiar, boolean clean, Entry... songs)
            throws Exception {
        Index index = LocalPlaylistEngine.index(Arrays.asList(songs), null);
        return LocalPlaylistEngine.rank(
                LocalPlaylistEngine.parse(prompt, index), index, count, familiar, clean, null);
    }

    static List<String> picks(String prompt, Entry... songs) throws Exception {
        return titles(rank(prompt, 25, true, false, songs).picks);
    }

    static LocalPlaylist.Result run(String prompt, int count, Entry... songs) throws Exception {
        LocalPlaylistLibrary.Library library = new LocalPlaylistLibrary.Library();
        library.entries.addAll(Arrays.asList(songs));
        library.readable = 1;
        library.storesReadable = true;
        return LocalPlaylist.from(library, prompt, count, true, false, null, null);
    }

    static Pick pick(Ranking ranking, String title) {
        for (Pick pick : ranking.picks) if (pick.entry.title.equals(title)) return pick;
        throw new AssertionError("Not picked: " + title + " in " + titles(ranking.picks));
    }

    static void rarity() throws Exception {
        List<Entry> songs = new ArrayList<>();
        for (int i = 0; i < 30; i++) songs.add(song("Love Song " + i, "Band " + i));
        songs.add(song("Hoppipolla", "Sigur Rós"));
        List<String> ranked = picks("love sigur", songs.toArray(new Entry[0]));
        check(ranked.get(0).equals("Hoppipolla"), "A rare artist word beats a common title word");
        check(ranked.size() > 1, "The common word still counts for the rest");

        List<Entry> artists = new ArrayList<>();
        for (int i = 0; i < 30; i++) artists.add(song("Track " + i, "Love Band " + i));
        artists.add(song("Zephyr", "Someone"));
        check(
                picks("love zephyr", artists.toArray(new Entry[0])).get(0).equals("Zephyr"),
                "A rare title word beats a common artist word");

        songs.add(song("Come As You Are", "Nirvana"));
        check(
                picks("nirvana", songs.toArray(new Entry[0])).equals(List.of("Come As You Are")),
                "A named artist is exactly that artist");
        check(
                picks("come as you are nirvana", songs.toArray(new Entry[0]))
                        .get(0)
                        .equals("Come As You Are"),
                "A title and artist together find the song");
    }

    static void namesAreNotVocabulary() throws Exception {
        Entry[] songs = {
            song("Around the World", "Daft Punk", "Electronic", 1997),
            song("Anarchy", "The Pistols", "Punk", 1977),
            song("Just a Girl", "No Doubt", "Ska", 1995),
            song("Sad But True", "Metallica", "Metal", 1991),
            song("Under the Bridge", "Red Hot", "Funk", 1991),
        };
        check(
                picks("daft punk", songs).equals(List.of("Around the World")),
                "A band's name isn't a punk request, and its tag doesn't conflict");
        check(
                picks("no doubt", songs).equals(List.of("Just a Girl")),
                "A band's name isn't a negation");
        check(
                !picks("punk without daft punk", songs).contains("Around the World"),
                "A negated band goes away");
        check(
                picks("punk without daft punk", songs).contains("Anarchy"),
                "...and the genre request still stands");

        Entry band = song("Alone Again Or", "Love");
        List<Entry> common = new ArrayList<>(List.of(band));
        for (int i = 0; i < 5; i++) common.add(song("Love Letter " + i, "Writer " + i));
        List<String> love = picks("love", common.toArray(new Entry[0]));
        check(love.get(0).equals("Alone Again Or"), "A one-word band ranks for its name");
        check(love.size() == 6, "...but doesn't hide the word's other meaning");

        Entry[] moodTitle = {
            song("Good Mood", "Singer", "Metal", null), song("Sunny", "Other", "Pop", null)
        };
        check(
                picks("good mood", moodTitle).contains("Sunny"),
                "A title repeating a mood phrase doesn't hide the mood");

        Entry[] vibes = {
            song("Chill Vibes", "Uploader"), song("Slow Jazz", "Quartet", "Jazz", null),
        };
        check(
                picks("chill vibes", vibes).containsAll(List.of("Chill Vibes", "Slow Jazz")),
                "A title made of mood words doesn't turn a mood request into that one song");

        Entry[] common2 = {
            song("Paranoid Android", "Radiohead"), song("No Love", "Someone"), song("Love", "Else")
        };
        check(
                !picks("no love songs", common2).contains("No Love"),
                "A title starting with a negation doesn't cancel the negation");
    }

    static void moodsAndGenres() throws Exception {
        Entry[] songs = {
            song("Heavy One", "Forge", "Death Metal", null),
            song("Slow Jazz", "Quartet", "Jazz", null),
            song("Lo-Fi Morning", "Beats", "Lo-Fi Hip Hop", null),
            song("Rock Anthem", "Stadium", "Rock", null),
            song("Drift Phonk", "Night", "Phonk", null),
            song("Pop Rock Hit", "Radio", "Pop Rock", null),
            song("Dance Floor", "Club", "House", null),
            song("Untagged", "Mystery"),
        };
        List<String> chill = picks("chillout", songs);
        check(!chill.contains("Heavy One"), "Metal contradicts chill");
        check(chill.containsAll(List.of("Slow Jazz", "Lo-Fi Morning")), "Chill genres fit");
        check(!chill.contains("Untagged"), "An untagged song isn't evidence of chill");

        Entry loud = song("Chill Pill", "Forge", "Death Metal", null);
        Ranking conflict = rank("chill", 25, true, false, loud);
        check(conflict.picks.isEmpty(), "A title word can't talk a song past what its tag says");
        check(conflict.contradicted == 1 && conflict.unverified.isEmpty(), "...nor be offered");
        check(run("chill", 5, loud).problem == LocalPlaylist.Problem.NO_MATCH, "...nor need tags");

        check(picks("chill metal", songs).contains("Heavy One"), "A named genre beats energy band");

        List<String> rock = picks("rock", songs);
        check(
                rock.containsAll(List.of("Rock Anthem", "Pop Rock Hit")),
                "Rock includes tags naming it");
        check(!rock.contains("Drift Phonk"), "Another family is a contradiction");
        check(!rock.contains("Untagged"), "Unknown doesn't pad a genre request");

        check(picks("pop", songs).contains("Pop Rock Hit"), "A two-genre tag counts for both");
        check(
                picks("hip hop", songs).contains("Lo-Fi Morning"),
                "Lo-fi hip hop is still hip hop for a named request");
        check(picks("hip-hop", songs).equals(picks("hip hop", songs)), "Spellings agree");

        Entry[] tricky = {
            song("Popular Thing", "Tagger", "Popular", null), song("Real Pop", "Star", "Pop", null),
        };
        check(picks("pop", tricky).equals(List.of("Real Pop")), "'Popular' isn't the genre pop");

        Ranking workout = rank("music to lift weights", 25, true, false, songs);
        check(pick(workout, "Rock Anthem") != null, "A phrase triggers its mood");
        check(
                !titles(workout.picks).contains("Slow Jazz"),
                "A low-energy genre doesn't fit a workout");

        Entry bySameArtist = song("Another One", "Forge");
        Ranking inherited =
                rank("metal", 25, true, false, song("Heavy", "Forge", "Metal", null), bySameArtist);
        check(
                pick(inherited, "Another One").why.contains(Why.ARTIST_GENRE),
                "The artist's other tags count, labelled as such");
        check(
                !titles(
                                rank(
                                                "chill",
                                                25,
                                                true,
                                                false,
                                                song("Heavy", "Forge", "Metal", null),
                                                bySameArtist)
                                        .picks)
                        .contains("Another One"),
                "...and count against a mood they contradict");

        Entry inPlaylist = song("Night Drive", "Unknown Band").playlist("Chill evenings");
        check(
                pick(rank("chill", 25, true, false, inPlaylist), "Night Drive")
                        .why
                        .contains(Why.PLAYLIST),
                "A playlist's own name is real evidence without any tags");
    }

    static void yearsWhenKnown() throws Exception {
        Entry[] songs = {
            song("Nineties Rock", "A", "Rock", 1994),
            song("Eighties Rock", "B", "Rock", 1985),
            song("Dateless Rock", "C", "Rock", null),
            song("Nineties Jazz", "D", "Jazz", 1996),
        };
        Ranking ranking = rank("90s rock", 25, true, false, songs);
        check(titles(ranking.picks).get(0).equals("Nineties Rock"), "A known match goes first");
        check(!titles(ranking.picks).contains("Eighties Rock"), "A known other decade is out");
        check(titles(ranking.picks).contains("Dateless Rock"), "An unknown year isn't a conflict");
        check(
                !pick(ranking, "Dateless Rock").why.contains(Why.YEAR),
                "...and is never credited with the year");
        check(ranking.contradicted == 2, "Conflicts are counted");

        check(!picks("rock no 80s", songs).contains("Eighties Rock"), "A negated decade excludes");
        check(picks("rock no 80s", songs).contains("Nineties Rock"), "...only that decade");
        check(picks("nineties", songs).contains("Nineties Jazz"), "Decade words work");
        check(
                picks("early 90s", song("Start", "X", "Pop", 1992), songs[0])
                        .equals(List.of("Start")),
                "Early narrows it");
        check(picks("late 90s", songs).equals(List.of("Nineties Jazz")), "Late narrows it");

        Entry later = song("Later", "E", "Pop", 2001);
        check(
                picks("1994", song("Exact", "F", "Pop", 1994), later).equals(List.of("Exact")),
                "A year means that year, not ten from it");
        check(picks("before 2000", songs).contains("Eighties Rock"), "Before");
        check(!picks("before 2000", later).contains("Later"), "Before excludes later");
        check(picks("after 2000", later).contains("Later"), "After");
        check(picks("from 1990 to 1995", songs).equals(List.of("Nineties Rock")), "Ranges");
        check(picks("1990-1995", songs).equals(List.of("Nineties Rock")), "Hyphen ranges");

        Entry album =
                new Entry("x", "stream:x", "Style", "Taylor Swift", "1989").genre("Pop").year(2014);
        check(picks("1989", album).equals(List.of("Style")), "A number in the library is a name");

        LocalPlaylist.Result dateless = run("80s", 25, song("Plain", "G"), song("Other", "H"));
        check(dateless.songs.isEmpty(), "No known years, no decade claim");
        check(dateless.problem == LocalPlaylist.Problem.NEEDS_TAGS, "Says what's missing");
        check(dateless.unverified.size() == 2, "...and offers them separately");
    }

    static void tempoWhenKnown() throws Exception {
        Entry[] songs = {
            song("Tagged", "A").bpm(122, Tempo.TAGGED),
            song("Measured", "B").bpm(118, Tempo.MEASURED),
            song("Far", "C").bpm(175, Tempo.TAGGED),
            song("Rock Guess", "D", "Rock", null),
            song("Nothing", "E"),
        };
        Ranking ranking = rank("120 bpm", 25, true, false, songs);
        check(
                new HashSet<>(titles(ranking.picks)).equals(Set.of("Tagged", "Measured")),
                "Only real tempos match a tempo request: " + titles(ranking.picks));
        check(pick(ranking, "Tagged").why.contains(Why.TEMPO_TAGGED), "A tag says it's a tag");
        check(pick(ranking, "Measured").why.contains(Why.TEMPO_MEASURED), "Measured says so");
        check(ranking.contradicted == 1, "A known other tempo is a contradiction");
        List<String> open = new ArrayList<>();
        for (Entry entry : ranking.unverified) open.add(entry.title);
        check(open.contains("Rock Guess"), "A genre isn't a tempo");
        check(
                !titles(rank("jazz 120 bpm", 25, true, false, songs).picks).contains("Tagged"),
                "A matching tempo alone doesn't make a song jazz");
        check(!open.contains("Far"), "A contradiction isn't offered");

        check(picks("under 130 bpm", songs).contains("Tagged"), "Under");
        check(!picks("under 130 bpm", songs).contains("Far"), "Under excludes faster");
        check(picks("over 150 bpm", songs).equals(List.of("Far")), "Over");
        check(picks("100-125 bpm", songs).containsAll(List.of("Tagged", "Measured")), "Range");
        check(picks("[target bpm: 175] songs", songs).equals(List.of("Far")), "Directive");
        check(picks("120bpm", songs).contains("Tagged"), "No space");

        Ranking guess = rank("chill", 25, true, false, song("Folk Guess", "F", "Folk", null));
        Set<Why> why = pick(guess, "Folk Guess").why;
        check(
                !why.contains(Why.TEMPO_TAGGED) && !why.contains(Why.TEMPO_MEASURED),
                "A genre never becomes a claimed tempo");
        check(pick(guess, "Folk Guess").entry.bpm == null, "...and no tempo appears on the song");

        Ranking genreOnly =
                rank("workout", 25, true, false, song("Soul Guess", "G", "Americana", null));
        check(
                genreOnly.picks.isEmpty()
                        || !pick(genreOnly, "Soul Guess").why.contains(Why.TEMPO_TAGGED),
                "Estimates stay estimates");

        AtomicInteger asked = new AtomicInteger();
        LocalPlaylistLibrary.Library library = new LocalPlaylistLibrary.Library();
        library.readable = 1;
        library.entries.add(song("Paranoid Android", "Radiohead"));
        LocalPlaylist.from(
                library, "radiohead", 5, true, false, null, entries -> asked.incrementAndGet());
        check(asked.get() == 0, "Files aren't touched for a request about names");
        LocalPlaylist.from(
                library, "120 bpm", 5, true, false, null, entries -> asked.incrementAndGet());
        check(asked.get() == 1, "Tempos are looked up when asked for");
        LocalPlaylist.from(
                library, "chill", 5, true, false, null, entries -> asked.incrementAndGet());
        check(asked.get() == 2, "A mood implies a tempo");
    }

    static void negation() throws Exception {
        Entry[] songs = {
            song("Iron Thing", "A", "Heavy Metal", null),
            song("Hard Road", "B", "Hard Rock", null),
            song("Grunge One", "C", "Grunge", null),
            song("Crying in the Rain", "D", "Pop", null),
            song("Happy Place", "E", "Pop", null),
            song("Beat Tape", "F", "Lo-Fi", null),
            song("Quiet Rain", "G", "Ambient", null),
        };
        List<String> rock = picks("rock without metal", songs);
        check(rock.contains("Hard Road"), "The positive part stays");
        check(!rock.contains("Iron Thing"), "The negated part goes");

        List<String> list = picks("rock without metal or grunge", songs);
        check(!list.contains("Grunge One") && !list.contains("Iron Thing"), "'or' continues");
        List<String> commas = picks("rock, no metal, grunge", songs);
        check(!commas.contains("Grunge One"), "A comma before a genre continues");
        check(
                picks("no metal and happy", songs).contains("Happy Place"),
                "'and' before a mood ends it");

        List<String> noRock = picks("no rock", songs);
        check(
                !noRock.contains("Iron Thing") && !noRock.contains("Hard Road"),
                "Family names rule out");
        check(noRock.contains("Happy Place"), "Only a negation: everything else is fine");

        check(!picks("no sad songs", songs).contains("Crying in the Rain"), "Negated mood words");
        check(picks("no sad songs", songs).contains("Happy Place"), "Others remain");
        check(!picks("don't play metal", songs).contains("Iron Thing"), "Contractions negate");
        check(!picks("sin metal", songs).contains("Iron Thing"), "Spanish negation");

        Query negated =
                LocalPlaylistEngine.parse(
                        "without lo-fi", LocalPlaylistEngine.index(Arrays.asList(songs), null));
        check(
                negated.moods.isEmpty() && negated.families.isEmpty(),
                "A negated phrase asks nothing");
        check(!picks("without lo-fi", songs).contains("Beat Tape"), "...and removes its songs");

        Entry[] names = {song("One", "Radiohead"), song("Two", "Bjork")};
        check(picks("not like radiohead", names).equals(List.of("Two")), "'Not like' is negation");
    }

    static void similarity() throws Exception {
        Entry[] songs = {
            song("Seed One", "Radiohead", "Alternative Rock", null),
            song("Neighbour", "Muse", "Alternative Rock", null),
            song("Cousin", "Blur", "Rock", null),
            song("Stranger", "Abba", "Pop", null),
            song("Untagged", "Nobody"),
        };
        List<String> similar = picks("something similar to Radiohead", songs);
        check(similar.get(0).equals("Neighbour"), "Sharing a genre goes first: " + similar);
        check(similar.contains("Cousin"), "The same family counts");
        check(!similar.contains("Seed One"), "'Like X' isn't X itself");
        check(
                !similar.contains("Stranger") && !similar.contains("Untagged"),
                "No evidence, no claim");
        check(
                pick(rank("like radiohead", 9, true, false, songs), "Neighbour")
                        .why
                        .contains(Why.SIMILAR_GENRE),
                "'Like' works too");

        check(picks("i like radiohead", songs).equals(List.of("Seed One")), "'I like X' means X");
        check(
                picks("radiohead and similar", songs).get(0).equals("Seed One"),
                "'And similar' keeps X");
        check(
                picks("radiohead and similar", songs).contains("Neighbour"),
                "...and adds neighbours");

        LocalPlaylist.Result bare =
                run("similar to Nobody", 10, song("Untagged", "Nobody"), song("Other", "Else"));
        check(bare.problem == LocalPlaylist.Problem.NO_SIMILAR, "Unsupported similarity says so");
        check(bare.songs.isEmpty(), "...and doesn't guess");

        Entry seed = song("Seed", "Nobody").playlist("Road trip");
        Entry friend = song("Friend", "Somebody").playlist("Road trip");
        Ranking playlists =
                rank("sounds like nobody", 9, true, false, seed, friend, song("Lone", "X"));
        check(titles(playlists.picks).equals(List.of("Friend")), "Shared playlists are evidence");
        check(pick(playlists, "Friend").why.contains(Why.SIMILAR_PLAYLIST), "...labelled as such");
    }

    static void engagement() throws Exception {
        Entry worn = song("Rock Worn", "A", "Rock", null).plays(90);
        Entry fresh = song("Rock Fresh", "B", "Rock", null).plays(0);
        check(
                titles(rank("rock", 5, true, false, worn, fresh).picks).get(0).equals("Rock Worn"),
                "Familiar favours what's been played");
        check(
                titles(rank("rock", 5, false, false, worn, fresh).picks)
                        .get(0)
                        .equals("Rock Fresh"),
                "Deeper cuts favour what hasn't");

        Entry liked = song("Liked Rock", "C", "Rock", null).favorite(true);
        Entry plain = song("Plain Rock", "D", "Rock", null);
        check(
                titles(rank("rock", 5, true, false, plain, liked).picks)
                        .get(0)
                        .equals("Liked Rock"),
                "A like orders matches");
        check(
                rank("jazz", 5, true, false, song("Popular", "E").plays(500).favorite(true))
                        .picks
                        .isEmpty(),
                "Plays and likes never qualify a song by themselves");

        Entry disliked = song("Bad Rock", "F", "Rock", null).disliked(true).plays(50);
        Ranking without = rank("rock", 5, true, false, disliked, plain);
        check(titles(without.picks).equals(List.of("Plain Rock")), "A dislike keeps a song out");
        check(without.disliked == 1, "...counted");
        check(
                picks(
                                "favorites",
                                song("L", "A").favorite(true),
                                song("N", "B"),
                                song("M", "C").plays(9))
                        .equals(List.of("L", "M")),
                "Favourites are liked or played a lot, nothing else");

        Entry untagged = song("Unplayed Thing", "Q").plays(0);
        check(
                titles(
                                rank(
                                                "new rock",
                                                5,
                                                true,
                                                false,
                                                untagged,
                                                song("Fresh Rock", "R", "Rock", null).plays(0))
                                        .picks)
                        .equals(List.of("Fresh Rock")),
                "'New' adds to a genre request, without qualifying anything on its own");
        check(
                titles(
                                rank(
                                                "top rock songs",
                                                5,
                                                true,
                                                false,
                                                song("Loved", "S").favorite(true),
                                                song("Rocker", "T", "Rock", null))
                                        .picks)
                        .equals(List.of("Rocker")),
                "Same for 'top'");
        Ranking unknownPlays = rank("something new", 5, true, false, song("Never", "A"));
        check(unknownPlays.picks.isEmpty(), "No statistics means not known to be unplayed");
        check(
                titles(
                                rank(
                                                "something new",
                                                5,
                                                true,
                                                false,
                                                song("Never", "A").plays(0),
                                                song("Often", "B").plays(4))
                                        .picks)
                        .equals(List.of("Never")),
                "Known unplayed is discovery");
        Entry newest = song("Newest", "A").added(1000L);
        check(
                titles(rank("recently added", 5, true, false, newest, song("Dateless", "B")).picks)
                        .equals(List.of("Newest")),
                "Recent needs a real date");
        check(
                titles(
                                rank(
                                                "best of radiohead",
                                                5,
                                                true,
                                                false,
                                                song("Theirs", "Radiohead"),
                                                song("Other", "Else").favorite(true))
                                        .picks)
                        .equals(List.of("Theirs")),
                "'Best of X' doesn't bring in other artists");

        Entry dirty = song("Dirty", "A", "Rock", null).explicit(true);
        Entry marked = song("Marked (Explicit)", "B", "Rock", null);
        Entry clean = song("Clean", "C", "Rock", null).explicit(false);
        Entry unknown = song("Unknown", "D", "Rock", null);
        Ranking cleaned = rank("rock", 9, true, true, dirty, marked, clean, unknown);
        check(
                new HashSet<>(titles(cleaned.picks)).equals(Set.of("Clean", "Unknown")),
                "Avoid explicit removes what's known, and only that");
        check(cleaned.explicit == 2, "...counted");
        check(rank("rock", 9, true, false, dirty).picks.size() == 1, "Off by default");
    }

    static void stableAndDiverse() throws Exception {
        List<Entry> songs = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            songs.add(song("Hit " + i, "Big Band", "Rock", null).plays(50));
        for (int i = 0; i < 6; i++) songs.add(song("Deep " + i, "Band " + i, "Rock", null));
        List<String> first =
                titles(rank("rock", 8, true, false, songs.toArray(new Entry[0])).picks);
        Collections.shuffle(songs, new Random(7));
        List<String> again =
                titles(rank("rock", 8, true, false, songs.toArray(new Entry[0])).picks);
        check(first.equals(again), "The order doesn't depend on how the library was read");
        check(first.size() == 8 && new HashSet<>(first).size() == 8, "Exactly as many, no repeats");
        long hits = first.stream().filter(title -> title.startsWith("Hit")).count();
        check(hits == 2, "One artist doesn't crowd a list: " + first);
        for (int i = 1; i < first.size(); i++)
            check(
                    !(first.get(i).startsWith("Hit") && first.get(i - 1).startsWith("Hit")),
                    "Spaced out");

        List<String> filled =
                titles(rank("rock", 16, true, false, songs.toArray(new Entry[0])).picks);
        check(filled.size() == 16, "Held-back songs fill the room since they do fit");
        check(
                picks(
                                        "big band",
                                        song("A", "Big Band"),
                                        song("B", "Big Band"),
                                        song("C", "Big Band"))
                                .size()
                        == 3,
                "No limit when that artist was asked for");

        Ranking duplicates =
                rank(
                        "rock",
                        9,
                        true,
                        false,
                        new Entry(
                                        "yt",
                                        "stream:abcdefghijk",
                                        "Song A (Official Video)",
                                        "The Band",
                                        "")
                                .genre("Rock"),
                        new Entry(
                                        "tg",
                                        "stream:src:telegram-native::-100_1",
                                        "Song A",
                                        "The Band feat. Guest",
                                        "")
                                .genre("Rock"),
                        new Entry(
                                        "tg2",
                                        "stream:src:telegram-native::-100_2",
                                        "Song A (Live)",
                                        "The Band",
                                        "")
                                .genre("Rock"),
                        new Entry("same", "stream:abcdefghijk", "Same Identity", "Other", "")
                                .genre("Rock"));
        check(duplicates.picks.size() == 2, "One copy per recording: " + titles(duplicates.picks));
        check(titles(duplicates.picks).contains("Song A (Live)"), "A different version stays");
        Set<String> identities = new HashSet<>();
        for (Pick pick : duplicates.picks) identities.add(pick.entry.identity);
        check(identities.size() == duplicates.picks.size(), "No duplicate identities");
    }

    static void textFolding() throws Exception {
        check(LocalPlaylistLexicon.fold("Beyoncé").equals("beyonce"), "Accents fold");
        check(
                LocalPlaylistLexicon.fold("Motörhead Øresund ß").equals("motorhead oresund ss"),
                "Letters");
        check(LocalPlaylistLexicon.fold("Drum & Bass").equals("drumandbass"), "D&B");
        check(LocalPlaylistLexicon.fold("R&B").equals("rnb"), "R&B");
        check(
                LocalPlaylistLexicon.stem("relajantes")
                        .equals(LocalPlaylistLexicon.stem("relajante")),
                "Plural");
        check(
                LocalPlaylistLexicon.stem("canciones").equals(LocalPlaylistLexicon.stem("cancion")),
                "Plural es");
        check(LocalPlaylistLexicon.stem("rock").equals("rock"), "Short words stay");
        check(LocalPlaylistLexicon.stem("mp3s").equals("mp3s"), "Digits stay");
        check(LocalPlaylistLexicon.words("दिल से").contains("दिल"), "Indic marks stay in words");
        check(LocalPlaylistLexicon.words("愛 x").contains("愛"), "One character can be a word");
        check(!LocalPlaylistLexicon.words("愛 x").contains("x"), "One Latin letter isn't");

        Entry[] names = {
            song("Halo", "Beyoncé"),
            song("Hoppipolla", "Sigur Rós"),
            song("दिल से रे", "ए आर रहमान"),
            song("愛", "歌手"),
            song("La Vida", "Canción Ñandú"),
            song("Ace of Spades", "Motörhead"),
        };
        check(picks("beyonce", names).equals(List.of("Halo")), "Prompts without accents match");
        check(picks("SIGUR ROS", names).equals(List.of("Hoppipolla")), "Any case");
        check(picks("motorhead", names).equals(List.of("Ace of Spades")), "Special letters");
        check(picks("दिल", names).equals(List.of("दिल से रे")), "Other scripts");
        check(picks("愛", names).equals(List.of("愛")), "One-character titles");
    }

    static void emptyAndInvalid() throws Exception {
        check(run("rock", 5).problem == LocalPlaylist.Problem.EMPTY_LIBRARY, "Empty library");
        check(run("rock", 5).songs.isEmpty(), "...with nothing to show");
        check(
                LocalPlaylist.Result.message(LocalPlaylist.Problem.EMPTY_LIBRARY) != null,
                "A message");
        for (LocalPlaylist.Problem problem : LocalPlaylist.Problem.values())
            check(
                    !LocalPlaylist.Result.message(problem).isEmpty(),
                    "Every problem explains itself");
        for (Why why : Why.values())
            check(!LocalPlaylist.Result.reason(why, 120.0).isEmpty(), "Every reason has a label");

        check(LocalPlaylist.Result.reason(Why.TEMPO_MEASURED, 120.4).contains("120"), "With value");
        check(
                run("   ", 5, song("A", "B")).problem == LocalPlaylist.Problem.NO_DESCRIPTION,
                "Blank");
        check(run(null, 5, song("A", "B")).problem == LocalPlaylist.Problem.NO_DESCRIPTION, "Null");
        check(
                run("x".repeat(LocalPlaylist.MAX_DESCRIPTION + 1), 5, song("A", "B")).problem
                        == LocalPlaylist.Problem.LONG_DESCRIPTION,
                "Too long");
        check(
                run("zzqqxx", 5, song("A", "B")).problem == LocalPlaylist.Problem.NO_MATCH,
                "No match");

        LocalPlaylistLibrary.Library broken = new LocalPlaylistLibrary.Library();
        broken.unreadable = 3;
        check(
                LocalPlaylist.from(broken, "rock", 5, true, false, null, null).problem
                        == LocalPlaylist.Problem.LIBRARY_UNAVAILABLE,
                "All parts unreadable isn't the same as empty");

        LocalPlaylist.Result some =
                run("play some music", 5, song("A", "B").plays(3), song("C", "D"));
        check(
                some.songs.size() == 2 && some.problem == null,
                "No constraint at all: everything fits");
        check(some.songs.get(0).toString().contains("A"), "...most played first");

        check(run("rock", 0, song("A", "B", "Rock", null)).songs.size() == 1, "At least one");
        check(run("rock", -5, song("A", "B", "Rock", null)).songs.size() == 1, "Negative count");
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) many.add(song("Track " + i, "Artist " + i, "Rock", null));
        check(
                run("rock", 100000, many.toArray(new Entry[0])).songs.size()
                        == LocalPlaylist.MAX_TRACKS,
                "A huge count is capped");
        check(
                run("rock", 4, many.subList(0, 2).toArray(new Entry[0])).songs.size() == 2,
                "Short list stays short");
    }

    static void caps() throws Exception {
        List<Entry> many = new ArrayList<>();
        Random random = new Random(3);
        String[] words = {"love", "night", "day", "rain", "fire", "blue", "road", "heart", "river"};
        for (int i = 0; i < LocalPlaylistEngine.MAX_ENTRIES + 5000; i++)
            many.add(
                    song(
                            words[random.nextInt(words.length)] + " " + i,
                            "Artist " + (i % 500),
                            i % 3 == 0 ? "Rock" : null,
                            i % 3 == 0 ? 1970 + i % 50 : null));
        long start = System.nanoTime();
        Index index = LocalPlaylistEngine.index(many, null);
        check(index.size() == LocalPlaylistEngine.MAX_ENTRIES, "The library is capped");
        Ranking ranking =
                LocalPlaylistEngine.rank(
                        LocalPlaylistEngine.parse("90s rock about rain, no metal", index),
                        index,
                        50,
                        true,
                        false,
                        null);
        check(ranking.picks.size() == 50, "A big library still answers");
        long millis = (System.nanoTime() - start) / 1_000_000;
        check(millis < 60_000, "Large library within budget: " + millis + " ms");

        Entry huge = song("word ".repeat(100_000), "name ".repeat(100_000));
        huge.genre("rock ".repeat(100_000)).playlist("list ".repeat(100_000));
        check(!picks("word", huge).isEmpty(), "Oversized metadata is cut, not fatal");
        String longPrompt = "rock ".repeat(5000);
        Index one = LocalPlaylistEngine.index(List.of(song("R", "A", "Rock", null)), null);
        check(
                !LocalPlaylistEngine.parse(longPrompt, one).families.isEmpty(),
                "Oversized prompts are cut");
    }

    static void cancellation() throws Exception {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 2000; i++) many.add(song("Track " + i, "Artist " + i, "Rock", null));
        try {
            LocalPlaylistEngine.index(many, () -> true);
            check(false, "Indexing stops");
        } catch (InterruptedException expected) {
            checks++;
        }
        Index index = LocalPlaylistEngine.index(many, null);
        Query query = LocalPlaylistEngine.parse("rock", index);
        AtomicInteger polls = new AtomicInteger();
        try {
            LocalPlaylistEngine.rank(
                    query, index, 5, true, false, () -> polls.incrementAndGet() > 3);
            check(false, "Ranking stops");
        } catch (InterruptedException expected) {
            check(polls.get() == 4, "Stops at the next check");
        }
        try {
            LocalPlaylistLibrary.collect(host(null), () -> true);
            check(false, "Reading stops");
        } catch (InterruptedException expected) {
            checks++;
        }
        Thread.currentThread().interrupt();
        try {
            LocalPlaylistEngine.index(many, null);
            check(false, "An interrupt stops too");
        } catch (InterruptedException expected) {
            check(Thread.interrupted(), "...and is left for the caller");
        }
    }

    // ── Combining what the library's parts report ───────────────────────────

    static Object real(String id, String title, String artist) throws Exception {
        return MixedSongCodec.decode(
                new JSONObject().put("videoId", id).put("title", title).put("artist", artist));
    }

    static class FakeHost implements Host {
        List<Shelf> playlists = new ArrayList<>(), offline = new ArrayList<>();
        List<Object> queue = new ArrayList<>(),
                downloads = new ArrayList<>(),
                files = new ArrayList<>();
        Map<String, Listened> listening = new HashMap<>();
        Map<String, String> likes = new HashMap<>();
        Map<String, MediaTags> media = new HashMap<>();
        final Set<String> broken = new HashSet<>();

        private void fail(String part) throws Exception {
            if (broken.contains(part)) throw new IllegalStateException(part);
        }

        @Override
        public List<Shelf> playlists() throws Exception {
            fail("playlists");
            return playlists;
        }

        @Override
        public List<Shelf> offlinePlaylists() throws Exception {
            fail("offline");
            return offline;
        }

        @Override
        public List<Object> queue() throws Exception {
            fail("queue");
            return queue;
        }

        @Override
        public List<Object> downloads() throws Exception {
            fail("downloads");
            return downloads;
        }

        @Override
        public Map<String, Listened> listening() throws Exception {
            fail("listening");
            return listening;
        }

        @Override
        public Map<String, String> likes() throws Exception {
            fail("likes");
            return likes;
        }

        @Override
        public List<Object> localFiles() throws Exception {
            fail("files");
            return files;
        }

        @Override
        public Map<String, MediaTags> mediaTags() throws Exception {
            fail("media");
            return media;
        }
    }

    static Host host(String ignored) {
        return new FakeHost();
    }

    static Entry entry(LocalPlaylistLibrary.Library library, String title) {
        for (Entry entry : library.entries) if (entry.title.equals(title)) return entry;
        throw new AssertionError("Missing " + title);
    }

    static void combining() throws Exception {
        FakeHost host = new FakeHost();
        Object youtube = real("abcdefghijk", "Catalogue Song", "Catalogue Artist");
        Object telegram = real("src:telegram-native::-100_5", "Channel Song", "Channel Artist");
        Object peer = real("src:soulseek-8::user/track.flac", "Peer Song", "Peer Artist");
        Object file =
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", "local:abc")
                                .put("title", "File Song")
                                .put("artist", "<unknown>")
                                .put("localUri", "content://media/external/audio/media/42"));
        host.playlists.add(new Shelf("Mixed", List.of(youtube, telegram, peer, file), false));

        Object download =
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", "abcdefghijk")
                                .put("title", "Catalogue Song")
                                .put("artist", "Catalogue Artist")
                                .put("albumName", "Real Album")
                                .put("localUri", "file:///data/saved/song.flac"));
        host.downloads.add(download);
        host.offline.add(new Shelf(null, List.of(telegram), true));

        Listened seen = new Listened("abcdefghijk");
        seen.plays = 7;
        host.listening.put(seen.id, seen);
        Listened onlyHeard = new Listened("src:deezer-2::99");
        onlyHeard.title = "Heard Song";
        onlyHeard.artist = "Heard Artist";
        onlyHeard.album = "Heard Album";
        onlyHeard.plays = 3;
        host.listening.put(onlyHeard.id, onlyHeard);
        Listened localPlay = new Listened("local:abc");
        localPlay.title = "File Song";
        localPlay.plays = 2;
        host.listening.put(localPlay.id, localPlay);
        Listened unusable = new Listened("not an id");
        unusable.title = "Unusable";
        host.listening.put(unusable.id, unusable);
        Listened nameless = new Listened("src:deezer-2::100");
        nameless.plays = 9;
        host.listening.put(nameless.id, nameless);

        Object hated = real("src:telegram-native::-100_9", "Hated", "Someone");
        host.queue.add(hated);
        host.likes.put(hated.getClass() == null ? "" : "src:telegram-native::-100_9", "DISLIKE");
        host.likes.put("src:soulseek-8::user/track.flac", "LIKE");
        host.media.put("media:42", new MediaTags(1999, "Trip Hop"));

        LocalPlaylistLibrary.Library library = LocalPlaylistLibrary.collect(host, null);
        check(library.unreadable == 0 && library.listeningKnown, "All parts readable");

        Entry catalogue = entry(library, "Catalogue Song");
        check(catalogue.song == youtube, "The saved stream row survives its downloaded copy");
        check(catalogue.album.equals("Real Album"), "...and gains what the copy knew");
        check(catalogue.plays == 7, "Statistics are attached by id");
        check(
                catalogue.location.equals("file:///data/saved/song.flac"),
                "The file is known for tags");
        check(
                MixedSongCodec.key(catalogue.song).equals("stream:abcdefghijk"),
                "Its identity stays");
        check(catalogue.playlists.contains("Mixed"), "Its playlist's name is kept");

        Entry channel = entry(library, "Channel Song");
        check(channel.song == telegram, "A Telegram row is the same object");
        check(Boolean.TRUE.equals(channel.favorite), "Liked through the offline likes list");
        check(!channel.playlists.contains(null), "The likes list isn't a playlist name");
        check(
                MixedSongCodec.text(channel.song, "videoId").equals("src:telegram-native::-100_5"),
                "Id");

        Entry soulseek = entry(library, "Peer Song");
        check(soulseek.song == peer, "Another source's row is the same object");
        check(Boolean.TRUE.equals(soulseek.favorite), "Liked through the like state");

        Entry local = entry(library, "File Song");
        check(local.song == file, "A local file stays a local file");
        check(local.artist.isEmpty(), "A placeholder artist stays unknown");
        check(local.year == 1999 && local.genre.equals("Trip Hop"), "Real tags are attached");
        check(local.plays == 2, "Statistics count for files, too");
        check(
                MixedSongCodec.key(local.song)
                        .equals("file:content://media/external/audio/media/42"),
                "A file keeps its identity");

        Entry heard = entry(library, "Heard Song");
        check(
                MixedSongCodec.text(heard.song, "videoId").equals("src:deezer-2::99"),
                "A song only heard keeps its real source id");
        check(heard.album.equals("Heard Album") && heard.plays == 3, "...and its real details");
        check(heard.genre == null && heard.year == null, "...with nothing invented");

        check(entry(library, "Hated").disliked, "A dislike is carried");
        for (Entry entry : library.entries) {
            check(!entry.title.equals("Unusable"), "An unknown id can't be replayed");
            check(!entry.identity.contains("100\n"), "Keys are clean");
            check(
                    !MixedSongCodec.text(entry.song, "videoId").equals("src:deezer-2::100"),
                    "No title, no song");
        }
        check(library.entries.size() == 6, "Six songs: " + library.entries.size());

        // The whole path, from the parts to the songs a caller gets.
        LocalPlaylist.Result result =
                LocalPlaylist.from(library, "catalogue artist", 5, true, false, null, null);
        check(result.songs.size() == 1 && result.songs.get(0) == youtube, "Real objects come back");
        LocalPlaylist.Result peerResult =
                LocalPlaylist.from(library, "peer artist", 5, true, false, null, null);
        check(peerResult.songs.get(0) == peer, "...from any source, not only one");
        check(
                LocalPlaylist.from(library, "hated", 5, true, false, null, null).songs.isEmpty(),
                "A disliked song stays out");
        check(
                LocalPlaylist.from(library, "trip hop 90s", 5, true, false, null, null).songs.get(0)
                        == file,
                "Tags from the media library reach ranking");

        FakeHost partial = new FakeHost();
        partial.playlists.add(new Shelf("One", List.of(telegram), false));
        partial.broken.addAll(
                Set.of("offline", "queue", "downloads", "listening", "likes", "files", "media"));
        LocalPlaylistLibrary.Library some = LocalPlaylistLibrary.collect(partial, null);
        check(
                some.entries.size() == 1 && some.unreadable == 7,
                "Other parts survive a missing one");
        check(
                !some.listeningKnown && entry(some, "Channel Song").plays == null,
                "No statistics stay unknown");

        FakeHost none = new FakeHost();
        none.broken.addAll(
                Set.of(
                        "playlists",
                        "offline",
                        "queue",
                        "downloads",
                        "listening",
                        "likes",
                        "files",
                        "media"));
        check(LocalPlaylistLibrary.collect(none, null).unavailable(), "All missing is reported");
        check(
                !LocalPlaylistLibrary.collect(new FakeHost(), null).unavailable(),
                "Empty isn't missing");
        FakeHost storesOnly = new FakeHost();
        storesOnly.broken.addAll(Set.of("playlists", "downloads"));
        check(
                LocalPlaylistLibrary.collect(storesOnly, null).unavailable(),
                "Other parts answering doesn't hide unreadable stores");

        // Whichever is read first, a stream row outlives a row pointing at its downloaded copy.
        FakeHost orders = new FakeHost();
        Object copy =
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", "zyxwvutsrqp")
                                .put("title", "Both")
                                .put("artist", "Band")
                                .put("localUri", "file:///data/saved/both.flac"));
        Object stream = real("zyxwvutsrqp", "Both", "Band");
        orders.playlists.add(new Shelf("A", List.of(copy), false));
        orders.playlists.add(new Shelf("B", List.of(stream), false));
        Entry both = entry(LocalPlaylistLibrary.collect(orders, null), "Both");
        check(both.song == stream, "The stream row is what's kept");
        check(both.location.equals("file:///data/saved/both.flac"), "The file stays known");
        check(both.playlists.containsAll(List.of("A", "B")), "Both playlists stay known");

        // The same file found by two ids merges rather than doubling.
        FakeHost twice = new FakeHost();
        Object a =
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", "local:one")
                                .put("title", "T")
                                .put("artist", "")
                                .put("localUri", "content://x/1"));
        Object b =
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", "local:two")
                                .put("title", "T")
                                .put("artist", "")
                                .put("localUri", "content://x/1"));
        twice.files.addAll(List.of(a, b));
        check(LocalPlaylistLibrary.collect(twice, null).entries.size() == 1, "One file, one song");

        check("Queen".equals(LocalPlaylistLibrary.knownArtist(" Queen ")), "Names stay");
        check(LocalPlaylistLibrary.knownArtist("Unknown Artist").isEmpty(), "Placeholders don't");
        check(LocalPlaylistLibrary.knownYear(0) == null, "Year 0 is unknown");
        check(LocalPlaylistLibrary.knownYear(1994) == 1994, "A year is a year");
        check(LocalPlaylistLibrary.knownYear(9999) == null, "The far future is unknown");
        check(LocalPlaylistLibrary.knownGenre("<unknown>") == null, "Placeholder genre");
        check(LocalPlaylistLibrary.knownGenre("Other") == null, "'Other' says nothing");
        check("Jazz".equals(LocalPlaylistLibrary.knownGenre(" Jazz ")), "A genre is a genre");
        check(LocalPlaylistLibrary.seconds("3:24") == 204, "Length");
        check(LocalPlaylistLibrary.seconds("1:00:00") == 3600, "Hours");
        check(LocalPlaylistLibrary.seconds("soon") == null, "Unknown length");
        check(
                LocalPlaylistLibrary.mediaId("content://media/external/audio/media/42?x=1")
                        .equals("media:42"),
                "Media ids");
        check(LocalPlaylistLibrary.mediaId("file:///music/a.mp3").isEmpty(), "Files have none");
    }

    static void listening() throws Exception {
        File directory = Files.createTempDirectory("listening").toFile();
        Files.write(
                directory.toPath().resolve("2026-10.json"),
                ("{\"month\":\"2026-10\",\"tracks\":[{\"id\":\"src:a-1::x\",\"title\":\"Old\","
                                + "\"artist\":\"Who\",\"plays\":2,\"last\":10,\"album\":\"First\"},"
                                + "{\"id\":\"\",\"title\":\"NoId\",\"plays\":9}]}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                directory.toPath().resolve("2026-11.json"),
                ("{\"month\":\"2026-11\",\"tracks\":[{\"id\":\"src:a-1::x\",\"title\":\"New\","
                                + "\"artist\":\"Who\",\"plays\":3,\"last\":20,\"album\":null}]}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                directory.toPath().resolve("2026-09.json"),
                "not json".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.toPath().resolve("notes.txt"), "{}".getBytes(StandardCharsets.UTF_8));
        Map<String, Listened> read = LocalPlaylistLibrary.readListening(directory, null);
        check(read.size() == 1, "Bad files and empty ids are skipped");
        Listened track = read.get("src:a-1::x");
        check(track.plays == 5, "Plays add up across months");
        check(track.title.equals("New"), "The latest name wins");
        check("First".equals(track.album), "A null album doesn't erase a real one");
        check(
                LocalPlaylistLibrary.readListening(new File(directory, "absent"), null).isEmpty(),
                "No statistics yet is empty, not a failure");
        try {
            LocalPlaylistLibrary.readListening(directory, () -> true);
            check(false, "Stops when cancelled");
        } catch (InterruptedException expected) {
            checks++;
        }
    }

    static void tempoLookups() throws Exception {
        List<Entry> entries = new ArrayList<>();
        Object measuredSong = real("src:a-1::m", "Measured", "A");
        Entry measured = new Entry(measuredSong, "stream:m", "Measured", "A", "");
        Entry tagged = new Entry(real("local:t", "Tagged", ""), "file:t", "Tagged", "", "");
        tagged.location = "file:t";
        Entry junk = new Entry(real("local:j", "Junk", ""), "file:j", "Junk", "", "");
        junk.location = "file:j";
        Entry gone = new Entry(real("local:g", "Gone", ""), "file:g", "Gone", "", "");
        gone.location = "file:g";
        Entry stream = new Entry(real("abcdefghijk", "Stream", ""), "stream:s", "Stream", "", "");
        entries.addAll(List.of(measured, tagged, junk, gone, stream));
        List<String> opened = new ArrayList<>();
        LocalPlaylistLibrary.addTempo(
                entries,
                id -> id.equals("src:a-1::m") ? 128.0 : id.equals("abcdefghijk") ? 9999.0 : null,
                location -> {
                    opened.add(location);
                    if (location.equals("file:g")) throw new java.io.FileNotFoundException();
                    return new ByteArrayInputStream(
                            location.equals("file:t") ? id3(3, "TBPM", "124") : new byte[64]);
                },
                null);
        check(
                measured.bpm == 128 && measured.tempo == Tempo.MEASURED,
                "An analysis counts as measured");
        check(tagged.bpm == 124 && tagged.tempo == Tempo.TAGGED, "A tag counts as tagged");
        check(junk.bpm == null && gone.bpm == null, "Unreadable stays unknown");
        check(stream.bpm == null, "An implausible value isn't kept");
        check(opened.size() == 3 && !opened.contains(null), "Only local files are opened");

        Entry[] many = new Entry[500];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Entry(real("local:" + i, "T" + i, ""), "file:" + i, "T" + i, "", "");
            many[i].location = "file:" + i;
        }
        AtomicInteger opens = new AtomicInteger();
        LocalPlaylistLibrary.addTempo(
                Arrays.asList(many),
                id -> null,
                location -> {
                    opens.incrementAndGet();
                    return new ByteArrayInputStream(new byte[32]);
                },
                null);
        check(opens.get() <= 300, "File reads are bounded: " + opens.get());
        try {
            LocalPlaylistLibrary.addTempo(
                    Arrays.asList(many), id -> null, location -> null, () -> true);
            check(false, "Stops when cancelled");
        } catch (InterruptedException expected) {
            checks++;
        }
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    static byte[] id3(int version, String frame, String value) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        // A cover image comes before the tempo, as it often does.
        out.writeBytes("APIC");
        int coverSize = 300;
        writeFrameSize(out, version, coverSize);
        out.writeShort(0);
        out.write(new byte[coverSize]);
        byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(frame);
        writeFrameSize(out, version, text.length + 1);
        out.writeShort(0);
        out.write(0);
        out.write(text);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream head = new DataOutputStream(file);
        head.writeBytes("ID3");
        head.write(version);
        head.write(0);
        head.write(0);
        writeSyncsafe(head, body.size());
        head.write(body.toByteArray());
        head.write(new byte[64]);
        return file.toByteArray();
    }

    static void writeFrameSize(DataOutputStream out, int version, int size) throws Exception {
        if (version == 4) writeSyncsafe(out, size);
        else out.writeInt(size);
    }

    static void writeSyncsafe(DataOutputStream out, int size) throws Exception {
        out.write(size >> 21 & 127);
        out.write(size >> 14 & 127);
        out.write(size >> 7 & 127);
        out.write(size & 127);
    }

    static void littleInt(ByteArrayOutputStream out, int value) {
        out.write(value & 255);
        out.write(value >> 8 & 255);
        out.write(value >> 16 & 255);
        out.write(value >> 24 & 255);
    }

    static byte[] vorbisComments(String... comments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        littleInt(out, 3);
        out.writeBytes("lib".getBytes(StandardCharsets.UTF_8));
        littleInt(out, comments.length);
        for (String comment : comments) {
            byte[] bytes = comment.getBytes(StandardCharsets.UTF_8);
            littleInt(out, bytes.length);
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    static byte[] flac(String... comments) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.writeBytes("fLaC");
        out.writeInt(34); // STREAMINFO, not last.
        out.write(new byte[34]);
        byte[] body = vorbisComments(comments);
        out.write(0x84);
        out.write(body.length >> 16 & 255);
        out.write(body.length >> 8 & 255);
        out.write(body.length & 255);
        out.write(body);
        out.write(new byte[32]);
        return file.toByteArray();
    }

    static byte[] ogg(String marker, String... comments) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes("OggS".getBytes(StandardCharsets.ISO_8859_1));
        file.write(new byte[40]);
        file.writeBytes(marker.getBytes(StandardCharsets.ISO_8859_1));
        file.write(vorbisComments(comments));
        file.write(new byte[16]);
        return file.toByteArray();
    }

    static byte[] atom(String name, byte[]... parts) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : parts) body.write(part);
        ByteArrayOutputStream atom = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(atom);
        out.writeInt(body.size() + 8);
        out.writeBytes(name);
        out.write(body.toByteArray());
        return atom.toByteArray();
    }

    static byte[] mp4(int bpm, boolean moovLast) throws Exception {
        byte[] data = new byte[10];
        data[8] = (byte) (bpm >> 8);
        data[9] = (byte) bpm;
        byte[] moov =
                atom(
                        "moov",
                        atom("mvhd", new byte[8]),
                        atom(
                                "udta",
                                atom(
                                        "meta",
                                        new byte[4],
                                        atom("ilst", atom("tmpo", atom("data", data))))));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(atom("ftyp", "M4A ".getBytes(StandardCharsets.ISO_8859_1), new byte[8]));
        if (!moovLast) file.write(moov);
        file.write(atom("mdat", new byte[4096]));
        if (moovLast) file.write(moov);
        return file.toByteArray();
    }

    static Double bpm(byte[] file) {
        return LocalPlaylistTags.bpm(new ByteArrayInputStream(file));
    }

    static void tags() throws Exception {
        check(bpm(id3(3, "TBPM", "128")) == 128, "ID3v2.3");
        check(bpm(id3(4, "TBPM", "128.5")) == 128.5, "ID3v2.4");
        check(bpm(id3(3, "TIT2", "128")) == null, "Another frame isn't a tempo");
        check(bpm(id3(3, "TBPM", "9000")) == null, "An implausible tempo isn't one");
        check(bpm(id3(3, "TBPM", "soon")) == null, "Words aren't one");
        check(bpm(id3(3, "TBPM", "0")) == null, "Zero isn't one");
        check(bpm(flac("TITLE=Song", "BPM=140")) == 140, "FLAC");
        check(bpm(flac("title=x", "tempo=99")) == 99, "FLAC keys in any case");
        check(bpm(flac("TITLE=Song")) == null, "Untagged FLAC");
        check(bpm(ogg("OpusTags", "BPM=90")) == 90, "Opus");
        check(bpm(ogg("\u0003vorbis", "TBPM=91")) == 91, "Vorbis");
        check(bpm(mp4(133, false)) == 133, "MP4");
        check(bpm(mp4(134, true)) == 134, "MP4 with its details after the audio");
        check(bpm(mp4(0, false)) == null, "MP4 without a tempo");
        check(bpm(new byte[0]) == null, "Empty file");
        check(bpm(new byte[3]) == null, "Tiny file");
        check(bpm(new byte[64]) == null, "Unknown format");

        // Damaged files never throw, whatever they claim about their sizes.
        Random random = new Random(11);
        byte[][] good = {
            id3(3, "TBPM", "128"),
            id3(4, "TBPM", "128"),
            flac("BPM=140"),
            ogg("OpusTags", "BPM=90"),
            mp4(133, false)
        };
        for (int round = 0; round < 6000; round++) {
            byte[] sample = good[round % good.length].clone();
            for (int i = 0; i < 1 + random.nextInt(8); i++)
                sample[random.nextInt(Math.min(sample.length, 200))] = (byte) random.nextInt(256);
            if (round % 5 == 0) sample = Arrays.copyOf(sample, random.nextInt(sample.length));
            Double value = bpm(sample);
            check(
                    value == null || (value >= 40 && value <= 240),
                    "Damaged files can't invent a tempo");
        }
    }

    // ── Staying local ───────────────────────────────────────────────────────

    static Path root() {
        Path at = Paths.get("").toAbsolutePath();
        while (at != null && !Files.isDirectory(at.resolve("extensions/telegram")))
            at = at.getParent();
        if (at == null) throw new AssertionError("Run from inside the repository");
        return at;
    }

    /** Whatever generation uses must ship in the shared runtime, not Telegram's optional one. */
    static void sharedRuntimeOnly() throws Exception {
        Path dir = root().resolve("extensions/telegram/src/main/java/app/bitchord/telegram");
        Set<String> allowed =
                Set.of(
                        "Strings",
                        "HostLog",
                        "DjMode",
                        "SmartDownloads",
                        "VideoSwitch",
                        "GuestPlaylists",
                        "MixedPlaylists",
                        "MixedSongCodec");
        String build =
                new String(Files.readAllBytes(root().resolve("build.py")), StandardCharsets.UTF_8);
        for (String name : allowed)
            check(build.contains("\"" + name + "\""), name + " is in the shared runtime");
        List<String> others = new ArrayList<>();
        List<String> code = new ArrayList<>();
        try (java.util.stream.Stream<Path> found = Files.list(dir)) {
            for (Path path : (Iterable<Path>) found::iterator) {
                String name = path.getFileName().toString().replace(".java", "");
                if (name.startsWith("LocalPlaylist"))
                    code.add(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                else if (!allowed.contains(name)) others.add(name);
            }
        }
        for (String text : code)
            for (String other : others)
                check(
                        !java.util.regex.Pattern.compile("\\b" + other + "\\b")
                                .matcher(text)
                                .find(),
                        "Depends on something outside the shared runtime: " + other);
    }

    static void noConnections() throws Exception {
        Path dir = root().resolve("extensions/telegram/src/main/java/app/bitchord/telegram");
        String[] banned = {
            "java.net",
            "HttpURLConnection",
            "okhttp",
            "URL(",
            "Socket",
            "SyncHttp",
            "Innertube",
            "SourceRegistry",
            "MusicSource",
            "TelegramBridge",
            "YtMusicRepository",
            "PlaylistImport",
            "SmartPlaylistService",
            "SmartPlaylistMatcher",
            "AcoustId",
            ".edit()",
            ".putString",
            "setValue",
            "compareAndSet",
            "savePlaylist",
            "createAll",
            "KeyTempo",
            "MediaExtractor",
            "MediaCodec",
            "AudioRecord"
        };
        int files = 0;
        try (java.util.stream.Stream<Path> found = Files.list(dir)) {
            for (Path path : (Iterable<Path>) found::iterator) {
                String name = path.getFileName().toString();
                if (!name.startsWith("LocalPlaylist")) continue;
                files++;
                String code = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                for (String word : banned)
                    check(!code.contains(word), name + " must stay local and read-only: " + word);
            }
        }
        check(files == 5, "All generation sources were scanned: " + files);
    }

    /** A small mixed library, tagged the patchy way a real one is, asked ordinary things. */
    static Entry[] catalogue() {
        return new Entry[] {
            song("Karma Police", "Radiohead", "Alternative Rock", 1997).plays(40).favorite(true),
            song("No Surprises", "Radiohead", "Alternative Rock", 1997).plays(12),
            song("Everything In Its Right Place", "Radiohead", "Electronic", 2000),
            song("Smells Like Teen Spirit", "Nirvana", "Grunge", 1991).plays(30),
            song("Enter Sandman", "Metallica", "Heavy Metal", 1991).explicit(false),
            song("Master of Puppets", "Metallica", "Thrash Metal", 1986),
            song("Billie Jean", "Michael Jackson", "Pop", 1982).plays(5),
            song("Dancing Queen", "ABBA", "Disco Pop", 1976),
            song("Take Five", "Dave Brubeck Quartet", "Jazz", 1959),
            song("So What", "Miles Davis", "Jazz", 1959).plays(2),
            song("Clair de Lune", "Debussy", "Classical", 1905),
            song("Weightless", "Marconi Union", "Ambient", 2011),
            song("Lo-Fi Study Session", "Chillhop Crew", "Lo-Fi", 2019),
            song("Despacito", "Luis Fonsi", "Reggaetón", 2017).plays(8),
            song("Lágrimas Negras", "Buena Vista", "Bolero", 1996),
            song("Sultans of Swing", "Dire Straits", "Rock", 1978),
            song("Blue in Green", "Miles Davis"),
            song("Night Drive", "Unknown Band").playlist("Chill evenings"),
            song("Gym Banger", "Some Producer").playlist("Workout 2024").plays(9),
            song("Around the World", "Daft Punk", "Electronic", 1997),
            song("Halo", "Beyoncé", "R&B", 2008).favorite(true),
            song("Explicit Track", "Rapper", "Hip Hop", 2015).explicit(true),
            song("Old Disliked", "Radiohead", "Alternative Rock", 1995).disliked(true),
            song("Mystery File", ""),
        };
    }

    static void catalogueRequests() throws Exception {
        Entry[] all = catalogue();

        check(
                new HashSet<>(picks("radiohead", all))
                        .equals(
                                Set.of(
                                        "Karma Police",
                                        "No Surprises",
                                        "Everything In Its Right Place")),
                "An artist, without their disliked song");
        check(picks("songs by Miles Davis", all).contains("Blue in Green"), "Untagged but named");
        check(picks("beyonce", all).equals(List.of("Halo")), "Accents");

        List<String> chill = picks("something chill to study to", all);
        check(chill.contains("Lo-Fi Study Session"), "Fits by genre and title");
        check(chill.contains("Night Drive"), "Fits by the user's own playlist");
        check(chill.contains("Weightless") && chill.contains("So What"), "Fits by genre");
        check(!chill.contains("Enter Sandman") && !chill.contains("Gym Banger"), "Heavy stays out");
        check(!chill.contains("Mystery File"), "Nothing known, nothing claimed");

        List<String> workout = picks("workout", all);
        check(workout.contains("Gym Banger"), "The user's workout playlist counts");
        check(workout.contains("Enter Sandman"), "High-energy genres fit");
        check(
                !workout.contains("Clair de Lune") && !workout.contains("Take Five"),
                "Calm stays out");

        List<String> nineties = picks("90s rock", all);
        check(nineties.contains("Karma Police"), "Decade and family");
        check(nineties.contains("Smells Like Teen Spirit"), "Grunge is rock");
        check(!nineties.contains("Sultans of Swing"), "A known 70s song is out");
        check(!nineties.contains("Halo"), "Another family is out");

        List<String> noMetal = picks("rock from the 90s without metal", all);
        check(noMetal.contains("Karma Police"), "Combined request keeps what fits");
        check(!noMetal.contains("Enter Sandman"), "Combined request drops the negated genre");

        List<String> like = picks("songs like Daft Punk", all);
        check(like.contains("Everything In Its Right Place"), "Same tagged genre");
        check(!like.contains("Around the World"), "Not the artist itself");
        check(!like.contains("Billie Jean"), "Unrelated genres stay out");

        List<String> tristes = picks("canciones tristes", all);
        check(!tristes.contains("Enter Sandman"), "Spanish mood excludes what contradicts it");
        check(tristes.contains("Clair de Lune"), "Spanish mood finds what fits");
        check(
                picks("música para dormir sin jazz", all).contains("Weightless"),
                "A Spanish phrase and negation together");
        check(
                !picks("música para dormir sin jazz", all).contains("Take Five"),
                "...with the negation applied");
        check(picks("reggaeton", all).equals(List.of("Despacito")), "Accent-free genre name");
        check(picks("r&b", all).equals(List.of("Halo")), "R&B");

        List<String> eighties = picks("happy songs from the 80s", all);
        check(eighties.contains("Billie Jean"), "Fits the mood and the decade");
        check(!eighties.contains("Master of Puppets"), "A year alone doesn't make a song happy");
        check(picks("best of the 90s", all).contains("Karma Police"), "A decade can stand alone");

        List<String> favorites = picks("my favorites", all);
        check(favorites.contains("Karma Police") && favorites.contains("Halo"), "Likes count");
        check(!favorites.contains("Old Disliked"), "A dislike outranks everything");

        Ranking clean = rank("hip hop", 10, true, true, all);
        check(clean.picks.isEmpty() && clean.explicit == 1, "Avoid explicit is respected");

        check(rank("zzqqxx", 10, true, false, all).picks.isEmpty(), "Unknown words find nothing");
        check(
                run("100 bpm", 10, all).problem == LocalPlaylist.Problem.NEEDS_TAGS,
                "No tempos known");
    }

    public static void main(String[] args) throws Exception {
        catalogueRequests();
        rarity();
        namesAreNotVocabulary();
        moodsAndGenres();
        yearsWhenKnown();
        tempoWhenKnown();
        negation();
        similarity();
        engagement();
        stableAndDiverse();
        textFolding();
        emptyAndInvalid();
        caps();
        cancellation();
        combining();
        listening();
        tempoLookups();
        tags();
        noConnections();
        sharedRuntimeOnly();
        System.out.println("LocalPlaylistTest: " + checks + " checks passed");
    }
}
