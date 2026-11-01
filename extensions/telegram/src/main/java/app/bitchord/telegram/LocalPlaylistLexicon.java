package app.bitchord.telegram;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text folding, word lists, moods and genre families for local playlist generation.
 *
 * <p>The stemmer, word lists, mood vocabulary and genre energy table are adapted from PixelMusic's
 * data/playlist/nlp/{NlpText,NlpLexicon,MoodProfile,GenreTaxonomy}.kt (commit 991f7c5), Copyright
 * (C) ianshulyadav and PixelMusic contributors, GNU GPL v3.
 */
final class LocalPlaylistLexicon {
    private LocalPlaylistLexicon() {}

    private static final int MIN_STEM = 4;
    private static final Pattern DIACRITICS = Pattern.compile("[\\u0300-\\u036f]+");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{M}\\p{Nd}#]+");
    private static final Map<String, String> COMPOUND_NAMES = new HashMap<>();
    private static final Pattern COMPOUND =
            Pattern.compile(
                    "(?<![\\p{L}\\p{Nd}])(?:lo[- ]fi|hip[- ]hop|trip[- ]hop|post[- ]rock"
                            + "|[kjc]-pop|neo[- ]soul|new age|chill out|synth pop|big band"
                            + "|bossa nova|banda sonora|singer[- ]songwriter|regional mexican"
                            + "|drum (?:and|&|n|'n') bass|r&b|r and b|rhythm and blues"
                            + "|rock (?:and|&|n|'n') roll|rock'n'roll|latin trap|trap latin[o]?)"
                            + "(?![\\p{L}\\p{Nd}])");

    static {
        COMPOUND_NAMES.put("drumandbass", "drumandbass");
        COMPOUND_NAMES.put("drumbass", "drumandbass");
        COMPOUND_NAMES.put("drumnbass", "drumandbass");
        COMPOUND_NAMES.put("rb", "rnb");
        COMPOUND_NAMES.put("randb", "rnb");
        COMPOUND_NAMES.put("rhythmandblues", "rnb");
        COMPOUND_NAMES.put("rockandroll", "rockandroll");
        COMPOUND_NAMES.put("rockroll", "rockandroll");
        COMPOUND_NAMES.put("rocknroll", "rockandroll");
        COMPOUND_NAMES.put("latintrap", "traplatino");
        COMPOUND_NAMES.put("traplatin", "traplatino");
        COMPOUND_NAMES.put("bandasonora", "soundtrack");
    }

    /** Lower case without accents, with multi-word genres joined so both sides compare equal. */
    static String fold(String text) {
        if (text == null || text.isEmpty()) return "";
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        folded = DIACRITICS.matcher(folded).replaceAll("");
        folded = Normalizer.normalize(folded, Normalizer.Form.NFC);
        StringBuilder letters = null;
        for (int i = 0; i < folded.length(); i++) {
            String swap = letter(folded.charAt(i));
            if (swap == null && letters == null) continue;
            if (letters == null) letters = new StringBuilder(folded.substring(0, i));
            if (swap != null) letters.append(swap);
            else letters.append(folded.charAt(i));
        }
        if (letters != null) folded = letters.toString();
        folded = folded.replace('’', '\'');
        Matcher found = COMPOUND.matcher(folded);
        if (!found.find()) return folded;
        StringBuffer joined = new StringBuffer();
        do {
            String key = found.group().replaceAll("[^\\p{L}\\p{Nd}]", "");
            String name = COMPOUND_NAMES.get(key);
            found.appendReplacement(joined, Matcher.quoteReplacement(name == null ? key : name));
        } while (found.find());
        found.appendTail(joined);
        return joined.toString();
    }

    private static String letter(char c) {
        switch (c) {
            case 'ø':
                return "o";
            case 'ł':
                return "l";
            case 'æ':
                return "ae";
            case 'œ':
                return "oe";
            case 'ß':
                return "ss";
            case 'đ':
                return "d";
            case 'ı':
                return "i";
            default:
                return null;
        }
    }

    /** Every word of a folded text, for names, where "Part 1" and "Part 2" must stay apart. */
    static List<String> parts(String folded) {
        List<String> parts = new ArrayList<>();
        for (String part : SPLIT.split(folded)) if (!part.isEmpty()) parts.add(part);
        return parts;
    }

