import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';

let server;
let appUrl;
const media = {
  url: 'https://media.example.org/episode.m3u8?token=private-source-token',
  contentType: 'application/vnd.apple.mpegurl', title: 'Household episode', sourceToken: 'capture-one',
  tracks: [
    { url: 'https://subtitles.example.org/en?token=private-subtitle-token', language: 'en', label: 'English' },
    { url: 'https://subtitles.example.org/ja', language: 'ja', label: 'Japanese' },
    { url: 'https://subtitles.example.org/pt', language: 'pt-BR', label: 'Portuguese (Brazil)' },
    { url: 'https://subtitles.example.org/de', language: 'de', label: 'German' },
    { url: 'https://subtitles.example.org/es', language: 'es', label: 'Spanish' },
    { url: 'https://subtitles.example.org/pt-generic', language: 'pt', label: 'Portuguese' },
  ],
};

test.beforeAll(async () => {
  const paths = new Map([
    ['/', new URL('../../src/mobile/index.html', import.meta.url)],
    ['/app.js', new URL('../../src/mobile/app.js', import.meta.url)],
    ['/handoff/coordinator.mjs', new URL('../../src/handoff/coordinator.mjs', import.meta.url)],
  ]);
  server = http.createServer(async (request, response) => {
    const file = paths.get(request.url);
    if (!file) { response.writeHead(404).end(); return; }
    response.setHeader('Content-Type', request.url === '/' ? 'text/html; charset=utf-8' : 'text/javascript; charset=utf-8');
    response.end(await readFile(file));
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  appUrl = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { if (server) await new Promise(resolve => server.close(resolve)); });

async function openApp(page, initialSnapshot = null) {
  const requests = [];
  page.on('request', request => requests.push({ url: request.url(), type: request.resourceType() }));
  await page.route('**/*', route => new URL(route.request().url()).origin === appUrl ? route.continue() : route.abort());
  await page.addInitScript(({ initialSnapshot }) => {
    window.__calls = [];
    window.__receiver = { active: false, playing: true, sourceUrl: null, tracks: [], enabled: false, currentIndex: null, position: 12, volume: 6, failText: false, holdMethod: null, held: [], job: 0 };
    const receiver = window.__receiver;
    if (initialSnapshot?.playback) {
      receiver.active = initialSnapshot.playback.active === true;
      receiver.playing = initialSnapshot.playback.playing !== false;
      receiver.sourceUrl = initialSnapshot.playback.sourceUrl || null;
      receiver.position = initialSnapshot.playback.positionSeconds || 0;
    }
    if (initialSnapshot?.remote) {
      const tracks = initialSnapshot.remote.tracks || [];
      receiver.tracks = Array.from({ length: tracks.length ? Math.max(...tracks.map(track => track.index)) + 1 : 0 }, () => ({}));
      receiver.enabled = initialSnapshot.remote.subtitlesEnabled === true;
      receiver.currentIndex = initialSnapshot.remote.selectedIndex;
    }
    const result = request => {
      const { method, params } = request;
      switch (method) {
        case 'app.ready': return { browserUrl: 'https://framextv.xyz/watch/284833/1/1', ...initialSnapshot };
        case 'app.browse': case 'app.handoffStatus': case 'app.checkUpdates': return { accepted: true };
        case 'app.handoffReady': case 'app.rememberRemote': receiver.rememberedRemote = params.remote; return { accepted: true };
        case 'app.settings.rescan': return receiver.rescanAutoConnect ? { connected: true, name: 'Other LUMA', devices: [{ host: '192.168.1.51', name: 'Other LUMA', port: 8121 }] } : { connection: { connected: false, configured: false }, devices: [{ host: '192.168.1.51', name: 'Other LUMA', port: 8121 }] };
        case 'app.settings.reset': return { connection: { connected: params.scope === 'kodi', configured: false }, settings: { hasReceiver: params.scope === 'kodi', hasKodi: params.scope === 'receiver', autoConnect: true } };
        case 'app.beginHandoff': receiver.activeJob = `job-${++receiver.job}`; return { sourceToken: receiver.activeJob };
        case 'app.cancelHandoff': if (params.sourceToken === receiver.activeJob) receiver.activeJob = null; return { accepted: true };
        case 'app.capture': if (window.__captureMedia) window.LumaApp.onCapturedMedia(window.__captureMedia); return { accepted: true };
        case 'device.discover': return { devices: [{ host: '192.168.1.50', name: 'Test LUMA', port: 8121 }] };
        case 'device.connect': return { connected: true, device: { name: 'Test LUMA' } };
        case 'device.disconnect': return { connected: false };
        case 'kodi.configure': return { configured: true };
        case 'luma.launchKodi': case 'luma.key': case 'luma.launchApp': return { accepted: true };
        case 'luma.powerOff': return { accepted: true };
        case 'luma.queueVolume':
          receiver.queuedVolume = params.value;
          clearTimeout(receiver.volumeTimer);
          receiver.volumeTimer = setTimeout(() => {
            receiver.volume = receiver.observedVolume ?? receiver.queuedVolume;
            (receiver.volumeWrites ||= []).push(receiver.queuedVolume);
            window.LumaApp.onConnectionState({ volume: { current: receiver.volume, max: 30 } });
          }, 2000);
          return { queued: true, volume: { current: params.value, max: 30, pending: true } };
        case 'luma.status': return { connected: true, volume: { current: receiver.volume, max: 30 } };
        case 'luma.apps': return { apps: Array.from({ length: 12 }, (_, index) => ({ packageName: `test.app${index}`, label: index === 0 ? '<img src=x onerror="window.appExecuted=true">' : `App ${index}` })) };
        case 'luma.text': return { accepted: true };
        case 'kodi.connect': return { ready: true };
        case 'kodi.open': receiver.active = true; receiver.playing = true; receiver.sourceUrl = params.media.url; receiver.tracks = []; receiver.enabled = false; receiver.currentIndex = null; return { accepted: true };
        case 'kodi.pause': receiver.playing = false; return { accepted: true };
        case 'kodi.resume': receiver.playing = true; return { accepted: true };
        case 'kodi.status': return { active: receiver.active, playing: receiver.playing, sourceUrl: receiver.sourceUrl, positionSeconds: receiver.position, durationSeconds: 600, canSeek: true };
        case 'kodi.getSubtitles':
          if (receiver.pendingTrack && --receiver.subtitleDelayReads <= 0) { receiver.tracks.push(receiver.pendingTrack); receiver.pendingTrack = null; }
          return { tracks: receiver.tracks.map((track, index) => ({ index, name: 'External' })), enabled: receiver.enabled, currentIndex: receiver.currentIndex };
        case 'kodi.prepareSubtitle': return { preparedId: 'prepared_' + btoa(params.track.url).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 95), format: 'srt', encoding: 'UTF-8', cueCount: 10 };
        case 'kodi.subtitleDelivery': return { downloaded: !receiver.holdDelivery };
        case 'kodi.addSubtitle': if (receiver.subtitleDelayReads > 0) receiver.pendingTrack = params.track; else receiver.tracks.push(params.track); return { accepted: true };
        case 'kodi.setSubtitle': receiver.enabled = params.index !== 'off'; receiver.currentIndex = receiver.enabled ? params.index : null; return { accepted: true };
        case 'kodi.seek': receiver.position = params.position; return { accepted: true };
        case 'kodi.stop': receiver.active = false; return { active: false };
        default: throw new Error('Unexpected fixture method');
      }
    };
    const respond = request => {
      if ((request.method.startsWith('kodi.') || request.method === 'luma.launchKodi') && request.params.sourceToken && request.params.sourceToken !== receiver.activeJob) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'SOURCE_CHANGED', message: 'stale job' } });
        return;
      }
      if (request.method === 'kodi.prepareSubtitle' && receiver.failPrepare) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: receiver.failPrepare, message: 'private-subtitle-token' } });
        return;
      }
      if (request.method === 'kodi.addSubtitle' && receiver.failSubtitle) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: typeof receiver.failSubtitle === 'string' ? receiver.failSubtitle : 'INVALID_SUBTITLE', message: 'private-subtitle-token' } });
        return;
      }
      if (request.method === 'kodi.open' && receiver.failOpen) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: receiver.failOpen, message: 'private-source-token' } });
        return;
      }
      if (request.method === 'kodi.getSubtitles' && receiver.failSubtitleAuth) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'KODI_AUTH', message: 'fixture-password private-subtitle-token' } });
        return;
      }
      if (['kodi.addSubtitle', 'kodi.setSubtitle', 'kodi.getSubtitles'].includes(request.method) && request.params.expectedSourceUrl && request.params.expectedSourceUrl !== receiver.sourceUrl) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'SOURCE_CHANGED', message: 'source changed' } });
        return;
      }
      if (request.method === 'kodi.connect' && receiver.failAuth) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'KODI_AUTH', message: 'fixture-password private-source-token' } });
        return;
      }
      if (request.method === 'kodi.connect' && receiver.failStorage) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'STORAGE_UNAVAILABLE', message: 'fixture-password' } });
        return;
      }
      if (request.method === 'app.handoffReady' && receiver.failRemember) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'REQUEST_FAILED', message: 'private-source-token' } });
        return;
      }
      if (request.method === 'luma.text' && receiver.failText) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'CONNECTION_FAILED', message: 'private-source-token <img src=x onerror="window.errorExecuted=true">' } });
        return;
      }
      const response = { id: request.id, ok: true, result: result(request) };
      if (request.method === 'kodi.open' && receiver.holdOpenResponse) {
        receiver.openResponse = response;
        return;
      }
      window.LumaRPC.receive(response);
    };
    window.__release = () => { receiver.holdMethod = null; for (const request of receiver.held.splice(0)) respond(request); };
    window.__releaseOpenResponse = code => {
      const response = receiver.openResponse;
      receiver.openResponse = null;
      receiver.holdOpenResponse = false;
      if (response) window.LumaRPC.receive(code ? { id: response.id, ok: false, error: { code } } : response);
    };
    window.LumaNative = { postMessage(text) {
      const request = JSON.parse(text);
      window.__calls.push(request);
      queueMicrotask(() => { if (receiver.holdMethod === request.method) receiver.held.push(request); else respond(request); });
    } };
  }, { initialSnapshot });
  await page.goto(appUrl);
  if (initialSnapshot?.connection?.connected === true) await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'true');
  else if (initialSnapshot?.settings?.hasReceiver && initialSnapshot?.settings?.hasKodi) await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  else await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeEnabled();
  return requests;
}

