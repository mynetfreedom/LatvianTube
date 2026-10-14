/**
 * LatvianTube — Latvian-language gate (reference implementation).
 *
 * This module is the executable specification for
 * `app/src/main/java/lv/latviantube/tv/data/filter/LatvianLanguageFilter.java`.
 * The Java port must produce identical verdicts for the fixtures in
 * `tools/fixtures.json`, which `evaluate.mjs` checks on every run.
 *
 * WHY THIS EXISTS
 * ---------------
 * YouTube metadata does not tell us reliably what language a video is in.
 * Probed 2026-05: the InnerTube `player` response omits `defaultAudioLanguage`
 * for most videos, and the channel "about" renderer no longer exposes a country
 * field to the WEB client. So "Latvian content" cannot be read off a single
 * authoritative field. It has to be *inferred* from several weak signals, and
 * the inference has to be explicit about its confidence.
 *
 * The gate therefore returns a score plus the individual signals that produced
 * it, so the UI can show *why* a video was admitted.
 */

// --- Signal vocabularies ---------------------------------------------------

/**
 * Diacritics are split by how diagnostic they are.
 *
 * EXCLUSIVE: within Latin-script European languages these characters are
 * effectively Latvian-only markers. A single "ā" in an otherwise English title
 * is strong evidence of Latvian.
 * SHARED: "š", "ž" and "č" are also Czech, Slovak, Slovene, Croatian, Lithuanian
 * and Turkish, so they count for much less on their own.
 */
export const LATVIAN_DIACRITICS_EXCLUSIVE = 'āēīūōņķļģ';
export const LATVIAN_DIACRITICS_SHARED = 'šžč';
export const LATVIAN_DIACRITICS = LATVIAN_DIACRITICS_EXCLUSIVE + LATVIAN_DIACRITICS_SHARED;

/** Latvian function words. High precision: these are not English/Russian words. */
export const LATVIAN_STOPWORDS = [
  'un', 'ar', 'no', 'par', 'kas', 'tas', 'tā', 'ir', 'nav', 'bet', 'vai', 'kā',
  'šis', 'šī', 'šie', 'mēs', 'jūs', 'viņš', 'viņa', 'viņi', 'es', 'tu',
  'uz', 'pie', 'pēc', 'pirms', 'arī', 'tikai', 'vēl', 'jau', 'tad', 'tāpēc',
  'jo', 'lai', 'kad', 'kur', 'kāpēc', 'cik', 'cits', 'cita', 'viss', 'visa',
  'ļoti', 'labi', 'jauns', 'jauna', 'liels', 'liela', 'mazs', 'maza',
  'diena', 'dienas', 'gads', 'gadi', 'laiks', 'cilvēki', 'dziesma', 'dziesmas',
  'latvija', 'latvijas', 'latviešu', 'latviski', 'rīga', 'rīgas',
  'sveiki', 'paldies', 'lūdzu', 'šodien', 'rīt', 'vakar', 'tagad',
  // Verb/pronoun forms common in headlines and episode titles. Added after the
  // registry verifier showed genuine Latvian titles scoring just under the
  // threshold (e.g. "Sivēni | Visiem vajag naudu").
  'visiem', 'vajag', 'var', 'būt', 'bija', 'būs', 'tiek', 'tika', 'mūsu',
  'jūsu', 'viņu', 'ko', 'kurš', 'kura', 'kurā', 'par', 'pret', 'vairs',
  'šeit', 'tur', 'tāpat', 'kopā', 'bez', 'starp', 'tieši',
];

/** Latvian-language channel/topic words used for search seeding. */
export const LATVIAN_TOPIC_WORDS = [
  'latviešu', 'latvija', 'latvijas', 'latviski', 'dziesmas', 'ziņas',
  'filma', 'filmas', 'multfilmas', 'sports', 'mūzika', 'virtuve', 'receptes',
  'vēsture', 'ceļojumi', 'daba', 'bērniem', 'komēdija', 'podkāsts',
];

/** Cyrillic range — a video titled in Cyrillic is not a Latvian-language video. */
const CYRILLIC = /[\u0400-\u04FF]/;

/** Russian function words, romanised misses aside. */
export const RUSSIAN_STOPWORDS = [
  'и', 'в', 'не', 'на', 'что', 'это', 'как', 'для', 'по', 'он', 'она', 'все',
  'но', 'за', 'от', 'так', 'же', 'бы', 'только', 'еще', 'уже', 'очень',
  'самый', 'новый', 'лучший', 'смотреть', 'онлайн',
];

/**
 * Latin-script English words that are common in titles globally. Weighted
 * weakly and deliberately: "official", "live" and "video" appear in genuine
 * Latvian titles too, so they must not veto a title on their own.
 */
export const ENGLISH_MARKERS = [
  'the', 'and', 'of', 'to', 'in', 'is', 'you', 'for', 'with', 'that', 'this',
  'are', 'it', 'be', 'as', 'at', 'by', 'from', 'or', 'an', 'we', 'your', 'my',
  'best', 'new', 'official', 'full', 'live', 'episode', 'trailer',
];

// --- Scoring model ---------------------------------------------------------

