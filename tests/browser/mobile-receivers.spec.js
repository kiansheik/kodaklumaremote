import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';

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
const savedPosition = {positionSeconds:125,durationSeconds:1355,resumeSeconds:125,completed:false,lastReceiver:'philips'};
const savedSeries = {
  version:1,storageAvailable:true,kind:'tv',id:'84',title:'Cached household series',overview:'This description was saved on the phone.',year:'2025',metadataAvailable:true,
  latest:{...savedPosition,season:1,episode:2},movieProgress:null,
  seasons:[
    {number:1,name:'Season 1',episodes:[
      {number:1,title:'Finished episode',progress:{positionSeconds:1355,durationSeconds:1355,resumeSeconds:0,completed:true,lastReceiver:'luma'}},
      {number:2,title:'Current episode',progress:savedPosition},
    ]},
    {number:2,name:'Season 2',episodes:[{number:1,title:'Next known episode',progress:null}]},
  ],nextEpisode:{kind:'tv',id:'84',season:2,episode:1},
};

test.beforeAll(async () => {
  const files = new Map([
    ['/', new URL('../../src/mobile/index.html', import.meta.url)],
    ['/app.js', new URL('../../src/mobile/app.js', import.meta.url)],
    ['/handoff/coordinator.mjs', new URL('../../src/handoff/coordinator.mjs', import.meta.url)],
    ['/handoff/sync.mjs', new URL('../../src/handoff/sync.mjs', import.meta.url)],
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

async function openApp(page, { selectedTarget = 'luma', tvConnected = true, textState = {} } = {}) {
  await page.addInitScript(({ selectedTarget, tvConnected, lumaMedia, textState }) => {
    window.__calls = [];
    const tv = window.__tv = {
      connected: tvConnected, name: 'Philips TV', host: '192.168.1.60', startupUrl: tvConnected ? 'http://192.168.1.10:4178' : '', pendingCommand: null,
      playback: { active: tvConnected, source: 'https://media.example.org/existing-tv.mp4', title: 'Already on TV', paused: false, positionSeconds: 30, durationSeconds: 900, canSeek: true, volume: { current: 40, max: 100 }, tracks: [], selectedIndex: null, subtitlesEnabled: false },
      ...textState,
    };
    const browserSetupUrl = () => tv.startupUrl ? tv.startupUrl + '/tv/connect.html' : '';
    tv.browserSetupUrl = browserSetupUrl();
    window.__holdMethod = null;
    window.__held = [];
    const result = ({ method, params }) => {
      if (method === 'app.ready') return {
        selectedTarget, browserUrl: 'https://framextv.xyz/watch/284833/1/1', connection: { connected: true, configured: true, host: '192.168.1.50', name: 'Test LUMA' },
        settings: { hasReceiver: true, hasPairing: true, autoConnect: true }, volume: { current: 6, max: 30 },
        playback: { active: true, sourceUrl: lumaMedia.url, playing: true, positionSeconds: 12, durationSeconds: 600, canSeek: true },
        remote: { media: lumaMedia, tracks: [{ index: 0, language: 'pt-BR', label: 'LUMA Portuguese' }], catalog: [], selectedIndex: 0, subtitlesEnabled: true }, receivers: { philips: tv },
      };
      if (method === 'app.picturePreferences') { window.__picturePreferences = params.value || {}; return { saved: true }; }
      if (method === 'app.selectTarget') return { selectedTarget: params.target, receivers: { philips: tv } };
      if (method === 'app.beginHandoff') return { sourceToken: 'attempt-tv' };
      if (method === 'watch.history') return {version:1,items:window.__historyItems||[]};
      if (method === 'watch.library') {
        const query=(params.query||'').trim(),offset=params.offset||0,limit=params.limit||50;
        const all=(window.__libraryItems||(window.__historyItems||[]).map(row=>({kind:row.kind,id:row.contentId,title:row.title,metadataAvailable:false,latest:{durationSeconds:600,positionSeconds:row.resumeSeconds||0,...row}}))).filter(row=>row.title.toLowerCase().includes(query.toLowerCase()));
        return {version:1,storageAvailable:!window.__libraryUnavailable,query,offset,limit,total:all.length,hasMore:offset+limit<all.length,items:all.slice(offset,offset+limit)};
      }
      if (method === 'watch.libraryDetails') {
        if(window.__libraryDetails) return window.__libraryDetails;
        const row=(window.__historyItems||[]).find(row=>row.kind===params.kind&&row.contentId===params.id);
        const progress={positionSeconds:row?.resumeSeconds||0,durationSeconds:600,resumeSeconds:row?.resumeSeconds||0,completed:row?.completed===true,lastReceiver:'philips'};
        return {version:1,storageAvailable:true,...params,title:row?.title||'Saved title',overview:'',year:'',metadataAvailable:false,latest:{...progress,season:row?.season??null,episode:row?.episode??null},movieProgress:params.kind==='movie'?progress:null,seasons:[],nextEpisode:null};
      }
      if (method === 'app.chooseCatalog') return window.__catalogMedia ? {...window.__catalogMedia,...(params.resume===false?{resumeSeconds:0}:{})} : null;
      if (method === 'app.cancelCatalog') return {accepted:true};
      if (['app.handoffReady', 'app.cancelHandoff', 'app.handoffStatus'].includes(method)) return { accepted: true };
      if (method === 'msx.start') { tv.startupUrl = 'http://192.168.1.10:4178'; tv.browserSetupUrl = browserSetupUrl(); tv.pairingOpen = true; tv.awaitingApproval = !window.__typingSetup; tv.pendingHost = window.__typingSetup ? '' : '192.168.1.60'; return tv; }
      if (method === 'msx.confirm') { tv.connected = true; tv.configured = true; tv.pairingOpen = false; tv.awaitingApproval = false; tv.pendingHost = ''; return tv; }
      if (method === 'msx.status') { if (window.__connectOnStatus) tv.connected = true; return tv; }
      if (method === 'matter.status') { window.__matter.paused = false; return window.__matter; }
      if (method === 'matter.key') return window.__matter;
      if (method === 'matter.powerOn') return { ...window.__matter, accepted: true, powerStateVerified: false, onOff: null };
      if (method === 'msx.textPairRequest') { tv.textPairing = true; tv.textPairingExpiresInSeconds = 60; return { pairing: true, expiresInSeconds: 60 }; }
      if (method === 'msx.textPairGrant') { tv.textPairing = false; tv.textPairingExpiresInSeconds = 0; tv.textPermissionSaved = true; tv.typingAvailable = true; tv.typingBusy = false; tv.textError = null; return tv; }
      if (method === 'msx.typeAddress') return { accepted: true, acknowledgedChars: window.__typingAck ?? 17, textAppliedVerified: false, textEntryMode: 'msx', totalChars: 17 };
      if (method === 'msx.typeBrowserAddress') {
        const typed = params.address === 'stable' ? 'https://kiansheik.io/kodaklumaremote/tv.html' : browserSetupUrl();
        return { accepted: true, acknowledgedChars: window.__typingAck ?? typed.length, textAppliedVerified: false, textEntryMode: window.__typingMode ?? 'browser', totalChars: window.__typingTotal ?? typed.length };
      }
      if (method === 'msx.launchBrowser') return { ...tv, accepted: true, pageLoadVerified: false };
      if (['msx.debugPing', 'msx.debugLabel', 'msx.reloadReceiver'].includes(method)) {
        const id = window.__debugSequence = (window.__debugSequence || 20) + 1;
        const receiptMethod = { 'msx.debugPing': 'ping', 'msx.debugLabel': 'set-label', 'msx.reloadReceiver': 'reload' }[method];
        if (method === 'msx.debugPing' && tv.playback.receiverDebug) tv.playback.receiverDebug.pingCount++;
        tv.lastCommand = { id: window.__debugBadReceipt ? id + 1 : id, method: receiptMethod, ok: true };
        return { ...tv, accepted: true, id };
      }
      if (method === 'msx.sample') {
        tv.playback = { ...tv.playback, active: true, source: 'https://media.example.org/public-sample.mp4', title: 'Public sample', paused: false, tracks: [], selectedIndex: null, subtitlesEnabled: false, error: null };
        tv.pendingCommand = null; tv.lastCommand = { id: 11, method: 'open', ok: true, error: '' };
        return { ...tv, accepted: true, id: 11 };
      }
      if (method === 'msx.open') {
        tv.playback = { ...tv.playback, active: true, source: params.source, title: params.title, tracks: params.subtitles.map((track, index) => ({ ...track, index })), selectedIndex: params.autoSubtitles === false ? -1 : 1, subtitlesEnabled: params.autoSubtitles !== false };
        tv.pendingCommand = null;
        return tv;
      }
      if (method === 'msx.playPause') { tv.playback.paused = !params.play; return tv; }
      if (method === 'msx.volume') { tv.playback.volume.current = params.value; return tv; }
      if (method === 'msx.subtitle') {
        const id = window.__subtitleSequence = (window.__subtitleSequence || 30) + 1;
        tv.playback.selectedIndex = params.enabled ? params.index : null; tv.playback.subtitlesEnabled = params.enabled;
        tv.lastCommand = { id: window.__subtitleBadReceipt ? id + 1 : id, method: 'subtitle', ok: true };
        return { ...tv, accepted: true, id };
      }
      if (method === 'msx.seek') { if(!window.__seekNoEffect) tv.playback.positionSeconds = params.seconds; if(window.__disconnectAfterSeek)tv.connected=false; return tv; }
      if (method === 'msx.stop') { tv.playback.active = false; return tv; }
      if (method === 'msx.disconnect') { tv.connected = false; return tv; }
      if (method === 'luma.launchReceiver') return {accepted:true};
      if (method === 'receiver.connect') return {ready:true};
      if (method === 'receiver.open') {window.__lumaSource=params.media.url;return {accepted:true};}
      if (method === 'receiver.seek') {window.__lumaPosition=params.position;return {accepted:true};}
      if (method === 'receiver.pause') return { accepted: true };
      if (method === 'receiver.scrub') return { accepted: true };
      if (method === 'msx.scrub') return { accepted: true };
      if (method === 'msx.settings') return { autoNext: params.autoNext === true };
      if (method === 'msx.display') { tv.playback.display = { subtitleScale: 1, brightness: 1, contrast: 1, ...tv.playback.display, ...params }; return tv; }
      if (method === 'receiver.display') return { subtitleScale: 1 };
      if (method === 'luma.key') return { accepted: true };
      if (method === 'receiver.status') return { active: true, sourceUrl: window.__lumaSource||lumaMedia.url, playing: !!window.__lumaSource, positionSeconds: window.__lumaPosition??12, durationSeconds: 600, canSeek: true };
      throw new Error(`Unexpected fixture request: ${method}`);
    };
    const respond = request => {
      if (request.method === 'msx.status' && window.__statusError) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__statusError, message: 'https://private.example.org/source?token=private-diagnostic-token' } });
      } else if (request.method === 'msx.textPairGrant' && window.__grantError) {
        tv.textError = window.__grantError;
        tv.typingBusy = false;
        window.LumaApp.onConnectionState({ receivers: { philips: tv } });
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__grantError } });
      } else if (request.method === 'msx.launchBrowser' && window.__launchError) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__launchError } });
      } else if (['msx.debugPing', 'msx.debugLabel', 'msx.reloadReceiver'].includes(request.method) && window.__debugError) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__debugError } });
      } else if (request.method === 'msx.subtitle' && window.__subtitleError) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__subtitleError } });
      } else if (request.method === 'matter.powerOn' && window.__powerOnError) {
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: window.__powerOnError } });
      } else if (['msx.typeAddress', 'msx.typeBrowserAddress'].includes(request.method) && window.__typingFailure) {
        const code = window.__typingError || 'TEXT_PARTIAL';
        tv.lastTyping = { acknowledgedChars: 4, partialInput: true, textAppliedVerified: false, error: code };
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code } });
      } else if (request.method === 'msx.sample' && window.__sampleFailure) {
        tv.playback = { ...tv.playback, active: false, error: 'PLAYBACK_REJECTED' };
        tv.lastCommand = { id: 11, method: 'open', ok: false, error: 'PLAYBACK_REJECTED' };
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'MSX_REJECTED' } });
      } else if (request.method === 'msx.open' && window.__subtitleFailure) {
        result(request);
        tv.playback.selectedIndex = null; tv.playback.subtitlesEnabled = false;
        tv.lastCommand = { id: 1, method: 'open', ok: false, error: 'SUBTITLE_FAILED' };
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'MSX_REJECTED' } });
      } else if (request.method === 'msx.open' && window.__unknownOpen) {
        tv.pendingCommand = { id: 1, method: 'open' };
        window.LumaRPC.receive({ id: request.id, ok: false, error: { code: 'MSX_TIMEOUT' } });
      } else {
        const reply = result(request);
        if (request.method === 'msx.textPairGrant' && window.__publishTextBeforeReply) window.LumaApp.onConnectionState({ receivers: { philips: tv } });
        window.LumaRPC.receive({ id: request.id, ok: true, result: reply });
      }
    };
    window.__release = () => { window.__holdMethod = null; for (const request of window.__held.splice(0)) respond(request); };
    window.LumaNative = { postMessage(text) {
      const request = JSON.parse(text);
      window.__calls.push(request);
      queueMicrotask(() => { if (request.method === window.__holdMethod) window.__held.push(request); else respond(request); });
    } };
  }, { selectedTarget, tvConnected, lumaMedia, textState });
  await page.goto(origin);
  await expect(page.locator(`#target-${selectedTarget}`)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'true');
}
async function fits(page) {
  expect(await page.evaluate(() => {
    const visible = document.querySelector('#sheet:not([hidden])') || document.querySelector('.page:not([hidden])');
    const elements = [...visible.querySelectorAll('button, input, summary')].filter(element => element.checkVisibility());
    const outside = elements.flatMap(element => {
      const rect = element.getBoundingClientRect();
      return rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth + 1 && rect.bottom <= innerHeight + 1 ? [] : [{ id: element.id, x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom }];
    });
    if (document.documentElement.scrollWidth > innerWidth || document.documentElement.scrollHeight > innerHeight) outside.push({ documentOverflow: true });
    return outside;
  })).toEqual([]);
}

