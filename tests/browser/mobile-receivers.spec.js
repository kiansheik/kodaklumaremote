import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';

let server;
let origin;
const lumaMedia = { url: 'https://media.example.org/luma.m3u8', contentType: 'application/vnd.apple.mpegurl', title: 'LUMA episode' };
const tvMedia = {
  url: 'https://media.example.org/philips.m3u8', contentType: 'application/vnd.apple.mpegurl', title: 'Philips episode', sourceToken: 'capture-tv',
  tracks: [
    { url: 'https://subs.example.org/en.vtt', language: 'en', label: 'English' },
    { url: 'https://subs.example.org/pt.vtt', language: 'pt-BR', label: 'Portuguese (Brazil)' },
    { url: 'https://subs.example.org/es.vtt', language: 'es', label: 'Spanish' },
  ],
};

test.beforeAll(async () => {
  const files = new Map([
    ['/', new URL('../../src/mobile/index.html', import.meta.url)],
    ['/app.js', new URL('../../src/mobile/app.js', import.meta.url)],
    ['/handoff/coordinator.mjs', new URL('../../src/handoff/coordinator.mjs', import.meta.url)],
  ]);
  server = http.createServer(async (request, response) => {
    const file = files.get(request.url);
    if (!file) { response.writeHead(404).end(); return; }
    response.setHeader('Content-Type', request.url === '/' ? 'text/html; charset=utf-8' : 'text/javascript; charset=utf-8');
    response.end(await readFile(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { if (server) await new Promise(resolve => server.close(resolve)); });

async function openApp(page, { selectedTarget = 'luma', tvConnected = true } = {}) {
  await page.addInitScript(({ selectedTarget, tvConnected, lumaMedia }) => {
    window.__calls = [];
    const tv = window.__tv = {
      connected: tvConnected, name: 'Philips TV', host: '192.168.1.60', startupUrl: tvConnected ? 'http://192.168.1.10:4178' : '', pendingCommand: null,
      playback: { active: tvConnected, source: 'https://media.example.org/existing-tv.mp4', title: 'Already on TV', paused: false, positionSeconds: 30, durationSeconds: 900, canSeek: true, volume: { current: 40, max: 100 }, tracks: [], selectedIndex: null, subtitlesEnabled: false },
    };
    window.__holdMethod = null;
    window.__held = [];
    const result = ({ method, params }) => {
      if (method === 'app.ready') return {
        selectedTarget, browserUrl: 'https://framextv.xyz/watch/284833/1/1', connection: { connected: true, configured: true, host: '192.168.1.50', name: 'Test LUMA' },
        settings: { hasReceiver: true, hasKodi: true, autoConnect: true }, volume: { current: 6, max: 30 },
        playback: { active: true, sourceUrl: lumaMedia.url, playing: true, positionSeconds: 12, durationSeconds: 600, canSeek: true },
        remote: { media: lumaMedia, tracks: [{ index: 0, language: 'pt-BR', label: 'LUMA Portuguese' }], catalog: [], selectedIndex: 0, subtitlesEnabled: true }, receivers: { philips: tv },
      };
      if (method === 'app.selectTarget') return { selectedTarget: params.target, receivers: { philips: tv } };
      if (method === 'app.beginHandoff') return { sourceToken: 'attempt-tv' };
      if (['app.handoffReady', 'app.cancelHandoff', 'app.handoffStatus'].includes(method)) return { accepted: true };
      if (method === 'msx.start') { tv.startupUrl = 'http://192.168.1.10:4178'; tv.awaitingApproval = true; tv.pendingHost = '192.168.1.60'; return tv; }
      if (method === 'msx.confirm') { tv.connected = true; tv.awaitingApproval = false; tv.pendingHost = ''; return tv; }
      if (method === 'msx.status') return tv;
      if (method === 'msx.open') {
        tv.playback = { ...tv.playback, active: true, source: params.source, title: params.title, tracks: params.subtitles.map((track, index) => ({ ...track, index })), selectedIndex: 1, subtitlesEnabled: true };
        tv.pendingCommand = null;
        return tv;
      }
      if (method === 'msx.playPause') { tv.playback.paused = !params.play; return tv; }
      if (method === 'msx.volume') { tv.playback.volume.current = params.value; return tv; }
      if (method === 'msx.subtitle') { tv.playback.selectedIndex = params.enabled ? params.index : null; tv.playback.subtitlesEnabled = params.enabled; return tv; }
      if (method === 'msx.seek') { tv.playback.positionSeconds = params.seconds; return tv; }
      if (method === 'msx.stop') { tv.playback.active = false; return tv; }
      if (method === 'msx.disconnect') { tv.connected = false; return tv; }
      if (method === 'kodi.pause') return { accepted: true };
      if (method === 'kodi.status') return { active: true, sourceUrl: lumaMedia.url, playing: false, positionSeconds: 12, durationSeconds: 600, canSeek: true };
      throw new Error(`Unexpected fixture request: ${method}`);
    };
    const respond = request => {
      if (request.method === 'msx.open' && window.__subtitleFailure) {
        result(request);
        tv.playback.selectedIndex = null; tv.playback.subtitlesEnabled = false;
        tv.lastCommand = { id: 1, method: 'open', ok: false, error: 'SUBTITLE_FAILED' };
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'MSX_REJECTED' } });
      } else if (request.method === 'msx.open' && window.__unknownOpen) {
        tv.pendingCommand = { id: 1, method: 'open' };
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'MSX_TIMEOUT' } });
      } else window.LumaRPC.receive({ id: request.id, ok: true, result: result(request) });
    };
    window.__release = () => { window.__holdMethod = null; for (const request of window.__held.splice(0)) respond(request); };
    window.LumaNative = { postMessage(text) {
      const request = JSON.parse(text);
      window.__calls.push(request);
      queueMicrotask(() => { if (request.method === window.__holdMethod) window.__held.push(request); else respond(request); });
    } };
  }, { selectedTarget, tvConnected, lumaMedia });
  await page.goto(origin);
  await expect(page.locator(`#target-${selectedTarget}`)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'true');
}
async function fits(page) {
  expect(await page.evaluate(() => {
    const visible = document.querySelector('#sheet:not([hidden])') || document.querySelector('.page:not([hidden])');
    const elements = [...visible.querySelectorAll('button, input')].filter(element => element.offsetParent !== null);
    return document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight && elements.every(element => {
      const rect = element.getBoundingClientRect();
      return rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth + 1 && rect.bottom <= innerHeight + 1;
    });
  })).toBe(true);
}