export const WEIGHTS = {
  // An exclusive diacritic alone reaches the threshold: one "ā" is already
  // strong evidence. Shared diacritics need company.
  exclusiveDiacritic: 4,
  sharedDiacritic: 2,
  diacriticCap: 12,
  stopwordPerHit: 2,
  stopwordCap: 12,
  curatedChannel: 6,
  cyrillic: -25,
  russianStopwordPerHit: -3,
  russianStopwordCap: -12,
  englishMarkerPerHit: -1,
  englishMarkerCap: -5,
};

/** Threshold on the summed score. */
export const ACCEPT_THRESHOLD = 4;

const tokenize = (text) =>
  (text || '')
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);

/**
 * Score one text blob (title + description + channel name) for Latvianness.
 *
 * @param {string} text
 * @param {{curatedChannel?: boolean, channelTitle?: string}} [opts]
 * @returns {{score:number, accepted:boolean, signals:Array<{id:string,label:string,weight:number}>}}
 */
export function scoreLatvian(text, opts = {}) {
  const signals = [];
  const raw = text || '';
  const lower = raw.toLowerCase();
  const tokens = tokenize(raw);
  const tokenSet = new Set(tokens);

  // 1. Latvian diacritics, weighted by how exclusive they are to Latvian.
  const exclusiveHits = [...new Set([...lower].filter((c) => LATVIAN_DIACRITICS_EXCLUSIVE.includes(c)))];
  const sharedHits = [...new Set([...lower].filter((c) => LATVIAN_DIACRITICS_SHARED.includes(c)))];
  if (exclusiveHits.length || sharedHits.length) {
    const w = Math.min(
      exclusiveHits.length * WEIGHTS.exclusiveDiacritic,
      WEIGHTS.diacriticCap,
    ) + Math.min(
      sharedHits.length * WEIGHTS.sharedDiacritic,
      WEIGHTS.diacriticCap,
    );
    signals.push({
      id: 'lv-diacritics',
      label: `Latvian diacritics: ${[...exclusiveHits, ...sharedHits].join(' ')}`,
      weight: w,
    });
  }

  // 2. Latvian function words.
  const lvHits = LATVIAN_STOPWORDS.filter((w) => tokenSet.has(w));
  if (lvHits.length) {
    const w = Math.min(lvHits.length * WEIGHTS.stopwordPerHit, WEIGHTS.stopwordCap);
    signals.push({
      id: 'lv-stopwords',
      label: `Latvian words: ${lvHits.slice(0, 6).join(', ')}`,
      weight: w,
    });
  }

  // 3. Curated-channel membership (strongest single positive).
  if (opts.curatedChannel) {
    signals.push({
      id: 'curated-channel',
      label: `Curated Latvian channel: ${opts.channelTitle ?? 'unknown'}`,
      weight: WEIGHTS.curatedChannel,
    });
  }

  // 4. Cyrillic script — decisive negative.
  if (CYRILLIC.test(raw)) {
    signals.push({ id: 'cyrillic', label: 'Cyrillic script detected', weight: WEIGHTS.cyrillic });
  } else {
    const ruHits = RUSSIAN_STOPWORDS.filter((w) => tokenSet.has(w));
    if (ruHits.length) {
      const w = Math.max(ruHits.length * WEIGHTS.russianStopwordPerHit, WEIGHTS.russianStopwordCap);
      signals.push({
        id: 'ru-stopwords',
        label: `Russian words: ${ruHits.slice(0, 6).join(', ')}`,
        weight: w,
      });
    }
  }

  // 5. English markers — weak negative, only meaningful without Latvian signals.
  const enHits = ENGLISH_MARKERS.filter((w) => tokenSet.has(w));
  if (enHits.length) {
    const w = Math.max(enHits.length * WEIGHTS.englishMarkerPerHit, WEIGHTS.englishMarkerCap);
    signals.push({
      id: 'en-markers',
      label: `English markers: ${enHits.slice(0, 6).join(', ')}`,
      weight: w,
    });
  }

  const score = signals.reduce((a, s) => a + s.weight, 0);
  return { score, accepted: score >= ACCEPT_THRESHOLD, signals };
}

/**
 * Gate a video record.
 *
 * @param {{title:string, description?:string, channelTitle?:string,
 *          channelId?:string, defaultAudioLanguage?:string}} video
 * @param {{isCuratedChannel?: (channelId:string)=>boolean}} [registry]
 */
export function gateVideo(video, registry = {}) {
  const curated = video.channelId && registry.isCuratedChannel
    ? registry.isCuratedChannel(video.channelId)
    : false;

  const text = [video.title, video.description, video.channelTitle].filter(Boolean).join(' \n ');
  const result = scoreLatvian(text, { curatedChannel: curated, channelTitle: video.channelTitle });

  // An explicit audio-language tag, when YouTube does supply one, is decisive.
  if (video.defaultAudioLanguage) {
    const isLv = video.defaultAudioLanguage.toLowerCase().startsWith('lv');
    result.signals.push({
      id: 'audio-language',
      label: `YouTube defaultAudioLanguage=${video.defaultAudioLanguage}`,
      weight: 0,
    });
    result.accepted = isLv;
    result.decisive = true;
  }
  result.curatedChannel = curated;
  return result;
}