test('Home and Back flank Up in the first D-pad row with original key routing and a centered header', async ({ page }) => {
  await page.clock.install();
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page);
  await expect(page.locator('.app-header [data-key]')).toHaveCount(0);
  expect(await page.locator('.dpad > button').evaluateAll(buttons => buttons.slice(0, 3).map(button => button.dataset.key))).toEqual(['home', 'up', 'back']);
  for (const viewport of [{ width: 320, height: 592 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    const boxes = await Promise.all(['home', 'up', 'back'].map(key => page.locator(`.dpad [data-key="${key}"]`).boundingBox()));
    expect(boxes[0].x).toBeLessThan(boxes[1].x); expect(boxes[1].x).toBeLessThan(boxes[2].x);
    expect(Math.abs(boxes[0].y - boxes[1].y)).toBeLessThan(1); expect(Math.abs(boxes[2].y - boxes[1].y)).toBeLessThan(1);
    const brand = await page.locator('.brand').boundingBox();
    expect(Math.abs(brand.x + brand.width / 2 - viewport.width / 2)).toBeLessThan(1);
    await fits(page);
  }
  await page.locator('.dpad [data-key="home"]').click();
  await expect(page.locator('#message')).toHaveText('Remote command sent.');
  await page.clock.runFor(150);
  await page.locator('.dpad [data-key="back"]').click();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'luma.key').map(call => call.params))).toEqual([{ key: 'home' }, { key: 'back' }]);
});