async function setup(page) {
  await page.getByLabel('Projector IP address').fill('192.168.1.50');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#kodi-panel')).toBeVisible();
  await page.getByLabel('Username', { exact: true }).fill('fixture-user');
  await page.getByLabel('Password', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
}
async function cast(page) {
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
}
async function settingsDevice(page, name = 'Projector connection') {
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name, exact: true }).click();
}
async function layout(page) {
  await expect.poll(() => page.evaluate(() => {
    const visible = document.querySelector('#sheet:not([hidden])') || document.querySelector('.page:not([hidden])');
    const controls = [...visible.querySelectorAll('button, input')].filter(element => element.offsetParent !== null);
    return {
      width: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
      height: document.documentElement.scrollHeight <= document.documentElement.clientHeight,
      controlsFit: controls.every(element => { const rect = element.getBoundingClientRect(); return rect.top >= 0 && rect.left >= 0 && rect.bottom <= innerHeight + 1 && rect.right <= innerWidth + 1; }),
    };
  })).toEqual({ width: true, height: true, controlsFit: true });
}

test('Remote, Devices and secondary panels fit small screens without document scrolling, including a keyboard-sized draft', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page);
  await layout(page);
  await setup(page);
  await layout(page);
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await layout(page);
  await page.getByRole('button', { name: 'Type text', exact: true }).click();
  await page.getByLabel('Text to send').fill('Keep my draft');
  await page.setViewportSize({ width: 360, height: 400 });
  await layout(page);
  await expect(page.getByRole('button', { name: 'Send text', exact: true })).toBeVisible();
  await expect(page.getByLabel('Text to send')).toHaveValue('Keep my draft');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await layout(page);
  await settingsDevice(page, 'Kodi login');
  await layout(page);
  await page.getByLabel('Password', { exact: true }).focus();
  await page.setViewportSize({ width: 360, height: 400 });
  await layout(page);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
  await page.reload();
  await page.getByRole('button', { name: '2 · Kodi', exact: true }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('Username', { exact: true })).toHaveValue('');
});