    static boolean meaningful(String word) {
        return word.length() > 1 || word.charAt(0) > 0x24f;
    }

    /** Words to match on. Single Latin letters and digits carry no signal; "愛" does. */
    static List<String> words(String folded) {
        List<String> words = new ArrayList<>();
        for (String word : parts(folded)) if (meaningful(word)) words.add(word);
        return words;
    }

    /**
     * Conservative English and Spanish stemmer, applied to both sides, so plural and gendered forms
     * converge. Nothing is cut below four letters, and words with digits stay whole.
     */
    static String stem(String word) {
        if (word.length() <= MIN_STEM) return word;
        for (int i = 0; i < word.length(); i++) if (Character.isDigit(word.charAt(i))) return word;
        String t = word;
        if (t.endsWith("ing") && t.length() - 3 >= MIN_STEM) return t.substring(0, t.length() - 3);
        if (t.endsWith("ed") && t.length() - 2 >= MIN_STEM) return t.substring(0, t.length() - 2);
        if (t.endsWith("s") && t.length() - 1 >= MIN_STEM) t = t.substring(0, t.length() - 1);
        if (t.endsWith("e") && t.length() - 1 >= MIN_STEM) t = t.substring(0, t.length() - 1);
        if ((t.endsWith("o") || t.endsWith("a")) && t.length() - 1 >= MIN_STEM)
            t = t.substring(0, t.length() - 1);
        return t;
    }

    static final Set<String> STOP_WORDS =
            set(
                    "the",
                    "a",
                    "an",
                    "and",
                    "or",
                    "nor",
                    "but",
                    "if",
                    "then",
                    "else",
                    "when",
                    "at",
                    "from",
                    "by",
                    "for",
                    "with",
                    "about",
                    "against",
                    "between",
                    "into",
                    "through",
                    "during",
                    "before",
                    "after",
                    "above",
                    "below",
                    "to",
                    "of",
                    "in",
                    "on",
                    "some",
                    "any",
                    "that",
                    "this",
                    "my",
                    "me",
                    "i",
                    "you",
                    "it",
                    "is",
                    "are",
                    "want",
                    "wanna",
                    "gonna",
                    "more",
                    "only",
                    "just",
                    "please",
                    "include",
                    "like",
                    "style",
                    "similar",
                    "sounds",
                    "kind",
                    "de",
                    "la",
                    "el",
                    "y",
                    "en",
                    "para",
                    "con",
                    "por",
                    "un",
                    "una",
                    "lo",
                    "los",
                    "las",
                    "que",
                    "mi",
                    "mis",
                    "es",
                    "algo",
                    "del",
                    "al",
                    "music",
                    "song",
                    "playlist",
                    "songs",
                    "tracks",
                    "track",
                    "cancion",
                    "canciones",
                    "musica",
                    "reproducir",
                    "play",
                    "list",
                    "lista",
                    "temas",
                    "tema",
                    "quiero",
                    "escuchar",
                    "show",
                    "give",
                    "poner",
                    "pon",
                    "dame",
                    "hazme",
                    "crear",
                    "crea",
                    "make",
                    "generate",
                    "genera",
                    "tunes",
                    "vibes",
                    "mood",
                    "bpm",
                    "year",
                    "years",
                    "something",
                    "anything",
                    "everything",
                    "stuff",
                    "thing",
                    "things",
                    "need",
                    "listen",
                    "listening",
                    "hear",
                    "get",
                    "find",
                    "type",
                    "sort",
                    "few",
                    "lots",
                    "can",
                    "could",
                    "would",
                    "we",
                    "us",
                    "our",
                    "have",
                    "has",
                    "be",
                    "do",
                    "does",
                    "so",
                    "very",
                    "really",
                    "too",
                    "them",
                    "they",
                    "these",
                    "those",
                    "your");

    static final Set<String> NEGATION_CUES =
            set(
                    "no",
                    "not",
                    "without",
                    "except",
                    "excluding",
                    "exclude",
                    "avoid",
                    "skip",
                    "minus",
                    "never",
                    "sin",
                    "menos",
                    "excepto",
                    "salvo",
                    "nada");

