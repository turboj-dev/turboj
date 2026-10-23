import type { CatalogEntry, ThemePalette } from './theme-catalog';

export type SpinPlan = { duration: number; target: number; indices: number[]; deadlines: number[] };

export function orderForSpin(entries: readonly CatalogEntry[]): CatalogEntry[] {
  const lightness = (hex: string): number => [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16) / 255)
    .map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
    .reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
  return [...entries].sort((a, b) => lightness(a.previewBackground ?? '#000000') - lightness(b.previewBackground ?? '#000000')
    || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** Rejection sampling avoids favoring any catalog entry, including large filtered lists. */
export function uniformIndex(count: number): number {
  if (!Number.isInteger(count) || count < 1 || count > 0x100000000) throw new RangeError('Invalid choice count');
  const limit = Math.floor(0x100000000 / count) * count;
  const sample = new Uint32Array(1);
  do { crypto.getRandomValues(sample); } while (sample[0] >= limit);
  return sample[0] % count;
}

/** Matches the native drum: roughly24 rows/second initially, cubic deceleration and no repeated candidates. */
export function spinPlan(count: number, target: number, duration: number): SpinPlan {
  if (!Number.isInteger(count) || count < 1 || !Number.isInteger(target) || target < 0 || target >= count
    || !Number.isInteger(duration) || duration < 3_000 || duration > 5_000) throw new RangeError('Invalid spin');
  const steps = Math.min(count - 1, Math.round(duration / 125));
  return { duration, target,
    indices: Array.from({ length: steps + 1 }, (_, index) => ((target - steps + index) % count + count) % count),
    deadlines: Array.from({ length: steps }, (_, index) => index === steps - 1 ? duration
      : duration * (1 - Math.cbrt(1 - (index + 1) / steps))),
  };
}

export class ThemeSpin {
  private root: HTMLElement;
  private drum: HTMLElement;
  private current: HTMLElement;
  private progress: HTMLProgressElement;
  private load: (entry: CatalogEntry, signal: AbortSignal) => Promise<ThemePalette>;
  private preview: (entry: CatalogEntry, palette: ThemePalette) => void;
  private complete: (entry: CatalogEntry, palette: ThemePalette) => void;
  private failed: (message: string) => void;
  private media = matchMedia('(prefers-reduced-motion: reduce)');
  private abort: AbortController | null = null;
  private entries: CatalogEntry[] = [];
  private plan: SpinPlan | null = null;
  private palettes = new Map<string, Promise<ThemePalette>>();
  private ready = new Map<string, ThemePalette>();
  private targetPalette: ThemePalette | null = null;
  private frame = 0;
  private last = 0;
  private elapsed = 0;
  private step = 0;
  private generation = 0;
  private waiting = false;
  private skipRequested = false;
  private prepared = false;

  constructor(root: HTMLElement, load: ThemeSpin['load'], preview: ThemeSpin['preview'], complete: ThemeSpin['complete'], failed: ThemeSpin['failed']) {
    this.root = root; this.load = load; this.preview = preview; this.complete = complete; this.failed = failed;
    this.drum = root.querySelector('[data-theme-drum]')!;
    this.current = root.querySelector('[data-theme-spin-current]')!;
    this.progress = root.querySelector('progress')!;
    document.addEventListener('visibilitychange', () => {
      if (!this.plan || this.root.dataset.state === 'completed') return;
      cancelAnimationFrame(this.frame); this.frame = 0; this.last = 0;
      if (document.hidden) this.root.dataset.state = 'paused';
      else if (this.prepared && !this.waiting) this.schedule();
      else this.root.dataset.state = 'loading';
    });
    this.media.addEventListener('change', () => { if (this.media.matches) this.skip(); });
  }

  get running(): boolean { return this.plan !== null; }

  async start(entries: readonly CatalogEntry[]): Promise<void> {
    this.cancel();
    if (!entries.length) return;
    const generation = ++this.generation;
    this.entries = orderForSpin(entries);
    this.plan = spinPlan(entries.length, uniformIndex(entries.length), 3_000 + uniformIndex(2_001));
    this.abort = new AbortController();
    const signal = this.abort.signal;
    this.elapsed = 0; this.step = 0; this.last = 0;
    this.root.hidden = false; this.root.dataset.state = 'loading';
    this.root.dataset.targetReady = 'false'; this.root.dataset.position = '0';
    this.root.dataset.duration = String(this.plan.duration); this.root.dataset.elapsed = '0';
    this.root.dataset.total = String(entries.length); this.root.dataset.target = this.entries[this.plan.target].id;
    this.progress.value = 0; this.progress.max = this.plan.duration;
    this.current.textContent = 'Preparing genuine theme previews…'; this.drum.textContent = this.rows(null);
    try {
      // The landing palette is validated before the clock starts and retained independently of the LRU.
      const targetPalette = await this.palette(this.entries[this.plan.target], signal);
      if (generation !== this.generation || !this.plan) return;
      this.targetPalette = targetPalette;
      this.root.dataset.targetReady = 'true';
      if (this.media.matches || this.skipRequested || this.plan.indices.length === 1) { this.land(); return; }
      await Promise.all(this.plan.indices.slice(0, 7).map((index) => this.palette(this.entries[index], signal)));
      if (generation !== this.generation || !this.plan) return;
      const first = this.entries[this.plan.indices[0]];
      const palette = this.ready.get(first.id)!;
      if (generation !== this.generation || !this.plan) return;
      this.prepared = true; this.show(first, palette); this.position(0); this.schedule();
    } catch (error: unknown) {
      if (generation !== this.generation) return;
      this.cancel(); this.failed(error instanceof Error ? error.message : 'Theme preview unavailable.');
    }
  }

  private palette(entry: CatalogEntry, signal: AbortSignal): Promise<ThemePalette> {
    let value = this.palettes.get(entry.id);
    if (!value) {
      const generation = this.generation;
      value = this.load(entry, signal).then((palette) => {
        if (generation === this.generation && this.palettes.get(entry.id) === value) this.ready.set(entry.id, palette);
        return palette;
      });
      this.palettes.set(entry.id, value);
    }
    return value;
  }

  private prefetch(signal: AbortSignal): void {
    if (!this.plan) return;
    const upcoming = this.plan.indices.slice(this.step + 1, this.step + 7).map((index) => this.entries[index]);
    const keep = new Set(upcoming.map((entry) => entry.id));
    keep.add(this.entries[this.plan.indices[this.step]].id);
    keep.add(this.entries[this.plan.target].id);
    for (const id of this.palettes.keys()) if (!keep.has(id)) { this.palettes.delete(id); this.ready.delete(id); }
    for (const entry of upcoming) void this.palette(entry, signal).catch(() => {});
  }

  private rows(index: number | null): string {
    const names = Array.from({ length: 6 }, (_, row) => {
      const entry = index === null ? null : this.entries[((index + row - 2) % this.entries.length + this.entries.length) % this.entries.length];
      const name = entry ? [...entry.name].slice(0, 28).join('') : 'Loading…';
      return `| ${name.padEnd(28)} |`;
    });
    return names.join('\n');
  }

  private position(value: number): void {
    const fraction = value - Math.floor(value);
    this.drum.style.transform = `translateY(${-fraction * 1.45}em)`;
    this.root.dataset.position = value.toFixed(3);
  }

  private show(entry: CatalogEntry, palette: ThemePalette): void {
    this.drum.textContent = this.rows(this.entries.findIndex((item) => item.id === entry.id));
    this.current.textContent = `Previewing ${entry.name}`;
    this.root.dataset.current = entry.id;
    this.preview(entry, palette);
  }

  private schedule(): void {
    if (!this.plan || !this.prepared || document.hidden || this.waiting) return;
    if (!this.last) this.last = performance.now();
    this.root.dataset.state = 'spinning';
    this.frame = requestAnimationFrame((time) => this.tick(time));
  }

  private tick(time: number): void {
    this.frame = 0;
    if (!this.plan || document.hidden) return;
    this.elapsed = Math.min(this.plan.duration, this.elapsed + (this.last ? time - this.last : 0)); this.last = time;
    const steps = this.plan.indices.length - 1;
    const position = steps * (1 - (1 - this.elapsed / this.plan.duration) ** 3);
    if (Math.floor(position) > this.step) {
      const generation = this.generation;
      const entry = this.entries[this.plan.indices[this.step + 1]];
      const advance = (palette: ThemePalette): void => {
        if (generation !== this.generation || !this.plan) return;
        this.step++;
        if (this.step >= steps) { this.land(); return; }
        this.waiting = false; this.show(entry, palette); this.prefetch(this.abort!.signal);
        this.position(Math.min(position, this.step + .999)); this.schedule();
      };
      const palette = this.ready.get(entry.id);
      if (palette) { advance(palette); return; }
      // Pause the active clock if a future palette is unexpectedly unavailable; never label the wrong colors.
      this.waiting = true; this.last = 0;
      void this.palette(entry, this.abort!.signal).then(advance).catch((error: unknown) => {
        if (generation !== this.generation) return;
        this.cancel(); this.failed(error instanceof Error ? error.message : 'Theme preview unavailable.');
      });
      return;
    }
    this.position(position);
    this.root.dataset.elapsed = String(Math.round(this.elapsed)); this.progress.value = this.elapsed;
    this.schedule();
  }

  skip(): void {
    if (!this.plan) return;
    if (this.targetPalette) this.land(); else this.skipRequested = true;
  }

  private land(): void {
    if (!this.plan || !this.targetPalette) return;
    const entry = this.entries[this.plan.target], palette = this.targetPalette;
    this.root.dataset.elapsed = String(this.plan.duration); this.root.dataset.state = 'completed';
    this.show(entry, palette); this.position(this.plan.indices.length - 1); this.progress.value = this.plan.duration;
    this.cancel(false); this.complete(entry, palette);
  }

  cancel(hide = true): void {
    this.generation++; cancelAnimationFrame(this.frame); this.frame = 0;
    this.abort?.abort(); this.abort = null; this.plan = null;
    this.targetPalette = null; this.palettes.clear(); this.ready.clear(); this.waiting = false; this.last = 0; this.skipRequested = false; this.prepared = false;
    if (hide) { this.root.hidden = true; this.root.dataset.state = 'idle'; }
  }
}
