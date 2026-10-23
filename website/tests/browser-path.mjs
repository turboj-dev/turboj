import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { chromium } from 'playwright';

/** Locate an installed browser without downloading one or assuming a username. */
export function resolveBrowserPath() {
  if (process.env.CHROME_PATH) {
    if (!existsSync(process.env.CHROME_PATH)) throw new Error('CHROME_PATH does not point to an existing browser executable.');
    return process.env.CHROME_PATH;
  }
  const managedBrowser = chromium.executablePath();
  if (existsSync(managedBrowser)) return managedBrowser;

  const systemBrowsers = process.platform === 'darwin'
    ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/Applications/Chromium.app/Contents/MacOS/Chromium']
    : process.platform === 'win32'
      ? [join(process.env.PROGRAMFILES ?? 'C:\\Program Files', 'Google/Chrome/Application/chrome.exe')]
      : ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  const availableSystemBrowser = systemBrowsers.find(existsSync);
  if (availableSystemBrowser) return availableSystemBrowser;

  const cacheRoot = process.env.PLAYWRIGHT_BROWSERS_PATH
    ?? (process.platform === 'darwin' ? join(homedir(), 'Library/Caches/ms-playwright')
      : process.platform === 'win32' ? join(process.env.LOCALAPPDATA ?? homedir(), 'ms-playwright')
        : join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'ms-playwright'));
  if (existsSync(cacheRoot)) {
    const installs = readdirSync(cacheRoot).filter((name) => /^chromium_headless_shell-\d+$/.test(name))
      .sort((a, b) => Number(b.split('-').at(-1)) - Number(a.split('-').at(-1)));
    const relativeExecutable = process.platform === 'darwin'
      ? `chrome-headless-shell-mac-${process.arch === 'arm64' ? 'arm64' : 'x64'}/chrome-headless-shell`
      : process.platform === 'win32' ? 'chrome-headless-shell-win64/headless_shell.exe'
        : 'chrome-headless-shell-linux64/chrome-headless-shell';
    for (const install of installs) {
      const candidate = join(cacheRoot, install, relativeExecutable);
      if (existsSync(candidate)) return candidate;
    }
  }
  throw new Error('No installed Chrome/Chromium found. Set CHROME_PATH to an existing executable; this test does not download browsers.');
}
