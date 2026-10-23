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
    window.__receiver = { active: false, playing: true, sourceUrl: null, content: null, tracks: [], enabled: false, currentIndex: null, position: 12, volume: 6, failText: false, holdMethod: null, held: [], job: 0, previewDelays: {}, running: true, installed: true, pairCode: '123456', pairings: 0, paired: false, subtitleScale: 1 };
    const receiver = window.__receiver;
    if (initialSnapshot?.playback) {
      receiver.active = initialSnapshot.playback.active === true;
      receiver.playing = initialSnapshot.playback.playing !== false;
      receiver.sourceUrl = initialSnapshot.playback.sourceUrl || null;
      receiver.content = initialSnapshot.playback.content || null;
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
        case 'app.settings.reset': return { connection: { connected: params.scope === 'pairing', configured: false }, settings: { hasReceiver: params.scope === 'pairing', hasPairing: params.scope === 'receiver', autoConnect: true } };
        case 'app.beginHandoff': receiver.activeJob = `job-${++receiver.job}`; return { sourceToken: receiver.activeJob };
        case 'app.chooseCatalog': return window.__catalogMedia || null;
        case 'app.cancelHandoff': if (params.sourceToken === receiver.activeJob) receiver.activeJob = null; return { accepted: true };
        case 'app.capture': if (window.__captureMedia) window.LumaApp.onCapturedMedia(window.__captureMedia); return { accepted: true };
        case 'device.discover': return { devices: [{ host: '192.168.1.50', name: 'Test LUMA', port: 8121 }] };
        case 'device.connect': return { connected: true, device: { name: 'Test LUMA' } };
        case 'device.disconnect': return { connected: false };
        case 'receiver.pairBegin': return { pairingId: `pairing-${++receiver.pairings}`, expiresInSeconds: 120 };
        case 'receiver.pairComplete': receiver.paired = true; return { paired: true, name: 'LUMA Receiver' };
        case 'receiver.display': if (params.subtitleScale !== undefined) receiver.subtitleScale = params.subtitleScale; return { subtitleScale: receiver.subtitleScale };
        case 'receiver.scrub': return { accepted: true };
        case 'luma.launchReceiver': receiver.running = true; return { accepted: true };
        case 'luma.key': case 'luma.launchApp': return { accepted: true };
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
        case 'receiver.connect': return { ready: true, name: 'LUMA Receiver', version: '1.0.0', receiverId: 'receiver-fixture' };
        case 'receiver.open': receiver.active = true; receiver.playing = true; receiver.sourceUrl = params.media.url; receiver.content = params.media.content || null; receiver.tracks = []; receiver.enabled = false; receiver.currentIndex = null; return { accepted: true };
        case 'receiver.pause': receiver.playing = false; return { accepted: true };
        case 'receiver.resume': receiver.playing = true; return { accepted: true };
        case 'receiver.status': return { active: receiver.active, playing: receiver.playing, sourceUrl: receiver.sourceUrl, positionSeconds: receiver.position, durationSeconds: 600, canSeek: true, ...(receiver.content ? {content: receiver.content} : {}), ...(receiver.stale ? {stale:true} : {}) };
        case 'receiver.getSubtitles':
          if (receiver.pendingTrack && --receiver.subtitleDelayReads <= 0) { receiver.tracks.push(receiver.pendingTrack); receiver.pendingTrack = null; }
          return { tracks: receiver.tracks.map((track, index) => ({ index, name: 'External' })), enabled: receiver.enabled, currentIndex: receiver.currentIndex };
        case 'receiver.prepareSubtitle': return { preparedId: 'prepared_' + btoa(params.track.url).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 95), format: 'srt', encoding: 'UTF-8', cueCount: 10 };
        case 'receiver.subtitleDelivery': return { downloaded: !receiver.holdDelivery };
        case 'receiver.addSubtitle': if (receiver.subtitleDelayReads > 0) receiver.pendingTrack = params.track; else receiver.tracks.push(params.track); return { accepted: true };
        case 'receiver.setSubtitle': receiver.enabled = params.index !== 'off'; receiver.currentIndex = receiver.enabled ? params.index : null; return { accepted: true };
        case 'receiver.seek': if(!receiver.seekNoEffect) receiver.position = params.position; if(receiver.staleAfterSeek) receiver.stale=true; if(receiver.contentAfterSeek) receiver.content=receiver.contentAfterSeek; return { accepted: true };
        case 'receiver.stop': receiver.active = false; return { active: false };
        case 'preview.frame': return { available: true, dataUri: receiver.previewDataUri || 'data:image/webp;base64,UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/vuUAAA=', positionSeconds: params.position };
        default: throw new Error('Unexpected fixture method');
      }
    };
    const respond = request => {
      if ((request.method.startsWith('receiver.') || request.method === 'luma.launchReceiver') && request.params.sourceToken && request.params.sourceToken !== receiver.activeJob) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'SOURCE_CHANGED', message: 'stale job' } });
        return;
      }
      if (['receiver.pairBegin', 'receiver.connect'].includes(request.method) && !receiver.running) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'RECEIVER_UNAVAILABLE', message: 'receiver offline' } });
        return;
      }
      if (request.method === 'luma.launchReceiver' && !receiver.installed) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'APP_NOT_AVAILABLE', message: 'not installed' } });
        return;
      }
      if (request.method === 'receiver.pairComplete' && (receiver.pairExpired || request.params.code !== receiver.pairCode)) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: receiver.pairExpired ? 'PAIRING_EXPIRED' : 'PAIRING_CODE_WRONG', message: 'private-pairing-token' } });
        return;
      }
      if (request.method === 'receiver.prepareSubtitle' && receiver.failPrepare) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: receiver.failPrepare, message: 'private-subtitle-token' } });
        return;
      }
      if (request.method === 'receiver.addSubtitle' && receiver.failSubtitle) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: typeof receiver.failSubtitle === 'string' ? receiver.failSubtitle : 'INVALID_SUBTITLE', message: 'private-subtitle-token' } });
        return;
      }
      if (request.method === 'receiver.open' && receiver.failOpen) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: receiver.failOpen, message: 'private-source-token' } });
        return;
      }
      if (request.method === 'receiver.getSubtitles' && receiver.failSubtitleAuth) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'RECEIVER_AUTH', message: 'private-pairing-token private-subtitle-token' } });
        return;
      }
      if (['receiver.addSubtitle', 'receiver.setSubtitle', 'receiver.getSubtitles'].includes(request.method) && request.params.expectedSourceUrl && request.params.expectedSourceUrl !== receiver.sourceUrl) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'SOURCE_CHANGED', message: 'source changed' } });
        return;
      }
      if (request.method === 'receiver.connect' && receiver.failAuth) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'RECEIVER_AUTH', message: 'private-pairing-token private-source-token' } });
        return;
      }
      if (request.method === 'receiver.connect' && receiver.failStorage) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'STORAGE_UNAVAILABLE', message: 'private-pairing-token' } });
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
      if (request.method === 'preview.frame' && receiver.previewUnsupported) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'PREVIEW_UNSUPPORTED', message: 'Preview unavailable' } });
        return;
      }
      const response = { id: request.id, ok: true, result: result(request) };
      if (request.method === 'receiver.open' && receiver.holdOpenResponse) {
        receiver.openResponse = response;
        return;
      }
      const previewDelay = request.method === 'preview.frame' ? receiver.previewDelays[String(request.params.position)] || 0 : 0;
      if (previewDelay) { setTimeout(() => window.LumaRPC.receive(response), previewDelay); return; }
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
  else if (initialSnapshot?.settings?.hasReceiver && initialSnapshot?.settings?.hasPairing) await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  else await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeEnabled();
  return requests;
}