test('unavailable Philips receiver becomes setup and never attempts a failing browser launch', async ({ page }) => {
  const matter = { available: true, paired: true, connected: true, capabilities: { navigation: true } };
  await openApp(page, { selectedTarget: 'philips', tvConnected: false, textState: { matter } });
  await page.locator('#show-more').click();
  await expect(page.locator('#open-tv-receiver')).toHaveText('Set up TV receiver');
  await page.locator('#open-tv-receiver').click();
  await expect(page.locator('#msx-title')).toHaveText('Set up the Philips receiver');
  await expect(page.locator('#browser-steps')).toContainText('Open Internet on the TV');
  await expect(page.locator('#msx-type-address')).toHaveText('Type and open selected address');
  await expect(page.locator('#msx-startup')).toHaveText('https://kiansheik.io/kodaklumaremote/tv.html');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'msx.launchBrowser'))).toBe(false);
});

test('one browser typing button offers stable and direct receiver addresses', async ({ page }) => {
  const matter = { available: true, paired: true, connected: true, capabilities: { navigation: true } };
  await openApp(page, { selectedTarget: 'philips', tvConnected: false, textState: {
    matter, startupUrl: 'http://192.168.1.10:4178', configured: true, pairingOpen: true, textPermissionSaved: true, typingAvailable: true,
  } });
  await page.locator('#show-more').click();
  await page.locator('#open-tv-receiver').click();
  await expect(page.locator('#receiver-address-stable')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#msx-type-address').click();
  await expect(page.locator('#msx-type-status')).toHaveText('Address typed and Go sent. Waiting for the TV browser…');
  await page.locator('#receiver-address-direct').click();
  await expect(page.locator('#msx-startup')).toHaveText('http://192.168.1.10:4178/tv/connect.html');
  await page.locator('#msx-type-address').click();
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'matter.key').length)).toBe(2);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.typeBrowserAddress').map(call => call.params))).toEqual([
    { address: 'stable' }, { address: 'direct' },
  ]);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'matter.key').map(call => call.params))).toEqual([
    { key: 'enter' }, { key: 'enter' },
  ]);
  await expect(page.locator('body')).not.toContainText('Media Station X');
});

test('first address-typing action prepares the receiver and requests one-time TV permission', async ({ page }) => {
  const matter = { available: true, paired: true, connected: true, capabilities: { navigation: true } };
  await openApp(page, { selectedTarget: 'philips', tvConnected: false, textState: { matter, configured: false, pairingOpen: false } });
  await page.evaluate(() => { window.__typingSetup = true; });
  await page.locator('#show-more').click();
  await page.locator('#open-tv-receiver').click();
  await page.locator('#msx-type-address').click();
  await expect(page.locator('#msx-text-form')).toBeVisible();
  expect(await page.evaluate(() => window.__calls.filter(call => ['msx.start', 'msx.textPairRequest'].includes(call.method)).map(call => call.method))).toEqual(['msx.start', 'msx.textPairRequest']);
});

test('Turn on TV requires its advertised discrete capability and stays separate from browser launch', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { selectedTarget: 'philips' });
  await page.locator('#show-more').click();
  await expect(page.locator('#turn-on-tv')).toBeHidden();
  await page.locator('#turn-on-tv').dispatchEvent('click');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'matter.powerOn'))).toBe(false);
  await page.evaluate(() => {
    window.__matter = { available: true, paired: true, connected: false, paused: true, capabilities: { powerOn: true } };
    window.LumaApp.onConnectionState({ receivers: { philips: { ...window.__tv, matter: window.__matter } } });
  });
  await expect(page.locator('#turn-on-tv')).toBeEnabled();
  await fits(page);
  await page.locator('#turn-on-tv').click();
  await expect(page.locator('#message')).toHaveText('TV accepted the turn-on request. Check its screen.');
  expect(await page.evaluate(() => window.__calls.filter(call => /^matter\./.test(call.method)).map(call => [call.method, call.params]))).toEqual([
    ['matter.status', { reconnect: true }], ['matter.powerOn', {}],
  ]);
  expect(await page.evaluate(() => window.__calls.some(call => ['msx.launchBrowser', 'msx.open', 'matter.powerOff'].includes(call.method)))).toBe(false);
  await page.evaluate(() => { window.__powerOnError = 'MATTER_NOT_CONNECTED'; });
  await page.locator('#turn-on-tv').click();
  await expect(page.locator('#message')).toContainText('Ligue com LAN');
  await page.evaluate(() => { window.__matter.capabilities.powerOn = 'true'; window.LumaApp.onConnectionState({ receivers: { philips: { ...window.__tv, matter: window.__matter } } }); });
  await expect(page.locator('#turn-on-tv')).toBeHidden();
});

test('sample stays inside the connected Philips app flow and waits for its real receipt before showing playback controls', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page);
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await expect(page.locator('#msx-sample')).toBeHidden();
  await page.locator('#target-philips').click();
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await expect(page.locator('#msx-sample')).toBeEnabled();
  await fits(page);
  await page.evaluate(() => { window.__holdMethod = 'msx.sample'; });
  await page.locator('#msx-sample').click();
  await expect(page.locator('#message')).toHaveText('Starting the sample on Philips TV…');
  await expect(page.locator('#msx-sample')).toBeDisabled();
  await expect(page.locator('#target-luma')).toBeDisabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.sample').map(call => call.params))).toEqual([{}]);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#page-remote')).toBeVisible();
  await expect(page.locator('#media-title')).toHaveText('Public sample');
  await expect(page.locator('#message')).toHaveText('Sample is playing on Philips TV. Your remote is ready.');
  await page.locator('#play-toggle').click();
  await expect(page.locator('#play-toggle')).toHaveText('Resume');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.playPause').map(call => call.params))).toEqual([{ play: false, expectedSourceUrl: 'https://media.example.org/public-sample.mp4' }]);
  expect(await page.evaluate(() => window.__calls.some(call => /^(receiver|luma)\./u.test(call.method)))).toBe(false);
});

test('sample is unavailable without a connected Philips receiver and failed autoplay is never called success', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips', tvConnected: false });
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await expect(page.locator('#msx-sample')).toBeVisible();
  await expect(page.locator('#msx-sample')).toBeDisabled();
  await page.evaluate(() => { window.__tv.connected = true; window.__sampleFailure = true; window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } }); });
  await page.locator('#msx-sample').click();
  await expect(page.locator('#message')).toHaveText('Press “Start playback” on the TV, then refresh playback here.');
  await expect(page.locator('#page-browse')).toBeVisible();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.sample').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'msx.status'))).toBe(true);
});

