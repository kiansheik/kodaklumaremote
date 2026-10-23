import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';

const CODE = '34970112332';
const QR = 'MT:FIXTURE-QR.0123456789';
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRuoAAAAASUVORK5CYII=';
let server, origin;
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

async function openApp(page, { paired = false, connected = paired, msx = false, capabilities = {} } = {}) {
  await page.addInitScript(({ paired, connected, msx, capabilities, code, qr, png }) => {
    window.__calls = [];
    window.__held = [];
    window.__hold = null;
    const matter = window.__matter = {
      available: true, paired, connected, pairing: false,
      capabilities: { navigation: true, power: true, volume: true, inputs: true, openWindow: true, ...capabilities },
      volume: { current: 1, min: 1, max: 254 },
      inputs: [{ index: 1, name: 'TV' }, { index: 2, name: 'HDMI 1' }], currentInput: 1,
    };
    const tv = window.__tv = {
      connected: msx, name: 'Philips TV', host: msx ? '192.168.1.60' : '', startupUrl: '', matter,
      playback: { active: msx, source: 'https://media.example/tv.mp4', title: 'TV episode', paused: false,
        positionSeconds: 12, durationSeconds: 600, canSeek: true, volume: { current: 40, max: 100 }, tracks: [], selectedIndex: -1, subtitlesEnabled: false },
    };
    const lumaMedia = { url: 'https://media.example/luma.mp4', contentType: 'video/mp4', title: 'LUMA episode' };
    function result({ method, params }) {
      if (method === 'app.ready') return {
        selectedTarget: 'philips', connection: { connected: true, configured: true, host: '192.168.1.50', name: 'LUMA fixture' },
        settings: { hasReceiver: true, hasPairing: true, autoConnect: true }, volume: { current: 6, max: 30 },
        playback: { active: true, sourceUrl: lumaMedia.url, playing: true, positionSeconds: 20, durationSeconds: 700, canSeek: true },
        remote: { media: lumaMedia, tracks: [], catalog: [], selectedIndex: null, subtitlesEnabled: false }, receivers: { philips: tv },
      };
      if (method === 'app.selectTarget') return { selectedTarget: params.target, receivers: { philips: tv } };
      if (method === 'app.scanMatterCode') return { setupCode: qr };
      if (method === 'matter.pair') {
        if (window.__asyncPair) { matter.pairing = true; return matter; }
        matter.paired = matter.connected = true; return matter;
      }
      if (method === 'matter.status' || method === 'matter.key') return matter;
      if (method === 'matter.volume') { matter.volume.current = params.value; return matter; }
      if (method === 'matter.input') { matter.currentInput = params.index; return matter; }
      if (method === 'matter.forget') { matter.paired = matter.connected = false; matter.volume = matter.currentInput = null; return matter; }
      if (method === 'matter.openWindow') return { manualCode: code, qrPng: png, expiresAt: Date.now() + (window.__shareDuration || 600000) };
      if (method === 'msx.status') return tv;
      if (method === 'msx.playPause') { tv.playback.paused = !params.play; return tv; }
      if (method === 'luma.key') return { accepted: true };
      if (method === 'luma.status') return { volume: { current: 6, max: 30 } };
      throw new Error(`Unexpected fixture request ${method}`);
    }
    function respond(request) {
      if (window.__failMethod === request.method && !(request.method === 'matter.forget' && request.params.localOnly === true)) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__failure || 'MATTER_PAIRING', message: `https://private.example/?setupCode=${code}` } });
      } else window.LumaRPC.receive({ id: request.id, ok: true, result: result(request) });
    }
    window.__release = () => { window.__hold = null; for (const request of window.__held.splice(0)) respond(request); };
    window.LumaNative = { postMessage(text) {
      const request = JSON.parse(text); window.__calls.push(request);
      queueMicrotask(() => { if (window.__hold === request.method) window.__held.push(request); else respond(request); });
    } };
  }, { paired, connected, msx, capabilities, code: CODE, qr: QR, png: PNG });
  await page.goto(origin);
  await expect(page.locator('#target-philips')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'true');
}
async function pairing(page) {
  if (await page.locator('#page-devices').isHidden()) {
    await page.locator('#show-more').click();
    await page.locator('#show-matter-setup').click();
  }
  await expect(page.locator('#matter-panel')).toBeVisible();
}
async function fits(page) {
  expect(await page.evaluate(() => {
    const visible = document.querySelector('#sheet:not([hidden])') || document.querySelector('.page:not([hidden])');
    return document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight &&
      [...visible.querySelectorAll('button,input,img')].filter(element => element.offsetParent !== null).every(element => {
        const rect = element.getBoundingClientRect(); return rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth + 1 && rect.bottom <= innerHeight + 1;
      });
  })).toBe(true);
}