test('captured video starts without subtitle commands and keeps every language for manual selection without exposing sources', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  const requests = await openApp(page);
  await setup(page);
  await cast(page);
  await expect(page.locator('#media-title')).toHaveText('Household episode');
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  const calls = await page.evaluate(() => window.__calls);
  const methods = calls.map(call => call.method);
  expect(methods.indexOf('luma.launchKodi')).toBeLessThan(methods.indexOf('kodi.open'));
  expect(calls.filter(call => ['kodi.addSubtitle', 'kodi.setSubtitle', 'kodi.getSubtitles'].includes(call.method))).toEqual([]);
  expect(calls.filter(call => ['kodi.open', 'kodi.addSubtitle', 'kodi.setSubtitle'].includes(call.method)).every(call => call.params.sourceToken === 'job-1' && call.params.expectedSourceUrl === media.url)).toBe(true);
  expect(calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
  expect(calls.find(call => call.method === 'app.handoffReady').params.remote).toEqual({
    media: { url: media.url, contentType: media.contentType, title: media.title },
    tracks: [],
    catalog: media.tracks.map(track => ({ url: track.url, language: track.language, label: track.label, index: null })),
    selectedIndex: null, subtitlesEnabled: false,
  });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-list button')).toHaveText(['Portuguese (Brazil)', 'English', 'Portuguese']);
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('#subtitle-list button')).toHaveText(['German', 'Japanese', 'Spanish']);
  await layout(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await layout(page);
  expect(await page.locator('body').innerText()).not.toContain('private-source-token');
  expect(await page.locator('body').innerText()).not.toContain('private-subtitle-token');
  expect(requests.every(request => new URL(request.url).origin === appUrl)).toBe(true);
  expect(requests.some(request => request.type === 'media')).toBe(false);
});

test('manual playback stays bound to the active receiver source while browsing another page and seeking requires an explicit action', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await cast(page);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await page.evaluate(() => window.LumaApp.onSourceChanged());
  await page.getByRole('tab', { name: 'Remote', exact: true }).click();
  await page.getByRole('button', { name: 'Pause', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Refresh volume', exact: true }).click();
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Go to time', exact: true }).click();
  await page.getByLabel('Playback position').focus();
  await page.keyboard.press('Home');
  await page.keyboard.press('ArrowRight');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'kodi.seek'))).toBe(false);
  await page.getByRole('button', { name: 'Go to position', exact: true }).click();
  const calls = await page.evaluate(() => window.__calls);
  const pause = calls.find(call => call.method === 'kodi.pause');
  expect(pause.params).toEqual({ expectedSourceUrl: media.url });
  expect(calls[calls.indexOf(pause) + 1].method).toBe('kodi.status');
  expect(calls.find(call => call.method === 'app.browse').params).toEqual({});
  expect(calls.filter(call => call.method === 'luma.key' && call.params.key === 'enter')).toHaveLength(0);
  expect(calls.find(call => call.method === 'kodi.seek').params).toEqual({ expectedSourceUrl: media.url, position: 1 });
  expect(calls.filter(call => call.method === 'kodi.setSubtitle').at(-1).params).toEqual({ expectedSourceUrl: media.url, index: 0 });
});

test('apps paginate as text and failed typing keeps its draft while clearing a lost projector connection', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page);
  await setup(page);
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Apps', exact: true }).click();
  await expect(page.locator('#app-list button')).toHaveCount(3);
  await expect(page.locator('#apps-page')).toHaveText('1 / 4');
  await layout(page);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('#apps-page')).toHaveText('2 / 4');
  await page.getByLabel('Find an app').fill('App 11');
  await expect(page.locator('#app-list button')).toHaveText(['App 11']);
  await page.getByRole('button', { name: 'App 11', exact: true }).click();
  expect(await page.locator('#app-list img').count()).toBe(0);
  expect(await page.evaluate(() => window.appExecuted)).toBeUndefined();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Type text', exact: true }).click();
  await page.getByLabel('Text to send').fill('Unsafe\u0001text');
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Use a single line of up to 1000 characters.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.text').length)).toBe(0);
  await page.getByLabel('Text to send').fill('ã'.repeat(600));
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(page.getByLabel('Text to send')).toHaveValue('');
  await page.getByLabel('Text to send').fill('Keep this draft');
  await page.evaluate(() => { window.__receiver.failText = true; });
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(page.getByLabel('Text to send')).toHaveValue('Keep this draft');
  await expect(page.locator('#device-badge')).toHaveText('No projector connected');
  await expect(page.locator('#message')).toHaveText('Could not connect to the projector. Check its power and Wi-Fi.');
  await expect(page.locator('body')).not.toContainText('private-source-token');
});

test('a source change cancels a pending handoff and late native replies cannot finish it', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await page.evaluate(() => { window.__receiver.holdMethod = 'kodi.open'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.evaluate(() => window.LumaApp.onSourceChanged());
  await expect(page.locator('#handoff-label')).toHaveText('The selected video changed. Choose it again in Browse.');
  await page.evaluate(() => window.__release());
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'app.handoffReady'))).toBe(false);
  await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeHidden();
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, url: 'https://media.example.org/second.m3u8', sourceToken: 'capture-two' }), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
});

test('capturing before setup waits for the receiver and session credentials, then starts once', async ({ page }) => {
  await openApp(page);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await page.evaluate(value => { window.__captureMedia = value; }, media);
  await page.getByRole('button', { name: 'Cast current video', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toHaveAttribute('aria-selected', 'true');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'luma.launchKodi'))).toBe(false);
  await setup(page);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.launchKodi').length)).toBe(1);
});

test('disconnect requires Kodi configuration again and Browse resumes unless the address form explicitly navigates', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await settingsDevice(page);
  await page.getByRole('button', { name: 'Disconnect projector', exact: true }).click();
  await expect(page.locator('#refresh-playback')).toBeDisabled();
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#kodi-panel')).toBeVisible();
  await expect(page.locator('#refresh-playback')).toBeDisabled();
  await page.getByLabel('Username', { exact: true }).fill('fixture-user');
  await page.getByLabel('Password', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect(page.locator('#refresh-playback')).toBeEnabled();
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  let calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'app.browse').at(-1).params).toEqual({});
  await page.getByLabel('Website address').fill('http://framextv.xyz/');
  await page.getByRole('button', { name: 'Open', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Check the entered details and try again.');
  await page.getByLabel('Website address').fill('https://framextv.xyz/');
  await page.getByRole('button', { name: 'Open', exact: true }).click();
  calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'app.browse').map(call => call.params)).toEqual([{}, { url: 'https://framextv.xyz/' }]);
  expect(calls.filter(call => call.method === 'kodi.configure')).toHaveLength(2);
});

