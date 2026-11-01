package app.bitchord.telegram;

import app.bitchord.telegram.LocalPlaylistLexicon.Family;
import app.bitchord.telegram.LocalPlaylistLexicon.Genre;
import app.bitchord.telegram.LocalPlaylistLexicon.Mood;
import app.bitchord.telegram.LocalPlaylistLexicon.MoodKind;
import app.bitchord.telegram.LocalPlaylistLexicon.Reading;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Turns a description into a ranked list of songs the user already has. Pure Java: no Android, no
 * files, no connections, and the same input always gives the same order.
 *
 * <p>Rarity-weighted scoring, the mood energy bands and the weights are adapted from PixelMusic's
 * data/playlist/nlp/{PlaylistIntentEngine,LibraryIndex}.kt (commit 991f7c5), Copyright (C)
 * ianshulyadav and PixelMusic contributors, GNU GPL v3.
 *
 * <p>A song is only included on evidence it really carries. Known metadata can rule a song out;
 * unknown metadata never rules one in, and never rules one out.
 */
final class LocalPlaylistEngine {
    private LocalPlaylistEngine() {}

    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_ENTRIES = 20000;
    private static final int MAX_FIELD = 300;
    private static final int MAX_FIELD_WORDS = 64;
    private static final int MAX_PROMPT_WORDS = 96;
    private static final int MAX_PHRASE_WORDS = 8;
    private static final int RECENT_POOL = 20;

    private static final double YEAR_BONUS = 25,
            GENRE_BONUS = 30,
            FAMILY_BONUS = 18,
            ARTIST_MENTION = 40,
            ALBUM_MENTION = 35,
            TITLE_MENTION = 30,
            ARTIST_WORD = 15,
            TITLE_WORD = 10,
            PLAYLIST_WORD = 8,
            ALBUM_WORD = 5,
            INHERITED = .6,
            MOOD_IN_TEXT = 8,
            MOOD_IN_PLAYLIST = .7,
            MOOD_CAP = 45,
            SIMILAR_GENRE = 30,
            SIMILAR_PLAYLIST = 20,
            KNOWN_TEMPO = 30,
            TEMPO_SIGMA = 18,
            TEMPO_TOLERANCE = 8,
            TEMPO_LIMIT = 20,
            MOOD_TEMPO = 15,
            MOOD_TEMPO_LIMIT = 45,
            MOOD_TEMPO_PENALTY = 20,
            ESTIMATE_MAX = 15,
            ESTIMATE_SIGMA = 32,
            ESTIMATE_EVIDENCE = 5,
            INHERITED_CONFLICT = 30,
            MIN_RARITY = .05;

    /** Why a song was picked. Tempo reasons are only ever used for a real tempo value. */
    enum Why {
        ARTIST,
        TITLE,
        ALBUM,
        PLAYLIST,
        GENRE,
        ARTIST_GENRE,
        YEAR,
        TEMPO_TAGGED,
        TEMPO_MEASURED,
        ENERGY_ESTIMATE,
        SIMILAR_GENRE,
        SIMILAR_PLAYLIST,
        LIKED,
        PLAYED,
        UNPLAYED,
        RECENT
    }

    enum Tempo {
        TAGGED,
        MEASURED
    }

    /** A song and what is really known about it. Anything unknown stays null. */
    static final class Entry {
        final Object song;
        final String identity, title, artist, album;
        final Set<String> playlists = new LinkedHashSet<>();
        String genre, location;
        Integer year, plays;
        Double bpm;
        Tempo tempo;
        Boolean explicit, favorite;
        boolean disliked;
        Long addedSeconds, durationSeconds;

        Entry(Object song, String identity, String title, String artist, String album) {
            this.song = song;
            this.identity = identity;
            this.title = text(title);
            this.artist = text(artist);
            this.album = text(album);
        }

        Entry genre(String value) {
            genre = value;
            return this;
        }

        Entry year(Integer value) {
            year = value;
            return this;
        }

        Entry bpm(double value, Tempo from) {
            bpm = value;
            tempo = from;
            return this;
        }

        Entry plays(Integer value) {
            plays = value;
            return this;
        }

        Entry favorite(Boolean value) {
            favorite = value;
            return this;
        }

        Entry explicit(Boolean value) {
            explicit = value;
            return this;
        }

        Entry disliked(boolean value) {
            disliked = value;
            return this;
        }

        Entry added(Long seconds) {
            addedSeconds = seconds;
            return this;
        }

        Entry playlist(String title) {
            if (title != null && !title.trim().isEmpty()) playlists.add(title);
            return this;
        }

        int playCount() {
            return plays == null ? 0 : plays;
        }

        private static String text(String value) {
            return value == null ? "" : value.trim();
        }
    }