    /** Multi-word similarity cues, as the words immediately before an artist name. */
    static final String[][] SIMILAR_CUES = {
        {"sounds", "like"},
        {"sound", "like"},
        {"kind", "of", "like"},
        {"similar", "to"},
        {"similar", "a"},
        {"parecido", "a"},
        {"in", "the", "style", "of"},
        {"al", "estilo", "de"},
        {"estilo", "de"},
        {"inspired", "by"},
        {"like"},
        {"como"},
        {"estilo"}
    };

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>();
        for (String value : values) out.add(fold(value));
        return Collections.unmodifiableSet(out);
    }

    // ── Moods ───────────────────────────────────────────────────────────────

    enum MoodKind {
        GENRE,
        FAVORITES,
        DISCOVERY,
        RECENT
    }

    enum Mood {
        CHILL(
                MoodKind.GENRE,
                new String[] {
                    "chill",
                    "chillout",
                    "relax",
                    "relajar",
                    "relajante",
                    "tranquilo",
                    "calma",
                    "calm",
                    "suave",
                    "soft",
                    "study",
                    "estudiar",
                    "sleep",
                    "dormir",
                    "peaceful",
                    "meditation",
                    "meditacion",
                    "ambient",
                    "acoustic",
                    "acustico",
                    "lofi",
                    "quiet",
                    "instrumental",
                    "piano",
                    "jazz",
                    "paz",
                    "desconectar",
                    "sereno",
                    "mellow",
                    "relajado",
                    "lounge",
                    "downtempo",
                    "unwind",
                    "zen"
                },
                new String[] {
                    "wind down", "para dormir", "para estudiar", "background music", "calm down"
                },
                new String[] {
                    "classical",
                    "jazz",
                    "lofi",
                    "acoustic",
                    "ambient",
                    "blues",
                    "chillout",
                    "chill",
                    "downtempo",
                    "newage",
                    "triphop",
                    "postrock",
                    "meditation"
                },
                15,
                0,
                .65,
                80.0),
        WORKOUT(
                MoodKind.GENRE,
                new String[] {
                    "workout",
                    "gym",
                    "gimnasio",
                    "run",
                    "correr",
                    "cardio",
                    "energetic",
                    "energico",
                    "fast",
                    "hype",
                    "pump",
                    "power",
                    "aggressive",
                    "agresivo",
                    "metal",
                    "electronic",
                    "electronica",
                    "dance",
                    "upbeat",
                    "intense",
                    "intenso",
                    "heavy",
                    "entrenar",
                    "ejercicio",
                    "entrenamiento",
                    "energia",
                    "motivacion",
                    "adrenalina",
                    "beast"
                },
                new String[] {
                    "lift weights",
                    "lifting weights",
                    "work out",
                    "working out",
                    "hit the gym",
                    "levantar pesas",
                    "para entrenar",
                    "para correr",
                    "pump up",
                    "get pumped"
                },
                new String[] {
                    "rock",
                    "metal",
                    "electronic",
                    "edm",
                    "dance",
                    "techno",
                    "house",
                    "pop",
                    "hiphop",
                    "rap",
                    "punk",
                    "drumandbass",
                    "dubstep",
                    "hardcore",
                    "deathcore",
                    "grindcore",
                    "metalcore",
                    "thrash",
                    "djent",
                    "screamo",
                    "hardstyle",
                    "dnb",
                    "trap",
                    "phonk",
                    "industrial",
                    "breakcore"
                },
                15,
                .55,
                1,
                150.0),
        SAD(
                MoodKind.GENRE,
                new String[] {
                    "sad",
                    "triste",
                    "melancholy",
                    "melancolia",
                    "melancolico",
                    "crying",
                    "llorar",
                    "broken",
                    "roto",
                    "rainy",
                    "rain",
                    "lluvia",
                    "dark",
                    "oscuro",
                    "somber",
                    "blue",
                    "depressed",
                    "deprimido",
                    "depre",
                    "slow",
                    "lento",
                    "nostalgia",
                    "nostalgico",
                    "bajon",
                    "heartbreak",
                    "lonely",
                    "solitario"
                },
                new String[] {
                    "feel sad",
                    "rainy day",
                    "broken heart",
                    "dia lluvioso",
                    "para llorar",
                    "me siento triste"
                },
                new String[] {
                    "acoustic", "folk", "classical", "blues", "soul", "ballad", "slowcore", "sad"
                },
                15,
                0,
                .65,
                80.0),
        HAPPY(
                MoodKind.GENRE,
                new String[] {
                    "happy",
                    "feliz",
                    "joy",
                    "alegria",
                    "alegre",
                    "party",
                    "fiesta",
                    "dance",
                    "celebration",
                    "celebracion",
                    "upbeat",
                    "sunny",
                    "bright",
                    "fun",
                    "diversion",
                    "summer",
                    "verano",
                    "animado",
                    "contento",
                    "groovy",
                    "vibrante"
                },
                new String[] {
                    "feel good",
                    "good vibes",
                    "feel happy",
                    "buena onda",
                    "buen humor",
                    "para bailar",
                    "good mood"
                },
                new String[] {
                    "pop", "dance", "reggae", "funk", "disco", "latin", "salsa", "afrobeat", "house"
                },
                15,
                .40,
                1,
                120.0),
        FAVORITES(
                MoodKind.FAVORITES,
                new String[] {
                    "favorite",
                    "favourite",
                    "favorito",
                    "loved",
                    "liked",
                    "top",
                    "best",
                    "mejor",
                    "preferido",
                    "gustar"
                },
                new String[] {
                    "most played",
                    "most listened",
                    "mas escuchada",
                    "me gusta",
                    "my favorites",
                    "my favourites",
                    "los mejores",
                    "the best"
                },
                new String[0],
                35,
                0,
                1,
                null),
        DISCOVERY(
                MoodKind.DISCOVERY,
                new String[] {
                    "discover",
                    "descubrir",
                    "new",
                    "nuevo",
                    "fresh",
                    "unplayed",
                    "recommendation",
                    "recomendacion",
                    "random",
                    "aleatorio",
                    "desconocido",
                    "surprise",
                    "sorpresa",
                    "explore",
                    "explorar"
                },
                new String[] {
                    "never heard",
                    "new to me",
                    "something new",
                    "algo nuevo",
                    "no escuchada",
                    "surprise me"
                },
                new String[0],
                35,
                0,
                1,
                null),
        RECENT(
                MoodKind.RECENT,
                new String[] {
                    "recent",
                    "reciente",
                    "recently",
                    "recientemente",
                    "ultimo",
                    "latest",
                    "agregado",
                    "anadido",
                    "nuevo",
                    "fresh",
                    "lanzamiento"
                },
                new String[] {
                    "recently added",
                    "just added",
                    "recien agregada",
                    "recien anadido",
                    "last added",
                    "newly added"
                },
                new String[0],
                35,
                0,
                1,
                null);

        final MoodKind kind;
        final Set<String> triggers;
        final List<String> phrases;
        final Set<String> genres;
        final double bonus, minEnergy, maxEnergy;
        final Double bpm;

        Mood(
                MoodKind kind,
                String[] synonyms,
                String[] phrases,
                String[] genres,
                double bonus,
                double minEnergy,
                double maxEnergy,
                Double bpm) {
            this.kind = kind;
            Set<String> stems = new HashSet<>();
            for (String word : synonyms) stems.add(stem(fold(word)));
            triggers = Collections.unmodifiableSet(stems);
            List<String> folded = new ArrayList<>();
            for (String phrase : phrases) folded.add(fold(phrase));
            this.phrases = Collections.unmodifiableList(folded);
            this.genres = new HashSet<>(Arrays.asList(genres));
            this.bonus = bonus;
            this.minEnergy = minEnergy;
            this.maxEnergy = maxEnergy;
            this.bpm = bpm;
        }

        boolean fits(Set<String> genreWords) {
            for (String word : genreWords)
                for (String genre : genres) if (aliasHits(genre, word)) return true;
            return false;
        }
    }

    /** Short names must be the whole word ("pop", not "popular"); longer ones may be compounds. */
    private static boolean aliasHits(String alias, String word) {
        return alias.length() < 5 ? word.equals(alias) : word.contains(alias);
    }

    // ── Genres ──────────────────────────────────────────────────────────────

    enum Family {
        GUITAR,
        URBAN,
        RNB_SOUL,
        ELECTRONIC,
        POP,
        LATIN,
        JAZZ,
        FOLK_COUNTRY,
        CLASSICAL,
        CHILL_AMBIENT,
        REGGAE,
        WORLD
    }

    static final class Genre {
        final Family family;
        final double energy;
        final String[] aliases;

        /** Names that mean "this family" in a request; empty for words that are moods instead. */
        final Set<String> requests;

        Genre(Family family, double energy, String[] aliases, String... requests) {
            this.family = family;
            this.energy = energy;
            this.aliases = aliases;
            this.requests = new HashSet<>(Arrays.asList(requests));
        }

        boolean hits(String word) {
            for (String alias : aliases) if (aliasHits(alias, word)) return true;
            return false;
        }
    }

    private static Genre g(Family family, double energy, String... aliases) {
        return new Genre(family, energy, aliases, aliases);
    }

    private static Genre tagOnly(Family family, double energy, String... aliases) {
        return new Genre(family, energy, aliases);
    }

    /** Most specific first: {@link #resolve} takes the energy of the first genre a tag names. */
    private static final Genre[] GENRES = {
        g(Family.GUITAR, .45, "postrock"),
        g(Family.LATIN, .78, "traplatino"),
        g(Family.LATIN, .80, "reggaeton", "perreo"),
        g(Family.REGGAE, .75, "dancehall"),
        g(Family.CHILL_AMBIENT, .25, "lofi"),
        g(Family.CHILL_AMBIENT, .40, "triphop"),
        g(Family.URBAN, .70, "hiphop"),
        g(Family.URBAN, .78, "trap"),
        g(Family.URBAN, .80, "phonk"),
        g(Family.URBAN, .80, "drill"),
        g(Family.URBAN, .80, "grime"),
        g(Family.URBAN, .72, "rap"),
        g(Family.GUITAR, .96, "metalcore", "deathcore", "grindcore", "thrash", "djent", "screamo"),
        g(Family.GUITAR, .92, "metal"),
        g(Family.GUITAR, .95, "hardcore"),
        g(Family.GUITAR, .90, "punk"),
        g(Family.GUITAR, .75, "grunge"),
        g(Family.GUITAR, .70, "emo"),
        g(Family.GUITAR, .50, "shoegaze"),
        g(Family.GUITAR, .68, "rockandroll", "rockabilly"),
        g(Family.GUITAR, .80, "rock"),
        g(Family.GUITAR, .60, "alternative", "indie"),
        g(Family.ELECTRONIC, .90, "dubstep"),
        g(Family.ELECTRONIC, .90, "drumandbass", "dnb"),
        g(Family.ELECTRONIC, .95, "hardstyle", "gabber"),
        g(Family.ELECTRONIC, .96, "breakcore"),
        g(Family.ELECTRONIC, .85, "techno"),
        g(Family.ELECTRONIC, .80, "house"),
        g(Family.ELECTRONIC, .80, "trance"),
        g(Family.ELECTRONIC, .60, "synthwave", "darksynth", "retrowave"),
        g(Family.ELECTRONIC, .90, "edm"),
        g(Family.ELECTRONIC, .85, "industrial"),
        g(Family.ELECTRONIC, .85, "electronic", "electronica", "electro"),
        tagOnly(Family.REGGAE, .50, "dub"),
        g(Family.REGGAE, .60, "reggae"),
        g(Family.REGGAE, .75, "ska"),
        g(Family.POP, .65, "synthpop"),
        g(Family.POP, .75, "kpop", "jpop", "cpop"),
        g(Family.POP, .70, "pop"),
        g(Family.RNB_SOUL, .55, "rnb"),
        g(Family.RNB_SOUL, .50, "neosoul"),
        g(Family.RNB_SOUL, .55, "soul", "motown"),
        g(Family.RNB_SOUL, .70, "funk"),
        g(Family.RNB_SOUL, .60, "gospel"),
        g(Family.JAZZ, .45, "blues"),
        g(Family.JAZZ, .35, "bossanova", "bossa"),
        g(Family.JAZZ, .55, "swing", "bebop", "bigband", "dixieland"),
        g(Family.JAZZ, .45, "jazz"),
        g(Family.LATIN, .75, "salsa"),
        g(Family.LATIN, .60, "bachata"),
        g(Family.LATIN, .78, "merengue"),
        g(Family.LATIN, .70, "cumbia"),
        g(Family.LATIN, .82, "dembow"),
        g(
                Family.LATIN,
                .65,
                "banda",
                "norteno",
                "corrido",
                "corridos",
                "ranchera",
                "mariachi",
                "vallenato",
                "regionalmexican"),
        g(Family.LATIN, .72, "latin", "latino", "latina"),
        g(Family.WORLD, .60, "flamenco"),
        g(Family.WORLD, .70, "afrobeat", "afrobeats", "afro"),
        g(Family.WORLD, .70, "amapiano"),
        new Genre(
                Family.CLASSICAL,
                .35,
                new String[] {
                    "classical",
                    "clasica",
                    "clasico",
                    "orchestr",
                    "orquesta",
                    "symphony",
                    "sinfonia",
                    "concerto",
                    "concierto",
                    "sonata",
                    "opera",
                    "baroque",
                    "barroco"
                },
                "classical",
                "clasica",
                "orchestral",
                "opera"),
        g(Family.CLASSICAL, .30, "piano"),
        new Genre(
                Family.CLASSICAL,
                .45,
                new String[] {"soundtrack", "score", "cinematic"},
                "soundtrack",
                "cinematic"),
        g(Family.CHILL_AMBIENT, .18, "newage"),
        g(Family.CHILL_AMBIENT, .25, "ambient"),
        g(Family.CHILL_AMBIENT, .30, "downtempo"),
        g(Family.CHILL_AMBIENT, .30, "vaporwave"),
        tagOnly(
                Family.CHILL_AMBIENT,
                .28,
                "chillout",
                "chillhop",
                "chillstep",
                "chillwave",
                "chill"),
        tagOnly(Family.CHILL_AMBIENT, .12, "meditation", "meditacion"),
        g(Family.FOLK_COUNTRY, .40, "folk"),
        g(Family.FOLK_COUNTRY, .55, "country"),
        g(Family.FOLK_COUNTRY, .55, "bluegrass"),
        g(Family.FOLK_COUNTRY, .50, "americana"),
        g(Family.FOLK_COUNTRY, .40, "singersongwriter", "cantautor"),
        g(Family.FOLK_COUNTRY, .35, "acoustic", "acustico", "unplugged"),
    };

    /** Requests for a whole family: "no rock" also rules out metal, "no metal" not rock. */
    private static final Map<String, Family> FAMILY_NAMES = new HashMap<>();

    static {
        FAMILY_NAMES.put("rock", Family.GUITAR);
        FAMILY_NAMES.put("electronic", Family.ELECTRONIC);
        FAMILY_NAMES.put("hiphop", Family.URBAN);
        FAMILY_NAMES.put("rap", Family.URBAN);
        FAMILY_NAMES.put("pop", Family.POP);
        FAMILY_NAMES.put("jazz", Family.JAZZ);
        FAMILY_NAMES.put("folk", Family.FOLK_COUNTRY);
        FAMILY_NAMES.put("latin", Family.LATIN);
        FAMILY_NAMES.put("classical", Family.CLASSICAL);
        FAMILY_NAMES.put("ambient", Family.CHILL_AMBIENT);
        FAMILY_NAMES.put("reggae", Family.REGGAE);
    }

    static Family familyNamed(String word) {
        return FAMILY_NAMES.get(word);
    }

    /** What a genre tag says. Empty families and null energy mean nothing is known. */
    static final class Reading {
        final Set<Family> families = EnumSet.noneOf(Family.class);
        Double energy;
    }

    /** A tag can name several genres ("pop rock", "Jazz; Soul"); each one counts. */
    static Reading resolve(Set<String> genreWords) {
        Reading reading = new Reading();
        if (genreWords.isEmpty()) return reading;
        Set<String> open = new HashSet<>(genreWords);
        for (Genre genre : GENRES) {
            boolean hit = false;
            for (Iterator<String> words = open.iterator(); words.hasNext(); )
                if (genre.hits(words.next())) {
                    hit = true;
                    words.remove();
                }
            if (!hit) continue;
            reading.families.add(genre.family);
            if (reading.energy == null) reading.energy = genre.energy;
        }
        return reading;
    }

    /** The genre a request word names, or null. Only exact words count on this side. */
    static Genre requested(String word) {
        for (Genre genre : GENRES) if (genre.requests.contains(word)) return genre;
        return null;
    }
}
