/**
 * LatvianTube — InnerTube client (reference implementation).
 *
 * Mirrors `app/src/main/java/lv/latviantube/tv/data/innertube/InnerTubeClient.java`.
 * Used here to prove, against live YouTube, that the endpoints the Android app
 * depends on actually behave as the Latvian-only pipeline assumes.
 *
 * ENDPOINT EVIDENCE (probed 2026-05, see REPORT.md):
 *   search              -> 200, `hl=lv&gl=LV` returns Latvian-language results
 *   browse channelId    -> 200, params EgZ2aWRlb3PyBgQKAjoA returns the uploads tab
 *   player              -> 200, but `defaultAudioLanguage` is usually ABSENT
 *   browse FEtrending   -> 400 "invalid argument"; the /feed/trending page
 *                          serves a consent wall to this host
 *
 * The last point is why the app does NOT ship a "Trending in Latvia" row. It
 * builds its home screen from the verified sources instead.
 */

const WEB_KEY = 'AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8';
const CLIENT_VERSION = '2.20240726.00.00';
const ENDPOINT = 'https://www.youtube.com/youtubei/v1';

/** Latvian language + Latvia region are pinned for every request. No exceptions. */
export function makeContext(hl = 'lv', gl = 'LV') {
  return { client: { clientName: 'WEB', clientVersion: CLIENT_VERSION, hl, gl } };
}

/** Params selecting a channel's "Videos" (uploads) tab. Verified working. */
export const TAB_VIDEOS = 'EgZ2aWRlb3PyBgQKAjoA';

export class InnerTubeClient {
  constructor({ hl = 'lv', gl = 'LV', onRaw } = {}) {
    this.hl = hl;
    this.gl = gl;
    this.onRaw = onRaw; // cache-everything hook: raw response + timestamp
  }

  async call(endpoint, body) {
    const started = Date.now();
    const res = await fetch(`${ENDPOINT}/${endpoint}?key=${WEB_KEY}&prettyPrint=false`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept-Language': `${this.hl}-${this.gl},${this.hl};q=0.9`,
        'User-Agent':
          'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      },
      body: JSON.stringify({ context: makeContext(this.hl, this.gl), ...body }),
    });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* non-JSON error body; keep raw text */
    }
    // Cache-and-record: never let an error page masquerade as "no results".
    this.onRaw?.({
      endpoint,
      request: body,
      status: res.status,
      ms: Date.now() - started,
      bytes: text.length,
      at: new Date().toISOString(),
      body: json ?? text.slice(0, 400),
    });
    if (!res.ok) {
      const err = new Error(`InnerTube ${endpoint} HTTP ${res.status}`);
      err.status = res.status;
      err.body = json ?? text.slice(0, 400);
      throw err;
    }
    return json;
  }

  /** Latvian-scoped search. `hl=lv` makes YouTube rank Latvian-language results up. */
  search(query) {
    return this.call('search', { query, context: makeContext(this.hl, this.gl) });
  }

  /** A channel's uploads tab. */
  channelVideos(channelId, { params = TAB_VIDEOS } = {}) {
    return this.call('browse', { browseId: channelId, params });
  }

  /** Player metadata for one video. */
  player(videoId) {
    return this.call('player', { videoId });
  }

  /** "Watch next" / related rail for one video. */
  related(videoId) {
    return this.call('next', { videoId });
  }
}

// --- Response parsing ------------------------------------------------------
// InnerTube nests the same video under several renderer keys depending on
// surface (search results, grids, rails). We collapse them all to one shape.

const VIDEO_RENDERER_KEYS = [
  'videoRenderer',
  'gridVideoRenderer',
  'compactVideoRenderer',
  'playlistVideoRenderer',
];

function textOf(node) {
  if (!node) return '';
  if (typeof node.simpleText === 'string') return node.simpleText;
  if (Array.isArray(node.runs)) return node.runs.map((r) => r.text ?? '').join('');
  return '';
}

