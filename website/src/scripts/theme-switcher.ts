import { themes, themeTokenPaths } from '../data/themes.generated';
import { resolveTheme, THEME_STORAGE_KEY } from './theme-preference';
import { ThemeSpin } from './theme-spin';
import {
  PALETTE_STORAGE_KEY, PAGE_SIZE, PaletteCache, cssToken, filterCatalog, isDarkPalette,
  limitedJson, nativePalette, pageFor, validateCatalog, validatePalette,
  type CatalogEntry, type ThemePalette,
} from './theme-catalog';

type ActiveTheme = { id: string; palette: ThemePalette | null };

/** Theme state is independent of the demo's document, scheduler and playback state. */
function initializeThemeSwitcher(): void {
  const root = document.documentElement;
  const group = document.querySelector<HTMLElement>('[data-theme-switcher]');
  const dialog = document.querySelector<HTMLDialogElement>('[data-theme-browser]');
  if (!group || !dialog || group.dataset.initialized === 'true') return;
  group.dataset.initialized = 'true';
  const buttons = [...group.querySelectorAll<HTMLButtonElement>('[data-theme-choice]')];
  const options = group.querySelector<HTMLDetailsElement>('[data-theme-options]')!;
  const menu = group.querySelector<HTMLDetailsElement>('[data-theme-menu]')!;
  const optionsSummary = options.querySelector<HTMLElement>('summary')!;
  const menuSummary = menu.querySelector<HTMLElement>('summary')!;
  const more = group.querySelector<HTMLButtonElement>('[data-theme-more]')!;
  const search = dialog.querySelector<HTMLInputElement>('[data-theme-search]')!;
  const grid = dialog.querySelector<HTMLElement>('[data-theme-grid]')!;
  const viewport = dialog.querySelector<HTMLElement>('[data-theme-viewport]')!;
  const scrollContent = dialog.querySelector<HTMLElement>('[data-theme-scroll-content]')!;
  const template = dialog.querySelector<HTMLTemplateElement>('[data-theme-preview-template]')!;
  const status = dialog.querySelector<HTMLElement>('[data-theme-status]')!;
  const pageStatus = dialog.querySelector<HTMLElement>('[data-theme-page]')!;
  const applyButton = dialog.querySelector<HTMLButtonElement>('[data-theme-apply]')!;
  const cancelButton = dialog.querySelector<HTMLButtonElement>('[data-theme-cancel]')!;
  const previous = dialog.querySelector<HTMLButtonElement>('[data-theme-previous]')!;
  const next = dialog.querySelector<HTMLButtonElement>('[data-theme-next]')!;
  const random = dialog.querySelector<HTMLButtonElement>('[data-theme-random]')!;
  const spinElement = dialog.querySelector<HTMLElement>('[data-theme-spin]')!;
  const skip = dialog.querySelector<HTMLButtonElement>('[data-theme-skip]')!;
  const themeColor = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  const desktopSlot = document.querySelector<HTMLElement>('[data-desktop-theme-slot]');
  const mobileSlot = document.querySelector<HTMLElement>('[data-mobile-theme-slot]');
  const mobile = window.matchMedia('(max-width: 1000px)');
  const cache = new PaletteCache();
  let active: ActiveTheme = { id: resolveTheme(root.dataset.theme, themes), palette: null };
  let activeGeneration = 0;
  let original: ActiveTheme | null = null;
  let catalog: CatalogEntry[] | null = null;
  let filtered: CatalogEntry[] = [];
  let selected = -1;
  let page = 0;
  let browserEpoch = 0;
  let pageEpoch = 0;
  let selectionEpoch = 0;
  let manifestAbort: AbortController | null = null;
  let pageAbort: AbortController | null = null;
  let selectionAbort: AbortController | null = null;
  let paletteRequests = new Map<string, Promise<ThemePalette>>();
  let spinning = false;
  let spin: ThemeSpin;
  let renderedStart = -1, renderedEnd = -1, scrollFrame = 0;

  const setSpinning = (value: boolean): void => {
    spinning = value; search.disabled = value; random.disabled = value || filtered.length === 0;
    previous.disabled = value || page === 0;
    next.disabled = value || page >= Math.ceil(filtered.length / PAGE_SIZE) - 1;
    viewport.hidden = value;
    if (value) applyButton.disabled = true; else spinElement.hidden = true;
  };

  const storedPalette = (id: string | undefined): ThemePalette | null => {
    try {
      const raw = localStorage.getItem(PALETTE_STORAGE_KEY);
      if (!raw || raw.length > 65_536) return null;
      const value = validatePalette(JSON.parse(raw), themeTokenPaths);
      return value?.id === id ? value : null;
    } catch { return null; }
  };

  const persist = (): void => {
    try {
      if (active.palette) localStorage.setItem(PALETTE_STORAGE_KEY, JSON.stringify(active.palette));
      else localStorage.removeItem(PALETTE_STORAGE_KEY);
      localStorage.setItem(THEME_STORAGE_KEY, active.id);
    } catch { /* Storage restrictions never prevent the current-page selection. */ }
  };

  const apply = (value: ActiveTheme, save = false): void => {
    activeGeneration++;
    for (const path of themeTokenPaths) root.style.removeProperty(cssToken(path));
    active = value;
    root.dataset.theme = value.id;
    if (value.palette) for (const path of themeTokenPaths) root.style.setProperty(cssToken(path), value.palette.colors[path]);
    root.style.colorScheme = (value.palette ? isDarkPalette(value.palette) : value.id === 'dark') ? 'dark' : 'light';
    for (const button of buttons) button.setAttribute('aria-pressed', String(button.dataset.themeChoice === value.id));
    const preset = themes.find((theme) => theme.id === value.id);
    if (themeColor) themeColor.content = value.palette?.colors['editor.background'] ?? preset?.themeColor ?? themes[0].themeColor;
    if (save) persist();
  };

  const closeMenu = (): void => { options.open = false; menu.open = false; };
  const place = (): void => {
    const slot = mobile.matches ? mobileSlot : desktopSlot;
    if (slot && group.parentElement !== slot) slot.append(group);
  };

  const stopRequests = (): void => {
    spin?.cancel(); setSpinning(false);
    cancelAnimationFrame(scrollFrame); scrollFrame = 0;
    browserEpoch++; pageEpoch++; selectionEpoch++;
    manifestAbort?.abort(); pageAbort?.abort(); selectionAbort?.abort(); paletteRequests.clear();
  };

  const finish = (commit: boolean): void => {
    if (!original) return;
    if (commit && applyButton.disabled) return;
    const previousTheme = original;
    original = null;
    stopRequests();
    if (commit) persist(); else apply(previousTheme);
    dialog.close();
    optionsSummary.focus();
  };

  const loadPalette = (entry: CatalogEntry, signal: AbortSignal, sharePageRequest = true): Promise<ThemePalette> => {
    const cached = cache.get(entry.id);
    if (cached) return Promise.resolve(cached);
    const pending = sharePageRequest ? paletteRequests.get(entry.id) : undefined;
    if (pending) return pending;
    const promise = limitedJson(entry.url, signal, 65_536).then((json) => {
      const palette = nativePalette(json, entry, themeTokenPaths);
      if (!palette) throw new Error(`${entry.name} has an invalid theme definition.`);
      cache.put(palette); return palette;
    });
    if (sharePageRequest) paletteRequests.set(entry.id, promise);
    return promise;
  };

  const previewColors = (card: HTMLElement, palette: ThemePalette): void => {
    const fields: Record<string, string> = {
      'menu-background': 'menu.background', 'menu-foreground': 'menu.foreground',
      'editor-background': 'editor.background', 'editor-foreground': 'editor.foreground',
      'console-background': 'messages.background', 'console-foreground': 'messages.foreground',
      keyword: 'syntax.keyword', string: 'syntax.string', cursor: 'editor.cursor', border: 'chrome.border',
    };
    for (const [name, path] of Object.entries(fields)) card.style.setProperty('--preview-' + name, palette.colors[path]);
    card.dataset.loaded = 'true';
  };

  const previewSelected = (focus: boolean): void => {
    const entry = filtered[selected];
    const request = ++selectionEpoch;
    selectionAbort?.abort(); selectionAbort = new AbortController();
    applyButton.disabled = true;
    for (const card of grid.querySelectorAll<HTMLButtonElement>('[data-theme-preview]')) {
      const isSelected = card.dataset.themePreview === entry?.id;
      card.setAttribute('aria-selected', String(isSelected)); card.tabIndex = isSelected ? 0 : -1;
      if (isSelected && focus) card.focus();
    }
    if (!entry) return;
    const present = (palette: ThemePalette): void => {
      if (!dialog.open || request !== selectionEpoch) return;
      const card = [...grid.querySelectorAll<HTMLElement>('[data-theme-preview]')].find((item) => item.dataset.themePreview === palette.id);
      if (card) previewColors(card, palette);
      apply({ id: palette.id, palette });
      status.textContent = `${palette.name} · Live preview`;
      applyButton.disabled = false;
    };
    const cached = cache.get(entry.id);
    if (cached) { present(cached); return; }
    status.textContent = `Loading ${entry.name}…`;
    void loadPalette(entry, selectionAbort.signal, false).then(present).catch((error: unknown) => {
      if (!dialog.open || request !== selectionEpoch) return;
      status.textContent = error instanceof Error ? error.message : 'Theme preview is unavailable.';
    });
  };

  const rowMetrics = (): { height: number; stride: number } => {
    const style = getComputedStyle(viewport);
    const height = parseFloat(style.getPropertyValue('--theme-row-height'));
    return { height, stride: height + parseFloat(style.getPropertyValue('--theme-row-gap')) };
  };

  const renderVisible = (focus: boolean, preview = false): void => {
    const { stride } = rowMetrics();
    const firstRow = Math.max(0, Math.floor(viewport.scrollTop / stride));
    const lastRow = Math.ceil((viewport.scrollTop + viewport.clientHeight) / stride);
    const start = Math.min(filtered.length, firstRow * 3), end = Math.min(filtered.length, lastRow * 3);
    page = Math.floor(firstRow / 2);
    previous.disabled = spinning || viewport.scrollTop <= 0;
    next.disabled = spinning || viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 1;
    random.disabled = !filtered.length || spinning;
    pageStatus.textContent = filtered.length ? `${start + 1}–${end} / ${filtered.length} themes` : 'No matching themes';
    if (renderedStart === start && renderedEnd === end) {
      if (preview) previewSelected(focus);
      return;
    }
    const ownedFocus = grid.contains(document.activeElement);
    renderedStart = start; renderedEnd = end;
    pageAbort?.abort(); pageAbort = new AbortController(); paletteRequests = new Map();
    const signal = pageAbort.signal;
    const request = ++pageEpoch;
    grid.replaceChildren();
    grid.style.top = `${firstRow * stride}px`;
    const entries = filtered.slice(start, end);
    for (let offset = 0; offset < entries.length; offset++) {
      const entry = entries[offset];
      const card = template.content.firstElementChild!.cloneNode(true) as HTMLButtonElement;
      card.dataset.themePreview = entry.id;
      card.setAttribute('aria-label', entry.name);
      card.setAttribute('aria-setsize', String(filtered.length));
      card.setAttribute('aria-posinset', String(start + offset + 1));
      card.setAttribute('aria-selected', String(start + offset === selected));
      card.tabIndex = start + offset === selected ? 0 : -1;
      card.querySelector<HTMLElement>('[data-theme-preview-name]')!.textContent = entry.name;
      card.addEventListener('click', () => { selected = filtered.findIndex((item) => item.id === entry.id); previewSelected(true); });
      grid.append(card);
      const cached = cache.get(entry.id);
      if (cached) { previewColors(card, cached); continue; }
      if (preview && start + offset === selected) continue; // Its independent selection request also paints this thumbnail.
      void loadPalette(entry, signal).then((palette) => {
        if (dialog.open && request === pageEpoch) previewColors(card, palette);
      }).catch(() => {
        if (dialog.open && request === pageEpoch) card.querySelector<HTMLElement>('[data-theme-preview-loading]')!.textContent = 'Preview unavailable';
      });
    }
    if (!entries.length) {
      selectionAbort?.abort(); selectionEpoch++;
      selected = -1; applyButton.disabled = true;
      status.textContent = 'Try another theme name.'; return;
    }
    if (preview) previewSelected(focus);
    else if (ownedFocus) viewport.focus({ preventScroll: true });
  };

  const renderPage = (focus: boolean, reset = false): void => {
    const { height, stride } = rowMetrics();
    scrollContent.style.height = `${Math.max(viewport.clientHeight, Math.ceil(filtered.length / 3) * stride - (stride - height))}px`;
    if (reset) { renderedStart = renderedEnd = -1; }
    const top = Math.floor(Math.max(0, selected) / 3) * stride;
    if (top < viewport.scrollTop) viewport.scrollTop = top;
    else if (top + height > viewport.scrollTop + viewport.clientHeight) viewport.scrollTop = top + height - viewport.clientHeight;
    renderVisible(focus, true);
  };

  const move = (delta: number): void => {
    if (!filtered.length || spinning) return;
    selected = Math.max(0, Math.min(filtered.length - 1, selected + delta));
    renderPage(true);
  };

  spin = new ThemeSpin(spinElement, async (entry, signal) => {
    const cached = cache.get(entry.id);
    if (cached) return cached;
    const json = await limitedJson(entry.url, signal, 65_536);
    const palette = nativePalette(json, entry, themeTokenPaths);
    if (!palette) throw new Error(`${entry.name} has an invalid theme definition.`);
    cache.put(palette); return palette;
  }, (entry, palette) => {
    if (dialog.open && spinning) apply({ id: entry.id, palette });
  }, (entry) => {
    if (!dialog.open || !spinning) return;
    setSpinning(false);
    selected = filtered.findIndex((item) => item.id === entry.id);
    page = pageFor(selected); renderPage(true);
  }, (message) => {
    if (!dialog.open) return;
    setSpinning(false); renderPage(false);
    status.textContent = message;
  });

  const openBrowser = async (): Promise<void> => {
    if (dialog.open) return;
    original = { ...active };
    closeMenu();
    search.value = ''; filtered = []; selected = -1; page = 0;
    renderedStart = renderedEnd = -1; viewport.scrollTop = 0;
    spin.cancel(); setSpinning(false);
    grid.replaceChildren(); applyButton.disabled = true; previous.disabled = next.disabled = true;
    pageStatus.textContent = 'Loading themes…'; status.textContent = '';
    dialog.showModal(); search.focus();
    manifestAbort = new AbortController(); const request = ++browserEpoch;
    try {
      catalog ??= validateCatalog(await limitedJson('/themes/catalog.json', manifestAbort.signal, 4_194_304), location.origin);
      if (!dialog.open || request !== browserEpoch) return;
      filtered = filterCatalog(catalog, search.value);
      selected = Math.max(0, filtered.findIndex((entry) => entry.id === original?.id));
      page = pageFor(selected); renderPage(false, true);
    } catch (error: unknown) {
      if (!dialog.open || request !== browserEpoch) return;
      pageStatus.textContent = 'Catalog unavailable';
      status.textContent = error instanceof Error ? `${error.message} Close and reopen to retry.` : 'Close and reopen to retry.';
    }
  };

  for (const button of buttons) button.addEventListener('click', () => {
    apply({ id: resolveTheme(button.dataset.themeChoice, themes), palette: null }, true);
    closeMenu(); optionsSummary.focus();
  });
  more.addEventListener('click', () => { void openBrowser(); });
  random.addEventListener('click', () => {
    if (spinning || !filtered.length) return;
    pageEpoch++; selectionEpoch++; pageAbort?.abort(); selectionAbort?.abort(); paletteRequests.clear();
    setSpinning(true);
    status.textContent = `Choosing from all ${filtered.length} matching themes. Skip to the result or Cancel to restore your theme.`;
    void spin.start(filtered);
    skip.focus();
  });
  skip.addEventListener('click', () => spin.skip());
  search.addEventListener('input', () => {
    filtered = filterCatalog(catalog ?? [], search.value); selected = filtered.length ? 0 : -1; page = 0; viewport.scrollTop = 0; renderPage(false, true);
  });
  viewport.addEventListener('scroll', () => {
    if (!dialog.open || spinning || scrollFrame) return;
    scrollFrame = requestAnimationFrame(() => { scrollFrame = 0; if (dialog.open && !spinning) renderVisible(false); });
  });
  previous.addEventListener('click', () => viewport.scrollBy({ top: -2 * rowMetrics().stride, behavior: 'instant' }));
  next.addEventListener('click', () => viewport.scrollBy({ top: 2 * rowMetrics().stride, behavior: 'instant' }));
  window.addEventListener('resize', () => { if (dialog.open && !spinning) renderPage(false, true); });
  applyButton.addEventListener('click', () => finish(true));
  cancelButton.addEventListener('click', () => finish(false));
  dialog.addEventListener('cancel', (event) => { event.preventDefault(); finish(false); });
  dialog.addEventListener('close', () => { if (original) { const saved = original; original = null; stopRequests(); apply(saved); } });
  dialog.addEventListener('keydown', (event) => {
    // Search inputs consume Escape to clear their value in some browsers. Cancellation
    // must win so every focused control restores the previously selected theme.
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); finish(false); return; }
    const input = event.target === search;
    if (!input && event.target !== viewport && !(event.target instanceof Node && grid.contains(event.target))) return;
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -3, ArrowDown: 3, PageUp: -PAGE_SIZE, PageDown: PAGE_SIZE };
    if (event.key === 'Enter') { event.preventDefault(); finish(true); }
    else if (event.key in moves && !(input && ['ArrowLeft', 'ArrowRight'].includes(event.key))) {
      event.preventDefault(); move(moves[event.key]);
    }
  });
  group.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation();
      if (menu.open) { menu.open = false; menuSummary.focus(); } else { closeMenu(); optionsSummary.focus(); }
    } else if (['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft'].includes(event.key)) {
      event.preventDefault();
      if (event.key === 'ArrowLeft') { menu.open = false; menuSummary.focus(); return; }
      if (event.target === optionsSummary) { options.open = true; menuSummary.focus(); return; }
      if (event.target === menuSummary) { menu.open = true; (buttons.find((button) => button.getAttribute('aria-pressed') === 'true') ?? buttons[0]).focus(); return; }
      const items = [...buttons, more]; const index = items.indexOf(document.activeElement as HTMLButtonElement);
      if (index >= 0) items[(index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length].focus();
    }
  });
  document.addEventListener('pointerdown', (event) => { if (event.target instanceof Node && !group.contains(event.target)) closeMenu(); });
  mobile.addEventListener('change', place);
  window.addEventListener('storage', (event) => {
    if (event.key !== THEME_STORAGE_KEY && event.key !== null) return;
    if (dialog.open) finish(false);
    const id = event.newValue ?? 'classic'; const palette = storedPalette(id);
    apply({ id: palette?.id ?? resolveTheme(id, themes), palette });
  });
  place();
  const cached = storedPalette(root.dataset.theme);
  if (cached && !themes.some((theme) => theme.id === cached.id)) {
    cache.put(cached); apply({ id: cached.id, palette: cached });
    // Confirm a cached custom choice against the current local catalog without flashing its palette.
    const startup = new AbortController();
    const generation = activeGeneration;
    const stillCurrent = (): boolean => activeGeneration === generation && !dialog.open && active.id === cached.id;
    void limitedJson('/themes/catalog.json', startup.signal, 4_194_304).then(async (json) => {
      const currentCatalog = validateCatalog(json, location.origin);
      if (!stillCurrent()) return;
      catalog ??= currentCatalog;
      const entry = currentCatalog.find((item) => item.id === cached.id);
      if (!entry) { apply({ id: 'classic', palette: null }, true); return; }
      const value = await limitedJson(entry.url, startup.signal, 65_536);
      const refreshed = nativePalette(value, entry, themeTokenPaths);
      if (refreshed && stillCurrent()) {
        cache.put(refreshed); apply({ id: refreshed.id, palette: refreshed }, true);
      }
    }).catch(() => { /* A valid cached choice remains usable offline. */ });
  } else apply({ id: resolveTheme(root.dataset.theme, themes), palette: null });
}

initializeThemeSwitcher();
