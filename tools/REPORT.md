# LatvianTube — Latvian filter evaluation

Generated: 2026-10-14T07:01:25.932Z
Endpoint: `https://www.youtube.com/youtubei/v1` with `hl=lv&gl=LV` pinned
Accept threshold: **4**

## Summary

| Metric | Value |
| --- | --- |
| Search queries run | 7 |
| Videos retrieved | 122 |
| Admitted as Latvian | 117 (95.9%) |
| Rejected | 5 (4.1%) |
| Distinct channels seen | 3 |
| Request errors | 0 |

## Per-query results

| Query | Retrieved | Admitted |
| --- | --- | --- |
| latviešu dziesmas | 11 | 11 |
| latvijas ziņas | 21 | 20 |
| latviešu filma | 12 | 11 |
| latviešu virtuve receptes | 19 | 19 |
| latvijas vēsture | 23 | 21 |
| bērniem latviski multfilmas | 15 | 15 |
| latviešu podkāsts | 21 | 20 |

## Sample admitted

| Title | Channel | Score |
| --- | --- | --- |
| VASARAS KRĀSAS· Labākās latviešu dziesmas 2026 | Latviešu Dziesmu Izlase | 20 |
| Labākās Latviešu Dziesmas 2026 | Latviešu Dziesmas | 10 |
| Nedzirdēta Mūzika 2026 \| Latviešu Dziesmas | Latviešu Dziesmas | 14 |
| Latviešu Vasaras Dziesmas 2026 | Latviešu Dziesmas | 6 |
| Labākās Latviešu Dziesmas 2026 | Latviešu Dziesmas | 10 |
| Jūnija Mūzika \| Latviešu Dziesmas 2026 | Latviešu Dziesmas | 10 |
| Jautras Latviešu Dziesmas 2026 | Latviešu Dziesmas | 6 |
| VASARAS MŪZIKA \| Latviešu Dziesmas 2026 | Latviešu Dziesmas | 10 |
| Dziedi Līdzi! \| Latviešu Dziesmas 2026 | Latviešu Dziesmas | 10 |
| Latviešu Ballīšu Hiti 2026 | Latviešu Dziesmas | 10 |
| VASARAS RITMOS · Populārākā latviešu popmūzika | Latviešu Dziesmu Izlase | 20 |
| 12. oktobra "Dienas ziņas" | LTV Ziņu dienests | 12 |

## Sample rejected

| Title | Channel | Score |
| --- | --- | --- |
| 📺Новости Латвии. 🕑 200 секунд. СГД заблокиро | Latvijas Ziņas | -19 |
| Saulessvece 1986 Latvijas filma | Zane Biele | 2 |
| Краткая история Латвии | Иван Зайцевский PRO исто | -25 |
| Latvieši | raitis grablovskis | 2 |
| Latvian - B1 \| Seagulls in Latvia \| Listenin | Latvian_Through_Stories | -1 |

## Fixture check

| Title | Expected | Score | Accepted | Pass | Note |
| --- | --- | --- | --- | --- | --- |
| Labākās Latviešu Dziesmas 2026 | true | 10 | true | yes | diacritics |
| Metronoms - Nāk Nakts (Asaru Krelles) | true | 4 | true | yes | diacritics |
| Latvijas ziņas šodien | true | 10 | true | yes | stopwords |
| Prāta Vētra - Ogres dziesma | true | 10 | true | yes | diacritics |
| DZIESMAS JAUNAI DIENAI 2026 | false | 2 | false | yes | no diacritics, no stopwords — needs channel signal |
| Новости Латвии сегодня | false | -25 | false | yes | cyrillic |
| Best of Latvia 4K | false | -2 | false | yes | english markers only |
| Русские песни | false | -25 | false | yes | cyrillic |

Fixtures passing: **8/8**

## Candidate channel registry

Channels observed in Latvian-language searches. Membership is the strongest
positive signal in the gate, so this list is the app's most valuable asset.

| Channel | ID | Seen in query |
| --- | --- | --- |
| LTV Ziņu dienests | `UCOSAAyJoybqsY5sZ76BaqFA` | latvijas ziņas |
| Latvian for Kids | `UCVIbKEpB8fEAxdNTAzHPqJA` | bērniem latviski multfilmas |
| F1LV Podkāsts | `UCenfeYTRB_hmQqI62aj2zZg` | latviešu podkāsts |

## Request log

| Endpoint | Status | Bytes | ms |
| --- | --- | --- | --- |
| search | 200 | 546204 | 725 |
| search | 200 | 519776 | 510 |
| search | 200 | 503486 | 468 |
| search | 200 | 639131 | 530 |
| search | 200 | 609379 | 462 |
| search | 200 | 347772 | 369 |
| search | 200 | 741301 | 652 |



## Limitations (read before trusting the numbers)

1. **Recall is bounded by the seed queries.** These are the queries the harness
   chose; a Latvian video that never surfaces for them is invisible here.
2. **The gate infers language; it does not observe it.** YouTube did not supply
   `defaultAudioLanguage` for any probed video, so audio language is never
   actually verified — only surrogate text signals are.
3. **Descriptions are sparse.** `descriptionSnippet` is often a one-line
   teaser, so scoring runs mostly on titles plus the channel name.
4. **Short titles are the failure mode.** `DZIESMAS JAUNAI DIENAI` is Latvian
   but carries no diacritics and no function words; only registry membership
   rescues it.
5. **No precision/recall figure is claimed.** There is no labelled ground-truth
   corpus here; the fixture table is a spot check, not a benchmark.