async function connectProjector(page) {
  await page.getByLabel('Projector IP address').fill('192.168.1.50');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#pair-panel')).toBeVisible();
}
async function requestCode(page) {
  await page.getByRole('button', { name: 'Show code on projector', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Enter the 6-digit code shown on the projector.');
  await expect(page.getByLabel('Code on the projector', { exact: true })).toBeVisible();
}
async function pair(page, code = '123456') {
  await requestCode(page);
  await page.getByLabel('Code on the projector', { exact: true }).fill(code);
  await page.getByRole('button', { name: 'Pair', exact: true }).click();
}
async function setup(page) {
  await connectProjector(page);
  await pair(page);
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
  await settingsDevice(page, 'Receiver pairing');
  await layout(page);
  await requestCode(page);
  await page.getByLabel('Code on the projector', { exact: true }).fill('12');
  await page.getByLabel('Code on the projector', { exact: true }).focus();
  await page.setViewportSize({ width: 360, height: 400 });
  await layout(page);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
  await page.reload();
  await page.getByRole('button', { name: '2 · Pair receiver', exact: true }).click();
  await expect(page.locator('#pair-form')).toBeHidden();
  await expect(page.locator('#pair-code')).toHaveValue('');
});

test('native saved content resumes LUMA Receiver after playback proof and persists metadata before history binding', async ({page}) => {
  await openApp(page); await setup(page);
  const content={key:'framextv:movie:42',kind:'movie',contentId:'42',season:null,episode:null,watchUrl:'https://framextv.cc/watch/42',title:'Household movie'};
  await page.evaluate(({media,content})=>window.LumaApp.onCapturedMedia({...media,content,resumeSeconds:125}),{media,content});
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  await expect(page.locator('#playback-status')).toContainText('2:05');
  const calls=await page.evaluate(()=>window.__calls);
  expect(calls.filter(call=>call.method==='receiver.open')).toHaveLength(1);
  expect(calls.filter(call=>call.method==='receiver.seek').map(call=>call.params)).toEqual([{position:125,expectedSourceUrl:media.url,expectedContentKey:content.key}]);
  const ready=calls.find(call=>call.method==='app.handoffReady');
  expect(ready.params.resumeApplied).toBe(true);
  expect(ready.params.remote.media).toMatchObject({content,resumeSeconds:125});
  expect(calls.findIndex(call=>call.method==='receiver.seek')).toBeLessThan(calls.indexOf(ready));
});

test('unverified receiver resume remains playing without Open replay or history binding', async ({page}) => {
  await page.clock.install(); await openApp(page); await setup(page);
  await page.evaluate(media=>{window.__receiver.seekNoEffect=true;window.LumaApp.onCapturedMedia({...media,resumeSeconds:125});},media);
  await expect.poll(()=>page.evaluate(()=>window.__calls.some(call=>call.method==='receiver.seek'))).toBe(true);
  await page.clock.runFor(1100);
  await expect(page.locator('#message')).toContainText('saved position could not be restored');
  expect(await page.evaluate(()=>window.__calls.filter(call=>call.method==='receiver.open').length)).toBe(1);
  expect(await page.evaluate(()=>window.__calls.find(call=>call.method==='app.handoffReady').params.resumeApplied)).toBe(false);
  expect(await page.evaluate(()=>window.__receiver.active)).toBe(true);
});

test('stale receiver state near the requested position cannot confirm resume', async ({page}) => {
  await page.clock.install(); await openApp(page); await setup(page);
  await page.evaluate(media=>{window.__receiver.staleAfterSeek=true;window.LumaApp.onCapturedMedia({...media,resumeSeconds:125});},media);
  await expect.poll(()=>page.evaluate(()=>window.__calls.some(call=>call.method==='receiver.seek'))).toBe(true);
  await page.clock.runFor(1100);
  await expect(page.locator('#message')).toContainText('saved position could not be restored');
  expect(await page.evaluate(()=>window.__receiver.position)).toBe(125);
  expect(await page.evaluate(()=>window.__calls.find(call=>call.method==='app.handoffReady').params.resumeApplied)).toBe(false);
});

test('same-URL canonical replacement cannot confirm or remember a stale resume', async ({page}) => {
  await page.clock.install(); await openApp(page); await setup(page);
  const selected={key:'framextv:tv:42:1:2',kind:'tv',contentId:'42',season:1,episode:2,watchUrl:'https://framextv.cc/watch/42/1/2',title:'Household series · S1 E2'};
  const replacement={key:'framextv:tv:42:1:3',kind:'tv',contentId:'42',season:1,episode:3,watchUrl:'https://framextv.cc/watch/42/1/3',title:'Household series · S1 E3'};
  await page.evaluate(({media,selected,replacement})=>{
    window.__receiver.contentAfterSeek=replacement;
    window.LumaApp.onCapturedMedia({...media,content:selected,resumeSeconds:125});
  },{media,selected,replacement});
  await expect.poll(()=>page.evaluate(()=>window.__calls.some(call=>call.method==='receiver.seek'))).toBe(true);
  await page.clock.runFor(1100);
  await expect.poll(()=>page.evaluate(()=>window.__calls.filter(call=>call.method==='receiver.status'&&call.params.expectedContentKey).length)).toBeGreaterThan(0);
  const calls=await page.evaluate(()=>window.__calls);
  expect(calls.filter(call=>call.method==='receiver.status'&&call.params.expectedSourceUrl===media.url).every(call=>call.params.expectedContentKey===selected.key)).toBe(true);
  expect(calls.filter(call=>call.method==='app.handoffReady')).toHaveLength(0);
  await expect(page.locator('#message')).toContainText('selected video changed');
});

test('captured video starts without subtitle commands and keeps every language for manual selection without exposing sources', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  const requests = await openApp(page);
  await setup(page);
  await page.evaluate(() => { window.__receiver.running = false; });
  await cast(page);
  await expect(page.locator('#media-title')).toHaveText('Household episode');
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  const calls = await page.evaluate(() => window.__calls);
  const methods = calls.map(call => call.method);
  const castMethods = methods.slice(methods.indexOf('app.beginHandoff')).filter(method => ['receiver.connect', 'luma.launchReceiver', 'receiver.open'].includes(method));
  expect(castMethods).toEqual(['receiver.connect', 'luma.launchReceiver', 'receiver.connect', 'receiver.open']);
  expect(calls.filter(call => ['receiver.addSubtitle', 'receiver.setSubtitle', 'receiver.getSubtitles'].includes(call.method))).toEqual([]);
  expect(calls.filter(call => ['receiver.open', 'receiver.addSubtitle', 'receiver.setSubtitle'].includes(call.method)).every(call => call.params.sourceToken === 'job-1' && call.params.expectedSourceUrl === media.url)).toBe(true);
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
  await page.getByLabel('Playback position').evaluate(element => { element.value = '1'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.seek'))).toBe(false);
  await page.getByLabel('Playback position').evaluate(element => element.dispatchEvent(new Event('change', {bubbles:true})));
  const calls = await page.evaluate(() => window.__calls);
  const pause = calls.find(call => call.method === 'receiver.pause');
  expect(pause.params).toEqual({ expectedSourceUrl: media.url });
  expect(calls[calls.indexOf(pause) + 1].method).toBe('receiver.status');
  expect(calls.find(call => call.method === 'app.browse').params).toEqual({});
  expect(calls.filter(call => call.method === 'luma.key' && call.params.key === 'enter')).toHaveLength(0);
  expect(calls.find(call => call.method === 'receiver.seek').params).toEqual({ expectedSourceUrl: media.url, position: 1 });
  expect(calls.filter(call => call.method === 'receiver.setSubtitle').at(-1).params).toEqual({ expectedSourceUrl: media.url, index: 0 });
});

test('main remote exposes back 30, scrub and catalog-proven next episode controls', async ({ page }) => {
  const content = {key:'framextv:tv:284833:1:1',kind:'tv',contentId:'284833',season:1,episode:1,watchUrl:'https://framextv.cc/watch/284833/1/1',title:'Household episode'};
  const nextMedia = {...media,url:'https://media.example.org/next.m3u8',sourceToken:'capture-next',content:{...content,key:'framextv:tv:284833:1:2',episode:2,watchUrl:'https://framextv.cc/watch/284833/1/2'}};
  await openApp(page, {
    connection:{connected:true,configured:true,host:'192.168.1.50',name:'Test LUMA'}, settings:{hasReceiver:true,hasPairing:true,autoConnect:true},
    playback:{active:true,playing:true,sourceUrl:media.url,content,positionSeconds:75,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false,nextEpisode:{kind:'tv',id:'284833',season:1,episode:2}},
  });
  await page.evaluate(value => { window.__catalogMedia = value; }, nextMedia);
  await expect(page.getByRole('button', {name:'Back 30 seconds'})).toBeEnabled();
  await expect(page.getByRole('button', {name:'Next episode',exact:true})).toBeEnabled();
  await page.getByRole('button', {name:'Back 30 seconds'}).click();
  await expect.poll(() => page.evaluate(() => window.__calls.find(call => call.method === 'receiver.seek')?.params.position)).toBe(45);
  await page.getByRole('button', {name:'Next episode',exact:true}).click();
  await expect.poll(() => page.evaluate(() => window.__calls.find(call => call.method === 'app.chooseCatalog')?.params)).toEqual({kind:'tv',id:'284833',season:1,episode:2,resume:true});
});

test('same-URL canonical transition hides stale controls until exact metadata arrives', async ({ page }) => {
  const first = {key:'framextv:tv:284833:1:1',kind:'tv',contentId:'284833',season:1,episode:1,watchUrl:'https://framextv.cc/watch/284833/1/1',title:'Episode one'};
  const second = {...first,key:'framextv:tv:284833:1:2',episode:2,watchUrl:'https://framextv.cc/watch/284833/1/2',title:'Episode two'};
  const connection = {connected:true,configured:true,host:'192.168.1.50',name:'Test LUMA'};
  await openApp(page, {
    connection, settings:{hasReceiver:true,hasPairing:true,autoConnect:true},
    playback:{active:true,playing:true,sourceUrl:media.url,content:first,positionSeconds:75,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content:first},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false,nextEpisode:{kind:'tv',id:'284833',season:1,episode:2}},
  });
  await page.evaluate(({connection,media,first}) => window.LumaApp.onConnectionState({
    connection, playback:{active:true,playing:true,sourceUrl:media.url,positionSeconds:2,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content:first},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false,nextEpisode:{kind:'tv',id:'284833',season:1,episode:2}},
  }), {connection,media,first});
  await expect(page.locator('#media-title')).toHaveText('Now playing');
  await expect(page.getByRole('button', {name:'Back 30 seconds'})).toBeDisabled();
  await expect(page.getByRole('button', {name:'Next episode',exact:true})).toBeDisabled();
  await expect(page.getByLabel('Playback position')).toBeDisabled();

  await page.evaluate(({connection,media,second}) => window.LumaApp.onConnectionState({
    connection, playback:{active:true,playing:true,sourceUrl:media.url,content:second,positionSeconds:61,durationSeconds:600,canSeek:true},
    remote:{media:{...media,title:'Episode two',content:second},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false,nextEpisode:{kind:'tv',id:'284833',season:1,episode:3}},
  }), {connection,media,second});
  await expect(page.locator('#media-title')).toHaveText('Episode two');
  await expect(page.getByRole('button', {name:'Back 30 seconds'})).toBeEnabled();
  await expect(page.getByRole('button', {name:'Next episode',exact:true})).toBeEnabled();
});

