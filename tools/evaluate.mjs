/**
 * LatvianTube — filter evaluation harness.
 *
 * Because this machine has no JDK and no Android SDK (see ../../ANALYSIS.md),
 * the Android app cannot be executed here. This harness exercises the *same*
 * algorithm and the *same* endpoints in Node so the pipeline is verified by
 * real evidence rather than assertion, and so the Java port has a fixture set
 * it must match.
 *
 * Run:  node tools/evaluate.mjs
 * Out:  tools/REPORT.md  (rewritten each run)
 */

import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { InnerTubeClient, extractVideos, extractChannels, sleep } from './innertube-client.mjs';
import { gateVideo, scoreLatvian, ACCEPT_THRESHOLD, LATVIAN_TOPIC_WORDS } from './latvian-filter.mjs';
import { registry } from './channel-registry.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

const SEARCH_QUERIES = [
  'latviešu dziesmas',
  'latvijas ziņas',
  'latviešu filma',
  'latviešu virtuve receptes',
  'latvijas vēsture',
  'bērniem latviski multfilmas',
  'latviešu podkāsts',
];

const requestLog = [];
const client = new InnerTubeClient({ onRaw: (r) => requestLog.push(r) });

const accepted = [];
const rejected = [];
const channelsSeen = new Map();
const errors = [];

console.log(`LatvianTube filter evaluation — accept threshold = ${ACCEPT_THRESHOLD}\n`);

for (const query of SEARCH_QUERIES) {
  let res;
  try {
    res = await client.search(query);
  } catch (e) {
    errors.push({ query, error: e.message, status: e.status });
    console.log(`  ! ${query} -> ${e.message}`);
    await sleep(800);
    continue;
  }
  const videos = extractVideos(res);
  for (const c of extractChannels(res)) {
    if (!channelsSeen.has(c.channelId)) channelsSeen.set(c.channelId, { ...c, fromQuery: query });
  }

  let ok = 0;
  for (const v of videos) {
    const verdict = gateVideo(v, registry);
    (verdict.accepted ? accepted : rejected).push({ ...v, verdict, query });
    if (verdict.accepted) ok++;
  }
  console.log(
    `  ${query.padEnd(32)} ${String(videos.length).padStart(3)} videos -> ` +
      `${String(ok).padStart(3)} admitted, ${videos.length - ok} rejected`,
  );
  await sleep(600); // paced: stay well inside normal usage
}

// --- Human-audited fixtures: the Java port must reproduce these verdicts ----

const FIXTURES = [
  { title: 'Labākās Latviešu Dziesmas 2026', expect: true, note: 'diacritics' },
  { title: 'Metronoms - Nāk Nakts (Asaru Krelles)', expect: true, note: 'diacritics' },
  { title: 'Latvijas ziņas šodien', expect: true, note: 'stopwords' },
  { title: 'Prāta Vētra - Ogres dziesma', expect: true, note: 'diacritics' },
  { title: 'DZIESMAS JAUNAI DIENAI 2026', expect: false, note: 'no diacritics, no stopwords — needs channel signal' },
  { title: 'Новости Латвии сегодня', expect: false, note: 'cyrillic' },
  { title: 'Best of Latvia 4K', expect: false, note: 'english markers only' },
  { title: 'Русские песни', expect: false, note: 'cyrillic' },
];