function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node)) {
    for (const item of node) walk(item, visit);
    return;
  }
  visit(node);
  for (const key of Object.keys(node)) walk(node[key], visit);
}

/** Find the first channel id (UC…) referenced anywhere inside a subtree. */
function findChannelId(node) {
  let found = null;
  walk(node, (n) => {
    if (found) return;
    const id = n.browseEndpoint?.browseId;
    if (typeof id === 'string' && id.startsWith('UC')) found = id;
  });
  return found;
}

/**
 * Modern (2024+) surfaces — channel tabs, search, home rails — no longer use
 * `videoRenderer`. They use `lockupViewModel`, whose title and byline live in a
 * nested `lockupMetadataViewModel`. Missing this shape silently yields zero
 * videos, which is exactly the bug this harness caught.
 */
function fromLockup(l) {
  const videoId = l.contentId;
  if (!videoId || (l.contentType && !String(l.contentType).includes('VIDEO'))) return null;

  const meta = l.metadata?.lockupMetadataViewModel;
  const rows = meta?.metadata?.contentMetadataViewModel?.metadataRows ?? [];
  const parts = rows
    .flatMap((r) => (r.metadataParts ?? []).map((p) => p.text?.content ?? textOf(p.text)))
    .filter(Boolean);

  // Metadata rows on a video lockup are [viewCount, publishedTime] and
  // optionally a byline; we keep them positionally and let the caller decide.
  return {
    videoId,
    title: meta?.title?.content ?? textOf(meta?.title),
    description: '',
    channelTitle: '',
    channelId: findChannelId(meta?.metadata ?? {}),
    lengthText: parts.find((p) => /^\d+:\d{2}/.test(p)) ?? '',
    viewCountText: parts.find((p) => /skat|view/i.test(p)) ?? '',
    publishedTimeText: parts.find((p) => /pirms|ago/i.test(p)) ?? '',
    thumbnail:
      l.contentImage?.thumbnailViewModel?.image?.sources?.at(-1)?.url ??
      l.contentImage?.collectionThumbnailViewModel?.primaryThumbnail?.thumbnailViewModel?.image?.sources?.at(-1)?.url ??
      null,
  };
}

/** Extract every video record from any InnerTube response, both eras of shape. */
export function extractVideos(response) {
  const out = new Map();
  walk(response, (node) => {
    // Modern lockup shape.
    const l = node.lockupViewModel;
    if (l) {
      const v = fromLockup(l);
      if (v && !out.has(v.videoId)) out.set(v.videoId, v);
    }
    // Classic renderer shapes.
    for (const key of VIDEO_RENDERER_KEYS) {
      const r = node[key];
      if (!r?.videoId || out.has(r.videoId)) continue;
      out.set(r.videoId, {
        videoId: r.videoId,
        title: textOf(r.title),
        description: textOf(r.descriptionSnippet),
        channelTitle: textOf(r.ownerText) || textOf(r.longBylineText) || textOf(r.shortBylineText),
        channelId: r.ownerText?.runs?.[0]?.navigationEndpoint?.browseEndpoint?.browseId
          ?? r.longBylineText?.runs?.[0]?.navigationEndpoint?.browseEndpoint?.browseId
          ?? null,
        lengthText: textOf(r.lengthText),
        viewCountText: textOf(r.viewCountText) || textOf(r.shortViewCountText),
        publishedTimeText: textOf(r.publishedTimeText),
        thumbnail: r.thumbnail?.thumbnails?.at(-1)?.url ?? null,
      });
    }
  });
  return [...out.values()];
}

/** Extract (channelId, title) pairs, for growing the curated registry. */
export function extractChannels(response) {
  const out = new Map();
  walk(response, (node) => {
    const r = node.channelRenderer;
    if (!r?.channelId || out.has(r.channelId)) return;
    out.set(r.channelId, {
      channelId: r.channelId,
      title: textOf(r.title),
      subscriberCountText: textOf(r.subscriberCountText) ?? textOf(r.videoCountText),
    });
  });
  return [...out.values()];
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
