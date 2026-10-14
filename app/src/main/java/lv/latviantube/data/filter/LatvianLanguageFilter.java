package lv.latviantube.data.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Latvian language gate — the component that makes LatvianTube Latvian-only.
 *
 * <h2>Why inference instead of a metadata check</h2>
 * YouTube does not expose a dependable "this video is in Latvian" field.
 * Probed directly (see {@code ANALYSIS.md}, evidence group E3):
 * <ul>
 *   <li>the InnerTube {@code player} response omitted {@code defaultAudioLanguage}
 *       for every video checked, so audio language is usually <em>unknown</em>;</li>
 *   <li>the channel "about" renderer no longer exposes a country field to the
 *       WEB client, so publisher country is unavailable;</li>
 *   <li>a channel name in Latvian implies nothing — see the excluded entries in
 *       {@code assets/channels_lv.json}, where a channel called "Latvijas Ziņas"
 *       publishes entirely in Russian.</li>
 * </ul>
 * The gate therefore scores several weak signals and states its confidence.
 * When a real language tag <em>is</em> present it becomes decisive and the
 * inference is bypassed.
 *
 * <h2>Faithfulness</h2>
 * This is a direct port of {@code tools/latvian-filter.mjs}, which is executed
 * against live YouTube by {@code tools/evaluate.mjs} and checked against eight
 * fixtures. The Java constants below must stay numerically identical to the
 * JavaScript weights; {@code tools/parity-check.mjs} asserts that.
 */
public final class LatvianLanguageFilter {

    private LatvianLanguageFilter() {
    }

    // --- Signal vocabularies ------------------------------------------------

    /**
     * Diacritics split by how diagnostic they are. Within Latin-script European
     * languages these are effectively Latvian-only, so a single one is strong
     * evidence. The shared set also occurs in Czech, Slovak, Slovene, Croatian,
     * Lithuanian and Turkish and therefore counts for much less alone.
     */
    public static final String DIACRITICS_EXCLUSIVE = "āēīūōņķļģ";
    public static final String DIACRITICS_SHARED = "šžč";

    /** Latvian function words: high precision, since they are not English or Russian. */
    private static final Set<String> LATVIAN_STOPWORDS = new HashSet<>(Arrays.asList(
            "un", "ar", "no", "par", "kas", "tas", "tā", "ir", "nav", "bet", "vai", "kā",
            "šis", "šī", "šie", "mēs", "jūs", "viņš", "viņa", "viņi", "es", "tu",
            "uz", "pie", "pēc", "pirms", "arī", "tikai", "vēl", "jau", "tad", "tāpēc",
            "jo", "lai", "kad", "kur", "kāpēc", "cik", "cits", "cita", "viss", "visa",
            "ļoti", "labi", "jauns", "jauna", "liels", "liela", "mazs", "maza",
            "diena", "dienas", "gads", "gadi", "laiks", "cilvēki", "dziesma", "dziesmas",
            "latvija", "latvijas", "latviešu", "latviski", "rīga", "rīgas",
            "sveiki", "paldies", "lūdzu", "šodien", "rīt", "vakar", "tagad",
            // Verb and pronoun forms common in headlines. Added after the registry
            // verifier showed genuine Latvian titles scoring just under threshold,
            // e.g. "Sivēni | Visiem vajag naudu".
            "visiem", "vajag", "var", "būt", "bija", "būs", "tiek", "tika", "mūsu",
            "jūsu", "viņu", "ko", "kurš", "kura", "kurā", "pret", "vairs",
            "šeit", "tur", "tāpat", "kopā", "bez", "starp", "tieši"
    ));

    /** A title in Cyrillic is not a Latvian-language video. */
    private static final Set<String> RUSSIAN_STOPWORDS = new HashSet<>(Arrays.asList(
            "и", "в", "не", "на", "что", "это", "как", "для", "по", "он", "она", "все",
            "но", "за", "от", "так", "же", "бы", "только", "еще", "уже", "очень",
            "самый", "новый", "лучший", "смотреть", "онлайн"
    ));

    /** Weighted weakly and deliberately: these appear in genuine Latvian titles too. */
    private static final Set<String> ENGLISH_MARKERS = new HashSet<>(Arrays.asList(
            "the", "and", "of", "to", "in", "is", "you", "for", "with", "that", "this",
            "are", "it", "be", "as", "at", "by", "from", "or", "an", "we", "your", "my",
            "best", "new", "official", "full", "live", "episode", "trailer"
    ));

    // --- Weights (must mirror tools/latvian-filter.mjs) ---------------------

    public static final int W_EXCLUSIVE_DIACRITIC = 4;
    public static final int W_SHARED_DIACRITIC = 2;
    public static final int W_DIACRITIC_CAP = 12;
    public static final int W_STOPWORD = 2;
    public static final int W_STOPWORD_CAP = 12;
    public static final int W_CURATED_CHANNEL = 6;
    public static final int W_CYRILLIC = -25;
    public static final int W_RUSSIAN_STOPWORD = -3;
    public static final int W_RUSSIAN_STOPWORD_CAP = -12;
    public static final int W_ENGLISH_MARKER = -1;
    public static final int W_ENGLISH_MARKER_CAP = -5;