test('a sample bridge timeout cannot be cleared by old status or replayed before a new Open receipt', async ({ page }) => {
  await page.clock.install();
  await openApp(page, { selectedTarget: 'philips' });
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await page.evaluate(() => { window.__holdMethod = 'msx.sample'; window.__tv.lastCommand = { id: 10, method: 'open', ok: true }; window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } }); });
  await page.locator('#msx-sample').click();
  await page.clock.runFor(61000);
  await expect(page.locator('#message')).toContainText('has not confirmed');
  await expect(page.locator('#msx-sample')).toBeDisabled();
  await page.evaluate(() => document.querySelector('#msx-sample').dispatchEvent(new Event('click')));
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.sample').length)).toBe(1);
  await page.evaluate(() => { window.__release(); window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } }); });
  await expect(page.locator('#msx-sample')).toBeEnabled();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.sample').length)).toBe(1);
});

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
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'msx.volume').length)).toBe(1);
  await page.evaluate(() => window.LumaApp.onConnectionState({ connection: { connected: false }, playback: { active: false } }));
  await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'false');
  await expect(page.locator('#device-badge')).toHaveAttribute('data-connected', 'true');
  await expect(page.locator('#media-title')).toHaveText('Already on TV');
  await page.locator('#target-luma').click();
  await expect(page.locator('#media-title')).toHaveText('LUMA episode');
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  const calls = await page.evaluate(() => window.__calls);
  expect(calls.filter(call => /disconnect|open|receiver\.|luma\./u.test(call.method))).toEqual([]);
  expect(calls.filter(call => call.method === 'msx.playPause').map(call => call.params)).toEqual([{ play: false, expectedSourceUrl: 'https://media.example.org/existing-tv.mp4' }]);
  expect(calls.filter(call => call.method === 'msx.volume').map(call => call.params)).toEqual([{ value: 41, expectedSourceUrl: 'https://media.example.org/existing-tv.mp4' }]);
});

test('TV volume combines taps after a cooldown and changing target cancels an unsent change', async ({ page }) => {
  await page.clock.install();
  await openApp(page, { selectedTarget: 'philips' });
  await page.locator('#volume-up').click();
  await page.locator('#volume-up').click();
  await page.locator('#volume-down').click();
  await page.evaluate(() => window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } }));
  await expect(page.locator('#volume-level')).toHaveText('41%');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.volume'))).toEqual([]);
  await page.clock.runFor(2100);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.volume').map(call => call.params.value))).toEqual([41]);
  await page.locator('#volume-up').click();
  await page.evaluate(() => { window.__holdMethod = 'app.selectTarget'; });
  await page.locator('#target-luma').click();
  await page.clock.runFor(2100);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.volume').length)).toBe(1);
  await page.evaluate(() => window.__release());
  await expect(page.locator('#target-luma')).toHaveAttribute('aria-pressed', 'true');
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
  expect(calls.find(call => call.method === 'msx.open').params).toEqual({ sourceToken: 'attempt-tv', source: tvMedia.url, mimeType: tvMedia.contentType, title: tvMedia.title, subtitles: tvMedia.tracks, preferredLanguage: 'pt-BR', autoSubtitles: false });
  expect(calls.find(call => call.method === 'app.handoffReady').params).toEqual({ target: 'philips', sourceToken: 'attempt-tv', resumeApplied:true });
  expect(calls.find(call => call.method === 'msx.subtitle').params).toEqual({ index: 0, enabled: true, expectedSourceUrl: tvMedia.url });
  expect(calls.some(call => /^(receiver|luma)\./u.test(call.method))).toBe(false);
});

test('offline Library opens from Remote and Browse and shows exact episode progress without changing playback', async ({page}) => {
  const network=[]; page.on('request',request=>network.push(request.url()));
  await page.setViewportSize({width:360,height:640}); await openApp(page,{selectedTarget:'philips'});
  await page.evaluate(detail=>{window.__libraryItems=[detail];window.__libraryDetails=detail;},savedSeries);
  await page.locator('#open-library').click();
  await expect(page.locator('#sheet-title')).toHaveText('Library');
  await expect(page.locator('#history-list button')).toContainText('Cached household series · S1 E2 · Saved at · 2:05 / 22:35 · Last on Philips TV');
  await page.locator('#history-list button').click();
  await expect(page.locator('#history-title')).toHaveText(savedSeries.title);
  await expect(page.locator('#history-overview')).toHaveText(savedSeries.overview);
  await expect(page.locator('#history-progress')).toContainText('S1 E2 · Saved at · 2:05 / 22:35');
  await expect(page.locator('#history-target')).toHaveText('Selected device: Philips TV.');
  await expect(page.locator('#history-resume')).toHaveText('Resume S1 E2');
  await expect(page.locator('#history-next')).toHaveText('Play next episode · S2 E1');
  await expect(page.locator('#history-episodes button').first()).toContainText('Watched · 22:35 / 22:35 · Last on Kodak LUMA');
  await page.locator('#history-episodes button').first().click();
  await expect(page.locator('#history-resume')).toHaveText('Watch again S1 E1');
  await expect(page.locator('#history-progress')).toContainText('S1 E1 · Watched');
  await mkdir('.local/tv-hello/android-player',{recursive:true});
  await page.screenshot({path:'.local/tv-hello/android-player/phone-offline-library-360.png'});
  expect(await page.evaluate(()=>['#history-back','#history-resume','#history-restart','#history-next','#history-target'].every(selector=>{const rect=document.querySelector(selector).getBoundingClientRect();return rect.left>=0&&rect.right<=innerWidth+1&&rect.top>=0&&rect.bottom<=innerHeight+1}))).toBe(true);
  for(const height of [844,600]) {
    await page.setViewportSize({width:390,height});
    expect(await page.evaluate(()=>['#history-back','#history-resume','#history-restart','#history-next','#history-target'].every(selector=>{const rect=document.querySelector(selector).getBoundingClientRect();return rect.left>=0&&rect.right<=innerWidth+1&&rect.top>=0&&rect.bottom<=innerHeight+1}))).toBe(true);
    await page.screenshot({path:`.local/tv-hello/android-player/phone-offline-library-390x${height}.png`});
  }
  await page.locator('#history-season').selectOption('2'); await page.locator('#history-episodes button').click();
  await expect(page.locator('#history-resume')).toHaveText('Play S2 E1');
  await expect(page.locator('#history-progress')).toHaveText('S2 E1 · Not watched yet');
  expect(await page.evaluate(()=>window.__calls.some(call=>['app.chooseCatalog','msx.open','receiver.open'].includes(call.method)))).toBe(false);
  expect(await page.evaluate(()=>window.__tv.playback.source)).toBe('https://media.example.org/existing-tv.mp4');
  expect(network.every(url=>new URL(url).origin===origin)).toBe(true);
  await page.locator('#close-sheet').click(); await page.getByRole('tab',{name:'Browse',exact:true}).click(); await page.locator('#show-history').click();
  await expect(page.locator('#history-list button')).toHaveCount(1);
});

test('Library Start over explicitly skips saved resume and sends one Philips Open', async ({page}) => {
  await openApp(page,{selectedTarget:'philips'});
  await page.evaluate(({detail,media})=>{window.__libraryItems=[detail];window.__libraryDetails=detail;window.__catalogMedia={...media,resumeSeconds:125};},{detail:savedSeries,media:tvMedia});
  await page.locator('#open-library').click(); await page.locator('#history-list button').click(); await page.locator('#history-restart').click();
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  const calls=await page.evaluate(()=>window.__calls);
  expect(calls.find(call=>call.method==='app.chooseCatalog').params).toEqual({kind:'tv',id:'84',season:1,episode:2,resume:false});
  expect(calls.filter(call=>call.method==='msx.open')).toHaveLength(1);
  expect(calls.some(call=>call.method==='msx.seek'||call.method==='receiver.open')).toBe(false);
});