test('the local interface loads without a native receiver or external network dependency', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  const requests = [];
  page.on('request', request => requests.push(request.url()));
  await page.route('**/*', route => new URL(route.request().url()).origin === appUrl ? route.continue() : route.abort());
  await page.goto(appUrl);
  await expect(page.locator('#message')).toHaveText('Open this page in the LUMA app to use the remote.');
  await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeDisabled();
  await page.getByRole('tab', { name: 'Remote', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeDisabled();
  await layout(page);
  expect(requests.every(url => new URL(url).origin === appUrl)).toBe(true);
});

test('bad Kodi authentication returns immediately to the unchanged form with a visible actionable error', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page);
  await page.getByLabel('Projector IP address').fill('192.168.1.50');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await page.getByLabel('Username', { exact: true }).fill('fixture-user');
  await page.getByLabel('Password', { exact: true }).fill('fixture-password');
  await page.evaluate(() => { window.__receiver.failAuth = true; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect(page.locator('#kodi-panel')).toBeVisible();
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.locator('#message')).toHaveText('Kodi did not accept the username or password. Check the Kodi connection details.');
  await expect(page.getByLabel('Username', { exact: true })).toHaveValue('fixture-user');
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('fixture-password');
  await expect(page.getByRole('button', { name: 'Use Kodi', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.connect').length)).toBe(1);
  await page.getByLabel('Password', { exact: true }).focus();
  await page.setViewportSize({ width: 360, height: 400 });
  await layout(page);
  expect(await page.locator('#message').evaluate(element => { const box = element.getBoundingClientRect(); return box.height >= 32 && box.top >= 0 && box.bottom <= innerHeight; })).toBe(true);
  await expect(page.locator('body')).not.toContainText('private-source-token');
  await page.evaluate(() => { window.__receiver.failAuth = false; });
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
});

test('Cancel revokes the native token before a queued video write and retry uses a fresh job token', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await page.evaluate(() => { window.__receiver.holdMethod = 'kodi.open'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.getByRole('button', { name: 'Cancel casting', exact: true }).click();
  await expect(page.locator('#handoff-label')).toHaveText('Casting was cancelled.');
  await expect.poll(() => page.evaluate(() => window.__calls.some(call => call.method === 'app.cancelHandoff' && call.params.sourceToken === 'job-1'))).toBe(true);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
  await page.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.status').length)).toBeGreaterThanOrEqual(3);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
  await page.evaluate(() => window.__release());
  expect(await page.evaluate(() => window.__receiver.active)).toBe(false);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'app.beginHandoff').map(call => call.params.sourceToken)).toEqual(['capture-one', 'capture-one']);
  expect(calls.filter(call => call.method === 'kodi.open').map(call => call.params.sourceToken)).toEqual(['job-1', 'job-2']);
  expect(calls.filter(call => call.method === 'kodi.open').at(-1).params.expectedSourceUrl).toBe(media.url);
  // The cancelled native reply explicitly proves its queued write was not sent.
  // A fresh job may proceed after that receipt; uncertain writes remain guarded.
  expect(calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
  expect(calls.filter(call => call.method === 'kodi.addSubtitle').every(call => call.params.sourceToken === 'job-2')).toBe(true);
});

function serviceSnapshot() {
  return {
    settings: { hasReceiver: true, hasKodi: true, autoConnect: true },
    connection: { connected: true, configured: true, host: '192.168.1.50', name: 'Restored LUMA' },
    volume: { current: 6, max: 30 },
    playback: { active: true, playing: true, sourceUrl: media.url, positionSeconds: 185, durationSeconds: 600, canSeek: true },
    remote: {
      media: { url: media.url, contentType: media.contentType, title: 'Restored household episode', sourceToken: 'old-job-must-not-be-reused' },
      tracks: [{ index: 0, language: 'en', label: 'English' }, { index: 1, language: 'pt-BR', label: 'Portuguese (Brazil)' }],
      selectedIndex: 1, subtitlesEnabled: true,
    },
  };
}

test('service restoration opens the existing remote and language aliases without reconnecting, credentials or replay', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  const snapshot = serviceSnapshot();
  snapshot.source = media;
  await openApp(page, snapshot);
  await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#media-title')).toHaveText('Restored household episode');
  await expect(page.locator('#playback-status')).toHaveText('Playing · 3:05 / 10:00');
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  await expect(page.locator('#kodi-username')).toHaveValue('');
  await expect(page.locator('#kodi-password')).toHaveValue('');
  expect(await page.evaluate(() => window.__calls.map(call => call.method))).toEqual(['app.ready']);
  await page.evaluate(() => window.LumaApp.onConnectionState({ volume: { current: 8, max: 30 } }));
  await expect(page.locator('#volume-level')).toHaveText('8 / 30');
  await layout(page);
  await page.evaluate(() => window.LumaApp.onSourceChanged());
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  const pause = await page.evaluate(() => window.__calls.find(call => call.method === 'kodi.pause'));
  expect(pause.params).toEqual({ expectedSourceUrl: media.url });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-list button')).toHaveText(['Portuguese (Brazil)', 'English']);
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => window.__calls.some(call => ['device.connect', 'kodi.configure', 'luma.launchKodi', 'kodi.open', 'app.beginHandoff'].includes(call.method)))).toBe(false);
  expect(await page.locator('body').innerText()).not.toContain('private-source-token');
});

