/**
 * Loads the authoritative curated registry that the Android app also ships as
 * `app/src/main/assets/channels_lv.json`. One source of truth, two readers.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REGISTRY_PATH = join(HERE, '..', 'app', 'src', 'main', 'assets', 'channels_lv.json');

export function loadRegistry(path = REGISTRY_PATH) {
  const data = JSON.parse(readFileSync(path, 'utf8'));
  const byId = new Map();
  const ordered = [];
  for (const cat of data.categories) {
    for (const ch of cat.channels) {
      const entry = { ...ch, categoryId: cat.id, categoryTitleLv: cat.titleLv };
      byId.set(ch.id, entry);
      ordered.push(entry);
    }
  }
  return {
    raw: data,
    categories: data.categories,
    channels: ordered,
    isCuratedChannel: (id) => byId.has(id),
    get: (id) => byId.get(id) ?? null,
    size: ordered.length,
  };
}

export const registry = loadRegistry();
