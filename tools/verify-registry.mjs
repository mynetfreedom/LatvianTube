/**
 * LatvianTube — curated registry verifier.
 *
 * The registry is the strongest positive signal in the language gate, so a
 * wrong entry both admits non-Latvian video and hides the gate's mistakes.
 * This script fetches each curated channel's uploads tab and reports how many
 * of the latest videos the gate actually accepts, plus which ones it rejects
 * and why. A channel that fails is a registry bug, not a filter bug.
 *
 * Run:  node tools/verify-registry.mjs
 */

import { InnerTubeClient, extractVideos, sleep } from './innertube-client.mjs';
import { gateVideo, scoreLatvian } from './latvian-filter.mjs';
import { registry } from './channel-registry.mjs';

const client = new InnerTubeClient();
const results = [];

console.log(`Verifying ${registry.size} curated channels against live uploads…\n`);

for (const ch of registry.channels) {
  let videos = [];
  let error = null;
  try {
    videos = extractVideos(await client.channelVideos(ch.id));
  } catch (e) {
    error = `${e.message}${e.status ? ` (HTTP ${e.status})` : ''}`;
  }

  // Score on title + channel title. The channel IS curated, so it also gets the
  // curated bonus — but we deliberately re-score WITHOUT it to see whether the
  // channel's own titles would stand up on their own.
  // On a channel's own uploads tab the response carries no byline, so stamp the
  // owning channel onto each video. Without this the curated bonus never fires —
  // a bug this script caught on its first run.
  const rows = videos.map((v) => {
    const stamped = { ...v, channelId: ch.id, channelTitle: ch.title };
    const withRegistry = gateVideo(stamped, registry);
    const withoutRegistry = scoreLatvian(`${v.title} ${ch.title}`);
    return { title: v.title, withRegistry, withoutRegistry };
  });

  const admitted = rows.filter((r) => r.withRegistry.accepted).length;
  const selfSufficient = rows.filter((r) => r.withoutRegistry.accepted).length;
  const pct = videos.length ? ((admitted / videos.length) * 100).toFixed(0) : '0';

  results.push({ ch, videos, rows, admitted, selfSufficient, error, pct });

  const flag = error ? 'ERR ' : Number(pct) >= 80 ? 'OK  ' : Number(pct) >= 50 ? 'WARN' : 'FAIL';
  console.log(
    `${flag} ${ch.title.slice(0, 28).padEnd(30)} ${String(videos.length).padStart(3)} videos, ` +
      `admitted ${String(admitted).padStart(3)} (${pct}%), self-sufficient ${String(selfSufficient).padStart(3)}` +
      (error ? `  ${error}` : ''),
  );

  // Show the rejects for any channel that is not clean.
  if (!error && Number(pct) < 80) {
    for (const r of rows.filter((x) => !x.withRegistry.accepted).slice(0, 3)) {
      console.log(`       reject: "${r.title.slice(0, 60)}" score=${r.withRegistry.score}`);
    }
  }

  await sleep(450);
}

const clean = results.filter((r) => !r.error && Number(r.pct) >= 80);
const weak = results.filter((r) => !r.error && Number(r.pct) < 80);

console.log(`\n--- Summary ---`);
console.log(`Channels verified clean (>=80% admitted): ${clean.length}/${results.length}`);
console.log(`Channels needing review:                  ${weak.length}`);
if (weak.length) {
  console.log('\nReview these registry entries:');
  for (const w of weak) {
    const worst = w.rows.filter((r) => !r.withRegistry.accepted)[0];
    console.log(`  - ${w.ch.title} (${w.ch.id}): ${w.pct}% admitted. e.g. "${worst?.title?.slice(0, 50)}" score=${worst?.withRegistry.score}`);
  }
}
const errored = results.filter((r) => r.error);
if (errored.length) {
  console.log('\nErrors:');
  for (const e of errored) console.log(`  - ${e.ch.title}: ${e.error}`);
}