test('resume events preserve text and browser drafts and cannot replace an in-flight captured handoff', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await cast(page);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await page.getByLabel('Website address').fill('https://framextv.xyz/search?q=household');
  await page.getByRole('tab', { name: 'Remote', exact: true }).click();
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Type text', exact: true }).click();
  await page.getByLabel('Text to send').fill('Keep this draft when I switch apps');
  const before = await page.evaluate(() => window.__calls.length);
  await page.evaluate(value => window.LumaApp.onConnectionState(value), serviceSnapshot());
  await expect(page.locator('#panel-typing')).toBeVisible();
  await expect(page.getByLabel('Text to send')).toHaveValue('Keep this draft when I switch apps');
  await expect(page.locator('#browser-url')).toHaveValue('https://framextv.xyz/search?q=household');
  expect(await page.evaluate(() => window.__calls.length)).toBe(before);
  await page.evaluate(() => { window.__receiver.holdMethod = 'kodi.open'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, url: 'https://media.example.org/next-episode.m3u8', title: 'Next household episode', sourceToken: 'capture-two' }), media);
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.evaluate(value => window.LumaApp.onConnectionState(value), serviceSnapshot());
  await expect(page.getByRole('button', { name: 'Cancel casting', exact: true })).toBeVisible();
  await expect(page.locator('#handoff-label')).toHaveText('Starting the selected video…');
  await page.evaluate(() => window.__release());
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  await expect(page.locator('#media-title')).toHaveText('Next household episode');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'device.connect')).toHaveLength(1);
  expect(calls.filter(call => call.method === 'kodi.open').map(call => call.params.media.url)).toEqual([media.url, 'https://media.example.org/next-episode.m3u8']);
});

test('restoration rejects aliases for another source and saved setup stays on Remote while disconnected', async ({ page }) => {
  const snapshot = serviceSnapshot();
  snapshot.playback.sourceUrl = 'https://media.example.org/different.m3u8';
  await openApp(page, snapshot);
  await expect(page.locator('#media-title')).toHaveText('Now playing');
  await expect(page.locator('#subtitle-list')).not.toContainText('Portuguese');
  expect(await page.evaluate(() => window.__calls.map(call => call.method))).toEqual(['app.ready']);
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: false, configured: false } }));
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeDisabled();
  const disconnected = serviceSnapshot();
  disconnected.connection.connected = false;
  disconnected.connection.configured = false;
  const disconnectedPage = await page.context().newPage();
  try {
    await openApp(disconnectedPage, disconnected);
    await expect(disconnectedPage.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
    await expect(disconnectedPage.getByRole('tab', { name: 'Devices', exact: true })).toBeHidden();
  } finally { await disconnectedPage.close(); }
});

test('receiver success survives failure of the optional service remember/local-pause acknowledgment', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await page.evaluate(() => { window.__receiver.failRemember = true; });
  await cast(page);
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.__receiver.active)).toBe(true);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'kodi.stop'))).toBe(false);
  await expect(page.locator('body')).not.toContainText('private-source-token');
});

test('opening the notification selects Remote while preserving drafts and active casting progress', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page);
  await setup(page);
  await cast(page);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await page.getByLabel('Website address').fill('https://framextv.xyz/search?q=continue');
  const before = await page.evaluate(() => window.__calls.length);
  await page.evaluate(() => window.LumaApp.onOpenRemote());
  await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#browser-url')).toHaveValue('https://framextv.xyz/search?q=continue');
  expect(await page.evaluate(() => window.__calls.length)).toBe(before);
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Type text', exact: true }).click();
  await page.getByLabel('Text to send').fill('Keep notification draft');
  await page.evaluate(() => window.LumaApp.onOpenRemote());
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.locator('#remote-text')).toHaveValue('Keep notification draft');
  await layout(page);
  await page.evaluate(() => { window.__receiver.holdMethod = 'kodi.open'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, url: 'https://media.example.org/notification-next.m3u8', title: 'Next episode', sourceToken: 'capture-next' }), media);
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.evaluate(() => window.LumaApp.onOpenRemote());
  await expect(page.locator('#panel-handoff')).toBeVisible();
  await expect(page.locator('#handoff-label')).toHaveText('Starting the selected video…');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'app.cancelHandoff'))).toBe(false);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  await expect(page.locator('#remote-text')).toHaveValue('Keep notification draft');
});

test('saved setup opens the remote while native reconnects and keeps configuration behind Settings', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page, { settings: { hasReceiver: true, hasKodi: true, autoConnect: true }, connection: { connected: false, configured: false } });
  await expect(page.locator('#message')).toHaveText('Connecting to your saved projector…');
  await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'false');
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toBeHidden();
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.__calls.map(call => call.method))).toEqual(['app.ready']);
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: true, configured: true, name: 'Saved LUMA' }, volume: { current: 6, max: 30 } }));
  await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'true');
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeEnabled();
  const corners = await page.evaluate(() => {
    const home = document.querySelector('[data-key="home"]').getBoundingClientRect();
    const back = document.querySelector('[data-key="back"]').getBoundingClientRect();
    const pad = document.querySelector('.dpad').getBoundingClientRect();
    return { homeLeft: home.left < back.left, sameRow: home.top === back.top, abovePad: home.bottom < pad.top, keys: document.querySelectorAll('.dpad button').length };
  });
  expect(corners).toEqual({ homeLeft: true, sameRow: true, abovePad: true, keys: 5 });
  await layout(page);
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await layout(page);
  await page.getByRole('button', { name: 'Kodi login', exact: true }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('Username', { exact: true })).toHaveValue('');
});

test('volume taps and slider show the pending value, stay usable and share one cooldown write', async ({ page }) => {
  await page.clock.install();
  await openApp(page, serviceSnapshot());
  const slider = page.getByRole('slider', { name: 'Projector volume level', exact: true });
  await expect(slider).toHaveAttribute('min', '0');
  await expect(slider).toHaveAttribute('max', '30');
  await expect(slider).toHaveValue('6');
  await slider.evaluate(element => { element.value = '12'; element.dispatchEvent(new Event('input', { bubbles: true })); });
  await expect(page.locator('#volume-level')).toHaveText('12 / 30');
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await expect(page.locator('#volume-level')).toHaveText('13 / 30');
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await expect(page.locator('#volume-level')).toHaveText('14 / 30');
  await page.getByRole('button', { name: 'Volume down', exact: true }).click();
  await expect(page.locator('#volume-level')).toHaveText('13 / 30');
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.__receiver.volumeWrites || [])).toEqual([]);
  await page.clock.runFor(2100);
  expect(await page.evaluate(() => window.__receiver.volumeWrites)).toEqual([13]);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.queueVolume').map(call => call.params))).toEqual([{ value: 12 }, { value: 13 }, { value: 14 }, { value: 13 }]);
  await page.evaluate(() => { window.__receiver.observedVolume = 6; });
  await slider.evaluate(element => { element.value = '7'; element.dispatchEvent(new Event('change', { bubbles: true })); });
  await expect(page.locator('#volume-level')).toHaveText('7 / 30');
  await page.clock.runFor(2100);
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  await expect(slider).toHaveValue('6');
  expect(await page.evaluate(() => window.__receiver.volumeWrites)).toEqual([13, 7]);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'luma.status'))).toBe(false);
  await page.evaluate(() => window.LumaApp.onConnectionState({ volume: { current: 30, max: 30 } }));
  await expect(page.getByRole('button', { name: 'Volume up', exact: true })).toBeDisabled();
});