test('late next-episode catalog result cannot replace a newer same-URL canonical item', async ({ page }) => {
  const first = {key:'framextv:tv:284833:1:1',kind:'tv',contentId:'284833',season:1,episode:1,watchUrl:'https://framextv.cc/watch/284833/1/1',title:'Episode one'};
  const second = {...first,key:'framextv:tv:284833:1:2',episode:2,watchUrl:'https://framextv.cc/watch/284833/1/2',title:'Episode two'};
  const connection = {connected:true,configured:true,host:'192.168.1.50',name:'Test LUMA'};
  const nextMedia = {...media,url:'https://media.example.org/next.m3u8',sourceToken:'capture-next',content:second};
  await openApp(page, {
    connection, settings:{hasReceiver:true,hasPairing:true,autoConnect:true},
    playback:{active:true,playing:true,sourceUrl:media.url,content:first,positionSeconds:75,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content:first},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false,nextEpisode:{kind:'tv',id:'284833',season:1,episode:2}},
  });
  await page.evaluate(value => { window.__catalogMedia = value; window.__receiver.holdMethod = 'app.chooseCatalog'; }, nextMedia);
  await page.getByRole('button', {name:'Next episode',exact:true}).click();
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.evaluate(({connection,media,second}) => window.LumaApp.onConnectionState({
    connection, playback:{active:true,playing:true,sourceUrl:media.url,content:second,positionSeconds:5,durationSeconds:600,canSeek:true},
    remote:{media:{...media,title:'Episode two',content:second},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false,nextEpisode:{kind:'tv',id:'284833',season:1,episode:3}},
  }), {connection,media,second});
  await page.evaluate(() => window.__release());
  await expect(page.locator('#message')).toContainText('selected video changed');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.open' || call.method === 'app.beginHandoff'))).toBe(false);
  await expect(page.locator('#media-title')).toHaveText('Episode two');
});

