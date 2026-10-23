export type CatalogEntry = Readonly<{ id: string; name: string; url: string; previewBackground?: string }>;
export type ThemePalette = Readonly<{ id: string; name: string; colors: Readonly<Record<string, string>> }>;
export const PALETTE_STORAGE_KEY = 'turboj.theme.palette';
export const PAGE_SIZE = 6;
export const PALETTE_CACHE_SIZE = 24;
const validId = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);

export function validatePalette(value: unknown, paths: readonly string[]): ThemePalette | null {
  if (!record(value) || !validId(value.id) || typeof value.name !== 'string'
    || value.name.trim().length === 0 || value.name.length > 120 || !record(value.colors)) return null;
  const colors: Record<string, string> = {};
  for (const path of paths) {
    const color = value.colors[path];
    if (typeof color !== 'string' || !/^#[\da-fA-F]{6}$/.test(color)) return null;
    colors[path] = color.toUpperCase();
  }
  return { id: value.id, name: value.name, colors };
}

export function nativePalette(value: unknown, entry: CatalogEntry, paths: readonly string[]): ThemePalette | null {
  if (!record(value) || value.schemaVersion !== 2 || value.id !== entry.id || value.name !== entry.name) return null;
  const colors: Record<string, unknown> = {};
  for (const path of paths) {
    const [section, field] = path.split('.');
    const group = value[section];
    if (!record(group)) return null;
    colors[path] = group[field];
  }
  return validatePalette({ id: value.id, name: value.name, colors }, paths);
}

export function validateCatalog(value: unknown, origin: string): CatalogEntry[] {
  if (!Array.isArray(value) || value.length > 10_000) throw new Error('The theme catalog is invalid or too large.');
  const seen = new Set<string>();
  const result: CatalogEntry[] = [];
  for (const entry of value) {
    if (!record(entry) || !validId(entry.id) || seen.has(entry.id)
      || typeof entry.name !== 'string' || !entry.name.trim() || entry.name.length > 120 || typeof entry.url !== 'string') continue;
    if (entry.previewBackground !== undefined && (typeof entry.previewBackground !== 'string' || !/^#[\da-fA-F]{6}$/.test(entry.previewBackground))) continue;
    let url: URL;
    try { url = new URL(entry.url, origin); } catch { continue; }
    if (url.origin !== origin || !url.pathname.startsWith('/themes/') || url.username || url.password) continue;
    seen.add(entry.id);
    result.push({ id: entry.id, name: entry.name, url: url.pathname + url.search,
      ...(typeof entry.previewBackground === 'string' ? { previewBackground: entry.previewBackground.toUpperCase() } : {}) });
  }
  if (!result.length) throw new Error('No valid themes are available in this catalog.');
  return result;
}

export function cssToken(path: string): string {
  return '--tj-' + path.replace('.', '-').replace(/[A-Z]/g, (letter) => '-' + letter.toLowerCase());
}

export function isDarkPalette(palette: ThemePalette): boolean {
  const color = palette.colors['ui.background'];
  const channels = [1, 3, 5].map((start) => {
    const value = parseInt(color.slice(start, start + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722 < 0.179;
}

export function filterCatalog(catalog: readonly CatalogEntry[], query: string): CatalogEntry[] {
  const needle = query.trim().toLocaleLowerCase('en');
  return catalog.filter((theme) => theme.name.toLocaleLowerCase('en').includes(needle)
    || theme.id.toLocaleLowerCase('en').includes(needle));
}

export function pageFor(index: number): number { return Math.floor(Math.max(0, index) / PAGE_SIZE); }

/** A bounded LRU stores only validated palettes that have actually been requested. */
export class PaletteCache {
  private entries = new Map<string, ThemePalette>();
  get(id: string): ThemePalette | undefined {
    const value = this.entries.get(id);
    if (value) { this.entries.delete(id); this.entries.set(id, value); }
    return value;
  }
  put(palette: ThemePalette): void {
    this.entries.delete(palette.id); this.entries.set(palette.id, palette);
    while (this.entries.size > PALETTE_CACHE_SIZE) this.entries.delete(this.entries.keys().next().value!);
  }
  get size(): number { return this.entries.size; }
}

export async function limitedJson(url: string, signal: AbortSignal, maxBytes: number): Promise<unknown> {
  const response = await fetch(url, { signal, credentials: 'same-origin' });
  if (!response.ok) throw new Error(`Theme request failed (${response.status}). Please try again.`);
  if (Number(response.headers.get('content-length')) > maxBytes) throw new Error('Theme data exceeds the size limit.');
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Theme data is unavailable.');
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) { await reader.cancel(); throw new Error('Theme data exceeds the size limit.'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder().decode(bytes));
}