test('pending volume does not jump on an old snapshot or late queue receipt and disconnect clears it', async ({ page }) => {
  await openApp(page, serviceSnapshot());
  await page.evaluate(() => { window.__receiver.holdMethod = 'luma.queueVolume'; });
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await expect(page.locator('#volume-level')).toHaveText('9 / 30');
  await page.evaluate(() => window.LumaApp.onConnectionState({ volume: { current: 6, max: 30 } }));
  await expect(page.locator('#volume-level')).toHaveText('9 / 30');
  await page.evaluate(() => window.__release());
  await expect(page.locator('#volume-level')).toHaveText('9 / 30');
  await page.evaluate(() => {
    window.__receiver.holdMethod = 'luma.queueVolume';
  });
  await page.getByRole('button', { name: 'Volume down', exact: true }).click();
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: false }, volume: { current: 9, max: 30 } }));
  await page.evaluate(() => window.__release());
  await expect(page.getByRole('button', { name: 'Volume up', exact: true })).toBeDisabled();
  await expect(page.locator('#volume-level')).toHaveText('9 / 30');
});

test('first setup authenticates after launch and resumes only the latest Cast without opening Kodi twice', async ({ page }) => {
  await openApp(page);
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await page.getByLabel('Projector IP address').fill('192.168.1.50');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await page.getByLabel('Username', { exact: true }).fill('fixture-user');
  await page.getByLabel('Password', { exact: true }).fill('fixture-password');
  await page.evaluate(() => { window.__receiver.holdMethod = 'kodi.connect'; });
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await expect(page.locator('#handoff-label')).toHaveText('Waiting for Kodi and checking its login…');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden();
  expect(await page.evaluate(() => window.__calls.filter(call => ['kodi.configure', 'luma.launchKodi', 'kodi.connect'].includes(call.method)).map(call => call.method))).toEqual(['kodi.configure', 'luma.launchKodi', 'kodi.connect']);
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, url: 'https://media.example.org/latest.m3u8', sourceToken: 'latest-capture', title: 'Latest episode' }), media);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'kodi.open').map(call => call.params.media.url)).toEqual(['https://media.example.org/latest.m3u8']);
  expect(calls.filter(call => call.method === 'luma.launchKodi')).toHaveLength(1);
  const diagnostics = calls.filter(call => call.method === 'app.handoffStatus').map(call => call.params);
  expect(diagnostics.map(item => item.stage)).toEqual(expect.arrayContaining(['captured', 'setup', 'launching', 'waiting', 'opening', 'ready']));
  expect(diagnostics.every(item => Object.keys(item).every(key => ['stage', 'code'].includes(key)) && !JSON.stringify(item).includes('http'))).toBe(true);
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toBeHidden();
});

test('configuration can rescan and forget saved settings without stopping receiver playback', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page, serviceSnapshot());
  await page.evaluate(() => { window.__receiver.rescanAutoConnect = true; });
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Find projector', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Projector connected. Your remote is ready.');
  await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#receiver-host')).toHaveValue('192.168.1.51');
  await expect(page.locator('#media-title')).toHaveText('Ready to cast');
  await page.evaluate(() => { window.__receiver.rescanAutoConnect = false; });
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Find projector', exact: true }).click();
  await expect(page.getByLabel('Projector IP address')).toHaveValue('192.168.1.51');
  await expect(page.locator('#message')).toHaveText('Projector found. Choose Connect.');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Forget saved setup', exact: true }).click();
  await layout(page);
  await page.getByRole('button', { name: 'Forget all saved settings', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden();
  await expect(page.getByLabel('Projector IP address')).toHaveValue('');
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.find(call => call.method === 'app.settings.reset').params).toEqual({ scope: 'all' });
  expect(calls.some(call => call.method === 'kodi.stop')).toBe(false);
  expect(await page.evaluate(() => window.__receiver.active)).toBe(true);
  await layout(page);
});

test('a local save failure keeps the setup draft and does not claim configuration succeeded', async ({ page }) => {
  await openApp(page);
  await page.getByLabel('Projector IP address').fill('192.168.1.50');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await page.getByLabel('Username', { exact: true }).fill('fixture-user');
  await page.getByLabel('Password', { exact: true }).fill('fixture-password');
  await page.evaluate(() => { window.__receiver.failStorage = true; });
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Settings could not be saved on this phone. Try setup again.');
  await expect(page.locator('#kodi-panel')).toBeVisible();
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('fixture-password');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden();
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'kodi.open'))).toBe(false);
});

test('canceling setup waiting prevents a queued Cast even if native validation finishes later', async ({ page }) => {
  await openApp(page);
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await page.getByLabel('Projector IP address').fill('192.168.1.50');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await page.getByLabel('Username', { exact: true }).fill('fixture-user');
  await page.getByLabel('Password', { exact: true }).fill('fixture-password');
  await page.evaluate(() => { window.__receiver.holdMethod = 'kodi.connect'; });
  await page.getByRole('button', { name: 'Use Kodi', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.getByRole('button', { name: 'Cancel waiting', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Checking stopped. Validated settings may already be saved. No video was sent.');
  await page.evaluate(() => { window.__release(); window.LumaApp.onConnectionState({ settings: { hasReceiver: true, hasKodi: true }, connection: { connected: true, configured: true } }); });
  await page.getByRole('tab', { name: 'Remote', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh volume', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Volume refreshed.');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'kodi.open' || call.method === 'app.beginHandoff'))).toBe(false);
});

test('languages load on demand with observed indices and already loaded choices do not add again', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page); await setup(page); await cast(page);
  await page.evaluate(() => { window.LumaApp.onSourceChanged(); window.__receiver.tracks.push({}); window.__receiver.subtitleDelayReads = 2; });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-list button')).toHaveText(['Portuguese (Brazil)', 'English', 'Portuguese']);
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  let calls = await page.evaluate(() => window.__calls);
  const lazyAdd = calls.filter(call => call.method === 'kodi.addSubtitle').at(-1);
  expect(lazyAdd.params.expectedSourceUrl).toBe(media.url);
  expect(lazyAdd.params.sourceToken).toBeUndefined();
  expect(calls.filter(call => call.method === 'kodi.setSubtitle').at(-1).params).toEqual({ expectedSourceUrl: media.url, index: 1 });
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'kodi.addSubtitle')).toHaveLength(2);
  expect(calls.filter(call => call.method === 'kodi.open')).toHaveLength(1);
  const remembered = calls.filter(call => call.method === 'app.rememberRemote').at(-1).params;
  expect(remembered.expectedSourceUrl).toBe(media.url);
  expect(remembered.remote.catalog).toHaveLength(media.tracks.length);
  expect(remembered.remote.catalog.find(track => track.language === 'en').index).toBe(1);
  expect(remembered.remote.catalog.find(track => track.language === 'ja').index).toBeNull();
  await layout(page);
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
});

