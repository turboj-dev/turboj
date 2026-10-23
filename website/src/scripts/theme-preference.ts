/** Only metadata is shared with the controller; palette values come from native theme exports. */
export type ThemeChoice = Readonly<{ id: string; name: string; label: string; themeColor: string }>;

export const THEME_STORAGE_KEY = 'turboj.theme';
export const DEFAULT_THEME = 'classic';

export function resolveTheme(value: unknown, themes: readonly ThemeChoice[]): string {
  return typeof value === 'string' && themes.some((theme) => theme.id === value)
    ? value : DEFAULT_THEME;
}

/** Runs synchronously in <head>, before CSS can paint a saved preference incorrectly. */
export function themeBootstrap(themes: readonly ThemeChoice[], paths: readonly string[] = []): string {
  const choices = JSON.stringify(themes.map(({ id, themeColor }) => ({ id, themeColor })))
    .replace(/</g, '\\u003c');
  const keys = JSON.stringify(paths).replace(/</g, '\\u003c');
  return `(()=>{const themes=${choices},paths=${keys},root=document.documentElement;let id=${JSON.stringify(DEFAULT_THEME)},palette=null;try{const saved=localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});if(themes.some(theme=>theme.id===saved))id=saved;else{const raw=localStorage.getItem('turboj.theme.palette');if(raw&&raw.length<=65536&&paths.length){const p=JSON.parse(raw);if(p&&p.id===saved&&/^[a-z][a-z0-9_-]{0,63}$/.test(p.id)&&typeof p.name==='string'&&p.name.trim().length>0&&p.name.length<=120&&p.colors&&typeof p.colors==='object'&&!Array.isArray(p.colors)&&paths.every(path=>typeof p.colors[path]==='string'&&/^#[0-9a-f]{6}$/i.test(p.colors[path]))){id=p.id;palette=p}}}}catch{}root.dataset.theme=id;let dark=id==='dark';if(palette){for(const path of paths)root.style.setProperty('--tj-'+path.replace('.','-').replace(/[A-Z]/g,c=>'-'+c.toLowerCase()),palette.colors[path]);const color=palette.colors['ui.background'];const rgb=[1,3,5].map(i=>{let n=parseInt(color.slice(i,i+2),16)/255;return n<=.04045?n/12.92:((n+.055)/1.055)**2.4});dark=rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722<.179}root.style.colorScheme=dark?'dark':'light';const meta=document.querySelector('meta[name="theme-color"]');if(meta)meta.content=palette?palette.colors['editor.background']:themes.find(theme=>theme.id===id).themeColor})();`;
}