test('scrub preview is debounced and latest-wins without seeking until change', async ({ page }) => {
  const content = {key:'framextv:tv:284833:1:1',kind:'tv',contentId:'284833',season:1,episode:1,watchUrl:'https://framextv.cc/watch/284833/1/1',title:'Household episode'};
  await openApp(page, {
    connection:{connected:true,configured:true,host:'192.168.1.50',name:'Test LUMA'}, settings:{hasReceiver:true,hasPairing:true,autoConnect:true},
    playback:{active:true,playing:true,sourceUrl:media.url,content,positionSeconds:12,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false},
  });
  await page.evaluate(() => { window.__receiver.previewDelays['60'] = 350; });
  const range = page.getByLabel('Playback position');
  const preview = page.locator('#scrub-preview');
  await range.evaluate(element => { element.value = '60'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect(preview).toBeVisible();
  await expect(page.locator('#scrub-preview-time')).toHaveText('1:00');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'preview.frame'))).toBe(false);
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'preview.frame').map(call => call.params.position))).toEqual([60]);
  await range.evaluate(element => { element.value = '120'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'preview.frame').map(call => call.params.position))).toEqual([60, 120]);
  await expect(preview).toHaveAttribute('data-image', 'true');
  await expect(preview).toHaveAttribute('data-position', '120');
  await expect(page.locator('#scrub-preview-time')).toHaveText('2:00');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.seek'))).toBe(false);
  await page.waitForTimeout(400);
  await expect(preview).toHaveAttribute('data-position', '120');
  expect(await page.locator('body').innerText()).not.toContain('private-source-token');
  const previewCalls = await page.evaluate(() => window.__calls.filter(call => call.method === 'preview.frame'));
  expect(previewCalls.map(call => call.params)).toEqual([
    {expectedSourceUrl:media.url,contentKey:content.key,position:60},
    {expectedSourceUrl:media.url,contentKey:content.key,position:120},
  ]);

  await range.evaluate(element => element.dispatchEvent(new Event('change', {bubbles:true})));
  await expect(preview).toBeHidden();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.seek').map(call => call.params.position))).toEqual([120]);

  await range.evaluate(element => { element.value = '180'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect(preview).toBeVisible();
  await page.evaluate(() => window.LumaApp.onConnectionState({selectedTarget:'philips'}));
  await expect(preview).toBeHidden();
  await page.evaluate(() => window.LumaApp.onConnectionState({selectedTarget:'luma'}));
  await range.evaluate(element => { element.value = '200'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect(preview).toBeVisible();
  await page.evaluate(() => window.LumaApp.onSourceChanged());
  await expect(preview).toBeHidden();
  await range.evaluate(element => { element.value = '220'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect(preview).toBeVisible();
  await page.evaluate(() => window.LumaApp.onConnectionState({playback:{active:false}}));
  await expect(preview).toBeHidden();
});

test('unsupported scrub frames leave a usable timestamp-only scrubber', async ({ page }) => {
  const content = {key:'framextv:tv:284833:1:1',kind:'tv',contentId:'284833',season:1,episode:1,watchUrl:'https://framextv.cc/watch/284833/1/1',title:'Household episode'};
  await openApp(page, {
    connection:{connected:true,configured:true,host:'192.168.1.50',name:'Test LUMA'}, settings:{hasReceiver:true,hasPairing:true,autoConnect:true},
    playback:{active:true,playing:true,sourceUrl:media.url,content,positionSeconds:12,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false},
  });
  await page.evaluate(() => { window.__receiver.previewUnsupported = true; });
  const range = page.getByLabel('Playback position');
  const preview = page.locator('#scrub-preview');
  await range.evaluate(element => { element.value = '90'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'preview.frame').length)).toBe(1);
  await expect(preview).toBeVisible();
  await expect(preview).toHaveAttribute('data-image', 'false');
  await expect(page.locator('#scrub-preview-time')).toHaveText('1:30');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.seek'))).toBe(false);
  await range.evaluate(element => element.dispatchEvent(new Event('pointercancel', {bubbles:true})));
  await expect(preview).toBeHidden();
});

test('scrubbing mirrors the latest position and JPEG frame to LUMA Receiver and clears it when the preview ends', async ({ page }) => {
  const content = {key:'framextv:tv:284833:1:1',kind:'tv',contentId:'284833',season:1,episode:1,watchUrl:'https://framextv.cc/watch/284833/1/1',title:'Household episode'};
  await openApp(page, {
    connection:{connected:true,configured:true,host:'192.168.1.50',name:'Test LUMA'}, settings:{hasReceiver:true,hasPairing:true,autoConnect:true},
    playback:{active:true,playing:true,sourceUrl:media.url,content,positionSeconds:12,durationSeconds:600,canSeek:true},
    remote:{media:{...media,content},tracks:[],catalog:[],selectedIndex:null,subtitlesEnabled:false},
  });
  const scrubs = () => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.scrub').map(call => call.params));
  const range = page.getByLabel('Playback position');
  const jpeg = 'data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2Q==';
  // Only one mirror request is in flight; drags while it is pending collapse to the latest position.
  await page.evaluate(() => { window.__receiver.previewUnsupported = true; window.__receiver.holdMethod = 'receiver.scrub'; });
  for (const value of ['60', '120', '180']) await range.evaluate((element, value) => { element.value = value; element.dispatchEvent(new Event('input', {bubbles:true})); }, value);
  await expect(page.locator('#scrub-preview')).toBeVisible();
  expect(await scrubs()).toEqual([{ active: true, positionSeconds: 60, durationSeconds: 600 }]);
  await page.evaluate(() => window.__release());
  await expect.poll(scrubs).toEqual([{ active: true, positionSeconds: 60, durationSeconds: 600 }, { active: true, positionSeconds: 180, durationSeconds: 600 }]);
  await range.evaluate(element => element.dispatchEvent(new Event('pointerup', {bubbles:true})));
  await expect(page.locator('#scrub-preview')).toBeHidden();
  await expect.poll(async () => (await scrubs()).at(-1)).toEqual({ active: false });
  await range.evaluate(element => element.dispatchEvent(new Event('blur')));
  expect(await scrubs()).toHaveLength(3);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.seek'))).toBe(false);

  // A WebP phone frame stays local; only a JPEG frame is forwarded with the position.
  await page.evaluate(() => { window.__receiver.previewUnsupported = false; });
  await range.evaluate(element => { element.value = '30'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect(page.locator('#scrub-preview')).toHaveAttribute('data-image', 'true');
  await expect.poll(async () => (await scrubs()).length).toBe(5);
  expect((await scrubs()).slice(3)).toEqual([{ active: true, positionSeconds: 30, durationSeconds: 600 }, { active: true, positionSeconds: 30, durationSeconds: 600 }]);
  await page.evaluate(jpeg => { window.__receiver.previewDataUri = jpeg; }, jpeg);
  await range.evaluate(element => { element.value = '90'; element.dispatchEvent(new Event('input', {bubbles:true})); });
  await expect(page.locator('#scrub-preview')).toHaveAttribute('data-position', '90');
  await expect.poll(async () => (await scrubs()).at(-1)).toEqual({ active: true, positionSeconds: 90, durationSeconds: 600, image: jpeg });
  await range.evaluate(element => element.dispatchEvent(new Event('change', {bubbles:true})));
  await expect.poll(async () => (await scrubs()).at(-1)).toEqual({ active: false });
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.seek').map(call => call.params.position))).toEqual([90]);
});

test('subtitle size is read from LUMA Receiver and stepped within bounds from the Subtitles sheet', async ({ page }) => {
  await openApp(page); await setup(page); await cast(page);
  await page.evaluate(() => { window.__receiver.subtitleScale = 1.4; });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-size')).toBeVisible();
  await expect(page.locator('#subtitle-size-value')).toHaveText('Size 140%');
  const displays = () => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.display').map(call => call.params));
  expect(await displays()).toEqual([{}]);
  await page.getByRole('button', { name: 'Larger subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-size-value')).toHaveText('Size 150%');
  await page.getByRole('button', { name: 'Smaller subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'Smaller subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-size-value')).toHaveText('Size 130%');
  await expect.poll(displays).toEqual([{}, { subtitleScale: 1.5 }, { subtitleScale: 1.4 }, { subtitleScale: 1.3 }]);
  expect(await page.evaluate(() => window.__receiver.subtitleScale)).toBe(1.3);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.evaluate(() => { window.__receiver.subtitleScale = 2.4; });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-size-value')).toHaveText('Size 240%');
  await page.getByRole('button', { name: 'Larger subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-size-value')).toHaveText('Size 250%');
  await expect(page.getByRole('button', { name: 'Larger subtitles', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Smaller subtitles', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.evaluate(() => { window.__receiver.subtitleScale = 0.5; });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-size-value')).toHaveText('Size 50%');
  await expect(page.getByRole('button', { name: 'Smaller subtitles', exact: true })).toBeDisabled();
  expect((await displays()).at(-1)).toEqual({});
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
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
  await page.evaluate(() => { window.__receiver.holdMethod = 'receiver.open'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.evaluate(() => window.LumaApp.onSourceChanged());
  await expect(page.locator('#handoff-label')).toHaveText('The selected video changed. Choose it again in Browse.');
  await page.evaluate(() => window.__release());
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'app.handoffReady'))).toBe(false);
  await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeHidden();
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, url: 'https://media.example.org/second.m3u8', sourceToken: 'capture-two' }), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
});

test('capturing before setup waits for the receiver and pairing, then starts once', async ({ page }) => {
  await openApp(page);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await page.evaluate(value => { window.__captureMedia = value; }, media);
  await page.getByRole('button', { name: 'Cast current video', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toHaveAttribute('aria-selected', 'true');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'luma.launchReceiver'))).toBe(false);
  await setup(page);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
  // The paired receiver already answers, so casting does not relaunch it.
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.launchReceiver').length)).toBe(0);
});

test('disconnect requires pairing again and Browse resumes unless the address form explicitly navigates', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await settingsDevice(page);
  await page.getByRole('button', { name: 'Disconnect projector', exact: true }).click();
  await expect(page.locator('#refresh-playback')).toBeDisabled();
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#pair-panel')).toBeVisible();
  await expect(page.locator('#refresh-playback')).toBeDisabled();
  await pair(page);
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
  expect(calls.filter(call => call.method === 'receiver.pairComplete')).toHaveLength(2);
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

test('a wrong pairing code returns immediately to the unchanged form with a visible actionable error', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page);
  await connectProjector(page);
  await expect(page.locator('#pair-form')).toBeHidden();
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await pair(page, '654321');
  await expect(page.locator('#pair-panel')).toBeVisible();
  await expect(page.locator('#sheet')).toBeHidden();
  await expect(page.locator('#message')).toHaveText('That code does not match the one on the projector. Check it and try again.');
  await expect(page.getByLabel('Code on the projector', { exact: true })).toHaveValue('654321');
  await expect(page.getByRole('button', { name: 'Pair', exact: true })).toBeEnabled();
  let calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'receiver.pairBegin').map(call => call.params)).toEqual([{}]);
  expect(calls.filter(call => call.method === 'receiver.pairComplete').map(call => call.params)).toEqual([{ pairingId: 'pairing-1', code: '654321' }]);
  expect(calls.some(call => ['receiver.connect', 'luma.launchReceiver', 'receiver.open'].includes(call.method))).toBe(false);
  await page.getByLabel('Code on the projector', { exact: true }).focus();
  await page.setViewportSize({ width: 360, height: 400 });
  await layout(page);
  expect(await page.locator('#message').evaluate(element => { const box = element.getBoundingClientRect(); return box.height >= 32 && box.top >= 0 && box.bottom <= innerHeight; })).toBe(true);
  await expect(page.locator('body')).not.toContainText('private-pairing-token');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden();
  await page.getByLabel('Code on the projector', { exact: true }).fill('123 456');
  await page.getByRole('button', { name: 'Pair', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'receiver.pairComplete').at(-1).params).toEqual({ pairingId: 'pairing-1', code: '123456' });
  expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
});

test('pairing requests a projector code, saves the pairing and opens the remote', async ({ page }) => {
  await openApp(page);
  await connectProjector(page);
  await expect(page.getByRole('button', { name: 'Show code on projector', exact: true })).toBeEnabled();
  await expect(page.locator('#pair-form')).toBeHidden();
  await requestCode(page);
  await expect(page.locator('#message')).toHaveText('Enter the 6-digit code shown on the projector.');
  await expect(page.getByLabel('Code on the projector', { exact: true })).toBeFocused();
  await page.getByLabel('Code on the projector', { exact: true }).fill('123456');
  await page.getByRole('button', { name: 'Pair', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#message')).toHaveText('Paired. Choose a video in Browse, then tap Cast.');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toBeHidden();
  await expect(page.locator('#pair-code')).toHaveValue('');
  await expect(page.locator('#pair-form')).toBeHidden();
  const calls = await page.evaluate(() => window.__calls);
  const methods = calls.map(call => call.method).filter(method => method.startsWith('receiver.') || method === 'luma.launchReceiver');
  expect(methods).toEqual(['receiver.pairBegin', 'receiver.pairComplete', 'receiver.connect']);
  expect(calls.find(call => call.method === 'receiver.pairComplete').params).toEqual({ pairingId: 'pairing-1', code: '123456' });
  const diagnostics = calls.filter(call => call.method === 'app.handoffStatus').map(call => call.params.stage);
  expect(diagnostics).toEqual(['setup', 'ready', 'setup', 'waiting', 'ready']);
  await page.getByRole('button', { name: 'Refresh playback', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Playback refreshed.');
});

test('pairing launches LUMA Receiver once when it does not answer, then shows the code', async ({ page }) => {
  await openApp(page);
  await connectProjector(page);
  await page.evaluate(() => { window.__receiver.running = false; });
  await requestCode(page);
  const methods = await page.evaluate(() => window.__calls.map(call => call.method).filter(method => method === 'receiver.pairBegin' || method === 'luma.launchReceiver'));
  expect(methods).toEqual(['receiver.pairBegin', 'luma.launchReceiver', 'receiver.pairBegin']);
  await expect(page.locator('#message')).toHaveText('Enter the 6-digit code shown on the projector.');
});

test('pairing reports when LUMA Receiver is not installed and keeps the code form hidden', async ({ page }) => {
  await openApp(page);
  await connectProjector(page);
  await page.evaluate(() => { window.__receiver.running = false; window.__receiver.installed = false; });
  await page.getByRole('button', { name: 'Show code on projector', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Install LUMA Receiver on the projector first, then try again.');
  await expect(page.locator('#message')).toHaveAttribute('data-error', 'true');
  await expect(page.locator('#pair-panel')).toBeVisible();
  await expect(page.locator('#pair-form')).toBeHidden();
  await expect(page.locator('#sheet')).toBeHidden();
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => ['receiver.pairBegin', 'luma.launchReceiver'].includes(call.method)).map(call => call.method)).toEqual(['receiver.pairBegin', 'luma.launchReceiver']);
  expect(calls.filter(call => call.method === 'app.handoffStatus').at(-1).params).toEqual({ stage: 'error', code: 'RECEIVER_NOT_INSTALLED' });
  expect(calls.some(call => call.method === 'receiver.pairComplete')).toBe(false);
});

test('an expired pairing code asks for a new code', async ({ page }) => {
  await openApp(page);
  await connectProjector(page);
  await page.evaluate(() => { window.__receiver.pairExpired = true; });
  await pair(page);
  await expect(page.locator('#message')).toHaveText('The pairing code expired. Show a new code on the projector.');
  await page.evaluate(() => { window.__receiver.pairExpired = false; });
  await requestCode(page);
  await page.getByLabel('Code on the projector', { exact: true }).fill('123456');
  await page.getByRole('button', { name: 'Pair', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Remote', exact: true })).toHaveAttribute('aria-selected', 'true');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.pairComplete').map(call => call.params.pairingId))).toEqual(['pairing-1', 'pairing-2']);
});

test('Cancel revokes the native token before a queued video write and retry uses a fresh job token', async ({ page }) => {
  await openApp(page);
  await setup(page);
  await page.evaluate(() => { window.__receiver.holdMethod = 'receiver.open'; });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.getByRole('button', { name: 'Cancel casting', exact: true }).click();
  await expect(page.locator('#handoff-label')).toHaveText('Casting was cancelled.');
  await expect.poll(() => page.evaluate(() => window.__calls.some(call => call.method === 'app.cancelHandoff' && call.params.sourceToken === 'job-1'))).toBe(true);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
  await page.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.status').length)).toBeGreaterThanOrEqual(3);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
  await page.evaluate(() => window.__release());
  expect(await page.evaluate(() => window.__receiver.active)).toBe(false);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'app.beginHandoff').map(call => call.params.sourceToken)).toEqual(['capture-one', 'capture-one']);
  expect(calls.filter(call => call.method === 'receiver.open').map(call => call.params.sourceToken)).toEqual(['job-1', 'job-2']);
  expect(calls.filter(call => call.method === 'receiver.open').at(-1).params.expectedSourceUrl).toBe(media.url);
  // The cancelled native reply explicitly proves its queued write was not sent.
  // A fresh job may proceed after that receipt; uncertain writes remain guarded.
  expect(calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
  expect(calls.filter(call => call.method === 'receiver.addSubtitle').every(call => call.params.sourceToken === 'job-2')).toBe(true);
});

function serviceSnapshot() {
  return {
    settings: { hasReceiver: true, hasPairing: true, autoConnect: true },
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
  await expect(page.locator('#pair-code')).toHaveValue('');
  await expect(page.locator('#pair-form')).toBeHidden();
  expect(await page.evaluate(() => window.__calls.map(call => call.method))).toEqual(['app.ready']);
  await page.evaluate(() => window.LumaApp.onConnectionState({ volume: { current: 8, max: 30 } }));
  await expect(page.locator('#volume-level')).toHaveText('8 / 30');
  await layout(page);
  await page.evaluate(() => window.LumaApp.onSourceChanged());
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  const pause = await page.evaluate(() => window.__calls.find(call => call.method === 'receiver.pause'));
  expect(pause.params).toEqual({ expectedSourceUrl: media.url });
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-list button')).toHaveText(['Portuguese (Brazil)', 'English']);
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => window.__calls.some(call => ['device.connect', 'receiver.pairBegin', 'receiver.pairComplete', 'receiver.connect', 'luma.launchReceiver', 'receiver.open', 'app.beginHandoff'].includes(call.method)))).toBe(false);
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
  await page.evaluate(() => { window.__receiver.holdMethod = 'receiver.open'; });
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
  expect(calls.filter(call => call.method === 'receiver.open').map(call => call.params.media.url)).toEqual([media.url, 'https://media.example.org/next-episode.m3u8']);
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
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.stop'))).toBe(false);
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
  await page.evaluate(() => { window.__receiver.holdMethod = 'receiver.open'; });
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
  await openApp(page, { settings: { hasReceiver: true, hasPairing: true, autoConnect: true }, connection: { connected: false, configured: false } });
  await expect(page.locator('#message')).toHaveText('Connecting to your saved projector…');
  await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'false');
  await expect(page.getByRole('tab', { name: 'Devices', exact: true })).toBeHidden();
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.__calls.map(call => call.method))).toEqual(['app.ready']);
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: true, configured: true, name: 'Saved LUMA' }, volume: { current: 6, max: 30 } }));
  await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'true');
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeEnabled();
  const navigation = await page.evaluate(() => {
    const home = document.querySelector('[data-key="home"]').getBoundingClientRect();
    const up = document.querySelector('[data-key="up"]').getBoundingClientRect();
    const back = document.querySelector('[data-key="back"]').getBoundingClientRect();
    const pad = document.querySelector('.dpad').getBoundingClientRect();
    return {
      flanksUp: home.right <= up.left && up.right <= back.left,
      sameRow: home.top === up.top && up.top === back.top,
      insidePad: home.left >= pad.left && back.right <= pad.right && home.top >= pad.top && back.bottom <= pad.bottom,
      keys: document.querySelectorAll('.dpad button').length,
      headerKeys: document.querySelectorAll('.topline [data-key]').length
    };
  });
  expect(navigation).toEqual({ flanksUp: true, sameRow: true, insidePad: true, keys: 7, headerKeys: 0 });
  await layout(page);
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await layout(page);
  await page.getByRole('button', { name: 'Receiver pairing', exact: true }).click();
  await expect(page.locator('#pair-panel')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Show code on projector', exact: true })).toBeEnabled();
  await expect(page.locator('#pair-form')).toBeHidden();
  await expect(page.locator('#pair-code')).toHaveValue('');
});