test('both receivers stay connected and switching isolates playback, subtitles and commands', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page);
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await expect(page.locator('#target-philips')).toHaveAttribute('data-connected', 'true');
  if (process.env.LUMA_UI_SCREENSHOTS === '1') await page.screenshot({ path: '.local/mobile-luma-320x592.png' });
  await page.locator('#target-philips').click();
  await expect(page.locator('#media-title')).toHaveText('Already on TV');
  await expect(page.locator('.volume-control label')).toContainText('Player volume');
  if (process.env.LUMA_UI_SCREENSHOTS === '1') await page.screenshot({ path: '.local/mobile-philips-320x592.png' });
  await expect(page.locator('#show-power')).toBeHidden();
  await expect(page.locator('.dpad')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeHidden();
  await page.locator('#play-toggle').click();
  await expect(page.locator('#play-toggle')).toHaveText('Resume');
  await page.locator('#volume-up').click();
  await expect(page.locator('#volume-level')).toHaveText('41%');
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: false }, playback: { active: false } }));
  await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'false');
  await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'true');
  await expect(page.locator('#media-title')).toHaveText('Already on TV');
  await page.locator('#target-luma').click();
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => /disconnect|open|kodi\.|luma\./u.test(call.method))).toEqual([]);
  expect(calls.filter(call => call.method === 'msx.playPause').map(call => call.params)).toEqual([{ play: false, expectedSourceUrl: 'https://media.example.org/existing-tv.mp4' }]);
  expect(calls.filter(call => call.method === 'msx.volume').map(call => call.params)).toEqual([{ value: 41, expectedSourceUrl: 'https://media.example.org/existing-tv.mp4' }]);
});

test('Philips capture binds its attempt, sends all language metadata and locks destination until acknowledged', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(() => { window.__holdMethod = 'msx.open'; });
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect.poll(() => page.evaluate(() => window.__calls.some(call => call.method === 'msx.open'))).toBe(true);
  await expect(page.locator('#target-luma')).toBeDisabled();
  await expect(page.locator('#target-philips')).toBeDisabled();
  await page.evaluate(() => window.__release());
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  await expect(page.locator('#target-luma')).toBeEnabled();
  await page.locator('#show-subtitles').click();
  await expect(page.locator('#subtitle-list button')).toHaveText(['Portuguese (Brazil)', 'English', 'Spanish']);
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.find(call => call.method === 'app.beginHandoff').params).toEqual({ sourceToken: tvMedia.sourceToken, target: 'philips' });
  expect(calls.find(call => call.method === 'msx.open').params).toEqual({ sourceToken: 'attempt-tv', source: tvMedia.url, mimeType: tvMedia.contentType, title: tvMedia.title, subtitles: tvMedia.tracks, preferredLanguage: 'pt-BR' });
  expect(calls.find(call => call.method === 'app.handoffReady').params).toEqual({ target: 'philips', sourceToken: 'attempt-tv' });
  expect(calls.find(call => call.method === 'msx.subtitle').params).toEqual({ index: 0, enabled: true, expectedSourceUrl: tvMedia.url });
  expect(calls.some(call => /^(kodi|luma)\./u.test(call.method))).toBe(false);
});