test('Library next episode uses the known typed selection and the selected Kodak target', async ({page}) => {
  await openApp(page,{selectedTarget:'luma'});
  await page.evaluate(({detail,media})=>{window.__libraryItems=[detail];window.__libraryDetails=detail;window.__catalogMedia={...media,resumeSeconds:0};},{detail:savedSeries,media:tvMedia});
  await page.locator('#open-library').click(); await page.locator('#history-list button').click();
  await expect(page.locator('#history-target')).toHaveText('Selected device: Kodak LUMA.');
  await page.locator('#history-next').click();
  await expect(page.locator('#message')).toHaveText('Video is playing. Choose a language in Subtitles.');
  const calls=await page.evaluate(()=>window.__calls);
  expect(calls.find(call=>call.method==='app.chooseCatalog').params).toEqual({kind:'tv',id:'84',season:2,episode:1,resume:true});
  expect(calls.filter(call=>call.method==='receiver.open')).toHaveLength(1);
  expect(calls.some(call=>call.method==='msx.open')).toBe(false);
});

test('offline Library pages and searches every saved title, with metadata fallback and unavailable storage guidance', async ({page}) => {
  await openApp(page,{selectedTarget:'philips'});
  await page.evaluate(()=>{window.__libraryItems=Array.from({length:51},(_,index)=>({kind:'movie',id:String(index+1),title:`Saved movie ${index+1}`,metadataAvailable:false,latest:null}));});
  await page.locator('#open-library').click(); await expect(page.locator('#history-list button')).toHaveCount(50);
  await page.locator('#history-more').click(); await expect(page.locator('#history-list button')).toHaveCount(51);
  await page.locator('#history-search').fill('Saved movie 51'); await page.locator('#history-search-form button').click();
  await expect(page.locator('#history-list button')).toHaveText('Saved movie 51 · Movie · Not watched yet');
  await page.evaluate(()=>{window.__libraryDetails={version:1,storageAvailable:true,kind:'movie',id:'51',title:'Saved movie 51',year:'',overview:'',metadataAvailable:false,latest:null,movieProgress:null,seasons:[],nextEpisode:null};});
  await page.locator('#history-list button').click();
  await expect(page.locator('#history-overview')).toHaveText('No description has been saved yet.');
  await expect(page.locator('#history-detail-status')).toContainText('More title details have not been saved yet');
  await expect(page.locator('#history-next')).toBeHidden();
  expect(await page.evaluate(()=>window.__calls.some(call=>call.method==='app.chooseCatalog'))).toBe(false);
  const reads=await page.evaluate(()=>window.__calls.filter(call=>call.method==='watch.library').map(call=>call.params));
  expect(reads).toEqual([{}, {offset:50,limit:50,query:''}, {query:'Saved movie 51'}]);
  await page.locator('#close-sheet').click(); await page.evaluate(()=>{window.__libraryUnavailable=true;window.__libraryItems=[];}); await page.locator('#open-library').click();
  await expect(page.locator('#history-status')).toHaveText('Library storage is unavailable on this phone.');
});

test('Continue Watching resolves typed native content, resumes Philips once and verifies saved position', async ({page}) => {
  await openApp(page,{selectedTarget:'philips'});
  const content={key:'framextv:tv:84:0:1',kind:'tv',contentId:'84',season:0,episode:1,watchUrl:'https://framextv.cc/watch/84/0/1',title:'Saved household series'};
  await page.evaluate(({content,media})=>{window.__historyItems=[{...content,resumeSeconds:125,completed:false}];window.__catalogMedia={...media,content,resumeSeconds:125};},{content,media:tvMedia});
  await page.getByRole('tab',{name:'Browse',exact:true}).click(); await page.locator('#show-history').click();
  await expect(page.locator('#history-list button')).toHaveText('Saved household series · S0 E1 · Saved at · 2:05 / 10:00');
  await page.locator('#history-list button').click();
  await page.locator('#history-resume').click();
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  await expect(page.locator('#seek-time')).toHaveText('2:05');
  const calls=await page.evaluate(()=>window.__calls);
  expect(calls.find(call=>call.method==='app.chooseCatalog').params).toEqual({kind:'tv',id:'84',season:0,episode:1,resume:true});
  expect(calls.find(call=>call.method==='app.chooseCatalog').timeoutMs).toBeUndefined();
  expect(calls.filter(call=>call.method==='msx.open')).toHaveLength(1);
  expect(calls.filter(call=>call.method==='msx.seek').map(call=>call.params)).toEqual([{seconds:125,expectedSourceUrl:tvMedia.url}]);
  expect(calls.find(call=>call.method==='app.handoffReady').params.resumeApplied).toBe(true);
  expect(calls.findIndex(call=>call.method==='msx.seek')).toBeLessThan(calls.findIndex(call=>call.method==='app.handoffReady'));
});

test('failed resume keeps playing without replay or binding saved progress; cancelled selection discards late media', async ({page}) => {
  await page.clock.install(); await openApp(page,{selectedTarget:'philips'});
  await page.evaluate(media=>{window.__seekNoEffect=true;window.LumaApp.onCapturedMedia({...media,resumeSeconds:125});},tvMedia);
  await expect.poll(()=>page.evaluate(()=>window.__calls.filter(call=>call.method==='msx.seek').length)).toBe(1);
  await page.clock.runFor(1100);
  await expect(page.locator('#message')).toContainText('saved position could not be restored');
  expect(await page.evaluate(()=>window.__calls.filter(call=>call.method==='msx.open').length)).toBe(1);
  expect(await page.evaluate(()=>window.__calls.find(call=>call.method==='app.handoffReady').params.resumeApplied)).toBe(false);
  await page.evaluate(()=>{window.__historyItems=[{key:'framextv:movie:42',kind:'movie',contentId:'42',season:null,episode:null,watchUrl:'https://framextv.cc/watch/42',title:'Another title',resumeSeconds:0}];window.__holdMethod='app.chooseCatalog';});
  await page.getByRole('tab',{name:'Browse',exact:true}).click(); await page.locator('#show-history').click(); await page.locator('#history-list button').click(); await page.locator('#history-resume').click();
  await expect(page.locator('#history-detail-status')).toContainText('Finding this video');
  await page.locator('#close-sheet').click();
  await expect.poll(()=>page.evaluate(()=>window.__calls.some(call=>call.method==='app.cancelCatalog'))).toBe(true);
  await page.evaluate(()=>window.__release());
  expect(await page.evaluate(()=>window.__calls.filter(call=>call.method==='msx.open').length)).toBe(1);
  await expect(page.locator('#target-luma')).toBeEnabled();
});

test('disconnected Philips snapshot near the requested position cannot confirm resume', async ({page}) => {
  await page.clock.install(); await openApp(page,{selectedTarget:'philips'});
  await page.evaluate(media=>{window.__disconnectAfterSeek=true;window.LumaApp.onCapturedMedia({...media,resumeSeconds:125});},tvMedia);
  await expect.poll(()=>page.evaluate(()=>window.__calls.some(call=>call.method==='msx.seek'))).toBe(true);
  await page.clock.runFor(1100);
  await expect(page.locator('#message')).toContainText('saved position could not be restored');
  expect(await page.evaluate(()=>window.__tv.playback.positionSeconds)).toBe(125);
  expect(await page.evaluate(()=>window.__calls.find(call=>call.method==='app.handoffReady').params.resumeApplied)).toBe(false);
});

test('Philips subtitles load only on selection with a full native deadline and Off never reopens video', async ({ page }) => {
  await page.clock.install();
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'msx.subtitle'))).toBe(false);
  await page.locator('#show-subtitles').click();
  await expect(page.locator('#subtitles-off')).toHaveAttribute('aria-pressed', 'true');
  await page.evaluate(() => { window.__holdMethod = 'msx.subtitle'; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await page.clock.runFor(15000);
  await expect(page.locator('#message')).toHaveText('Loading the selected language…');
  await expect(page.locator('#target-luma')).toBeDisabled();
  await expect(page.locator('#subtitles-off')).toBeDisabled();
  await page.evaluate(() => window.__release());
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#subtitles-off').click();
  await expect(page.locator('#message')).toHaveText('Subtitles turned off.');
  await expect(page.locator('#subtitles-off')).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.subtitle').map(call => call.params))).toEqual([
    { index: 0, enabled: true, expectedSourceUrl: tvMedia.url }, { index: 0, enabled: false, expectedSourceUrl: tvMedia.url },
  ]);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.open').length)).toBe(1);
  await expect(page.locator('#media-title')).toHaveText(tvMedia.title);
});

