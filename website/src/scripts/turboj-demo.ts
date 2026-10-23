import {
  DEMO_DURATION_MS,
  DEMO_SOURCE,
  advanceDemoState,
  createDemoState,
  type DemoState,
} from './demo-timeline';

type Token = { text: string; className?: string };
type RenderedToken = { element: Text | HTMLSpanElement; token: Token; rendered: string };

// This fixed, reviewed snippet is tokenized once; no user code is evaluated.
function tokenize(line: string): Token[] {
  const expression = /("(?:[^"\\]|\\.)*"|\b(?:public|class|static|void)\b)/g;
  const result: Token[] = [];
  let position = 0;
  for (const match of line.matchAll(expression)) {
    const index = match.index;
    if (index > position) result.push({ text: line.slice(position, index) });
    result.push({
      text: match[0],
      className: match[0].startsWith('"') ? 'demo-token-string' : 'demo-token-keyword',
    });
    position = index + match[0].length;
  }
  if (position < line.length) result.push({ text: line.slice(position) });
  return result;
}

function initializeDemo(root: HTMLElement): void {
  if (root.dataset.demoInitialized === 'true') return;
  const get = <T extends HTMLElement>(name: string) => root.querySelector<T>(`[data-demo-${name}]`);
  const screen = get('screen');
  const source = get('source');
  const lines = get('lines');
  const cursor = get('cursor');
  const filename = get('filename');
  const status = get('status');
  const shortcut = get('shortcut');
  const output = get('output');
  const position = get('position');
  const progress = get<HTMLProgressElement>('progress');
  const announcement = get('announcement');
  const controls = get('controls');
  const fallback = get('fallback');
  const replay = root.querySelector<HTMLButtonElement>('[data-demo-action="replay"]');
  const pause = root.querySelector<HTMLButtonElement>('[data-demo-action="pause"]');
  const skip = root.querySelector<HTMLButtonElement>('[data-demo-action="skip"]');
  if (!screen || !source || !lines || !cursor || !filename || !status || !shortcut || !output
    || !position || !progress || !announcement || !controls || !fallback || !replay || !pause || !skip) return;

  const sourceLines = DEMO_SOURCE.split('\n');
  const renderedLines: {
    tokens: RenderedToken[];
    offset: number;
    length: number;
    newline?: Text;
    lineNumber: HTMLSpanElement;
  }[] = [];
  const sourceFragment = document.createDocumentFragment();
  const gutterFragment = document.createDocumentFragment();
  let offset = 0;
  for (const [index, line] of sourceLines.entries()) {
    const element = document.createElement('span');
    element.className = 'demo-code-line';
    const tokens = tokenize(line).map((token): RenderedToken => {
      const node = token.className ? document.createElement('span') : document.createTextNode('');
      if (node instanceof HTMLSpanElement && token.className) node.className = token.className;
      element.append(node);
      return { element: node, token, rendered: '' };
    });
    sourceFragment.append(element);
    // Text nodes preserve the exact source when copied or inspected; CSS rows must
    // not add another line break (the line wrappers remain inline).
    const newline = index < sourceLines.length - 1 ? document.createTextNode('') : undefined;
    if (newline) sourceFragment.append(newline);
    const lineNumber = document.createElement('span');
    lineNumber.className = 'demo-line-number';
    lineNumber.textContent = String(index + 1);
    gutterFragment.append(lineNumber);
    renderedLines.push({ tokens, offset, length: line.length, newline, lineNumber });
    offset += line.length + 1;
  }
  source.replaceChildren(sourceFragment);
  lines.replaceChildren(gutterFragment);
  progress.max = DEMO_DURATION_MS;

  let state: DemoState = createDemoState();
  let sourceLength = -1;
  let started = false;
  let manualPaused = false;
  let onScreen = false;
  let frameId: number | undefined;
  let lastFrame: number | undefined;
  let lastAnnouncement = '';
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');

  function announce(message: string): void {
    if (lastAnnouncement === message) return;
    announcement!.textContent = message;
    lastAnnouncement = message;
  }

  function render(): void {
    const finished = state.phase === 'completed';
    const suspended = started && !finished && (manualPaused || document.hidden || !onScreen);
    root.dataset.state = suspended ? 'paused' : state.phase;
    root.dataset.phase = state.phase;
    root.dataset.elapsed = String(Math.round(state.elapsed));
    root.dataset.sourceLength = String(state.sourceLength);
    root.dataset.manualPaused = String(manualPaused);
    root.dataset.started = String(started);
    if (sourceLength !== state.sourceLength) {
      for (const line of renderedLines) {
        line.lineNumber.hidden = line.offset > state.sourceLength;
        let remaining = Math.max(0, Math.min(line.length, state.sourceLength - line.offset));
        for (const item of line.tokens) {
          const text = item.token.text.slice(0, remaining);
          if (text !== item.rendered) {
            item.element.textContent = text;
            item.rendered = text;
          }
          remaining = Math.max(0, remaining - item.token.text.length);
        }
        if (line.newline) {
          const text = state.sourceLength > line.offset + line.length ? '\n' : '';
          if (line.newline.data !== text) line.newline.data = text;
        }
      }
      sourceLength = state.sourceLength;
      cursor!.style.setProperty('--cursor-line', String(state.cursorLine));
      cursor!.style.setProperty('--cursor-column', String(state.cursorColumn));
      position!.textContent = `${state.cursorLine + 1}:${state.cursorColumn + 1}`;
    }
    const title = `${state.filename}${state.filename !== 'Untitled.java' ? ' *' : ''}`;
    if (filename!.textContent !== title) filename!.textContent = title;
    if (status!.textContent !== state.status) status!.textContent = state.status;
    if (shortcut!.textContent !== state.shortcut) shortcut!.textContent = state.shortcut;
    if (output!.textContent !== state.output) output!.textContent = state.output;
    progress!.value = state.elapsed;
    const pauseLabel = manualPaused ? 'Resume' : 'Pause';
    if (pause!.textContent !== pauseLabel) pause!.textContent = pauseLabel;
    pause!.disabled = finished || !started;
    pause!.setAttribute('aria-pressed', String(manualPaused));
    skip!.disabled = finished;
    cursor!.hidden = false;
    if (finished) announce('Demo complete. HelloWorld.java compiled successfully and printed Hello TurboJ!');
  }

  function canPlay(): boolean {
    return started && !manualPaused && !document.hidden && onScreen
      && !motion.matches && state.phase !== 'completed';
  }

  function freezeClock(): void {
    if (frameId !== undefined) cancelAnimationFrame(frameId);
    frameId = undefined;
    if (lastFrame !== undefined) {
      state = advanceDemoState(state, state.elapsed + Math.max(0, performance.now() - lastFrame));
    }
    lastFrame = undefined;
  }

  function frame(timestamp: number): void {
    frameId = undefined;
    if (!canPlay()) {
      lastFrame = undefined;
      render();
      return;
    }
    state = advanceDemoState(state, state.elapsed + Math.max(0, timestamp - (lastFrame ?? timestamp)));
    lastFrame = timestamp;
    render();
    if (state.elapsed >= DEMO_DURATION_MS) {
      lastFrame = undefined;
      return;
    }
    frameId = requestAnimationFrame(frame);
  }

  function synchronizeClock(): void {
    if (!started && onScreen && !document.hidden) started = true;
    if (canPlay()) {
      if (frameId === undefined) {
        lastFrame = performance.now();
        frameId = requestAnimationFrame(frame);
      }
    } else {
      freezeClock();
    }
    render();
  }

  function complete(): void {
    freezeClock();
    started = true;
    manualPaused = false;
    state = advanceDemoState(state, DEMO_DURATION_MS);
    render();
  }

  replay.addEventListener('click', () => {
    freezeClock();
    state = createDemoState();
    started = true;
    manualPaused = false;
    lastAnnouncement = '';
    if (motion.matches) {
      complete();
    } else {
      announce('Demo restarted. Editing, compiling, then running HelloWorld.java.');
      synchronizeClock();
    }
  });
  pause.addEventListener('click', () => {
    if (!started || state.phase === 'completed') return;
    freezeClock();
    // A click can arrive exactly at the final event; keep the final state stable.
    if (state.elapsed >= DEMO_DURATION_MS) {
      manualPaused = false;
      render();
      return;
    }
    manualPaused = !manualPaused;
    announce(manualPaused ? 'Demo paused.' : 'Demo resumed.');
    synchronizeClock();
  });
  skip.addEventListener('click', complete);
  document.addEventListener('visibilitychange', synchronizeClock);
  motion.addEventListener('change', () => {
    if (motion.matches) complete();
  });

  root.dataset.demoInitialized = 'true';
  screen.hidden = false;
  controls.hidden = false;
  progress.hidden = false;
  fallback.hidden = true;
  render();

  if (motion.matches) complete();
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver((entries) => {
      const entry = entries[entries.length - 1];
      if (!entry) return;
      onScreen = entry.isIntersecting && entry.intersectionRatio >= 0.15;
      if (onScreen && !started && !document.hidden) started = true;
      synchronizeClock();
    }, { threshold: [0, 0.15] });
    observer.observe(screen);
  } else {
    onScreen = true;
    started = true;
    synchronizeClock();
  }
}

export function initializeTurboJDemos(): void {
  document.querySelectorAll<HTMLElement>('[data-turboj-demo]').forEach(initializeDemo);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initializeTurboJDemos, { once: true });
} else {
  initializeTurboJDemos();
}