test('service restoration retains the complete lazy catalog and loads a new language without replay', async ({ page }) => {
  const snapshot = serviceSnapshot();
  snapshot.remote.tracks = [{ index: 0, language: 'pt-BR', label: 'Portuguese (Brazil)' }];
  snapshot.remote.catalog = media.tracks.map(track => ({ url: track.url, language: track.language, label: track.label, index: track.language === 'pt-BR' ? 0 : null }));
  snapshot.remote.selectedIndex = 0;
  await openApp(page, snapshot);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByLabel('Find a language').fill('Japanese');
  await page.getByRole('button', { name: 'Japanese', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Japanese', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'kodi.addSubtitle').map(call => call.params.track.language)).toEqual(['ja']);
  expect(calls.some(call => ['luma.launchKodi', 'kodi.open', 'app.beginHandoff', 'device.connect'].includes(call.method))).toBe(false);
  expect(calls.filter(call => call.method.startsWith('kodi.')).every(call => call.params.expectedSourceUrl === media.url && call.params.sourceToken === undefined)).toBe(true);
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
});

test('a rejected on-demand subtitle leaves the current language and catalog usable and auth recovery never replays', async ({ page }) => {
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.evaluate(() => { window.__receiver.failSubtitle = true; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'English', exact: true })).toBeEnabled();
  await expect(page.locator('#sheet-message')).toHaveAttribute('data-error', 'true');
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'kodi.open')).toHaveLength(1);
  expect(await page.evaluate(() => ({ selected: window.__receiver.currentIndex, playing: window.__receiver.playing }))).toEqual({ selected: 0, playing: true });
  await page.evaluate(() => { window.__receiver.failSubtitle = false; window.__receiver.failSubtitleAuth = true; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.locator('#kodi-panel')).toBeVisible();
  await expect(page.locator('#message')).toHaveText('Kodi did not accept the username or password. Check the Kodi connection details.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
});

test('an uncertain subtitle Add waits within its bound then releases controls without resending or replaying', async ({ page }) => {
  await page.clock.install();
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now() + 1000)));
  await page.evaluate(() => { window.__receiver.failSubtitle = 'KODI_RESPONSE'; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.addSubtitle').length)).toBe(2);
  await page.clock.runFor(1000);
  await expect(page.getByRole('button', { name: 'English', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.clock.fastForward(31000);
  await expect(page.getByRole('button', { name: 'English', exact: true })).toBeEnabled();
  await expect(page.locator('#sheet-message')).toHaveText('Kodi did not register a subtitle in time.');
  await expect(page.locator('#sheet-message')).toHaveAttribute('data-error', 'true');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'kodi.addSubtitle')).toHaveLength(2);
  expect(calls.filter(call => call.method === 'kodi.open')).toHaveLength(1);
  expect(calls.filter(call => call.method === 'app.rememberRemote')).toHaveLength(1);
  expect(await page.evaluate(() => ({ selected: window.__receiver.currentIndex, playing: window.__receiver.playing }))).toEqual({ selected: 0, playing: true });
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
});

for (const rejectedMethod of ['kodi.open']) {
  test(`a verified ${rejectedMethod} rejection reports promptly and retries only after Try again`, async ({ page }) => {
    await openApp(page); await setup(page);
    await page.evaluate(method => {
      window.__receiver[method === 'kodi.open' ? 'failOpen' : 'failSubtitle'] = 'KODI_REJECTED';
    }, rejectedMethod);
    await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
    await expect(page.locator('#handoff-label')).toHaveText('Kodi rejected this request before it could be applied. Try casting again.');
    await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeEnabled();
    let calls = await page.evaluate(() => window.__calls);
    expect(calls.filter(call => call.method === rejectedMethod)).toHaveLength(1);
    expect(calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(0);
    expect(await page.evaluate(() => window.__receiver.tracks.length)).toBe(0);
    await page.evaluate(() => { window.__receiver.failOpen = null; window.__receiver.failSubtitle = false; });
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
    calls = await page.evaluate(() => window.__calls);
    expect(calls.filter(call => call.method === rejectedMethod)).toHaveLength(2);
    expect(calls.filter(call => call.method === 'kodi.open')).toHaveLength(rejectedMethod === 'kodi.open' ? 2 : 1);
    expect(calls.filter(call => call.method === 'kodi.addSubtitle')).toHaveLength(0);
    expect(calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
    const attempts = calls.filter(call => call.method === rejectedMethod);
    expect(attempts[0].params.sourceToken).not.toBe(attempts[1].params.sourceToken);
    expect(await page.evaluate(() => ({ tracks: window.__receiver.tracks.length, selected: window.__receiver.currentIndex, playing: window.__receiver.playing }))).toEqual({ tracks: 0, selected: null, playing: true });
    await expect(page.locator('body')).not.toContainText('private-source-token');
    await expect(page.locator('body')).not.toContainText('private-subtitle-token');
  });
}

test('playing video finishes casting without subtitle work while the Open response is still outstanding', async ({ page }) => {
  await openApp(page); await setup(page);
  await page.evaluate(() => { window.__receiver.holdOpenResponse = true; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeEnabled();
  const before = await page.evaluate(() => ({ calls: window.__calls, pending: Boolean(window.__receiver.openResponse), selected: window.__receiver.currentIndex }));
  expect(before.pending).toBe(true);
  expect(before.selected).toBe(null);
  expect(before.calls.filter(call => call.method === 'kodi.open')).toHaveLength(1);
  expect(before.calls.filter(call => call.method === 'kodi.addSubtitle')).toHaveLength(0);
  expect(before.calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
  expect(before.calls.filter(call => call.method === 'app.cancelHandoff')).toHaveLength(0);
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  await page.evaluate(() => window.__releaseOpenResponse());
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
});

test('subtitle receiver failures cannot block startup and the full catalog remains available afterward', async ({ page }) => {
  await openApp(page); await setup(page);
  await page.evaluate(() => { window.__receiver.failSubtitle = 'KODI_REJECTED'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeEnabled();
  const remote = await page.evaluate(() => window.__receiver.rememberedRemote);
  expect(remote.catalog).toHaveLength(media.tracks.length);
  expect(remote.selectedIndex).toBe(null);
  expect(remote.subtitlesEnabled).toBe(false);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'app.cancelHandoff'))).toBe(false);
  await page.evaluate(() => { window.__receiver.failSubtitle = false; });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
  expect(await page.evaluate(() => window.__receiver.playing)).toBe(true);
  await expect(page.locator('body')).not.toContainText('private-source-token');
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
});

test('a late native receipt after bridge timeout permits an explicit recast without overlapping Open requests', async ({ page }) => {
  await page.clock.install();
  await openApp(page); await setup(page);
  await page.evaluate(() => { window.__receiver.holdOpenResponse = true; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  await page.clock.fastForward(31000);
  await page.evaluate(value => {
    window.__receiver.active = false;
    window.__receiver.sourceUrl = null;
    window.LumaApp.onCapturedMedia({ ...value, sourceToken: 'capture-two' });
  }, media);
  await page.clock.runFor(1000);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
  await page.evaluate(() => window.__releaseOpenResponse('KODI_UNAVAILABLE'));
  await page.clock.runFor(1000);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'kodi.open').map(call => call.params.sourceToken)).toEqual(['job-1', 'job-2']);
  expect(await page.evaluate(() => window.__receiver.playing)).toBe(true);
});

test('missing Brazilian Portuguese starts immediately and lets the user choose English afterward', async ({ page }) => {
  await openApp(page); await setup(page);
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, tracks: value.tracks.filter(track => track.language !== 'pt-BR') }), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => ['kodi.addSubtitle', 'kodi.getSubtitles', 'kodi.setSubtitle'].includes(call.method))).toHaveLength(0);
  const remote = calls.find(call => call.method === 'app.handoffReady').params.remote;
  expect(remote.catalog).toHaveLength(5);
  expect(remote.selectedIndex).toBe(null);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
});

test('projector power requires confirmation, defaults to Cancel and dismisses without commands', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page);
  await page.getByRole('tab', { name: 'Remote', exact: true }).click();
  const power = page.getByRole('button', { name: 'Power off projector', exact: true });
  await expect(power).toBeDisabled();
  await layout(page);
  await page.getByRole('tab', { name: 'Devices', exact: true }).click();
  await setup(page);
  const snapshot = serviceSnapshot();
  snapshot.remote.media.title = 'Restored household episode — a very long episode title for a small phone';
  await page.evaluate(value => window.LumaApp.onConnectionState(value), snapshot);
  await expect(page.locator('#media-title')).toHaveText(snapshot.remote.media.title);
  await expect(power).toBeEnabled();
  await layout(page);
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Back', exact: true })).toBeVisible();
  for (const dismiss of ['cancel', 'escape', 'backdrop']) {
    await power.click();
    await expect(page.getByRole('dialog', { name: 'Turn off the projector?' })).toBeVisible();
    await expect(page.locator('#panel-power')).toContainText('This will interrupt playback.');
    await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    await layout(page);
    if (dismiss === 'cancel') await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    else if (dismiss === 'escape') await page.keyboard.press('Escape');
    else await page.locator('#power-backdrop').click({ position: { x: 1, y: 1 } });
    await expect(page.locator('#sheet')).toBeHidden();
    await expect(power).toBeFocused();
  }
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.powerOff'))).toHaveLength(0);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.key'))).toHaveLength(0);
});

