/** A deterministic illustration of the workflow, never a compiler or benchmark. */
export const DEMO_SOURCE = [
  'public class HelloWorld {',
  '    public static void main(String[] args) {',
  '        System.out.println("Hello TurboJ!");',
  '    }',
  '}',
].join('\n');

export const DEMO_FILENAME = 'HelloWorld.java';
export const DEMO_OUTPUT = 'FAST RUN · HelloWorld\nHello TurboJ!\n\n[exit 0] Completed';
export const DEMO_DURATION_MS = 8_000;
export const DEMO_TYPING_START_MS = 800;
export const DEMO_COMPILE_MS = 5_500;
export const DEMO_RUN_MS = 6_700;

export type DemoPhase = 'idle' | 'typing' | 'compiling' | 'running' | 'completed';

export type DemoEvent = Readonly<
  | { at: number; type: 'filename'; filename: string }
  | { at: number; type: 'character'; sourceLength: number }
  | { at: number; type: 'build-complete' }
  | { at: number; type: 'output'; output: string }
  | { at: number; type: 'phase'; phase: DemoPhase }
>;

export interface DemoState {
  readonly elapsed: number;
  readonly eventIndex: number;
  readonly phase: DemoPhase;
  readonly filename: string;
  readonly sourceLength: number;
  readonly cursorLine: number;
  readonly cursorColumn: number;
  readonly status: 'READY' | 'COMPILING...' | 'BUILD OK · 0 errors';
  readonly shortcut: '' | 'F9 MAKE' | 'CTRL+F9 RUN';
  readonly output: string;
  readonly progress: number;
}

function createEvents(): readonly DemoEvent[] {
  const events: DemoEvent[] = [{ at: 500, type: 'filename', filename: DEMO_FILENAME }];
  let at = DEMO_TYPING_START_MS;
  for (let index = 0; index < DEMO_SOURCE.length; index += 1) {
    events.push({ at, type: 'character', sourceLength: index + 1 });
    at += DEMO_SOURCE[index] === '\n' ? 115 : 25;
  }
  events.push(
    { at: DEMO_COMPILE_MS, type: 'phase', phase: 'compiling' },
    { at: 6_200, type: 'build-complete' },
    { at: DEMO_RUN_MS, type: 'phase', phase: 'running' },
    { at: 7_200, type: 'output', output: 'FAST RUN · HelloWorld\nHello TurboJ!\n' },
    { at: DEMO_DURATION_MS, type: 'phase', phase: 'completed' },
  );
  return Object.freeze(events.map((event) => Object.freeze(event)));
}

export const DEMO_EVENTS = createEvents();

export function createDemoState(): DemoState {
  return {
    elapsed: 0,
    eventIndex: 0,
    phase: 'idle',
    filename: 'Untitled.java',
    sourceLength: 0,
    cursorLine: 0,
    cursorColumn: 0,
    status: 'READY',
    shortcut: '',
    output: '',
    progress: 0,
  };
}

/** Advance by absolute elapsed time. Replay starts from createDemoState(). */
export function advanceDemoState(previous: DemoState, elapsed: number): DemoState {
  const finiteElapsed = Number.isFinite(elapsed) ? elapsed : previous.elapsed;
  const time = Math.min(DEMO_DURATION_MS, Math.max(previous.elapsed, finiteElapsed, 0));
  let next = { ...previous, elapsed: time, progress: time / DEMO_DURATION_MS };
  while (next.eventIndex < DEMO_EVENTS.length) {
    const event = DEMO_EVENTS[next.eventIndex];
    if (!event || event.at > time) break;
    next = { ...next, eventIndex: next.eventIndex + 1 };
    if (event.type === 'filename') {
      next = { ...next, filename: event.filename };
    } else if (event.type === 'character') {
      const character = DEMO_SOURCE[event.sourceLength - 1];
      next = {
        ...next,
        phase: 'typing',
        sourceLength: event.sourceLength,
        cursorLine: character === '\n' ? next.cursorLine + 1 : next.cursorLine,
        cursorColumn: character === '\n' ? 0 : next.cursorColumn + 1,
      };
    } else if (event.type === 'build-complete') {
      next = { ...next, status: 'BUILD OK · 0 errors', shortcut: '' };
    } else if (event.type === 'output') {
      next = { ...next, output: event.output };
    } else {
      const phase = event.phase;
      next = {
        ...next,
        phase,
        status: phase === 'compiling' ? 'COMPILING...' : 'BUILD OK · 0 errors',
        shortcut: phase === 'compiling' ? 'F9 MAKE' : phase === 'running' ? 'CTRL+F9 RUN' : '',
        output: phase === 'completed' ? DEMO_OUTPUT : phase === 'running' ? 'FAST RUN · HelloWorld' : '',
      };
    }
  }
  return next;
}