test('booted receiver casts before native projector navigation becomes available', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await openApp(page, {
    settings: { hasReceiver: true, hasPairing: true, autoConnect: true },
    connection: { connected: true, nativeConnected: false, receiverConnected: true, configured: true,
      host: '192.168.1.50', name: 'LUMA Receiver' },
  });
  await expect(page.locator('#device-badge')).toHaveText('LUMA Receiver ready');
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeDisabled();
  await cast(page);
  const coldBootCalls = await page.evaluate(() => window.__calls.map(call => call.method));
  expect(coldBootCalls).toContain('receiver.connect');
  expect(coldBootCalls).toContain('receiver.open');
  expect(coldBootCalls).not.toContain('luma.launchReceiver');
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: {
    connected: true, nativeConnected: true, receiverConnected: true, configured: true,
    host: '192.168.1.50', name: 'Test LUMA',
  } }));
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeEnabled();
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

test('first setup pairs after launching the receiver and resumes only the latest Cast without launching it twice', async ({ page }) => {
  await openApp(page);
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await connectProjector(page);
  await page.evaluate(() => { window.__receiver.running = false; });
  await requestCode(page);
  await page.getByLabel('Code on the projector', { exact: true }).fill('123456');
  await page.evaluate(() => { window.__receiver.holdMethod = 'receiver.connect'; });
  await page.getByRole('button', { name: 'Pair', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await expect(page.locator('#handoff-label')).toHaveText('Checking the connection…');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden();
  expect(await page.evaluate(() => window.__calls.filter(call => ['receiver.pairBegin', 'luma.launchReceiver', 'receiver.pairComplete', 'receiver.connect'].includes(call.method)).map(call => call.method)))
    .toEqual(['receiver.pairBegin', 'luma.launchReceiver', 'receiver.pairBegin', 'receiver.pairComplete', 'receiver.connect']);
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, url: 'https://media.example.org/latest.m3u8', sourceToken: 'latest-capture', title: 'Latest episode' }), media);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'receiver.open').map(call => call.params.media.url)).toEqual(['https://media.example.org/latest.m3u8']);
  expect(calls.filter(call => call.method === 'luma.launchReceiver')).toHaveLength(1);
  const diagnostics = calls.filter(call => call.method === 'app.handoffStatus').map(call => call.params);
  expect(diagnostics.map(item => item.stage)).toEqual(expect.arrayContaining(['captured', 'setup', 'launching', 'waiting', 'opening', 'ready']));
  expect(diagnostics.every(item => Object.keys(item).every(key => ['stage', 'code'].includes(key)) && !JSON.stringify(item).includes('http'))).toBe(true);
  await expect(page.locator('#pair-code')).toHaveValue('');
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
  await expect(page.locator('#pair-code')).toHaveValue('');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.find(call => call.method === 'app.settings.reset').params).toEqual({ scope: 'all' });
  expect(calls.some(call => call.method === 'receiver.stop')).toBe(false);
  expect(await page.evaluate(() => window.__receiver.active)).toBe(true);
  await layout(page);
});

