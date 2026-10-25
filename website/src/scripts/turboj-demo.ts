import { seekChapter, STORY_LOOP_HOLD_MS, validateChapter, validateManifest, type DemoChapter, type DemoEvent, type DemoManifest } from './demo-timeline';

const root = document.querySelector<HTMLElement>('[data-turboj-demo]');
if (root) initialize(root);

function initialize(root: HTMLElement) {
  const get = <T extends HTMLElement = HTMLElement>(part: string) => root.querySelector<T>(`[data-demo-${part}]`)!;
  const screen = get('screen');
  const loadingSurface = get('loading');
  const external = get('external');
  const overlay = get('overlay');
  const stage = root.querySelector<HTMLElement>('.demo-stage')!;
  const source = get('source');
  const output = get('output');
  const commandLine = get('console-command');
  const commandText = get('console-command-text');
  const cursor = get('cursor');
  const viewport = root.querySelector<HTMLElement>('.demo-code-viewport')!;
  const progress = get<HTMLProgressElement>('progress');
  const checkpoints = get<HTMLSelectElement>('checkpoint');
  const pause = root.querySelector<HTMLButtonElement>('[data-demo-action="pause"]')!;
  const prompt = get('prompt');
  const promptText = get('prompt-text');
  const promptCursor = get('prompt-cursor');
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
  let promptPhrase = '';
  let promptStarted = 0;
  let promptCharacters = 0;
  let focusRevision = 0;
  document.addEventListener('focusin', (event) => {
    if (event.target !== pause && event.target !== document.body) focusRevision++;
  });
  document.addEventListener('pointerdown', (event) => {
    if (event.target !== pause) focusRevision++;
  });

  function loopsStory() { return fullStory && selected === manifest?.chapters.length - 1 && !motion.matches; }
  function playbackDuration() { return chapter ? chapter.durationMs + (loopsStory() ? STORY_LOOP_HOLD_MS : 0) : 0; }
  function canPlay() {
    return !!chapter && !loading && !manualPaused && onScreen && !document.hidden && elapsed < playbackDuration();
  }
  function clockState() {
    root.dataset.state = loading ? 'loading' : !chapter ? 'error' : elapsed >= playbackDuration() ? 'completed' : canPlay() ? 'playing' : 'paused';
    root.dataset.loopHold = String(loopsStory() && !!chapter && elapsed >= chapter.durationMs);
    root.dataset.manualPaused = String(manualPaused);
    root.dataset.started = String(autoStarted);
    pause.textContent = canPlay() ? 'Pause' : 'Play';
    const label = canPlay() ? 'Pause the recorded story' : selected === 4 && chapter && elapsed >= playbackDuration() ? 'Replay the recorded five-chapter story' : 'Play or continue the recorded five-chapter story';
    pause.setAttribute('aria-label', label);
    pause.title = label;
    for (const button of buttons) {
      if (button.dataset.demoChapter !== undefined) continue;
      button.disabled = loading || !chapter
        || (button.dataset.demoAction === 'previous' && selected === 0)
        || (button.dataset.demoAction === 'next' && selected === manifest?.chapters.length - 1);
    }
    checkpoints.disabled = loading;
  }
  // These editorial captions describe captured phases. They never alter source bytes or event times.
  // Group typing/selection checkpoints so a sentence can finish on the existing active playback clock.
  function describe(event: DemoEvent) {
    const id = event.id;
    if (selected === 0) {
      if (id === 'hello-run-result') return 'Hello TurboJ! Play to continue.';
      if (id.startsWith('hello-run')) return 'Running HelloWorld.java.';
      if (id.startsWith('hello-compile')) return 'Compiling HelloWorld.java.';
      if (id === 'hello-save-dialog' || id === 'hello-source') return 'Saving HelloWorld.java.';
      return 'Browser replay. No Java runs here.';
    }
    if (selected === 1) {
      if (id === 'tests-before-refactor-result' || id === 'tests-output') return 'Both JUnit tests pass.';
      if (id.startsWith('tests-before')) return 'Running two JUnit tests.';
      return id.startsWith('price') ? 'Writing a price calculator.' : 'Adding two JUnit tests.';
    }
    if (selected === 2) {
      if (id === 'tests-after-refactor-result' || id === 'refactoring-preserved') return 'Both tests still pass after refactoring.';
      if (id.startsWith('tests-after')) return 'Rerunning tests after refactoring.';
      return id.startsWith('extract') ? 'Extracting the applyDiscount method.' : 'Renaming discount to discountedPrice.';
    }
    if (selected === 3) {
      if (id === 'autotest-fails-result') return 'One test fails after saving.';
      if (id === 'autotest-fails-start') return 'Saving starts automatic tests.';
      if (id === 'autotest-passes-result') return 'Both tests pass after saving.';
      if (id === 'autotest-fixed' || id === 'autotest-passes-start') return 'Saving the corrected calculation.';
      if (id === 'autorun-saved-result') return 'Updated program output received.';
      if (id === 'autorun-saved-start') return 'Saving starts the updated program.';
      return id.startsWith('autotest') ? 'Auto Test waits for a successful save.' : 'Auto Run waits for a successful save.';
    }
    if (id === 'spring-stopped') return loopsStory() ? 'Recorded server stopped. The story repeats.' : 'Session complete. The server stopped.';
    if (id === 'curl-result' || id === 'curl-visible-result') return event.external ? 'curl returned Hello from TurboJ!' : 'curl returned HTTP 200 and JSON in Output.';
    if (id === 'curl-commandline-open') return 'Esc, then : opens the Output command line.';
    if (id.startsWith('curl')) return event.external ? 'Running external curl.' : 'Running :! curl from TurboJ Output.';
    if (id === 'spring-startup-result') return 'Spring Boot is ready for HTTP.';
    if (id === 'maven-import-result') return 'Cached Maven dependencies resolved.';
    if (id.startsWith('maven-import')) return 'Importing the Maven project.';
    if (id === 'spring-source') return 'Opening the Spring Boot application.';
    return 'Starting Spring with a Maven goal.';
  }
  function renderPrompt(event: DemoEvent) {
    const phrase = describe(event);
    if (phrase !== promptPhrase) {
      promptPhrase = phrase;
      promptStarted = elapsed;
      promptCharacters = 0;
      prompt.setAttribute('aria-label', phrase);
      prompt.title = phrase;
      if (announced !== phrase) {
        get('announcement').textContent = phrase;
        announced = phrase;
      }
    }
    // Pausing makes the entire sentence readable. Resuming never removes already visible letters.
    promptCharacters = Math.max(promptCharacters, !canPlay() || motion.matches ? phrase.length : Math.floor((elapsed - promptStarted) / 24) + 1);
    const visible = phrase.slice(0, promptCharacters);
    if (promptText.textContent !== visible) promptText.textContent = visible;
    promptCursor.style.opacity = !canPlay() || motion.matches || Math.floor(elapsed / 500) % 2 === 0 ? '1' : '0';
  }
  function showMessage(message: string) {
    promptText.textContent = message;
    prompt.setAttribute('aria-label', message);
    prompt.title = message;
    promptCursor.style.opacity = '1';
    get('announcement').textContent = message;
  }
  function unavailable() {
    const message = 'Replay unavailable. Reload to retry.';
    screen.hidden = true;
    overlay.hidden = true;
    external.hidden = true;
    loadingSurface.hidden = false;
    get('loading-text').textContent = message;
    root.dataset.loading = 'false';
    stage.setAttribute('aria-busy', 'false');
    get('controls').hidden = false;
    showMessage(message);
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
      if (restoreFocus) pause.focus({ preventScroll: true });
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
    if (!chapter || loading) return;
    const current = seekChapter(chapter, elapsed);
    elapsed = Math.max(0, Math.min(playbackDuration(), elapsed));
    const event = current.event;
    if (renderedEvent !== event.id) {
      renderSource(event);
      get('filename').textContent = event.filename;
      get('status').textContent = event.status;
      if (event.outputFraction !== undefined) screen.style.setProperty('--demo-output-height', `calc((100% - var(--demo-menu-height) - var(--demo-status-height)) * ${event.outputFraction})`);
      else screen.style.removeProperty('--demo-output-height');
      get('workspace').classList.toggle('demo-has-explorer', !!event.explorer?.visible);
      get('explorer').hidden = !event.explorer?.visible;
      get('explorer-rows').textContent = event.explorer?.visible
        ? event.explorer.rows.map((row) => `${'  '.repeat(row.depth)}${row.expanded ? '−' : '+'} ${row.text}`).join('\n') : '';
      get('command').textContent = event.command ? `Recorded command: ${event.command}` : '';
      renderOverlay(event);
      get<HTMLAnchorElement>('frame').href = event.screenshot ?? manifest.detailsUrl;
      output.textContent = event.external ? '' : chapter.outputs[event.outputId];
      const line = event.commandLine;
      commandLine.hidden = event.external || !line?.visible;
      screen.classList.toggle('demo-native-command', !commandLine.hidden);
      get('console-command-hint').textContent = line?.hint ?? '';
      commandText.replaceChildren();
      if (line?.visible && !event.external) {
        commandText.append(document.createTextNode(line.text.slice(0, line.caret)));
        const caret = document.createElement('span');
        caret.className = line.focused ? 'demo-console-command-caret' : '';
        const next = line.caret + ((line.text.codePointAt(line.caret) ?? 0) > 0xffff ? 2 : 1);
        caret.textContent = line.text.slice(line.caret, next);
        commandText.append(caret, document.createTextNode(line.text.slice(next)));
      }
      get('external-output').textContent = event.external ? chapter.outputs[event.outputId] : '';
      external.hidden = !event.external;
      screen.hidden = event.external;
      // The recorded command row changes the remaining output height. Restore scroll
      // only after every surface is visible and its captured geometry is in place.
      viewport.scrollTop = Math.max(0, event.topLine - 1) * parseFloat(getComputedStyle(source).lineHeight);
      output.scrollTop = output.scrollHeight;
      commandText.scrollLeft = commandText.scrollWidth;
      get('operation').textContent = `${chapter.title} · ${event.label}${event.dialogLabel ? ` · ${event.dialogLabel}` : ''}`;
      root.dataset.event = event.id;
      root.dataset.phase = event.kind;
      renderedEvent = event.id;
      const checkpoint = [...chapter.checkpoints].reverse().find((item) => item.atMs <= elapsed);
      if (checkpoint) checkpoints.value = checkpoint.id;
      updateTime();
    }
    root.dataset.elapsed = String(Math.round(elapsed * 100) / 100);
    progress.value = current.elapsedMs / chapter.durationMs;
    clockState();
    renderPrompt(event);
  }
  function freeze() {
    if (frameId) cancelAnimationFrame(frameId);
    frameId = 0;
    if (lastFrame && chapter && !loading) elapsed = Math.min(playbackDuration(), elapsed + Math.max(0, performance.now() - lastFrame));
    lastFrame = 0;
    render();
  }
  function synchronize() {
    // Visibility suspension can consume the last fraction of an interval in freeze().
    // Advance on resume even when no further animation frame is eligible to run.
    if (chapter && !loading && !manualPaused && onScreen && !document.hidden && elapsed >= playbackDuration() && fullStory) {
      if (selected < manifest.chapters.length - 1) { void selectChapter(selected + 1, true); return; }
      if (loopsStory()) { void selectChapter(0, true); return; }
      fullStory = false;
    }
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
    if (chapter && elapsed >= playbackDuration()) {
      lastFrame = 0;
      synchronize();
    } else frameId = requestAnimationFrame(tick);
  }
  async function selectChapter(index: number, play: boolean) {
    if (!manifest || index < 0 || index >= manifest.chapters.length) return;
    freeze();
    const epoch = ++loadEpoch;
    const restorePlayFocus = document.activeElement === pause || overlay.contains(document.activeElement) || screen.contains(document.activeElement);
    const initialFocusRevision = focusRevision;
    selected = index;
    loading = true;
    manualPaused = !play;
    if (overlay.contains(document.activeElement) || screen.contains(document.activeElement)) pause.focus({ preventScroll: true });
    root.dataset.chapter = manifest.chapters[index].id;
    root.dataset.loading = 'true';
    // Keep the last themed frame while fetching the next chapter. Replacing it
    // with the immutable Classic screenshot caused flashes during Play/looping.
    loadingSurface.hidden = !!chapter;
    stage.setAttribute('aria-busy', 'true');
    const message = `Loading ${manifest.chapters[index].title}…`;
    get('loading-text').textContent = message;
    showMessage(message);
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
      elapsed = 0;
      loading = false;
      renderedEvent = '';
      renderedSource = '';
      promptPhrase = '';
      root.dataset.loading = 'false';
      get('cache').hidden = selected !== 4 || manifest.dependencyCache !== 'warm';
      checkpoints.replaceChildren(...chapter.checkpoints.map((checkpoint) => new Option(checkpoint.label, checkpoint.id)));
      get('load-status').hidden = true;
      get('controls').hidden = false;
      get('current').hidden = false;
      progress.hidden = false;
      loadingSurface.hidden = true;
      stage.setAttribute('aria-busy', 'false');
      render();
      synchronize();
      // Disabling a focused button during an async load can send focus to the body.
      // Restore it only if the user has not focused or clicked something else meanwhile.
      if (restorePlayFocus && focusRevision === initialFocusRevision && (document.activeElement === document.body || document.activeElement === pause)) pause.focus({ preventScroll: true });
    } catch (error) {
      if (epoch !== loadEpoch) return;
      chapters.delete(entry.id);
      loading = false;
      chapter = undefined;
      get('load-status').textContent = 'Recorded chapter unavailable. Select a chapter to retry, or open the full session details.';
      clockState();
      unavailable();
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
        if (!wasPlaying) {
          // A focused Play button can remain offscreen after page scrolling. Explicit Play
          // brings the recording back; ordinary offscreen suspension never changes scroll.
          const bounds = stage.getBoundingClientRect();
          const visibleHeight = Math.max(0, Math.min(innerHeight, bounds.bottom) - Math.max(0, bounds.top));
          if (visibleHeight < bounds.height * .15) stage.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
          // The sole visible Play button continues all chapters, including after the initial Hello hold.
          fullStory = true;
          if (elapsed >= chapter.durationMs && elapsed >= playbackDuration()) { void selectChapter((selected + 1) % manifest.chapters.length, true); break; }
        }
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
    if (motion.matches) { freeze(); manualPaused = true; }
    synchronize();
  });
  const observer = new IntersectionObserver(([entry]) => {
    onScreen = entry.isIntersecting && entry.intersectionRatio >= .15;
    synchronize();
  }, { threshold: [0, .15] });
  observer.observe(stage);
  stage.setAttribute('aria-busy', 'true');
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
    get('load-status').textContent = 'Recorded session unavailable. Open session details for sources and evidence.';
    clockState();
    unavailable();
  });
}
