/** Captured checkpoints, never inferred editor effects or invented operation times. */
export const CHAPTER_TITLES = ['Hello World', 'Tests', 'Refactoring', 'AutoRun / AutoTest', 'Spring Boot + curl'] as const;
/** Editorial reading time after the last captured result, never part of an operation measurement. */
export const STORY_LOOP_HOLD_MS = 3000;

export interface DemoManifest {
  schemaVersion: 1;
  id: string;
  verified: boolean;
  keymap: string;
  dependencyCache?: string;
  menus: string[];
  detailsUrl: string;
  chapters: { id: string; title: string; url: string; durationMs: number; sha256: string }[];
}
export interface DemoEvent {
  id: string;
  atMs: number;
  kind: string;
  label: string;
  sourceId: string;
  outputId: string;
  filename: string;
  cursorOffset: number;
  selectionStart: number;
  selectionEnd: number;
  topLine: number;
  status: string;
  external: boolean;
  command?: string;
  operationId?: string;
  dialogLabel?: string;
  dialogId?: string;
  screenshot?: string;
  menuItems?: { text?: string; separator?: boolean; enabled?: boolean; selected?: boolean; accelerator?: string }[];
  explorer?: { visible: boolean; rows: { text: string; depth: number; expanded: boolean; selected: boolean }[] };
  commandLine?: { visible: boolean; text: string; caret: number; focused?: boolean; hint?: string };
  outputFraction?: number;
}
export interface DemoOperation {
  id: string;
  label: string;
  startMs: number;
  endMs: number;
  durationMs: number;
  boundary: string;
}
export interface DemoChapter {
  id: string;
  title: string;
  durationMs: number;
  sources: Record<string, { file: string; text: string; sha256: string }>;
  outputs: Record<string, string>;
  events: DemoEvent[];
  dialogs?: Record<string, { title: string; components: { type: string; text: string; enabled?: boolean; selected?: boolean | string }[] }[]>;
  operations: DemoOperation[];
  checkpoints: { id: string; label: string; atMs: number }[];
}

/** Binary search complete snapshots: seeking never depends on previous playback. */
export function seekChapter(chapter: DemoChapter, requestedMs: number) {
  const elapsedMs = Math.max(0, Math.min(chapter.durationMs, Number.isFinite(requestedMs) ? requestedMs : 0));
  let low = 0;
  let high = chapter.events.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (chapter.events[middle].atMs <= elapsedMs) low = middle + 1;
    else high = middle;
  }
  const event = chapter.events[Math.max(0, low - 1)];
  return { elapsedMs, event, completed: elapsedMs >= chapter.durationMs };
}

/** Runtime guard keeps a missing, incomplete, or incompatible capture off the hero. */
export function validateManifest(value: DemoManifest) {
  if (value.schemaVersion !== 1 || value.verified !== true || !value.id || !Array.isArray(value.menus)
    || value.chapters?.length !== CHAPTER_TITLES.length
    || value.chapters.some((chapter, index) => chapter.title !== CHAPTER_TITLES[index]
      || !chapter.id || !Number.isFinite(chapter.durationMs) || chapter.durationMs <= 0
      || !chapter.url.startsWith('/demo/hero-story/'))) throw new Error('The recorded session is unavailable.');
  return value;
}
export function validateChapter(value: DemoChapter, id: string) {
  if (value.id !== id || !Number.isFinite(value.durationMs) || value.durationMs <= 0
    || !value.events?.length || value.events[0].atMs !== 0
    || !Array.isArray(value.operations) || !Array.isArray(value.checkpoints)
    || value.events.some((event, index) => !Number.isFinite(event.atMs) || event.atMs < 0
      || event.atMs > value.durationMs || (index > 0 && event.atMs < value.events[index - 1].atMs)
      || !(event.sourceId in value.sources) || typeof value.outputs[event.outputId] !== 'string'
      || (event.outputFraction !== undefined && (!Number.isFinite(event.outputFraction) || event.outputFraction <= 0 || event.outputFraction >= 1))
      || (event.commandLine !== undefined && (typeof event.commandLine.visible !== 'boolean'
        || typeof event.commandLine.text !== 'string' || !Number.isInteger(event.commandLine.caret)
        || event.commandLine.caret < 0 || event.commandLine.caret > event.commandLine.text.length))
      || (event.dialogId !== undefined && !value.dialogs?.[event.dialogId]?.length))
    || value.operations.some((operation) => operation.startMs < 0 || operation.endMs > value.durationMs
      || operation.endMs < operation.startMs || Math.abs(operation.endMs - operation.startMs - operation.durationMs) > .01)) {
    throw new Error('The recorded chapter is unavailable.');
  }
  return value;
}