test('a local save failure keeps the setup draft and does not claim configuration succeeded', async ({ page }) => {
  await openApp(page);
  await connectProjector(page);
  await page.evaluate(() => { window.__receiver.failStorage = true; });
  await pair(page);
  await expect(page.locator('#message')).toHaveText('Settings could not be saved on this phone. Try setup again.');
  await expect(page.locator('#pair-panel')).toBeVisible();
  await expect(page.getByLabel('Code on the projector', { exact: true })).toHaveValue('123456');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden();
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.open'))).toBe(false);
});

test('canceling setup waiting prevents a queued Cast even if native validation finishes later', async ({ page }) => {
  await openApp(page);
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await connectProjector(page);
  await requestCode(page);
  await page.getByLabel('Code on the projector', { exact: true }).fill('123456');
  await page.evaluate(() => { window.__receiver.holdMethod = 'receiver.connect'; });
  await page.getByRole('button', { name: 'Pair', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__receiver.held.length)).toBe(1);
  await page.getByRole('button', { name: 'Cancel waiting', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Pairing stopped. No video was sent.');
  await page.evaluate(() => { window.__release(); window.LumaApp.onConnectionState({ settings: { hasReceiver: true, hasPairing: true }, connection: { connected: true, configured: true } }); });
  await page.getByRole('tab', { name: 'Remote', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh volume', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Volume refreshed.');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'receiver.open' || call.method === 'app.beginHandoff'))).toBe(false);
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
  const lazyAdd = calls.filter(call => call.method === 'receiver.addSubtitle').at(-1);
  expect(lazyAdd.params.expectedSourceUrl).toBe(media.url);
  expect(lazyAdd.params.sourceToken).toBeUndefined();
  expect(calls.filter(call => call.method === 'receiver.setSubtitle').at(-1).params).toEqual({ expectedSourceUrl: media.url, index: 1 });
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'receiver.addSubtitle')).toHaveLength(2);
  expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
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
  expect(calls.filter(call => call.method === 'receiver.addSubtitle').map(call => call.params.track.language)).toEqual(['ja']);
  expect(calls.some(call => ['luma.launchReceiver', 'receiver.open', 'app.beginHandoff', 'device.connect'].includes(call.method))).toBe(false);
  // Caption size is a projector-wide display setting, not bound to the active source.
  expect(calls.filter(call => call.method === 'receiver.display').map(call => call.params)).toEqual([{}]);
  expect(calls.filter(call => call.method.startsWith('receiver.') && call.method !== 'receiver.display').every(call => call.params.expectedSourceUrl === media.url && call.params.sourceToken === undefined)).toBe(true);
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
  expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
  expect(await page.evaluate(() => ({ selected: window.__receiver.currentIndex, playing: window.__receiver.playing }))).toEqual({ selected: 0, playing: true });
  await page.evaluate(() => { window.__receiver.failSubtitle = false; window.__receiver.failSubtitleAuth = true; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.locator('#pair-panel')).toBeVisible();
  await expect(page.locator('#message')).toHaveText('LUMA Receiver no longer recognizes this phone. Pair it again.');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
});