    /** Minimum total score for admission. */
    public static final int ACCEPT_THRESHOLD = 4;

    // --- Entry points -------------------------------------------------------

    /**
     * Gate a video by its text content and channel identity.
     *
     * @param text            title + description + channel name, concatenated
     * @param curatedChannel  whether the publishing channel is in the curated registry
     * @param channelTitle    channel name, for the explanation string
     * @param audioLanguage   YouTube's {@code defaultAudioLanguage}, or null. When
     *                        non-null it decides the outcome outright.
     */
    public static FilterVerdict gate(String text,
                                     boolean curatedChannel,
                                     String channelTitle,
                                     String audioLanguage) {
        List<FilterVerdict.Signal> signals = new ArrayList<>();
        String raw = text == null ? "" : text;
        String lower = raw.toLowerCase(new Locale("lv", "LV"));
        Set<String> tokens = tokenize(raw);

        // 1. Diacritics, weighted by exclusivity.
        Set<Character> exclusive = new LinkedHashSet<>();
        Set<Character> shared = new LinkedHashSet<>();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (DIACRITICS_EXCLUSIVE.indexOf(c) >= 0) {
                exclusive.add(c);
            } else if (DIACRITICS_SHARED.indexOf(c) >= 0) {
                shared.add(c);
            }
        }
        if (!exclusive.isEmpty() || !shared.isEmpty()) {
            int w = Math.min(exclusive.size() * W_EXCLUSIVE_DIACRITIC, W_DIACRITIC_CAP)
                    + Math.min(shared.size() * W_SHARED_DIACRITIC, W_DIACRITIC_CAP);
            StringBuilder label = new StringBuilder("Latviešu diakritiskās zīmes: ");
            for (Character c : exclusive) {
                label.append(c).append(' ');
            }
            for (Character c : shared) {
                label.append(c).append(' ');
            }
            signals.add(new FilterVerdict.Signal("lv-diacritics", label.toString().trim(), w));
        }

        // 2. Latvian function words.
        List<String> lvHits = retainAll(tokens, LATVIAN_STOPWORDS);
        if (!lvHits.isEmpty()) {
            int w = Math.min(lvHits.size() * W_STOPWORD, W_STOPWORD_CAP);
            signals.add(new FilterVerdict.Signal("lv-stopwords",
                    "Latviešu vārdi: " + join(lvHits, 6), w));
        }

        // 3. Curated-channel membership: the strongest single positive.
        if (curatedChannel) {
            signals.add(new FilterVerdict.Signal("curated-channel",
                    "Latviešu kanāls: " + (channelTitle == null ? "nezināms" : channelTitle),
                    W_CURATED_CHANNEL));
        }

        // 4. Cyrillic is decisive-negative; otherwise look for Russian words.
        if (containsCyrillic(raw)) {
            signals.add(new FilterVerdict.Signal("cyrillic", "Kirilica", W_CYRILLIC));
        } else {
            List<String> ruHits = retainAll(tokens, RUSSIAN_STOPWORDS);
            if (!ruHits.isEmpty()) {
                int w = Math.max(ruHits.size() * W_RUSSIAN_STOPWORD, W_RUSSIAN_STOPWORD_CAP);
                signals.add(new FilterVerdict.Signal("ru-stopwords",
                        "Krievu vārdi: " + join(ruHits, 6), w));
            }
        }

        // 5. English markers: weak negative, only meaningful without Latvian signals.
        List<String> enHits = retainAll(tokens, ENGLISH_MARKERS);
        if (!enHits.isEmpty()) {
            int w = Math.max(enHits.size() * W_ENGLISH_MARKER, W_ENGLISH_MARKER_CAP);
            signals.add(new FilterVerdict.Signal("en-markers",
                    "Angļu vārdi: " + join(enHits, 6), w));
        }

        int score = 0;
        for (FilterVerdict.Signal s : signals) {
            score += s.getWeight();
        }
        boolean accepted = score >= ACCEPT_THRESHOLD;

        // A stated language tag overrides inference entirely.
        boolean decisive = false;
        if (audioLanguage != null && !audioLanguage.isEmpty()) {
            boolean isLatvian = audioLanguage.toLowerCase(Locale.ROOT).startsWith("lv");
            signals.add(new FilterVerdict.Signal("audio-language",
                    "YouTube valodas tags: " + audioLanguage, 0));
            accepted = isLatvian;
            decisive = true;
        }

        return new FilterVerdict(score, accepted, signals, decisive);
    }

    /** Convenience overload for scoring bare text with no channel context. */
    public static FilterVerdict gateText(String text) {
        return gate(text, false, null, null);
    }

    // --- Helpers ------------------------------------------------------------

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(Character.toLowerCase(cp));
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            i += width;
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static List<String> retainAll(Set<String> tokens, Set<String> vocabulary) {
        List<String> hits = new ArrayList<>();
        for (String token : tokens) {
            if (vocabulary.contains(token)) {
                hits.add(token);
            }
        }
        return hits;
    }

    private static boolean containsCyrillic(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> items, int limit) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(items.size(), limit);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