console.log('\nFixtures:');
const fixtureRows = FIXTURES.map((f) => {
  const r = scoreLatvian(f.title);
  const pass = r.accepted === f.expect;
  console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${JSON.stringify(f.title).padEnd(42)} score=${String(r.score).padStart(3)} accepted=${r.accepted}`);
  return { ...f, ...r, pass };
});

// --- Report ----------------------------------------------------------------

const pct = (a, b) => (b === 0 ? 'n/a' : `${((a / b) * 100).toFixed(1)}%`);
const total = accepted.length + rejected.length;

const table = (rows, n = 12) =>
  rows
    .slice(0, n)
    .map((v) => `| ${v.title.replace(/\|/g, '\\|').slice(0, 46)} | ${v.channelTitle.slice(0, 24)} | ${v.verdict.score} |`)
    .join('\n');

const md = `# LatvianTube — Latvian filter evaluation

Generated: ${new Date().toISOString()}
Endpoint: \`https://www.youtube.com/youtubei/v1\` with \`hl=lv&gl=LV\` pinned
Accept threshold: **${ACCEPT_THRESHOLD}**

## Summary

| Metric | Value |
| --- | --- |
| Search queries run | ${SEARCH_QUERIES.length} |
| Videos retrieved | ${total} |
| Admitted as Latvian | ${accepted.length} (${pct(accepted.length, total)}) |
| Rejected | ${rejected.length} (${pct(rejected.length, total)}) |
| Distinct channels seen | ${channelsSeen.size} |
| Request errors | ${errors.length} |

## Per-query results

| Query | Retrieved | Admitted |
| --- | --- | --- |
${SEARCH_QUERIES.map((q) => {
  const got = [...accepted, ...rejected].filter((v) => v.query === q);
  return `| ${q} | ${got.length} | ${got.filter((v) => v.verdict.accepted).length} |`;
}).join('\n')}

## Sample admitted

| Title | Channel | Score |
| --- | --- | --- |
${table(accepted)}

## Sample rejected

| Title | Channel | Score |
| --- | --- | --- |
${table(rejected)}

## Fixture check

| Title | Expected | Score | Accepted | Pass | Note |
| --- | --- | --- | --- | --- | --- |
${fixtureRows.map((f) => `| ${f.title.replace(/\|/g, '\\|')} | ${f.expect} | ${f.score} | ${f.accepted} | ${f.pass ? 'yes' : 'NO'} | ${f.note} |`).join('\n')}

Fixtures passing: **${fixtureRows.filter((f) => f.pass).length}/${fixtureRows.length}**

## Candidate channel registry

Channels observed in Latvian-language searches. Membership is the strongest
positive signal in the gate, so this list is the app's most valuable asset.

| Channel | ID | Seen in query |
| --- | --- | --- |
${[...channelsSeen.values()].slice(0, 40).map((c) => `| ${c.title.replace(/\|/g, '\\|')} | \`${c.channelId}\` | ${c.fromQuery} |`).join('\n')}

## Request log

| Endpoint | Status | Bytes | ms |
| --- | --- | --- | --- |
${requestLog.map((r) => `| ${r.endpoint} | ${r.status} | ${r.bytes} | ${r.ms} |`).join('\n')}

${errors.length ? `## Errors\n\n${errors.map((e) => `- \`${e.query}\`: ${e.error}`).join('\n')}\n` : ''}

## Limitations (read before trusting the numbers)

1. **Recall is bounded by the seed queries.** These are the queries the harness
   chose; a Latvian video that never surfaces for them is invisible here.
2. **The gate infers language; it does not observe it.** YouTube did not supply
   \`defaultAudioLanguage\` for any probed video, so audio language is never
   actually verified — only surrogate text signals are.
3. **Descriptions are sparse.** \`descriptionSnippet\` is often a one-line
   teaser, so scoring runs mostly on titles plus the channel name.
4. **Short titles are the failure mode.** \`DZIESMAS JAUNAI DIENAI\` is Latvian
   but carries no diacritics and no function words; only registry membership
   rescues it.
5. **No precision/recall figure is claimed.** There is no labelled ground-truth
   corpus here; the fixture table is a spot check, not a benchmark.
`;

writeFileSync(join(HERE, 'REPORT.md'), md);
console.log(`\nTotal: ${total} videos, ${accepted.length} admitted, ${rejected.length} rejected.`);
console.log(`Fixtures: ${fixtureRows.filter((f) => f.pass).length}/${fixtureRows.length} pass.`);
console.log('Wrote tools/REPORT.md');