test('an uncertain subtitle Add waits within its bound then releases controls without resending or replaying', async ({ page }) => {
  await page.clock.install();
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now() + 1000)));
  await page.evaluate(() => { window.__receiver.failSubtitle = 'RECEIVER_RESPONSE'; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.addSubtitle').length)).toBe(2);
  await page.clock.runFor(1000);
  await expect(page.getByRole('button', { name: 'English', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.clock.fastForward(31000);
  await expect(page.getByRole('button', { name: 'English', exact: true })).toBeEnabled();
  await expect(page.locator('#sheet-message')).toHaveText('The projector did not load the subtitle in time.');
  await expect(page.locator('#sheet-message')).toHaveAttribute('data-error', 'true');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'receiver.addSubtitle')).toHaveLength(2);
  expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
  expect(calls.filter(call => call.method === 'app.rememberRemote')).toHaveLength(1);
  expect(await page.evaluate(() => ({ selected: window.__receiver.currentIndex, playing: window.__receiver.playing }))).toEqual({ selected: 0, playing: true });
  await expect(page.locator('body')).not.toContainText('private-subtitle-token');
});

for (const rejectedMethod of ['receiver.open']) {
  test(`a verified ${rejectedMethod} rejection reports promptly and retries only after Try again`, async ({ page }) => {
    await openApp(page); await setup(page);
    await page.evaluate(method => {
      window.__receiver[method === 'receiver.open' ? 'failOpen' : 'failSubtitle'] = 'RECEIVER_REJECTED';
    }, rejectedMethod);
    await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
    await expect(page.locator('#handoff-label')).toHaveText('LUMA Receiver rejected this request before applying it. Try casting again.');
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
    expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(rejectedMethod === 'receiver.open' ? 2 : 1);
    expect(calls.filter(call => call.method === 'receiver.addSubtitle')).toHaveLength(0);
    expect(calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
    const attempts = calls.filter(call => call.method === rejectedMethod);
    expect(attempts[0].params.sourceToken).not.toBe(attempts[1].params.sourceToken);
    expect(await page.evaluate(() => ({ tracks: window.__receiver.tracks.length, selected: window.__receiver.currentIndex, playing: window.__receiver.playing }))).toEqual({ tracks: 0, selected: null, playing: true });
    await expect(page.locator('body')).not.toContainText('private-source-token');
    await expect(page.locator('body')).not.toContainText('private-subtitle-token');
  });
}

test('a synchronous bridge write failure is explicit not-sent proof and permits retry', async ({ page }) => {
  await openApp(page); await setup(page);
  await page.evaluate(() => {
    const post = window.LumaNative.postMessage.bind(window.LumaNative);
    window.__bridgeThrows = 0;
    window.LumaNative.postMessage = text => {
      const request = JSON.parse(text);
      if (request.method === 'receiver.open' && window.__bridgeThrows++ === 0) throw new Error('fixture bridge unavailable before write');
      post(text);
    };
  });
  await page.evaluate(value => window.LumaApp.onCapturedMedia(value), media);
  await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  expect(await page.evaluate(() => window.__bridgeThrows)).toBe(2);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'app.handoffReady').length)).toBe(1);
});

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
  expect(before.calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
  expect(before.calls.filter(call => call.method === 'receiver.addSubtitle')).toHaveLength(0);
  expect(before.calls.filter(call => call.method === 'app.handoffReady')).toHaveLength(1);
  expect(before.calls.filter(call => call.method === 'app.cancelHandoff')).toHaveLength(0);
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  await page.evaluate(() => window.__releaseOpenResponse());
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
});