test('Philips subtitle failures and invalid receipts do not reopen or relabel playing video', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  await page.locator('#show-subtitles').click();
  await page.evaluate(() => { window.__subtitleError = 'SUBTITLE_FAILED'; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.locator('#message')).toContainText('Video playback is unchanged');
  await expect(page.getByRole('button', { name: 'English', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('#media-title')).toHaveText(tvMedia.title);
  await expect(page.locator('#play-toggle')).toHaveText('Pause');
  await page.evaluate(() => { window.__subtitleError = null; window.__subtitleBadReceipt = true; });
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.locator('#message')).toContainText('could not finish');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.open').length)).toBe(1);
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.subtitle').length)).toBe(2);
});

test('browser receiver requires matching TV approval without disturbing LUMA', async ({ page }) => {
  const matter = { available: true, paired: true, connected: true, capabilities: { navigation: true } };
  await openApp(page, { selectedTarget: 'philips', tvConnected: false, textState: { matter } });
  await page.locator('#tab-devices').dispatchEvent('click');
  await page.locator('#tv-setup-video').dispatchEvent('click');
  await page.evaluate(() => {
    window.__tv.startupUrl = 'http://192.168.1.10:4178'; window.__tv.browserSetupUrl = 'http://192.168.1.10:4178/tv/connect.html';
    window.__tv.awaitingApproval = true; window.__tv.pendingHost = '192.168.1.60';
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  });
  await expect(page.locator('#msx-pairing')).toContainText('192.168.1.60');
  await expect(page.locator('#msx-confirm')).toBeVisible();
  await page.locator('#msx-confirm').dispatchEvent('click');
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
  expect(await page.evaluate(() => window.__calls.some(call => /^(receiver|luma)\./u.test(call.method)))).toBe(false);
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

test('Philips scrubbing and subtitle size go to the TV and never mirror to or configure LUMA Receiver', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips' });
  const range = page.getByLabel('Playback position');
  await expect(range).toBeEnabled();
  await range.evaluate(element => { element.value = '120'; element.dispatchEvent(new Event('input', { bubbles: true })); });
  await expect(page.locator('#scrub-preview')).toBeVisible();
  await expect(page.locator('#scrub-preview-time')).toHaveText(/^2:00 \/ /);
  await range.evaluate(element => element.dispatchEvent(new Event('pointerup', { bubbles: true })));
  await expect(page.locator('#scrub-preview')).toBeHidden();
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.scrub').map(call => call.params.active))).toContain(true);
  await page.locator('#show-subtitles').click();
  await expect(page.locator('#subtitle-size')).toBeVisible();
  await page.getByRole('button', { name: 'Larger subtitles', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__calls.find(call => call.method === 'msx.display')?.params)).toEqual({ subtitleScale: 1.1 });
  await page.locator('#close-sheet').click();
  expect(await page.evaluate(() => window.__calls.filter(call => ['receiver.scrub', 'receiver.display'].includes(call.method)))).toEqual([]);
  // The same gesture on LUMA does mirror, so the absence above is target-specific.
  await page.locator('#target-luma').click();
  await expect(page.locator('#target-luma')).toHaveAttribute('aria-pressed', 'true');
  await range.evaluate(element => { element.value = '60'; element.dispatchEvent(new Event('input', { bubbles: true })); });
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'receiver.scrub').map(call => call.params))).toEqual([{ active: true, positionSeconds: 60, durationSeconds: 600 }]);
});

test('Philips brightness uses the same 2% series preference with a guarded display command', async ({ page }) => {
  const episode1 = {key:'framextv:tv:84:1:1',kind:'tv',contentId:'84',season:1,episode:1,watchUrl:'https://framextv.cc/watch/84/1/1',title:'TV series · S1 E1'};
  const episode2 = {...episode1,key:'framextv:tv:84:1:2',episode:2,watchUrl:'https://framextv.cc/watch/84/1/2',title:'TV series · S1 E2'};
  await openApp(page, { selectedTarget: 'philips' });
  await page.evaluate(({media,content}) => window.LumaApp.onCapturedMedia({...media,content}), {media:tvMedia,content:episode1});
  await expect(page.locator('#message')).toContainText('Ready on Philips TV');
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'msx.display').at(-1)?.params)).toEqual({ brightness: 1, expectedSourceUrl: tvMedia.url });
  await page.getByRole('button', { name: 'More', exact: true }).click();
  await page.getByRole('button', { name: 'Brighter', exact: true }).click();
  await expect(page.locator('#brightness-value')).toHaveText('Brightness +2%');
  await expect.poll(() => page.evaluate(() => window.__picturePreferences?.['framextv:tv:84']?.brightness)).toBe(0.02);
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  const secondUrl = 'https://media.example.org/philips-2.m3u8';
  await page.evaluate(({media,content,url}) => window.LumaApp.onCapturedMedia({...media,url,sourceToken:'capture-tv-2',content}), {media:tvMedia,content:episode2,url:secondUrl});
  await expect.poll(() => page.evaluate(() => window.__calls.filter(call => call.method === 'msx.display').at(-1)?.params)).toEqual({ brightness: 1.02, expectedSourceUrl: secondUrl });
});

test('Philips setup, approval and remote panels fit a narrow phone without scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  const matter = { available: true, paired: true, connected: true, capabilities: { navigation: true } };
  await openApp(page, { selectedTarget: 'philips', tvConnected: false, textState: { matter } });
  await fits(page);
  await page.locator('#tab-devices').dispatchEvent('click');
  await page.locator('#tv-setup-video').dispatchEvent('click');
  await page.evaluate(() => {
    window.__tv.awaitingApproval = true; window.__tv.pendingHost = '192.168.1.60';
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  });
  await fits(page);
  await expect(page.locator('#msx-confirm')).toBeVisible();
  await page.locator('#msx-confirm').dispatchEvent('click');
  await fits(page);
  await page.evaluate(media => window.LumaApp.onCapturedMedia(media), tvMedia);
  await expect(page.locator('#message')).toHaveText('Ready on Philips TV. You can use the remote.');
  await page.locator('#show-subtitles').click();
  await fits(page);
  await page.locator('#close-sheet').click();
  await page.locator('#show-more').click();
  await fits(page);
  await expect(page.getByRole('button', { name: 'Type text', exact: true })).toBeHidden();
  await page.locator('#close-sheet').click();
  await expect(page.getByLabel('Playback position')).toBeVisible();
  await fits(page);
});