test('manual pairing masks and clears the code while an explicit native request is pending', async ({ page }) => {
  await openApp(page);
  await expect(page.locator('#matter-code')).toHaveAttribute('type', 'password');
  await expect(page.locator('#matter-code')).toHaveAttribute('autocomplete', 'off');
  await page.evaluate(() => { window.__hold = 'matter.pair'; });
  await page.locator('#matter-code').fill('3497-011-2332');
  await page.locator('#matter-pair').click();
  await expect(page.locator('#matter-code')).toHaveValue('');
  await expect(page.locator('#matter-pair')).toBeDisabled();
  await expect(page.locator('#target-luma')).toBeDisabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'matter.pair').map(call => call.params))).toEqual([{ setupCode: CODE }]);
  expect(await page.locator('body').textContent()).not.toContain(CODE);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#page-remote')).toBeVisible();
  await expect(page.locator('#device-badge')).toHaveText('TV remote connected');
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
});

test('QR scanning preserves the entire base38 payload and pairs through the native bridge', async ({ page }) => {
  await openApp(page);
  await page.locator('#matter-scan').click();
  await expect(page.locator('#page-remote')).toBeVisible();
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'app.scanMatterCode')).toHaveLength(1);
  expect(calls.find(call => call.method === 'matter.pair').params).toEqual({ setupCode: QR });
  expect(await page.locator('body').textContent()).not.toContain(QR);
});

test('accepted asynchronous pairing waits for native readiness before exposing the remote', async ({ page }) => {
  await openApp(page);
  await page.evaluate(() => { window.__asyncPair = true; window.__hold = 'matter.status'; });
  await page.locator('#matter-code').fill(CODE);
  await page.locator('#matter-pair').click();
  await expect.poll(() => page.evaluate(() => window.__held.some(request => request.method === 'matter.status'))).toBe(true);
  await expect(page.locator('#page-devices')).toBeVisible();
  await expect(page.locator('#target-luma')).toBeDisabled();
  await expect(page.locator('#matter-code')).toHaveValue('');
  await page.evaluate(() => {
    Object.assign(window.__matter, { paired: true, connected: true, pairing: false });
    window.__release();
  });
  await expect(page.locator('#page-remote')).toBeVisible();
  await expect(page.locator('[data-key="home"]')).toBeEnabled();
  await pairing(page);
  await expect(page.locator('#matter-share')).toBeEnabled();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(request => request.method === 'matter.pair').length)).toBe(1);
});

test('scan cancellation and pairing errors do not expose returned diagnostic secrets or send another pairing', async ({ page }) => {
  await openApp(page);
  await page.evaluate(() => { window.__failMethod = 'app.scanMatterCode'; window.__failure = 'MATTER_SCAN_CANCELLED'; });
  await page.locator('#matter-scan').click();
  await expect(page.locator('#matter-scan')).toBeEnabled();
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'matter.pair'))).toBe(false);
  await page.evaluate(() => { window.__failMethod = 'matter.pair'; window.__failure = 'MATTER_PAIRING'; });
  await page.locator('#matter-code').fill(CODE);
  await page.locator('#matter-pair').click();
  await expect(page.locator('#message')).toContainText('Pairing did not finish');
  await expect(page.locator('#matter-code')).toHaveValue('');
  const text = await page.locator('body').textContent();
  expect(text).not.toContain(CODE); expect(text).not.toContain('private.example');
});

test('saved Matter pairing reconnects without a code and provides hardware controls without MSX', async ({ page }) => {
  await openApp(page, { paired: true, connected: true });
  await expect(page.locator('#page-remote')).toBeVisible();
  await expect(page.locator('.dpad')).toBeVisible();
  await expect(page.locator('#volume-range')).toHaveAttribute('min', '1');
  await expect(page.locator('#volume-range')).toHaveAttribute('max', '254');
  await expect(page.locator('#play-toggle')).toBeDisabled();
  await pairing(page);
  await expect(page.locator('#matter-form')).toBeHidden();
  await page.locator('#matter-reconnect').click();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'matter.pair' || call.method === 'msx.start'))).toEqual([]);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'matter.status'))).toBe(true);
});