test('subtitle receiver failures cannot block startup and the full catalog remains available afterward', async ({ page }) => {
  await openApp(page); await setup(page);
  await page.evaluate(() => { window.__receiver.failSubtitle = 'RECEIVER_REJECTED'; });
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
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
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
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.open').length)).toBe(1);
  await page.evaluate(() => window.__releaseOpenResponse('RECEIVER_UNAVAILABLE'));
  await page.clock.runFor(1000);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => call.method === 'receiver.open').map(call => call.params.sourceToken)).toEqual(['job-1', 'job-2']);
  expect(await page.evaluate(() => window.__receiver.playing)).toBe(true);
});

test('missing Brazilian Portuguese starts immediately and lets the user choose English afterward', async ({ page }) => {
  await openApp(page); await setup(page);
  await page.evaluate(value => window.LumaApp.onCapturedMedia({ ...value, tracks: value.tracks.filter(track => track.language !== 'pt-BR') }), media);
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => ['receiver.addSubtitle', 'receiver.getSubtitles', 'receiver.setSubtitle'].includes(call.method))).toHaveLength(0);
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


test('a subtitle file LUMA Receiver cannot read is reported and another language remains usable without replay', async ({ page }) => {
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.evaluate(() => { window.__receiver.failSubtitle = 'SUBTITLE_INVALID'; });
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.locator('#sheet-message')).toHaveText('This subtitle file has no valid timed captions. Choose another subtitle.');
  await page.evaluate(() => { window.__receiver.failSubtitle = null; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const calls = await page.evaluate(() => window.__calls);
  const add = calls.filter(call => call.method === 'receiver.addSubtitle').at(-1);
  expect(add.params.track.url).toBe(media.tracks[0].url);
  expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
});

test('LUMA subtitles go straight to the receiver as catalog URLs with no phone download or delivery step', async ({ page }) => {
  await openApp(page); await setup(page); await cast(page);
  await page.getByRole('button', { name: 'Subtitles', exact: true }).click();
  await page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Portuguese (Brazil)', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => ['receiver.prepareSubtitle', 'receiver.subtitleDelivery'].includes(call.method))).toEqual([]);
  const add = calls.find(call => call.method === 'receiver.addSubtitle');
  expect(add.params.track.url).toBe(media.tracks.find(track => track.language === 'pt-BR' || track.language === 'pt-br')?.url ?? add.params.track.url);
  expect(add.params.preparedId).toBeUndefined();
  expect(calls.filter(call => call.method === 'receiver.open')).toHaveLength(1);
});