test('TV setup diagnostics show only observed fixed guidance and keep narrow-phone controls visible', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { selectedTarget: 'philips', tvConnected: false });
  await expect(page.locator('#browser-steps')).toContainText('Open Internet on the TV');
  await expect(page.locator('#msx-diagnostic')).toBeHidden();
  const cases = [
    ['HTTPS_REQUEST', 'direct phone address uses http'],
    ['ORIGIN_REJECTED', 'app connection was rejected'],
    ['PAIRING_EXPIRED', 'Tap Type and open selected address'],
    ['BOOTSTRAP_READY', 'Open “Connect this TV”'],
  ];
  for (const [lastReason, hint] of cases) {
    await page.evaluate(reason => window.LumaApp.onConnectionState({ receivers: { philips: {
      ...window.__tv, startupUrl: 'http://192.168.1.10:4178',
      diagnostics: { connections: 1, lastReason: reason, lastOrigin: 'https://private-origin.example.org/path?token=private-diagnostic-token', lastMessage: '<img src=x onerror="window.diagnosticExecuted=true">' },
    } } }), lastReason);
    await expect(page.locator('#msx-diagnostic')).toContainText(hint);
    await fits(page);
  }
  await page.evaluate(() => window.LumaApp.onConnectionState({ receivers: { philips: {
    ...window.__tv, startupUrl: 'http://192.168.1.10:4178', diagnostics: { connections: 0, lastReason: 'https://private-origin.example.org/unknown?token=private-diagnostic-token' },
  } } }));
  await expect(page.locator('#msx-diagnostic')).toBeHidden();
  await expect(page.locator('#msx-type-address')).toBeDisabled();
  await expect(page.locator('#msx-type-status')).toContainText('Tap Type and open selected address');
  expect(await page.locator('body').innerText()).not.toContain('private-origin');
  expect(await page.locator('body').innerText()).not.toContain('private-diagnostic-token');
  expect(await page.evaluate(() => window.diagnosticExecuted)).toBeUndefined();
});

test('receiver diagnostics distinguish saved approval without current state from an actual fresh TV report', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { selectedTarget: 'philips', tvConnected: false, textState: {
    startupUrl: 'http://192.168.1.10:4178', configured: true, pairingOpen: false, textPermissionSaved: true, typingAvailable: true,
  } });
  const report = async (connected, diagnostics) => page.evaluate(({ connected, diagnostics }) => {
    window.__tv.connected = connected;
    window.__tv.diagnostics = diagnostics;
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  }, { connected, diagnostics });
  const diagnostics = { bootstrapAccepted: 1, helloAccepted: 1, pollAccepted: 3, stateAccepted: 9,
    lastHelloMsAgo: 100, lastPollMsAgo: 30, lastStateMsAgo: -1, sessionPresent: true, sessionApproved: true,
    lastReason: 'POLL_READY', sessionId: 'private-session-token', lastOrigin: 'https://private-diagnostic.example.org/?token=secret' };
  await report(false, diagnostics);
  await expect(page.locator('#msx-diagnostic')).toHaveText('TV allowed. Waiting for receiver status.');
  await expect(page.locator('#target-philips')).toHaveAttribute('data-connected', 'false');
  await page.getByRole('tab', { name: 'Browse', exact: true }).click();
  await expect(page.locator('#msx-sample')).toBeDisabled();
  await page.getByRole('tab', { name: 'Devices', exact: true }).click();
  await fits(page);

  await report(true, { ...diagnostics, stateAccepted: 10, lastStateMsAgo: 0, lastReason: 'STATE_READY' });
  await expect(page.locator('#target-philips')).toHaveAttribute('data-connected', 'true');
  await page.locator('#show-tv-setup').click();
  await expect(page.locator('#msx-diagnostic')).toHaveText('Receiver status received. TV connection ready.');
  await fits(page);

  await report(false, { ...diagnostics, stateAccepted: 10, lastStateMsAgo: 13000 });
  await expect(page.locator('#msx-diagnostic')).toHaveText('TV status is no longer current. Waiting for the receiver.');
  await report(false, { ...diagnostics, stateAccepted: 100, sessionPresent: false, sessionApproved: false });
  await expect(page.locator('#msx-diagnostic')).toHaveText('TV reached this phone. Waiting for receiver connection.');
  await report(false, { ...diagnostics, sessionApproved: false });
  await expect(page.locator('#msx-diagnostic')).toHaveText('TV reached this phone. Approval needed.');
  await fits(page);
  const text = await page.locator('body').innerText();
  expect(text).not.toContain('private-session-token');
  expect(text).not.toContain('private-diagnostic.example.org');
  expect(await page.evaluate(() => window.__calls.some(call => call.method === 'msx.confirm' || call.method === 'msx.sample' || call.method === 'msx.launchBrowser'))).toBe(false);
});

test('developer tools stay collapsed and require observed receiver capabilities with safe revisions', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { selectedTarget: 'philips', textState: { playback: { active: false } } });
  await page.locator('#show-tv-setup').click();
  await expect(page.locator('#msx-developer')).toHaveJSProperty('open', false);
  await page.locator('#msx-developer > summary').click();
  await expect(page.locator('#msx-setup-fields')).toBeHidden();
  for (const id of ['ping', 'label', 'reload']) {
    await expect(page.locator('#msx-debug-' + id)).toBeDisabled();
    await page.locator('#msx-debug-' + id).dispatchEvent('click');
  }
  await expect(page.locator('#msx-debug-support')).toContainText('not reported');
  await fits(page);
  await page.evaluate(() => {
    window.__tv.diagnostics = { assetRevision: 'b'.repeat(64) };
    window.__tv.playback = { active: false, receiverDebug: { revision: 'a'.repeat(64), uptimeSeconds: 35.9, pingCount: 2, remoteReload: true, remoteCommands: true, inputCount: 5, lastInput: 'pointer-arrow', pointerLockSupported: true, pointerLocked: false, pointerMoveCount:9, pointerLockError:'not-allowed' } };
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  });
  await expect(page.locator('#msx-debug-revisions')).toHaveText('Loaded: aaaaaaaaaa · Server: bbbbbbbbbb · Revision mismatch.');
  await expect(page.locator('#msx-debug-runtime')).toHaveText('Uptime: 35s · Ping count: 2');
  await expect(page.locator('#msx-debug-support')).toContainText('reported by this page');
  await expect(page.locator('#msx-debug-support')).toContainText('Inputs: 5 · Last: pointer-arrow.');
  await expect(page.locator('#msx-debug-support')).toContainText('Pointer lock: API present.');
  await expect(page.locator('#msx-debug-support')).toContainText('Pointer moves: 9. Pointer capture: not-allowed.');
  await fits(page);
  await expect(page.locator('#msx-debug-reload')).toBeEnabled();
  for (const uptime of [-0.1, 315360000.1, Infinity, NaN]) {
    await page.evaluate(uptimeSeconds => {
      window.__tv.playback.receiverDebug.uptimeSeconds = uptimeSeconds;
      window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
    }, uptime);
    await expect(page.locator('#msx-debug-runtime')).toHaveText('Uptime: not reported · Ping count: 2');
  }
  await page.evaluate(() => {
    window.__tv.diagnostics.assetRevision = 'https://private.example/revision?token=secret';
    window.__tv.playback.receiverDebug.revision = '<img src=x onerror="window.debugExecuted=true">';
    window.__tv.playback.receiverDebug.uptimeSeconds = 'private-token';
    window.__tv.playback.receiverDebug.pingCount = 2.5;
    window.__tv.playback.receiverDebug.inputCount = 1000000001;
    window.__tv.playback.receiverDebug.lastInput = '<private-input-text>';
    window.__tv.playback.receiverDebug.pointerLockSupported = 'true';
    window.__tv.playback.receiverDebug.pointerMoveCount = -1;
    window.__tv.playback.receiverDebug.pointerLockError = 'secret-native-error';
    window.__tv.playback.receiverDebug.pointerLocked = 1;
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  });
  await expect(page.locator('#msx-debug-revisions')).toHaveText('Loaded: not reported · Server: not reported');
  await expect(page.locator('#msx-debug-runtime')).toHaveText('Uptime: not reported · Ping count: not reported');
  await expect(page.locator('#msx-debug-support')).not.toContainText('Inputs:');
  await expect(page.locator('#msx-debug-support')).not.toContainText('private-input');
  await expect(page.locator('#msx-debug-support')).not.toContainText('Pointer lock:');
  await expect(page.locator('#msx-debug-support')).not.toContainText('Pointer moves:');
  await expect(page.locator('#msx-debug-support')).not.toContainText('secret-native-error');
  expect(await page.evaluate(() => window.debugExecuted)).toBeUndefined();
  expect(await page.locator('body').innerText()).not.toContain('private-token');
  expect(await page.evaluate(() => window.__calls.some(call => /^msx\.(debugPing|debugLabel|reloadReceiver)$/.test(call.method)))).toBe(false);
  await page.evaluate(() => {
    window.__tv.connected = false;
    window.__tv.playback = { active: false };
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  });
  await expect(page.locator('#msx-developer')).toBeHidden();
  await expect(page.locator('#msx-setup-fields')).toBeVisible();
});