test('MSX setup requires explicit listener start and matching TV approval without disturbing LUMA', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips', tvConnected: false });
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'msx.start'))).toBe(false);
  await page.locator('#msx-start').click();
  await expect(page.locator('#msx-startup')).toHaveText('192.168.1.10:4178');
  await expect(page.locator('#msx-pairing')).toContainText('192.168.1.60');
  await page.locator('#msx-confirm').click();
  await expect(page.locator('#page-remote')).toBeVisible();
  await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'true');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.confirm').map(call => call.params))).toEqual([{ host: '192.168.1.60' }]);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'device.disconnect'))).toBe(false);
});

test('a late native bind restores the saved Philips target before the next capture', async ({ page }) => {
  await openApp(page);
  await page.evaluate(() => window.LumaApp.onConnectionState({ selectedTarget: 'philips', receivers: { philips: window.__tv } }));
  await expect(page.locator('#target-philips')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#media-title')).toHaveText('Already on TV');
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'app.selectTarget'))).toBe(false);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'msx.open'))).toBe(true);
  expect(await page.evaluate(() => window.__calls.some(call => /^(kodi|luma)\./u.test(call.method)))).toBe(false);
});

test('unknown MSX open outcome is observed before another open can be sent', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(() => { window.__unknownOpen = true; });
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toContainText('TV is still checking');
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), { ...tvMedia, sourceToken: 'capture-again' });
  await expect(page.locator('#message')).toContainText('previous request');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.open').length)).toBe(1);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await expect(page.locator('#cast-selected')).toBeDisabled();
  await page.evaluate(() => {
    delete window.__tv.pendingCommand;
    window.__tv.lastCommand = { id: 1, method: 'open', ok: false, error: 'VIDEO_FAILED' };
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  });
  await expect(page.locator('#cast-selected')).toBeEnabled();
});

test('subtitle failure adopts a playing TV video and retries only the selected language', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(() => { window.__subtitleFailure = true; });
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toContainText('Choose subtitles in the remote');
  await expect(page.locator('#page-remote')).toBeVisible();
  await page.locator('#show-subtitles').click();
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.open').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.subtitle').length)).toBe(1);
});

test('an in-flight remote command locks the target until its response is consumed', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(() => { window.__holdMethod = 'msx.playPause'; });
  await page.locator('#play-toggle').click();
  await expect(page.locator('#target-luma')).toBeDisabled();
  await page.evaluate(() => window.__release());
  await expect(page.locator('#play-toggle')).toHaveText('Resume');
  await page.locator('#target-luma').click();
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await expect(page.locator('#play-toggle')).toHaveText('Pause');
});

test('native target restoration waits until an in-flight command updates its original receiver', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(() => { window.__holdMethod = 'msx.playPause'; });
  await page.locator('#play-toggle').click();
  await page.evaluate(() => window.LumaApp.onConnectionState({ selectedTarget: 'luma' }));
  await expect(page.locator('#target-philips')).toHaveAttribute('aria-pressed', 'true');
  await page.evaluate(() => window.__release());
  await expect(page.locator('#target-luma')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await expect(page.locator('#play-toggle')).toHaveText('Pause');
  await page.locator('#target-philips').click();
  await expect(page.locator('#play-toggle')).toHaveText('Resume');
});

test('Philips setup, approval and remote panels fit a narrow phone without scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { selectedTarget: 'philips', tvConnected: false });
  await fits(page);
  await page.locator('#msx-start').click();
  await fits(page);
  await page.locator('#msx-confirm').click();
  await fits(page);
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  await page.locator('#show-subtitles').click();
  await fits(page);
  await page.locator('#close-sheet').click();
  await page.locator('#show-more').click();
  await fits(page);
  await expect(page.getByRole('button', { name: 'Type text', exact: true })).toBeHidden();
  await page.getByRole('button', { name: 'Go to time', exact: true }).click();
  await fits(page);
});