    static void check(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || (cancelled != null && cancelled.getAsBoolean()))
            throw new InterruptedException();
    }

    // ── Library index ───────────────────────────────────────────────────────

    private static final class Song {
        final Entry entry;
        final Set<String> title, artist, album, playlist, genreStems, genreWords;
        final Set<String> credits;
        final String mainArtist, recording, titleText, albumText;
        final Reading reading;

        /** Tags on this artist's other songs, used softly when this one has none. */
        Profile inherited;

        Song(Entry entry) {
            this.entry = entry;
            title = stems(entry.title);
            artist = stems(entry.artist);
            album = stems(entry.album);
            Set<String> inPlaylists = new HashSet<>();
            for (String name : entry.playlists) inPlaylists.addAll(stems(name));
            playlist = inPlaylists;
            String tag = LocalPlaylistLexicon.fold(limit(entry.genre));
            genreWords = new HashSet<>(LocalPlaylistLexicon.words(tag));
            genreStems = new HashSet<>();
            for (String word : genreWords) genreStems.add(LocalPlaylistLexicon.stem(word));
            reading = LocalPlaylistLexicon.resolve(genreWords);
            credits = credits(entry.artist);
            String main = "";
            for (String name : credits) {
                main = name;
                break;
            }
            mainArtist = main;
            recording = recordingKey(entry.title, main);
            titleText = joined(LocalPlaylistLexicon.fold(limit(entry.title)));
            albumText = joined(LocalPlaylistLexicon.fold(limit(entry.album)));
        }

        boolean tagged() {
            return !genreStems.isEmpty();
        }
    }

    private static final class Profile {
        final Set<String> stems = new HashSet<>();
        final Set<Family> families = EnumSet.noneOf(Family.class);
        final Set<String> words = new HashSet<>();
        double energy;
        int energies;

        Double energy() {
            return energies == 0 ? null : energy / energies;
        }
    }

    private static String limit(String value) {
        if (value == null) return "";
        return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD);
    }

    private static Set<String> stems(String value) {
        Set<String> out = new HashSet<>();
        List<String> words = LocalPlaylistLexicon.words(LocalPlaylistLexicon.fold(limit(value)));
        for (int i = 0; i < words.size() && i < MAX_FIELD_WORDS; i++)
            out.add(LocalPlaylistLexicon.stem(words.get(i)));
        return out;
    }

    /** Credited names of an artist field, lead first: "A feat. B", "A, B", "A & B". */
    private static Set<String> credits(String artist) {
        Set<String> names = new LinkedHashSet<>();
        String folded = LocalPlaylistLexicon.fold(limit(artist)).trim();
        if (folded.isEmpty()) return names;
        String whole = joined(folded);
        names.add(joined(folded.replace("&", " and ")));
        String[] parts =
                folded.split("\\s*(?:,|;|/|&|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bfeaturing\\b)\\s*");
        Set<String> lead = new LinkedHashSet<>();
        if (parts.length > 0 && !parts[0].trim().isEmpty()) lead.add(joined(parts[0]));
        lead.addAll(names);
        names = lead;
        names.add(whole);
        for (String part : parts) if (!part.trim().isEmpty()) names.add(joined(part));
        names.remove("");
        return names;
    }

    private static String joined(String folded) {
        return String.join(" ", LocalPlaylistLexicon.parts(folded));
    }

    /** Same song on another source: title without upload decorations, plus the lead artist. */
    private static String recordingKey(String title, String mainArtist) {
        if (mainArtist.isEmpty()) return "";
        String clean =
                LocalPlaylistLexicon.fold(limit(title))
                        .replaceAll(
                                "[(\\[][^)\\]]*\\b(?:official|video|audio|lyrics?|visuali[sz]er"
                                        + "|hd|hq|4k|explicit)\\b[^)\\]]*[)\\]]",
                                " ");
        String name = joined(clean);
        return name.isEmpty() ? "" : name + "\n" + mainArtist;
    }

    /** The library prepared for scoring. Immutable, so one index can serve many requests. */
    static final class Index {
        private final List<Song> songs = new ArrayList<>();
        private final Map<String, Integer> frequency = new HashMap<>();
        private final Map<String, Integer> phrases = new HashMap<>();
        private final Map<String, Integer> titleWords = new HashMap<>();
        private final Map<String, Integer> playlistSizes = new HashMap<>();
        private double maxIdf;

        int size() {
            return songs.size();
        }

        double rarity(String stem) {
            Integer df = frequency.get(stem);
            if (df == null) return 1;
            double idf = Math.max(MIN_RARITY, Math.log(1.0 + (double) songs.size() / df));
            return Math.max(MIN_RARITY, Math.min(1, idf / maxIdf));
        }
    }

    private static final int PHRASE_ARTIST = 1, PHRASE_ALBUM = 2, PHRASE_TITLE = 4;

    static Index index(List<Entry> entries, BooleanSupplier cancelled) throws InterruptedException {
        Index index = new Index();
        Map<String, Profile> profiles = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (index.songs.size() >= MAX_ENTRIES) break;
            if (index.songs.size() % 128 == 0) check(cancelled);
            if (!seen.add(entry.identity)) continue;
            Song song = new Song(entry);
            index.songs.add(song);
            Set<String> terms = new HashSet<>(song.title);
            terms.addAll(song.artist);
            terms.addAll(song.album);
            terms.addAll(song.genreStems);
            for (String term : terms) index.frequency.merge(term, 1, Integer::sum);
            for (String name : song.credits) {
                addPhrase(index, name, PHRASE_ARTIST);
                if (name.startsWith("the ")) addPhrase(index, name.substring(4), PHRASE_ARTIST);
            }
            if (song.albumText.indexOf(' ') > 0) addPhrase(index, song.albumText, PHRASE_ALBUM);
            if (song.titleText.indexOf(' ') > 0) addPhrase(index, song.titleText, PHRASE_TITLE);
            for (String word : LocalPlaylistLexicon.words(song.titleText + " " + song.albumText))
                index.titleWords.merge(word, 1, Integer::sum);
            for (String name : entry.playlists) index.playlistSizes.merge(name, 1, Integer::sum);
            if (song.tagged() && !song.mainArtist.isEmpty()) {
                Profile profile = profiles.computeIfAbsent(song.mainArtist, k -> new Profile());
                profile.stems.addAll(song.genreStems);
                profile.words.addAll(song.genreWords);
                profile.families.addAll(song.reading.families);
                if (song.reading.energy != null) {
                    profile.energy += song.reading.energy;
                    profile.energies++;
                }
            }
        }
        index.maxIdf = Math.log(1.0 + Math.max(1, index.songs.size()));
        for (Song song : index.songs)
            if (!song.tagged()) song.inherited = profiles.get(song.mainArtist);
        return index;
    }

    private static void addPhrase(Index index, String phrase, int kind) {
        if (phrase.isEmpty()) return;
        if (phrase.split(" ").length > MAX_PHRASE_WORDS) return;
        index.phrases.merge(phrase, kind, (a, b) -> a | b);
    }

    // ── Reading a description ───────────────────────────────────────────────

    /** The structured meaning of a description against one library. */
    static final class Query {
        final Set<String> stems = new LinkedHashSet<>();
        final Set<String> freeStems = new LinkedHashSet<>();
        final Map<String, Double> weights = new HashMap<>();
        final Set<String> artists = new LinkedHashSet<>(), albums = new LinkedHashSet<>();
        final Set<String> titles = new LinkedHashSet<>();
        final List<int[]> years = new ArrayList<>(), notYears = new ArrayList<>();
        final Map<Mood, Double> moods = new EnumMap<>(Mood.class);
        final Set<Mood> notMoods = EnumSet.noneOf(Mood.class);
        final Set<Family> families = EnumSet.noneOf(Family.class);
        final Set<String> genreWords = new LinkedHashSet<>(), genreStems = new HashSet<>();
        final Set<String> notStems = new LinkedHashSet<>(), notPhrases = new LinkedHashSet<>();
        final Set<String> notGenreWords = new LinkedHashSet<>();
        final Set<Family> notFamilies = EnumSet.noneOf(Family.class);
        Double bpmLow, bpmHigh;
        String similarTo;
        boolean keepSeed;

        /** A name was given clearly enough that other artists don't belong in the list. */
        boolean strictNames;

        boolean hasTempoTarget() {
            return bpmLow != null || bpmHigh != null;
        }

        /** The mood's implied tempo; used softly, and only against a real value. */
        Double moodBpm() {
            double sum = 0;
            int count = 0;
            for (Mood mood : moods.keySet())
                if (mood.bpm != null) {
                    sum += mood.bpm;
                    count++;
                }
            return count == 0 ? null : sum / count;
        }

        boolean wantsTempo() {
            return hasTempoTarget() || moodBpm() != null;
        }

        /** Something positive to look for, rather than only things to leave out. */
        boolean positive() {
            return !stems.isEmpty()
                    || !artists.isEmpty()
                    || !albums.isEmpty()
                    || !titles.isEmpty()
                    || !years.isEmpty()
                    || !moods.isEmpty()
                    || !families.isEmpty()
                    || hasTempoTarget()
                    || similarTo != null;
        }

        /** Anything to go on besides a "similar to" artist. */
        private boolean beyondSimilar() {
            return !stems.isEmpty()
                    || !artists.isEmpty()
                    || !albums.isEmpty()
                    || !titles.isEmpty()
                    || !years.isEmpty()
                    || !moods.isEmpty()
                    || !families.isEmpty()
                    || hasTempoTarget();
        }

        boolean named() {
            return !artists.isEmpty() || !albums.isEmpty() || !titles.isEmpty();
        }

        /**
         * Says what kind of music is wanted. A year or tempo then narrows that down, rather than
         * qualifying a song that has nothing else in common with the request.
         */
        boolean describes() {
            return named()
                    || similarTo != null
                    || !freeStems.isEmpty()
                    || !families.isEmpty()
                    || genreMood();
        }

        /** Likes, plays or dates are all that was asked for, so they can qualify a song. */
        boolean behaviourOnly() {
            return !named()
                    && similarTo == null
                    && freeStems.isEmpty()
                    && years.isEmpty()
                    && families.isEmpty()
                    && !hasTempoTarget()
                    && !genreMood();
        }

        /** Asks only for metadata a song might simply not have, not for a name or a word. */
        boolean metadataOnly() {
            return freeStems.isEmpty()
                    && artists.isEmpty()
                    && albums.isEmpty()
                    && titles.isEmpty()
                    && similarTo == null
                    && (!years.isEmpty() || hasTempoTarget() || !families.isEmpty() || genreMood());
        }

        private boolean genreMood() {
            for (Mood mood : moods.keySet()) if (mood.kind == MoodKind.GENRE) return true;
            return false;
        }
    }

    private static final Map<String, Integer> DECADE_WORDS = new HashMap<>();

    static {
        DECADE_WORDS.put("fifties", 1950);
        DECADE_WORDS.put("sixties", 1960);
        DECADE_WORDS.put("seventies", 1970);
        DECADE_WORDS.put("eighties", 1980);
        DECADE_WORDS.put("nineties", 1990);
        DECADE_WORDS.put("noughties", 2000);
        DECADE_WORDS.put("cincuentas", 1950);
        DECADE_WORDS.put("sesentas", 1960);
        DECADE_WORDS.put("setentas", 1970);
        DECADE_WORDS.put("ochentas", 1980);
        DECADE_WORDS.put("noventas", 1990);
    }

    /** Pure and free of side effects. {@code description} is capped by the caller's limit. */
    static Query parse(String description, Index index) {
        Query query = new Query();
        String text = description == null ? "" : description;
        if (text.length() > MAX_DESCRIPTION) text = text.substring(0, MAX_DESCRIPTION);
        text = directives(text, query);
        String folded =
                LocalPlaylistLexicon.fold(text)
                        .replaceAll("\\b(?:don't|dont|do not|doesn't|didn't)\\b", " not ")
                        .replace(",", " and ");
        List<String> words = new ArrayList<>(LocalPlaylistLexicon.parts(folded));
        if (words.size() > MAX_PROMPT_WORDS)
            words = new ArrayList<>(words.subList(0, MAX_PROMPT_WORDS));
        boolean[] used = new boolean[words.size()];
        Parser parser = new Parser(query, index, words, used);
        parser.mentions();
        parser.negations();
        parser.tempos();
        parser.years();
        parser.genres();
        parser.moods();
        parser.remaining();
        return query;
    }

    private static String directives(String text, Query query) {
        java.util.regex.Matcher target =
                java.util.regex.Pattern.compile(
                                "\\[\\s*target\\s+bpm\\s*:\\s*(\\d{2,3})[^\\]]*\\]",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(text);
        if (target.find()) tempoAround(query, Integer.parseInt(target.group(1)));
        return text.replaceAll("(?i)\\[\\s*target\\s+(?:bpm|length)\\s*:[^\\]]*\\]", " ");
    }

    private static void tempoAround(Query query, double bpm) {
        if (bpm < 40 || bpm > 240) return;
        query.bpmLow = bpm - TEMPO_TOLERANCE;
        query.bpmHigh = bpm + TEMPO_TOLERANCE;
    }

    private static final class Parser {
        final Query query;
        final Index index;
        final List<String> words;
        final boolean[] used;

        /** Span starts of artists found, for similarity cues. */
        final Map<Integer, Integer> artistSpans = new TreeMap<>();

        final Map<Integer, Integer> spanKinds = new HashMap<>();

        Parser(Query query, Index index, List<String> words, boolean[] used) {
            this.query = query;
            this.index = index;
            this.words = words;
            this.used = used;
        }

        private String span(int from, int to) {
            return String.join(" ", words.subList(from, to));
        }

        /**
         * Names found in the library are claimed first, so their words aren't read as anything
         * else: "No Doubt" isn't a negation, "Daft Punk" isn't a punk request.
         */
        void mentions() {
            for (int i = 0; i < words.size(); ) {
                int end = -1, kinds = 0;
                for (int to = Math.min(words.size(), i + MAX_PHRASE_WORDS); to > i; to--) {
                    Integer found = index.phrases.get(span(i, to));
                    if (found == null) continue;
                    if (to - i == 1 && !plainName(words.get(i), found)) continue;
                    if (to - i >= 2 && isVocabulary(i, to, found)) continue;
                    if (found == PHRASE_TITLE
                            && LocalPlaylistLexicon.NEGATION_CUES.contains(words.get(i))) continue;
                    end = to;
                    kinds = found;
                    break;
                }
                if (end < 0) {
                    i++;
                    continue;
                }
                spanKinds.put(i, kinds);
                artistSpans.put(i, end);
                i = end;
            }
        }

        private boolean plainName(String word, int kinds) {
            if ((kinds & PHRASE_ARTIST) == 0) return false;
            String stem = LocalPlaylistLexicon.stem(word);
            if (LocalPlaylistLexicon.STOP_WORDS.contains(word)) return false;
            if (LocalPlaylistLexicon.NEGATION_CUES.contains(word)) return false;
            if (LocalPlaylistLexicon.requested(word) != null) return false;
            for (Mood mood : Mood.values()) if (mood.triggers.contains(stem)) return false;
            return !DECADE_WORDS.containsKey(word) && !isNumber(word);
        }

        /** A title made of mood words ("Good Mood", "Chill Vibes") doesn't hide the mood. */
        private boolean isVocabulary(int from, int to, int kinds) {
            if ((kinds & PHRASE_ARTIST) != 0) return false;
            String phrase = span(from, to);
            for (Mood mood : Mood.values()) if (mood.phrases.contains(phrase)) return true;
            for (int i = from; i < to; i++) {
                String word = words.get(i);
                if (LocalPlaylistLexicon.STOP_WORDS.contains(word)) continue;
                if (LocalPlaylistLexicon.requested(word) != null) continue;
                if (!moodWord(LocalPlaylistLexicon.stem(word))) return false;
            }
            return true;
        }

        private boolean moodWord(String stem) {
            for (Mood mood : Mood.values()) if (mood.triggers.contains(stem)) return true;
            return false;
        }

        private boolean isNumber(String word) {
            for (int i = 0; i < word.length(); i++)
                if (!Character.isDigit(word.charAt(i))) return false;
            return true;
        }

        /** Applies the found names, now knowing which are negated or asked to be similar to. */
        private void apply(int start, int end, boolean negated) {
            int kinds = spanKinds.get(start);
            String name = span(start, end);
            for (int i = start; i < end; i++) used[i] = true;
            if (negated) {
                query.notPhrases.add(name);
                return;
            }
            if ((kinds & PHRASE_ARTIST) != 0) {
                int cue = similarCue(start, end);
                if (cue >= 0 && query.similarTo == null) {
                    query.similarTo = name;
                    for (int i = cue; i < start; i++) used[i] = true;
                    return;
                }
                if (startsSimilarTail(end)) {
                    query.similarTo = name;
                    query.keepSeed = true;
                    return;
                }
                query.artists.add(name);
                if (end - start == 1) {
                    // A one-word band can also just be a word: "love" may mean either.
                    String stem = LocalPlaylistLexicon.stem(name);
                    query.stems.add(stem);
                    query.weights.put(stem, index.rarity(stem));
                    if (index.titleWords.getOrDefault(name, 0) < 3) query.strictNames = true;
                } else query.strictNames = true;
            }
            if ((kinds & PHRASE_ALBUM) != 0) query.albums.add(name);
            if ((kinds & PHRASE_TITLE) != 0) query.titles.add(name);
            if ((kinds & (PHRASE_ALBUM | PHRASE_TITLE)) != 0) query.strictNames = true;
        }

        /** Index of the cue's first word right before an artist, or -1. "I like X" isn't one. */
        private int similarCue(int start, int end) {
            for (String[] cue : LocalPlaylistLexicon.SIMILAR_CUES) {
                int from = start - cue.length;
                if (from < 0) continue;
                boolean all = true;
                for (int i = 0; i < cue.length; i++)
                    all &= !used[from + i] && words.get(from + i).equals(cue[i]);
                if (!all) continue;
                if (cue.length == 1 && cue[0].equals("like") && from > 0) {
                    String before = words.get(from - 1);
                    if (before.equals("i") || before.equals("we") || before.equals("really"))
                        return -1;
                }
                return from;
            }
            return -1;
        }

        private boolean startsSimilarTail(int end) {
            if (end + 1 >= words.size()) return false;
            String first = words.get(end), second = words.get(end + 1);
            boolean tail =
                    (first.equals("and") && second.equals("similar"))
                            || (first.equals("y") && second.startsWith("similar"));
            if (tail) {
                used[end] = true;
                used[end + 1] = true;
            }
            return tail;
        }

        void negations() {
            Set<Integer> negatedSpans = new HashSet<>();
            for (int i = 0; i < words.size(); i++) {
                if (used[i]
                        || inSpan(i)
                        || !LocalPlaylistLexicon.NEGATION_CUES.contains(words.get(i))) continue;
                used[i] = true;
                int at = i + 1;
                while (at < words.size()) {
                    while (at < words.size()
                            && !artistSpans.containsKey(at)
                            && LocalPlaylistLexicon.STOP_WORDS.contains(words.get(at)))
                        used[at++] = true;
                    if (at >= words.size()) break;
                    Integer spanEnd = artistSpans.get(at);
                    if (spanEnd != null) {
                        negatedSpans.add(at);
                        apply(at, spanEnd, true);
                        at = spanEnd;
                    } else if (notYear(at)) {
                        used[at] = true;
                        at++;
                    } else if (notMoodPhrase(at)) {
                        at = phraseEnd;
                    } else {
                        negateWord(words.get(at));
                        used[at] = true;
                        at++;
                    }
                    if (at >= words.size()) break;
                    String next = words.get(at);
                    if (next.equals("or") || next.equals("nor") || next.equals("ni")) {
                        used[at++] = true;
                    } else if ((next.equals("and") || next.equals("y"))
                            && at + 1 < words.size()
                            && (LocalPlaylistLexicon.requested(words.get(at + 1)) != null
                                    || artistSpans.containsKey(at + 1))) {
                        used[at++] = true;
                    } else break;
                }
                i = at - 1;
            }
            for (Map.Entry<Integer, Integer> span : artistSpans.entrySet())
                if (!negatedSpans.contains(span.getKey()) && !used[span.getKey()])
                    apply(span.getKey(), span.getValue(), false);
        }

        private boolean inSpan(int at) {
            for (Map.Entry<Integer, Integer> span : artistSpans.entrySet())
                if (at >= span.getKey() && at < span.getValue()) return true;
            return false;
        }

        private int phraseEnd;

        private boolean notMoodPhrase(int at) {
            for (Mood mood : Mood.values())
                for (String phrase : mood.phrases) {
                    String[] parts = phrase.split(" ");
                    if (at + parts.length > words.size()) continue;
                    if (!span(at, at + parts.length).equals(phrase)) continue;
                    query.notMoods.add(mood);
                    for (int i = at; i < at + parts.length; i++) used[i] = true;
                    phraseEnd = at + parts.length;
                    return true;
                }
            return false;
        }

        private void negateWord(String word) {
            String stem = LocalPlaylistLexicon.stem(word);
            query.notStems.add(stem);
            Genre genre = LocalPlaylistLexicon.requested(word);
            if (genre != null) {
                // "No jazz" is about jazz. It doesn't take back the chill mood jazz also suits.
                query.notGenreWords.add(word);
                Family family = LocalPlaylistLexicon.familyNamed(word);
                if (family != null) query.notFamilies.add(family);
                return;
            }
            for (Mood mood : Mood.values())
                if (mood.kind == MoodKind.GENRE && mood.triggers.contains(stem))
                    query.notMoods.add(mood);
        }

        private boolean notYear(int at) {
            int[] range = decade(at);
            String word = words.get(at);
            if (range == null && word.matches("\\d{4}")) {
                int year = Integer.parseInt(word);
                String previous = at > 0 ? words.get(at - 1) : "";
                if (year < 1900 || year > 2100) return false;
                if (previous.equals("before")) range = new int[] {0, year - 1};
                else if (previous.equals("after")) range = new int[] {year + 1, 9999};
                else if (previous.equals("since")) range = new int[] {year, 9999};
                else range = new int[] {year, year};
            }
            if (range == null) return false;
            query.notYears.add(range);
            return true;
        }

        /** A decade or a marked year at a word, without using it up. */
        private int[] decade(int at) {
            String word = words.get(at);
            Integer named = DECADE_WORDS.get(word);
            if (named != null) return new int[] {named, named + 9};
            if (word.matches("\\d{2}s")) {
                int two = Integer.parseInt(word.substring(0, 2));
                int start = two < 30 ? 2000 + two : 1900 + two;
                return new int[] {start, start + 9};
            }
            if (word.matches("\\d{4}s")) {
                int start = Integer.parseInt(word.substring(0, 4));
                return start >= 1900 && start <= 2100 ? new int[] {start, start + 9} : null;
            }
            return null;
        }

        void tempos() {
            for (int i = 0; i < words.size(); i++) {
                if (used[i]) continue;
                String word = words.get(i);
                Integer value = null;
                int numberAt = i;
                if (word.matches("\\d{2,3}bpm")) value = Integer.parseInt(word.replace("bpm", ""));
                else if (word.equals("bpm")) {
                    if (i > 0 && !used[i - 1] && words.get(i - 1).matches("\\d{2,3}")) {
                        value = Integer.parseInt(words.get(i - 1));
                        numberAt = i - 1;
                    } else if (i + 1 < words.size() && words.get(i + 1).matches("\\d{2,3}")) {
                        value = Integer.parseInt(words.get(i + 1));
                        numberAt = i + 1;
                    }
                }
                if (value == null || value < 40 || value > 240) continue;
                used[i] = true;
                used[numberAt] = true;
                int before = Math.min(i, numberAt) - 1;
                if (before >= 0
                        && !used[before]
                        && words.get(before).matches("\\d{2,3}")
                        && Integer.parseInt(words.get(before)) < value) {
                    used[before] = true;
                    query.bpmLow = (double) Integer.parseInt(words.get(before));
                    query.bpmHigh = (double) value;
                    continue;
                }
                String cue = before >= 0 ? words.get(before) : "";
                String cue2 = before >= 1 ? words.get(before - 1) + " " + cue : "";
                boolean below =
                        cue.equals("under")
                                || cue.equals("below")
                                || cue.equals("max")
                                || cue2.equals("less than")
                                || cue2.equals("slower than")
                                || cue2.equals("at most");
                boolean above =
                        cue.equals("over")
                                || cue.equals("above")
                                || cue.equals("min")
                                || cue2.equals("more than")
                                || cue2.equals("faster than")
                                || cue2.equals("at least");
                if (!below && !above) {
                    tempoAround(query, value);
                    continue;
                }
                if (below) query.bpmHigh = (double) value;
                else query.bpmLow = (double) value;
                used[before] = true;
                if (!cue2.isEmpty()
                        && cue2.indexOf(' ') > 0
                        && !cue.matches("under|below|max|over|above|min")) used[before - 1] = true;
            }
        }

        void years() {
            for (int i = 0; i < words.size(); i++) {
                if (used[i]) continue;
                String word = words.get(i);
                String previous = i > 0 ? words.get(i - 1) : "";
                int[] range = decade(i);
                if (range != null) {
                    if (previous.equals("early")) range[1] = range[0] + 3;
                    else if (previous.equals("mid")) {
                        range[1] = range[0] + 6;
                        range[0] = range[0] + 3;
                    } else if (previous.equals("late")) range[0] = range[0] + 6;
                    if (previous.equals("early")
                            || previous.equals("mid")
                            || previous.equals("late")) used[i - 1] = true;
                    used[i] = true;
                    query.years.add(range);
                    continue;
                }
                if (!word.matches("\\d{4}")) continue;
                int year = Integer.parseInt(word);
                if (year < 1900 || year > 2100) continue;
                if (previous.equals("before")
                        || previous.equals("pre")
                        || previous.equals("until")
                        || previous.equals("till")) {
                    query.years.add(new int[] {0, year - 1});
                } else if (previous.equals("after")
                        || previous.equals("post")
                        || previous.equals("since")) {
                    query.years.add(new int[] {previous.equals("since") ? year : year + 1, 9999});
                } else if (i + 1 < words.size()
                        && words.get(i + 1).matches("\\d{4}")
                        && Integer.parseInt(words.get(i + 1)) > year) {
                    used[i + 1] = true;
                    query.years.add(new int[] {year, Integer.parseInt(words.get(i + 1))});
                } else if (i + 2 < words.size()
                        && (words.get(i + 1).equals("to") || words.get(i + 1).equals("through"))
                        && words.get(i + 2).matches("\\d{4}")) {
                    used[i + 2] = true;
                    query.years.add(new int[] {year, Integer.parseInt(words.get(i + 2))});
                } else if (previous.equals("from")
                        || previous.equals("in")
                        || previous.equals("year")
                        || previous.equals("released")
                        || !index.titleWords.containsKey(word)) {
                    query.years.add(new int[] {year, year});
                } else continue;
                used[i] = true;
            }
        }

        void genres() {
            for (int i = 0; i < words.size(); i++) {
                if (used[i]) continue;
                String word = words.get(i);
                Genre genre = LocalPlaylistLexicon.requested(word);
                if (genre == null) continue;
                query.families.add(genre.family);
                query.genreWords.add(word);
                String stem = LocalPlaylistLexicon.stem(word);
                query.genreStems.add(stem);
                query.stems.add(stem);
                query.weights.put(stem, index.rarity(stem));
                used[i] = true;
            }
        }

        void moods() {
            StringBuilder open = new StringBuilder(" ");
            for (int i = 0; i < words.size(); i++)
                if (!used[i]) open.append(words.get(i)).append(' ');
            String text = open.toString();
            List<String> phrases = new ArrayList<>();
            for (Mood mood : Mood.values()) {
                if (query.notMoods.contains(mood)) continue;
                double strength = 0;
                for (int i = 0; i < words.size(); i++)
                    if (!used[i] && mood.triggers.contains(LocalPlaylistLexicon.stem(words.get(i))))
                        strength += .5;
                for (String phrase : mood.phrases)
                    if (text.contains(" " + phrase + " ")) {
                        strength += 1;
                        phrases.add(phrase);
                        break;
                    }
                if (strength > 0) query.moods.put(mood, Math.min(1, strength));
            }
            // A phrase's words have said what they mean and aren't searched for as well.
            for (String phrase : phrases) {
                String[] parts = phrase.split(" ");
                for (int i = 0; i + parts.length <= words.size(); i++) {
                    if (!span(i, i + parts.length).equals(phrase)) continue;
                    for (int n = i; n < i + parts.length; n++) used[n] = true;
                }
            }
        }

        void remaining() {
            for (int i = 0; i < words.size(); i++) {
                String word = words.get(i);
                if (used[i] || !LocalPlaylistLexicon.meaningful(word)) continue;
                if (LocalPlaylistLexicon.STOP_WORDS.contains(word)) continue;
                if (isNumber(word) && !index.titleWords.containsKey(word)) continue;
                String stem = LocalPlaylistLexicon.stem(word);
                query.stems.add(stem);
                query.weights.put(stem, index.rarity(stem));
                boolean moodWord = false;
                for (Mood mood : Mood.values()) moodWord |= mood.triggers.contains(stem);
                if (!moodWord) query.freeStems.add(stem);
            }
        }
    }

    // ── Ranking ─────────────────────────────────────────────────────────────

    static final class Pick {
        final Entry entry;
        final double score;
        final Set<Why> why;
        private final String recording, artist;
        private final boolean named;

        private Pick(Song song, double score, Score reasons) {
            entry = song.entry;
            this.score = score;
            why = reasons.why;
            recording = song.recording;
            artist = song.mainArtist;
            named = reasons.named;
        }
    }

    /** The outcome of one request. Everything is counted so the caller can explain a short list. */
    static final class Ranking {
        final List<Pick> picks = new ArrayList<>();

        /** Not contradicted, but nothing supports them either. Never mixed into {@link #picks}. */
        final List<Entry> unverified = new ArrayList<>();

        int matched, contradicted, disliked, explicit;

        /** "Similar to X" found X, but no tags or shared playlists to compare it with. */
        boolean similarUnsupported;
    }

    private static final class Score {
        double value;
        boolean evidence, ruledOut, penalized, named, narrowed;
        final Set<Why> why = EnumSet.noneOf(Why.class);

        void add(double points, Why reason) {
            value += points;
            evidence = true;
            why.add(reason);
        }
    }

    /** Similarity evidence around the seed artist, gathered once per request. */
    private static final class Seed {
        final Set<String> stems = new HashSet<>();
        final Set<Family> families = EnumSet.noneOf(Family.class);
        final Set<String> playlists = new HashSet<>();

        boolean supported() {
            return !stems.isEmpty() || !families.isEmpty() || !playlists.isEmpty();
        }
    }

    static Ranking rank(
            Query query,
            Index index,
            int count,
            boolean familiar,
            boolean avoidExplicit,
            BooleanSupplier cancelled)
            throws InterruptedException {
        Ranking ranking = new Ranking();
        Seed seed = query.similarTo == null ? null : seed(query.similarTo, index);
        if (seed != null && !seed.supported()) {
            ranking.similarUnsupported = true;
            if (!query.keepSeed && !query.beyondSimilar()) return ranking;
            seed = null;
        }
        Set<String> recent = query.moods.containsKey(Mood.RECENT) ? recent(index) : null;
        List<Pick> matches = new ArrayList<>();
        List<Entry> unverified = new ArrayList<>();
        int done = 0;
        boolean anyNamed = false;
        for (Song song : index.songs) {
            if (done++ % 128 == 0) check(cancelled);
            Entry entry = song.entry;
            if (entry.disliked) {
                ranking.disliked++;
                continue;
            }
            if (avoidExplicit && explicit(entry)) {
                ranking.explicit++;
                continue;
            }
            Score score = score(song, query, index, seed, recent);
            if (score.ruledOut) {
                ranking.contradicted++;
                continue;
            }
            // Likes and plays only order songs that fit; they can't qualify or disqualify one.
            boolean fits =
                    score.value > 0 && (score.evidence || (score.narrowed && !query.describes()));
            engagement(score, entry, familiar);
            if (fits || !query.positive()) {
                if (score.named) anyNamed = true;
                matches.add(new Pick(song, score.value, score));
            } else if (query.metadataOnly() && !score.penalized) unverified.add(entry);
        }
        if (query.strictNames && anyNamed) matches.removeIf(pick -> !pick.named);
        ranking.matched = matches.size();
        matches.sort(BEST_FIRST);
        unverified.sort(MOST_PLAYED);
        for (Entry entry : unverified) {
            if (ranking.unverified.size() >= count) break;
            ranking.unverified.add(entry);
        }
        ranking.picks.addAll(select(matches, count, query.named()));
        return ranking;
    }

    private static final Comparator<Pick> BEST_FIRST =
            (a, b) -> {
                int order = Double.compare(b.score, a.score);
                if (order != 0) return order;
                order = Integer.compare(b.entry.playCount(), a.entry.playCount());
                return order != 0 ? order : a.entry.identity.compareTo(b.entry.identity);
            };

    private static final Comparator<Entry> MOST_PLAYED =
            (a, b) -> {
                int order = Integer.compare(b.playCount(), a.playCount());
                return order != 0 ? order : a.identity.compareTo(b.identity);
            };

    private static boolean explicit(Entry entry) {
        if (Boolean.TRUE.equals(entry.explicit)) return true;
        return LocalPlaylistLexicon.fold(limit(entry.title)).matches(".*[(\\[]explicit[)\\]].*");
    }

    private static Set<String> recent(Index index) {
        List<Entry> dated = new ArrayList<>();
        for (Song song : index.songs) if (song.entry.addedSeconds != null) dated.add(song.entry);
        dated.sort(
                (a, b) -> {
                    int order = Long.compare(b.addedSeconds, a.addedSeconds);
                    return order != 0 ? order : a.identity.compareTo(b.identity);
                });
        Set<String> pool = new HashSet<>();
        for (int i = 0; i < dated.size() && i < RECENT_POOL; i++) pool.add(dated.get(i).identity);
        return pool;
    }

    private static Seed seed(String artist, Index index) {
        Seed seed = new Seed();
        for (Song song : index.songs) {
            if (!byArtist(song, artist)) continue;
            seed.stems.addAll(song.genreStems);
            seed.families.addAll(song.reading.families);
            seed.playlists.addAll(song.entry.playlists);
        }
        return seed;
    }

    private static boolean byArtist(Song song, String phrase) {
        for (String name : song.credits)
            if (name.equals(phrase) || name.equals("the " + phrase)) return true;
        return false;
    }

    private static Score score(Song song, Query query, Index index, Seed seed, Set<String> recent) {
        Score score = new Score();
        Entry entry = song.entry;
        if (negated(song, query, score)) {
            score.ruledOut = true;
            return score;
        }
        if (!years(entry, query, score) || !tempoTarget(entry, query, score)) {
            score.ruledOut = true;
            return score;
        }
        if (!genres(song, query, score) || !moods(song, query, score, recent)) {
            score.ruledOut = true;
            return score;
        }
        for (String phrase : query.artists)
            if (byArtist(song, phrase)) {
                score.add(ARTIST_MENTION, Why.ARTIST);
                score.named = true;
            }
        if (query.albums.contains(song.albumText)) {
            score.add(ALBUM_MENTION, Why.ALBUM);
            score.named = true;
        }
        if (query.titles.contains(song.titleText)) {
            score.add(TITLE_MENTION, Why.TITLE);
            score.named = true;
        }
        for (String stem : query.stems) {
            double weight = query.weights.getOrDefault(stem, 1.0);
            if (song.artist.contains(stem)) score.add(ARTIST_WORD * weight, Why.ARTIST);
            if (song.title.contains(stem)) score.add(TITLE_WORD * weight, Why.TITLE);
            if (song.album.contains(stem)) score.add(ALBUM_WORD * weight, Why.ALBUM);
            if (song.playlist.contains(stem)) score.add(PLAYLIST_WORD * weight, Why.PLAYLIST);
            // A genre word already scored in genres(); this covers tags outside the taxonomy.
            if (!query.genreStems.contains(stem) && song.genreStems.contains(stem))
                score.add(GENRE_BONUS, Why.GENRE);
        }
        if (seed != null && !similar(song, query, index, seed, score)) {
            score.ruledOut = true;
            return score;
        }
        if (seed == null && query.keepSeed && byArtist(song, query.similarTo))
            score.add(ARTIST_MENTION, Why.ARTIST);
        moodTempo(song, query, score);
        return score;
    }

    /** True when the song has something the description said to leave out. */
    private static boolean negated(Song song, Query query, Score score) {
        for (String phrase : query.notPhrases) {
            if (byArtist(song, phrase)) return true;
            if (phrase.equals(song.albumText) || phrase.equals(song.titleText)) return true;
        }
        for (String stem : query.notStems)
            if (song.artist.contains(stem)
                    || song.title.contains(stem)
                    || song.album.contains(stem)
                    || song.playlist.contains(stem)
                    || song.genreStems.contains(stem)) return true;
        for (Family family : query.notFamilies) {
            if (song.reading.families.contains(family) && !query.families.contains(family))
                return true;
            if (song.inherited != null
                    && song.inherited.families.contains(family)
                    && !query.families.contains(family)) {
                score.value -= INHERITED_CONFLICT;
                score.penalized = true;
            }
        }
        for (String word : query.notGenreWords)
            if (song.genreWords.contains(word)) return true;
            else if (song.inherited != null && song.inherited.words.contains(word)) {
                score.value -= INHERITED_CONFLICT;
                score.penalized = true;
            }
        for (Mood mood : query.notMoods)
            for (String trigger : mood.triggers)
                if (song.title.contains(trigger)
                        || song.album.contains(trigger)
                        || song.playlist.contains(trigger)) return true;
        return false;
    }

    private static boolean years(Entry entry, Query query, Score score) {
        if (entry.year == null) return true;
        int year = entry.year;
        for (int[] range : query.notYears) if (year >= range[0] && year <= range[1]) return false;
        if (query.years.isEmpty()) return true;
        for (int[] range : query.years)
            if (year >= range[0] && year <= range[1]) {
                score.value += YEAR_BONUS;
                score.why.add(Why.YEAR);
                score.narrowed = true;
                return true;
            }
        return false;
    }

    private static boolean tempoTarget(Entry entry, Query query, Score score) {
        if (!query.hasTempoTarget() || entry.bpm == null) return true;
        double low = query.bpmLow == null ? entry.bpm : query.bpmLow;
        double high = query.bpmHigh == null ? entry.bpm : query.bpmHigh;
        double distance =
                entry.bpm < low ? low - entry.bpm : entry.bpm > high ? entry.bpm - high : 0;
        if (distance > TEMPO_LIMIT) return false;
        score.value += KNOWN_TEMPO * Math.exp(-(distance / TEMPO_SIGMA) * (distance / TEMPO_SIGMA));
        score.why.add(tempoWhy(entry));
        score.narrowed = true;
        return true;
    }

    private static Why tempoWhy(Entry entry) {
        return entry.tempo == Tempo.TAGGED ? Why.TEMPO_TAGGED : Why.TEMPO_MEASURED;
    }

    /** False when the song's own tag is in a different family from the one asked for. */
    private static boolean genres(Song song, Query query, Score score) {
        if (query.families.isEmpty()) return true;
        boolean hit = false;
        for (Family family : song.reading.families) hit |= query.families.contains(family);
        if (!song.reading.families.isEmpty() && !hit) return false;
        if (hit) score.add(FAMILY_BONUS, Why.GENRE);
        for (String word : query.genreWords)
            if (song.genreStems.contains(LocalPlaylistLexicon.stem(word))) {
                score.add(GENRE_BONUS, Why.GENRE);
                break;
            }
        if (song.inherited != null && !song.tagged()) {
            boolean sibling = false;
            for (Family family : song.inherited.families)
                sibling |= query.families.contains(family);
            if (sibling) score.add(FAMILY_BONUS * INHERITED, Why.ARTIST_GENRE);
            else if (!song.inherited.families.isEmpty()) {
                score.value -= INHERITED_CONFLICT;
                score.penalized = true;
            }
        }
        return true;
    }

    /** Capped so one song can't win just by naming every mood. False on a real conflict. */
    private static boolean moods(Song song, Query query, Score score, Set<String> recent) {
        double total = 0;
        boolean namedGenre = false;
        for (Family family : song.reading.families) namedGenre |= query.families.contains(family);
        for (Map.Entry<Mood, Double> found : query.moods.entrySet()) {
            Mood mood = found.getKey();
            double strength = found.getValue();
            Entry entry = song.entry;
            switch (mood.kind) {
                case GENRE:
                    if (song.reading.energy != null
                            && !namedGenre
                            && (song.reading.energy < mood.minEnergy
                                    || song.reading.energy > mood.maxEnergy)) return false;
                    if (mood.fits(song.genreWords)) {
                        total += mood.bonus * strength;
                        score.why.add(Why.GENRE);
                        score.evidence = true;
                        moodExtra(mood, song, score);
                    } else if (song.inherited != null && !song.tagged()) {
                        Double energy = song.inherited.energy();
                        if (energy != null
                                && (energy < mood.minEnergy || energy > mood.maxEnergy)) {
                            score.value -= INHERITED_CONFLICT;
                            score.penalized = true;
                        } else if (mood.fits(song.inherited.words)) {
                            total += mood.bonus * strength * INHERITED;
                            score.why.add(Why.ARTIST_GENRE);
                            score.evidence = true;
                        }
                    }
                    for (String trigger : mood.triggers) {
                        if (song.title.contains(trigger) || song.album.contains(trigger)) {
                            score.add(MOOD_IN_TEXT * strength, Why.TITLE);
                            break;
                        }
                    }
                    for (String trigger : mood.triggers) {
                        if (song.playlist.contains(trigger)) {
                            total += mood.bonus * strength * MOOD_IN_PLAYLIST;
                            score.why.add(Why.PLAYLIST);
                            score.evidence = true;
                            break;
                        }
                    }
                    break;
                case FAVORITES:
                    if (Boolean.TRUE.equals(entry.favorite)) {
                        total += mood.bonus * strength;
                        score.why.add(Why.LIKED);
                        score.evidence |= query.behaviourOnly();
                    } else if (entry.playCount() >= 5) {
                        total += mood.bonus * strength * .6;
                        score.why.add(Why.PLAYED);
                        score.evidence |= query.behaviourOnly();
                    }
                    break;
                case DISCOVERY:
                    if (entry.plays != null && entry.plays == 0) {
                        total += mood.bonus * strength;
                        score.why.add(Why.UNPLAYED);
                        score.evidence |= query.behaviourOnly();
                    }
                    break;
                case RECENT:
                    if (recent != null && recent.contains(entry.identity)) {
                        total += mood.bonus * strength;
                        score.why.add(Why.RECENT);
                        score.evidence |= query.behaviourOnly();
                    }
                    break;
            }
        }
        score.value += Math.min(MOOD_CAP, total);
        return true;
    }

    private static void moodExtra(Mood mood, Song song, Score score) {
        Entry entry = song.entry;
        if (mood == Mood.CHILL && entry.durationSeconds != null && entry.durationSeconds > 200)
            score.value += 3;
        if (mood == Mood.WORKOUT && entry.playCount() > 5) score.value += 5;
    }

    /** The seed artist's tags or playlists decide; no evidence means no similarity claim. */
    private static boolean similar(Song song, Query query, Index index, Seed seed, Score score) {
        boolean own = byArtist(song, query.similarTo);
        if (own && !query.keepSeed) return false;
        if (own) {
            score.add(ARTIST_MENTION, Why.ARTIST);
            return true;
        }
        int sharedStems = 0;
        for (String stem : song.genreStems) if (seed.stems.contains(stem)) sharedStems++;
        if (sharedStems > 0) {
            // Half for any overlap, the rest for how much of the artist's tags it covers.
            double covered = (double) sharedStems / seed.stems.size();
            score.add(SIMILAR_GENRE * (.5 + .5 * covered), Why.SIMILAR_GENRE);
        } else {
            boolean family = false;
            for (Family value : song.reading.families) family |= seed.families.contains(value);
            if (family) score.add(FAMILY_BONUS, Why.SIMILAR_GENRE);
        }
        double shared = 0;
        for (String name : song.entry.playlists)
            if (seed.playlists.contains(name))
                shared += 1 / Math.log(2 + index.playlistSizes.getOrDefault(name, 1));
        if (shared > 0)
            score.add(Math.min(SIMILAR_PLAYLIST, shared * SIMILAR_PLAYLIST), Why.SIMILAR_PLAYLIST);
        return true;
    }

    /**
     * What a mood implies for tempo. A real value beats the guess; the guess from a genre tag is
     * labelled as an estimate and is never presented as a tempo.
     */
    private static void moodTempo(Song song, Query query, Score score) {
        Double target = query.moodBpm();
        if (target == null || query.hasTempoTarget()) return;
        Entry entry = song.entry;
        if (entry.bpm != null) {
            double distance = Math.abs(entry.bpm - target);
            double points =
                    MOOD_TEMPO * Math.exp(-(distance / TEMPO_SIGMA) * (distance / TEMPO_SIGMA));
            if (distance <= TEMPO_SIGMA) score.add(points, tempoWhy(entry));
            else if (distance > MOOD_TEMPO_LIMIT) {
                score.value -= MOOD_TEMPO_PENALTY;
                score.penalized = true;
            } else score.value += points;
            return;
        }
        if (song.reading.energy == null) return;
        double estimate = 60 + song.reading.energy * 120;
        double distance = Math.abs(estimate - target);
        double points =
                ESTIMATE_MAX * Math.exp(-(distance / ESTIMATE_SIGMA) * (distance / ESTIMATE_SIGMA));
        score.value += points;
        if (points >= ESTIMATE_EVIDENCE) {
            score.evidence = true;
            score.why.add(Why.ENERGY_ESTIMATE);
        }
    }

    /** Liked and often played songs first, or the less played ones for deeper cuts. */
    private static void engagement(Score score, Entry entry, boolean familiar) {
        if (Boolean.TRUE.equals(entry.favorite)) {
            score.value += 3;
            score.why.add(Why.LIKED);
        }
        if (entry.plays == null) return;
        if (familiar) {
            score.value += Math.min(6, 1.5 * Math.log(1 + entry.plays));
            if (entry.plays >= 5) score.why.add(Why.PLAYED);
        } else if (entry.plays == 0) {
            score.value += 2;
            score.why.add(Why.UNPLAYED);
        } else score.value -= Math.min(3, .75 * Math.log(1 + entry.plays));
    }

    // ── Choosing ────────────────────────────────────────────────────────────

    /**
     * Best first, one copy of each recording, and no artist crowding a list that didn't ask for
     * them. Songs held back by the artist limit fill any room left, since they do fit.
     */
    private static List<Pick> select(List<Pick> matches, int count, boolean focused) {
        Map<String, String> recordings = new HashMap<>();
        List<Pick> unique = new ArrayList<>();
        for (Pick pick : matches) {
            String key = pick.recording;
            if (!key.isEmpty() && recordings.putIfAbsent(key, pick.entry.identity) != null)
                continue;
            unique.add(pick);
        }
        int limit = focused ? Integer.MAX_VALUE : Math.max(2, (int) Math.ceil(count * .25));
        Map<String, Integer> perArtist = new HashMap<>();
        List<Pick> chosen = new ArrayList<>(), held = new ArrayList<>();
        for (Pick pick : unique) {
            String artist = pick.artist;
            int used = artist.isEmpty() ? 0 : perArtist.getOrDefault(artist, 0);
            if (used >= limit) {
                held.add(pick);
                continue;
            }
            if (!artist.isEmpty()) perArtist.put(artist, used + 1);
            chosen.add(pick);
            if (chosen.size() >= count) break;
        }
        for (Pick pick : held) {
            if (chosen.size() >= count) break;
            chosen.add(pick);
        }
        return focused ? chosen : spaced(chosen);
    }

    /** Pulls the next different artist forward instead of playing one twice in a row. */
    private static List<Pick> spaced(List<Pick> ranked) {
        List<Pick> rest = new ArrayList<>(ranked), out = new ArrayList<>();
        String last = "";
        while (!rest.isEmpty()) {
            int at = 0;
            if (!last.isEmpty())
                for (int i = 0; i < rest.size(); i++)
                    if (!rest.get(i).artist.equals(last)) {
                        at = i;
                        break;
                    }
            Pick pick = rest.remove(at);
            out.add(pick);
            last = pick.artist;
        }
        // Whatever piled up at the end moves to the last place it isn't next to itself.
        for (int i = out.size() - 1; i > 0; i--) {
            String artist = out.get(i).artist;
            if (artist.isEmpty() || !artist.equals(out.get(i - 1).artist)) continue;
            for (int to = i - 1; to >= 0; to--) {
                boolean afterOk = !out.get(to).artist.equals(artist);
                boolean beforeOk = to == 0 || !out.get(to - 1).artist.equals(artist);
                if (afterOk && beforeOk) {
                    out.add(to, out.remove(i));
                    i++;
                    break;
                }
            }
        }
        return out;
    }
}