test('developer tools send exact RPCs with a full acknowledgement budget and reload waits for reconnect', async ({ page }) => {
  await page.clock.install();
  await page.setViewportSize({ width: 320, height: 592 });
  await openApp(page, { selectedTarget: 'philips', textState: {
    diagnostics: { assetRevision: 'a'.repeat(64), helloAccepted: 8 },
    playback: { active: false, receiverDebug: { revision: 'a'.repeat(64), uptimeSeconds: 35, pingCount: 0, remoteReload: true, remoteCommands: true } },
  } });
  await page.locator('#show-tv-setup').click();
  await page.locator('#msx-developer > summary').click();
  await expect(page.locator('#msx-setup-fields')).toBeHidden();
  await page.locator('#msx-debug-ping').click();
  await expect(page.locator('#msx-debug-result')).toHaveText('TV ping acknowledged.');
  await expect(page.locator('#msx-debug-runtime')).toContainText('Ping count: 1');
  await page.locator('#msx-debug-label').click();
  await expect(page.locator('#msx-debug-result')).toHaveText('Test message acknowledged by the TV.');
  await page.evaluate(() => { window.__holdMethod = 'msx.reloadReceiver'; });
  await page.locator('#msx-debug-reload').click();
  for (const id of ['ping', 'label', 'reload']) await expect(page.locator('#msx-debug-' + id)).toBeDisabled();
  await page.clock.runFor(16000);
  await expect(page.locator('#msx-debug-result')).toHaveText('Waiting for the TV acknowledgement…');
  await page.evaluate(() => window.__release());
  await expect(page.locator('#msx-debug-result')).toHaveText('Reload acknowledged. Waiting for the TV to reconnect…');
  await expect(page.locator('#message')).toHaveText('Reload acknowledged. Waiting for the TV to reconnect…');
  await fits(page);
  const report = async (helloAccepted, connected = true, revision = 'a'.repeat(64)) => page.evaluate(({ helloAccepted, connected, revision }) => {
    window.__tv.connected = connected;
    window.__tv.diagnostics.helloAccepted = helloAccepted;
    window.__tv.playback.receiverDebug.revision = revision;
    window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } });
  }, { helloAccepted, connected, revision });
  await report(8, true, 'b'.repeat(64));
  await expect(page.locator('#msx-debug-result')).toContainText('Waiting for the TV to reconnect');
  await report(9, true, null);
  await expect(page.locator('#msx-debug-result')).toContainText('Waiting for the TV to reconnect');
  await report(9, false);
  await expect(page.locator('#msx-debug-result')).toContainText('Waiting for the TV to reconnect');
  await report(9);
  await expect(page.locator('#msx-debug-result')).toHaveText('TV page reconnected.');
  await expect(page.locator('#message')).toHaveText('TV page reconnected.');
  await page.locator('#show-tv-setup').click();
  await page.locator('#msx-debug-reload').click();
  await expect(page.locator('#msx-debug-result')).toContainText('Waiting for the TV to reconnect');
  await report(0);
  await report(10);
  await expect(page.locator('#msx-debug-result')).toContainText('Waiting for the TV to reconnect');
  await page.clock.runFor(30000);
  expect(await page.evaluate(() => window.__calls.filter(call => /^msx\.(debugPing|debugLabel|reloadReceiver)$/.test(call.method)).map(call => [call.method, call.params]))).toEqual([
    ['msx.debugPing', {}], ['msx.debugLabel', {}], ['msx.reloadReceiver', {}], ['msx.reloadReceiver', {}],
  ]);
  await page.locator('#msx-developer > summary').click();
  await page.locator('#msx-refresh').click();
  expect(await page.evaluate(() => window.__calls.at(-1).method)).toBe('msx.status');
});

test('developer tools block active reload and pending commands and never replay failed receipts', async ({ page }) => {
  await page.clock.install();
  await openApp(page, { selectedTarget: 'philips', textState: {
    playback: { active: true, receiverDebug: { revision: 'a'.repeat(64), uptimeSeconds: 20, pingCount: 0, remoteReload: true, remoteCommands: true } },
  } });
  await page.locator('#show-tv-setup').click();
  await page.locator('#msx-developer > summary').click();
  await expect(page.locator('#msx-debug-ping')).toBeEnabled();
  await expect(page.locator('#msx-debug-reload')).toBeDisabled();
  await page.locator('#msx-debug-reload').dispatchEvent('click');
  await page.evaluate(() => { window.__tv.pendingCommand = { id: 9, method: 'open' }; window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } }); });
  for (const id of ['ping', 'label', 'reload']) {
    await expect(page.locator('#msx-debug-' + id)).toBeDisabled();
    await page.locator('#msx-debug-' + id).dispatchEvent('click');
  }
  expect(await page.evaluate(() => window.__calls.some(call => /^msx\.(debugPing|debugLabel|reloadReceiver)$/.test(call.method)))).toBe(false);
  await page.evaluate(() => { window.__tv.pendingCommand = null; window.__debugBadReceipt = true; window.LumaApp.onConnectionState({ receivers: { philips: window.__tv } }); });
  await page.locator('#msx-debug-ping').click();
  await expect(page.locator('#msx-debug-result')).toContainText('did not confirm');
  await page.evaluate(() => { window.__debugBadReceipt = false; window.__debugError = 'MSX_TIMEOUT'; });
  await page.locator('#msx-debug-label').click();
  await expect(page.locator('#msx-debug-result')).toHaveText('TV command outcome is unknown. Check the TV before trying again.');
  await page.clock.runFor(30000);
  expect(await page.evaluate(() => window.__calls.filter(call => /^msx\.(debugPing|debugLabel|reloadReceiver)$/.test(call.method)).map(call => call.method))).toEqual(['msx.debugPing', 'msx.debugLabel']);
  await page.locator('#target-luma').click();
  await page.locator('#msx-debug-ping').dispatchEvent('click');
  expect(await page.evaluate(() => window.__calls.filter(call => call.method === 'msx.debugPing').length)).toBe(1);
});

test('read-only MSX status errors use fixed messages without replacing LUMA connection state', async ({ page }) => {
  await openApp(page, { selectedTarget: 'philips', tvConnected: false });
  await page.evaluate(() => window.LumaApp.onConnectionState({ receivers: { philips: { ...window.__tv, startupUrl: 'http://192.168.1.10:4178' } } }));
  for (const [code, text] of [
    ['MSX_NETWORK', 'Check this phone’s Wi-Fi'],
    ['MSX_PAIRING', 'Allow your TV'],
    ['MSX_UNAVAILABLE', 'The TV receiver is not connected'],
    ['MSX_REJECTED', 'TV could not finish this request'],
  ]) {
    await page.evaluate(value => { window.__statusError = value; }, code);
    await page.locator('#msx-refresh').click();
    await expect(page.locator('#message')).toContainText(text);
    await expect(page.locator('#target-luma')).toHaveAttribute('data-connected', 'true');
  }
  expect(await page.locator('body').innerText()).not.toContain('private-diagnostic-token');
  expect(await page.locator('body').innerText()).not.toContain('private.example.org');
});