test('composite Philips remote routes hardware to Matter and video to MSX while LUMA stays independent', async ({ page }) => {
  await openApp(page, { paired: true, connected: true, msx: true });
  await page.getByRole('button', { name: 'Home', exact: true }).click();
  await page.locator('#volume-up').click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'matter.volume').length)).toBe(1);
  await page.locator('#play-toggle').click();
  await expect(page.locator('#play-toggle')).toHaveText('Resume');
  await page.locator('#target-luma').click();
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await page.waitForTimeout(110);
  await page.getByRole('button', { name: 'Right', exact: true }).click();
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'matter.key').map(call => call.params)).toEqual([{ key: 'home' }]);
  expect(calls.filter(call => call.method === 'matter.volume').map(call => call.params)).toEqual([{ value: 2 }]);
  expect(calls.filter(call => call.method === 'msx.playPause').map(call => call.params)).toEqual([{ play: false, expectedSourceUrl: 'https://media.example/tv.mp4' }]);
  expect(calls.filter(call => call.method === 'luma.key').map(call => call.params)).toEqual([{ key: 'right' }]);
  expect(calls.some(call => /disconnect|powerOff|openWindow/u.test(call.method))).toBe(false);
});

test('adding another phone only opens an explicit window and clears its code and QR when closed', async ({ page }) => {
  await openApp(page, { paired: true });
  await pairing(page);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'matter.openWindow'))).toBe(false);
  await page.locator('#matter-share').click();
  await expect(page.locator('#matter-share-code')).toHaveText(CODE);
  await expect(page.locator('#matter-qr')).toHaveAttribute('src', PNG);
  await page.locator('#close-sheet').click();
  await expect(page.locator('#matter-share-code')).toHaveText('');
  await expect(page.locator('#matter-qr')).not.toHaveAttribute('src');
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'matter.openWindow').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.some(call => /forget|reset|disconnect/u.test(call.method)))).toBe(false);
});

test('an expired additional-phone code is removed automatically and cannot remain in the DOM', async ({ page }) => {
  await page.clock.install();
  await openApp(page, { paired: true });
  await pairing(page);
  await page.evaluate(() => { window.__shareDuration = 2000; });
  await page.locator('#matter-share').click();
  await expect(page.locator('#matter-share-code')).toHaveText(CODE);
  await page.clock.fastForward(2100);
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.locator('#matter-share-code')).toHaveText('');
  await expect(page.locator('#matter-qr')).not.toHaveAttribute('src');
  await expect(page.locator('#message')).toContainText('Pairing code expired');
});

test('an unreachable TV can be forgotten locally after explicit confirmation without changing LUMA setup', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { paired: true });
  await page.locator('#show-settings').click();
  await expect(page.locator('#settings-disconnect')).toBeEnabled();
  await page.locator('#show-reset').click();
  await fits(page);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'matter.forget'))).toBe(false);
  await page.evaluate(() => { window.__failMethod = 'matter.forget'; window.__failure = 'MATTER_NOT_CONNECTED'; });
  await page.locator('[data-reset="all"]').click();
  await expect(page.locator('#panel-reset')).toBeVisible();
  await page.locator('#forget-matter-local').click();
  await expect(page.locator('#matter-form')).toBeVisible();
  await expect(page.locator('#message')).toContainText('The TV may still list this phone');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'matter.forget').map(call => call.params)).toEqual([{ confirmed: true }, { confirmed: true, localOnly: true }]);
  expect(calls.some(call => call.method === 'app.settings.reset' || call.method === 'msx.reset' || call.method === 'device.disconnect')).toBe(false);
  await page.evaluate(() => { window.__matter.capabilities.volume = false; });
  await page.locator('#matter-code').fill(CODE);
  await page.locator('#matter-pair').click();
  await expect(page.locator('#page-remote')).toBeVisible();
  await expect(page.locator('#volume-range')).toBeDisabled();
  await expect(page.locator('#volume-level')).toHaveText('—');
  await page.locator('#target-luma').click();
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await expect(page.locator('[data-key="right"]')).toBeEnabled();
});

for (const size of [{ width: 320, height: 592 }, { width: 390, height: 844 }]) {
  test(`Matter pairing, composite remote and sharing fit without scrolling at ${size.width}×${size.height}`, async ({ page }) => {
    await page.setViewportSize(size);
    await openApp(page);
    await fits(page);
    await page.locator('#matter-code').fill(CODE);
    await page.locator('#matter-pair').click();
    await expect(page.locator('#page-remote')).toBeVisible();
    await expect(page.locator('[data-key="home"]')).toBeEnabled();
    await fits(page);
    await pairing(page);
    await fits(page);
    await expect(page.locator('#matter-share')).toBeEnabled();
    await page.locator('#matter-share').click();
    await fits(page);
  });
}