test('manual update check is a separate Settings action and fits a small phone', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page); await setup(page);
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await layout(page);
  await page.getByRole('button', { name: 'Check for updates', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Update check requested.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'app.checkUpdates').map(call => call.params))).toEqual([{}]);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.powerOff'))).toHaveLength(0);
  await layout(page);
});

test('Turn off sends exactly one confirmed power request and disables on connection loss', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page); await setup(page);
  const power = page.getByRole('button', { name: 'Power off projector', exact: true });
  await page.evaluate(() => { window.__receiver.holdMethod = 'luma.powerOff'; });
  await power.click();
  const confirm = page.getByRole('button', { name: 'Turn off', exact: true });
  await confirm.click();
  await expect(confirm).toBeDisabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.powerOff').map(call => call.params))).toEqual([{ confirmed: true }]);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.locator('#message')).toHaveText('Power command sent.');
  await power.click();
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: false, configured: false } }));
  await expect(confirm).toBeDisabled();
  await expect(power).toBeDisabled();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await layout(page);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.powerOff'))).toHaveLength(1);
});


test('invalid subtitle bytes never reach Kodi and another language remains usable without replay', async ({ page }) => {
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.evaluate(() => { window.__receiver.failPrepare = 'SUBTITLE_INVALID'; });
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.locator('#sheet-message')).toHaveText('This subtitle file has no valid timed captions. Choose another subtitle.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.addSubtitle').length)).toBe(0);
  await page.evaluate(() => { window.__receiver.failPrepare = null; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const calls = await page.evaluate(() => window.__calls);
  const add = calls.find(call => call.method === 'kodi.addSubtitle');
  expect(add.params.track.url).toBe(media.tracks[0].url);
  expect(add.params.preparedId).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
  const saved = calls.filter(call => call.method === 'app.rememberRemote').at(-1).params.remote.catalog.find(track => track.language === 'en');
  expect(saved.url).toBe(media.tracks[0].url);
  expect(saved.preparedId).toBe(add.params.preparedId);
  expect(calls.filter(call => call.method === 'kodi.open')).toHaveLength(1);
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
});

test('subtitle selection waits for complete normalized file delivery after the Add acknowledgement', async ({ page }) => {
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.evaluate(() => { window.__receiver.holdDelivery = true; });
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.subtitleDelivery').length)).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'app.rememberRemote').length)).toBe(0);
  await page.evaluate(() => { window.__receiver.holdDelivery = false; });
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.addSubtitle').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'kodi.open').length)).toBe(1);
});
