import { seekChapter, validateChapter, validateManifest, type DemoChapter, type DemoEvent, type DemoManifest } from './demo-timeline';

const root = document.querySelector<HTMLElement>('[data-turboj-demo]');
if (root) initialize(root);

function initialize(root: HTMLElement) {
  const get = <T extends HTMLElement = HTMLElement>(part: string) => root.querySelector<T>(`[data-demo-${part}]`)!;
  const screen = get('screen');
  const fallback = root.querySelector<HTMLElement>('.demo-fallback')!;
  const external = get('external');
  const overlay = get('overlay');
  const source = get('source');
  const output = get('output');
  const cursor = get('cursor');
  const viewport = root.querySelector<HTMLElement>('.demo-code-viewport')!;
  const progress = get<HTMLProgressElement>('progress');
  const checkpoints = get<HTMLSelectElement>('checkpoint');
  const pause = root.querySelector<HTMLButtonElement>('[data-demo-action="pause"]')!;
  const buttons = [...root.querySelectorAll<HTMLButtonElement>('button')];
  const chapterButtons = [...root.querySelectorAll<HTMLButtonElement>('[data-demo-chapter]')];
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const chapters = new Map<string, Promise<DemoChapter>>();
  type Token = { start: number; text: string; kind: string };
  const tokens = new Map<string, Token[]>();
  let manifest: DemoManifest;
  let chapter: DemoChapter | undefined;
  let selected = 0;
  let elapsed = 0;
  let frameId = 0;
  let lastFrame = 0;
  let loadEpoch = 0;
  let loading = true;
  let onScreen = false;
  let manualPaused = false;
  let fullStory = false;
  let autoStarted = false;
  let renderedEvent = '';
  let renderedSource = '';
  let announced = '';

  function canPlay() {
    return !!chapter && !loading && !manualPaused && !motion.matches && onScreen && !document.hidden && elapsed < chapter.durationMs;
  }
  function clockState() {
    root.dataset.state = loading ? 'loading' : !chapter ? 'error' : elapsed >= chapter.durationMs ? 'completed' : canPlay() ? 'playing' : 'paused';
    root.dataset.manualPaused = String(manualPaused);
    root.dataset.started = String(autoStarted);
    pause.textContent = canPlay() ? 'Pause' : elapsed > 0 && elapsed < (chapter?.durationMs ?? 0) ? 'Resume' : 'Play';
    for (const button of buttons) {
      if (button.dataset.demoChapter !== undefined) continue;
      button.disabled = loading || !chapter || (motion.matches && ['pause', 'story'].includes(button.dataset.demoAction!))
        || (button.dataset.demoAction === 'previous' && selected === 0)
        || (button.dataset.demoAction === 'next' && selected === manifest?.chapters.length - 1);
    }
    checkpoints.disabled = loading;
  }
  function updateTime() {
    if (!chapter) return;
    const operation = [...chapter.operations].reverse().find((item) => item.startMs <= elapsed);
    get('time').textContent = operation ? elapsed < operation.endMs
      ? `${operation.label} — recorded operation in progress`
      : `${operation.label} · ${operation.durationMs.toLocaleString('en', { maximumFractionDigits: 2 })} ms in this session` : '';
  }
  function renderSource(event: DemoEvent) {
    if (!chapter) return;
    const snapshot = chapter.sources[event.sourceId];
    const identity = `${event.sourceId}:${event.selectionStart}:${event.selectionEnd}`;
    if (renderedSource !== identity) {
      let pieces = tokens.get(event.sourceId);
      if (!pieces) {
        pieces = [];
        const expression = /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:public|private|protected|class|interface|enum|record|static|final|void|int|long|double|boolean|new|return|if|else|for|while|try|catch|throw|throws|import|package|extends|implements|this|super|null|true|false)\b/g;
        let offset = 0;
        if (snapshot.file.endsWith('.java')) {
          for (const match of snapshot.text.matchAll(expression)) {
            if (pieces.length >= 90) break;
            if (match.index > offset) pieces.push({ start: offset, text: snapshot.text.slice(offset, match.index), kind: '' });
            const text = match[0];
            pieces.push({ start: match.index, text, kind: text.startsWith('/') ? 'comment' : /^["']/.test(text) ? 'string' : 'keyword' });
            offset = match.index + text.length;
          }
        }
        pieces.push({ start: offset, text: snapshot.text.slice(offset), kind: '' });
        tokens.set(event.sourceId, pieces);
      }
      const fragment = document.createDocumentFragment();
      const selectionStart = Math.min(event.selectionStart, event.selectionEnd);
      const selectionEnd = Math.max(event.selectionStart, event.selectionEnd);
      for (const token of pieces) {
        const start = Math.max(0, Math.min(token.text.length, selectionStart - token.start));
        const end = Math.max(start, Math.min(token.text.length, selectionEnd - token.start));
        const node = token.kind ? document.createElement('span') : document.createDocumentFragment();
        if (node instanceof HTMLElement) node.className = `demo-token-${token.kind}`;
        node.append(document.createTextNode(token.text.slice(0, start)));
        if (end > start) {
          const selection = document.createElement('mark');
          selection.textContent = token.text.slice(start, end);
          node.append(selection);
        }
        node.append(document.createTextNode(token.text.slice(end)));
        fragment.append(node);
      }
      source.replaceChildren(fragment);
      const count = snapshot.text.split('\n').length;
      get('lines').textContent = Array.from({ length: count }, (_, index) => String(index + 1)).join('\n');
      renderedSource = identity;
    }
    const beforeCaret = snapshot.text.slice(0, Math.max(0, event.cursorOffset));
    const lines = beforeCaret.split('\n');
    cursor.style.setProperty('--cursor-line', String(lines.length - 1));
    cursor.style.setProperty('--cursor-column', String([...lines.at(-1)!].length));
    cursor.hidden = event.selectionStart !== event.selectionEnd;
    get('position').textContent = `${lines.length}:${[...lines.at(-1)!].length + 1}`;
    root.dataset.sourceLength = String(snapshot.text.length);
    viewport.scrollTop = Math.max(0, event.topLine - 1) * parseFloat(getComputedStyle(source).lineHeight);
  }
  function renderOverlay(event: DemoEvent) {
    const restoreFocus = overlay.contains(document.activeElement);
    const sourceOwnedFocus = screen.contains(document.activeElement);
    const dialogs = event.dialogId ? chapter?.dialogs?.[event.dialogId] : undefined;
    const menu = event.menuItems?.length ? event.menuItems : undefined;
    overlay.replaceChildren();
    overlay.hidden = event.external || (!dialogs?.length && !menu);
    screen.inert = !overlay.hidden;
    const note = get('surface-note');
    note.hidden = overlay.hidden;
    note.textContent = dialogs?.length ? 'Recorded native dialog — read-only reconstruction. Scroll to inspect the captured text.' : 'Recorded native menu — read-only reconstruction.';
    if (overlay.hidden) {
      if (restoreFocus) checkpoints.focus({ preventScroll: true });
      return;
    }
    function textElement(tag: string, text: string, className = '') {
      const element = document.createElement(tag);
      element.textContent = text;
      element.className = className;
      return element;
    }
    if (dialogs?.length) {
      overlay.className = 'demo-recorded-overlay';
      overlay.removeAttribute('role');
      overlay.removeAttribute('aria-label');
      overlay.removeAttribute('tabindex');
      for (const dialog of dialogs) {
        const panel = document.createElement('section');
        panel.className = 'demo-recorded-dialog';
        panel.setAttribute('aria-label', `Recorded native dialog: ${dialog.title}`);
        panel.setAttribute('role', 'region');
        panel.tabIndex = 0;
        panel.append(textElement('h3', dialog.title));
        const content = document.createElement('div');
        content.className = 'demo-recorded-components';
        const actions = document.createElement('div');
        actions.className = 'demo-recorded-actions';
        const previews = dialog.components.filter((item) => item.type === 'text' && item.text.includes('public class '));
        const isPreview = dialog.title.startsWith('RefactorJ') && previews.length === 2;
        panel.classList.toggle('demo-recorded-preview', isPreview);
        const pair = document.createElement('div');
        pair.className = 'demo-recorded-diff';
        for (const item of dialog.components) {
          // Native scrollbar/window decorations become browser scrolling, not inert fake controls.
          if (item.type === 'button' && /^[v^<>x]$/.test(item.text)) continue;
          const element = textElement(item.type === 'button' || item.type === 'label' ? 'span' : 'pre', item.type === 'button' ? `[${item.text}]` : item.text, `demo-recorded-${item.type}`);
          element.dataset.recordedType = item.type;
          if (item.enabled === false) element.classList.add('demo-recorded-disabled');
          if (element.tagName === 'PRE') {
            element.tabIndex = 0;
            element.setAttribute('role', 'region');
            element.setAttribute('aria-label', isPreview && previews.includes(item) ? `Recorded ${previews.indexOf(item) === 0 ? 'before' : 'after'} source` : `Recorded ${item.type === 'select' ? 'selected file or option' : item.type === 'input' ? 'input value' : 'text'}`);
          }
          if (item.type === 'button') actions.append(element);
          else if (isPreview && previews.includes(item)) {
            pair.append(element);
            if (!pair.isConnected && !content.contains(pair)) content.append(pair);
          } else content.append(element);
        }
        panel.append(content, actions);
        overlay.append(panel);
      }
    } else if (menu) {
      overlay.className = 'demo-recorded-overlay demo-recorded-menu';
      overlay.setAttribute('role', 'region');
      overlay.setAttribute('aria-label', 'Recorded native menu');
      overlay.tabIndex = 0;
      for (const item of menu) {
        if (item.separator) { overlay.append(document.createElement('hr')); continue; }
        const checked = item.selected || ['Auto Run on Save', 'Auto Test on Save', 'Show Pressed Shortcuts'].includes(item.text ?? '');
        const row = textElement('div', `${checked ? item.selected ? '[x] ' : '[ ] ' : '    '}${item.text ?? ''}`, 'demo-recorded-menu-row');
        row.dataset.recordedSelected = String(!!item.selected);
        if (item.enabled === false) row.classList.add('demo-recorded-disabled');
        if (item.accelerator) row.append(textElement('span', item.accelerator.replace('pressed ', '').replace(/meta /g, 'Cmd+').replace(/ctrl /g, 'Ctrl+').replace(/shift /g, 'Shift+').replace(/alt /g, 'Alt+').replace('EQUALS', '=').replace('MINUS', '-')));
        overlay.append(row);
      }
    }
    if (restoreFocus || sourceOwnedFocus) (overlay.querySelector<HTMLElement>('[tabindex]') ?? overlay).focus({ preventScroll: true });
  }
  function render() {
    if (!chapter) return;
    const current = seekChapter(chapter, elapsed);
    elapsed = current.elapsedMs;
    const event = current.event;
    if (renderedEvent !== event.id) {
      renderSource(event);
      get('filename').textContent = event.filename;
      get('status').textContent = event.status;
      get('workspace').classList.toggle('demo-has-explorer', !!event.explorer?.visible);
      get('explorer').hidden = !event.explorer?.visible;
      get('explorer-rows').textContent = event.explorer?.visible
        ? event.explorer.rows.map((row) => `${'  '.repeat(row.depth)}${row.expanded ? '−' : '+'} ${row.text}`).join('\n') : '';
      get('command').textContent = event.command ? `Recorded command: ${event.command}` : '';
      renderOverlay(event);
      get<HTMLAnchorElement>('frame').href = event.screenshot ?? manifest.detailsUrl;
      output.textContent = event.external ? '' : chapter.outputs[event.outputId];
      output.scrollTop = output.scrollHeight;
      get('external-output').textContent = event.external ? chapter.outputs[event.outputId] : '';
      external.hidden = !event.external;
      screen.hidden = event.external;
      get('operation').textContent = `${chapter.title} · ${event.label}${event.dialogLabel ? ` · ${event.dialogLabel}` : ''}`;
      root.dataset.event = event.id;
      root.dataset.phase = event.kind;
      renderedEvent = event.id;
      // Source snapshots may arrive on every keystroke; only announce actions/results.
      const message = /typ|input|caret|select/i.test(event.kind) || /(?:typing-)?line-\d/.test(event.id) ? '' : `${chapter.title}. ${event.label}`;
      if (message && message !== announced) {
        get('announcement').textContent = message;
        announced = message;
      }
      const checkpoint = [...chapter.checkpoints].reverse().find((item) => item.atMs <= elapsed);
      if (checkpoint) checkpoints.value = checkpoint.id;
      updateTime();
    }
    root.dataset.elapsed = String(Math.round(elapsed * 100) / 100);
    progress.value = elapsed / chapter.durationMs;
    clockState();
  }
  function freeze() {
    if (frameId) cancelAnimationFrame(frameId);
    frameId = 0;
    if (lastFrame && chapter && !loading) elapsed = Math.min(chapter.durationMs, elapsed + Math.max(0, performance.now() - lastFrame));
    lastFrame = 0;
    render();
  }
  function synchronize() {
    if (canPlay()) {
      if (!frameId) {
        autoStarted = true;
        lastFrame = performance.now();
        frameId = requestAnimationFrame(tick);
      }
    } else freeze();
    clockState();
  }
  function tick(now: number) {
    frameId = 0;
    if (!canPlay()) { lastFrame = 0; synchronize(); return; }
    elapsed += Math.max(0, now - lastFrame);
    lastFrame = now;
    render();
    if (chapter && elapsed >= chapter.durationMs) {
      lastFrame = 0;
      if (fullStory && selected < manifest.chapters.length - 1) void selectChapter(selected + 1, true);
      else { fullStory = false; synchronize(); }
    } else frameId = requestAnimationFrame(tick);
  }
  async function selectChapter(index: number, play: boolean) {
    if (!manifest || index < 0 || index >= manifest.chapters.length) return;
    freeze();
    const epoch = ++loadEpoch;
    selected = index;
    loading = true;
    manualPaused = !play || motion.matches;
    if (overlay.contains(document.activeElement) || screen.contains(document.activeElement)) chapterButtons[index].focus({ preventScroll: true });
    root.dataset.chapter = manifest.chapters[index].id;
    root.dataset.loading = 'true';
    screen.hidden = true;
    overlay.hidden = true;
    external.hidden = true;
    fallback.hidden = false;
    get('load-status').hidden = false;
    get('load-status').textContent = `Loading ${manifest.chapters[index].title} recorded chapter…`;
    chapterButtons.forEach((button, position) => button.setAttribute('aria-pressed', String(position === index)));
    clockState();
    const entry = manifest.chapters[index];
    try {
      if (!chapters.has(entry.id)) chapters.set(entry.id, fetch(entry.url).then((response) => {
        if (!response.ok) throw new Error('The recorded chapter could not be loaded.');
        return response.json();
      }).then((value) => validateChapter(value, entry.id)));
      const loaded = await chapters.get(entry.id)!;
      if (epoch !== loadEpoch) return;
      if (loaded.title !== entry.title || loaded.durationMs !== entry.durationMs) throw new Error('Chapter does not match its manifest.');
      chapter = loaded;
      elapsed = motion.matches ? chapter.durationMs : 0;
      loading = false;
      renderedEvent = '';
      renderedSource = '';
      root.dataset.loading = 'false';
      get('cache').hidden = selected !== 4 || manifest.dependencyCache !== 'warm';
      checkpoints.replaceChildren(...chapter.checkpoints.map((checkpoint) => new Option(checkpoint.label, checkpoint.id)));
      get('load-status').hidden = true;
      get('controls').hidden = false;
      get('current').hidden = false;
      progress.hidden = false;
      fallback.hidden = true;
      render();
      synchronize();
    } catch (error) {
      if (epoch !== loadEpoch) return;
      chapters.delete(entry.id);
      loading = false;
      chapter = undefined;
      screen.hidden = true;
      external.hidden = true;
      fallback.hidden = false;
      get('load-status').textContent = 'Recorded chapter unavailable. Select a chapter to retry, or open the full session details.';
      clockState();
    }
  }
  function seek(atMs: number) {
    freeze();
    fullStory = false;
    manualPaused = true;
    elapsed = atMs;
    renderedEvent = '';
    render();
  }
  root.addEventListener('click', (click) => {
    const button = (click.target as HTMLElement).closest<HTMLButtonElement>('button');
    if (!button || button.disabled) return;
    if (button.dataset.demoChapter !== undefined) {
      fullStory = false;
      void selectChapter(Number(button.dataset.demoChapter), false);
      return;
    }
    if (!chapter) return;
    switch (button.dataset.demoAction) {
      case 'pause': {
        const wasPlaying = canPlay();
        freeze();
        manualPaused = wasPlaying;
        if (elapsed >= chapter.durationMs) { elapsed = 0; renderedEvent = ''; manualPaused = false; }
        render(); synchronize(); break;
      }
      case 'replay':
        fullStory = false;
        void selectChapter(selected, true); break;
      case 'previous': case 'next':
        fullStory = false;
        void selectChapter(selected + (button.dataset.demoAction === 'next' ? 1 : -1), false); break;
      case 'skip': seek(chapter.durationMs); break;
      case 'story': fullStory = true; void selectChapter(0, true); break;
    }
  });
  checkpoints.addEventListener('change', () => {
    const checkpoint = chapter?.checkpoints.find((item) => item.id === checkpoints.value);
    if (checkpoint) seek(checkpoint.atMs);
  });
  document.addEventListener('visibilitychange', synchronize);
  motion.addEventListener('change', () => {
    if (motion.matches && chapter) seek(chapter.durationMs);
    synchronize();
  });
  const observer = new IntersectionObserver(([entry]) => {
    onScreen = entry.isIntersecting && entry.intersectionRatio >= .15;
    synchronize();
  }, { threshold: [0, .15] });
  observer.observe(root.querySelector('.demo-stage')!);
  get('load-status').hidden = false;
  clockState();
  void fetch('/demo/hero-story/manifest.json').then((response) => {
    if (!response.ok) throw new Error('The recorded session could not be loaded.');
    return response.json();
  }).then((value) => {
    manifest = validateManifest(value);
    get('keymap').textContent = manifest.keymap;
    get('menu').textContent = manifest.menus.join('   ');
    get<HTMLAnchorElement>('details').href = manifest.detailsUrl;
    get('controls').hidden = false;
    return selectChapter(0, !motion.matches);
  }).catch(() => {
    loading = false;
    get('load-status').textContent = 'Recorded session unavailable. The image above is an earlier native screenshot. Open session details for sources and evidence.';
    clockState();
  });
}
