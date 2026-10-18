import { test, expect } from '@playwright/test';
import { createControllerServer, PUBLIC_SAMPLE_URL } from '../../scripts/kodi-controller.mjs';

const receiver = { host: '192.168.1.50', port: 8080, username: 'test-user', password: 'test-password' };
let server;
let controllerUrl;
let calls;
let state;

function time(total) {
  return { hours: Math.floor(total / 3600), minutes: Math.floor(total % 3600 / 60), seconds: total % 60, milliseconds: 0 };
}

async function fakeRpc(url, options) {
  const request = JSON.parse(options.body);
  calls.push({ url, authorization: options.headers.Authorization, ...request });
  if (state.fail) {
    return Response.json({ jsonrpc: '2.0', id: request.id, error: { message: state.fail } });
  }
  let result;
  switch (request.method) {
    case 'JSONRPC.Ping': result = 'pong'; break;
    case 'Application.GetProperties': result = { name: 'Kodi', version: { major: 21, minor: 3 } }; break;
    case 'Player.Open': if (state.openGate) await state.openGate; result = 'OK'; break;
    case 'Player.GetActivePlayers': result = state.active ? [{ playerid: 1, type: 'video' }] : []; break;
    case 'Player.GetProperties':
      result = {
        speed: state.playing ? 1 : 0, time: time(state.position), totaltime: time(600), canseek: true,
        subtitles: state.subtitleTracks, subtitleenabled: state.subtitleEnabled,
        currentsubtitle: state.subtitleTracks.find(track => track.index === state.subtitleIndex) || {}
      };
      break;
    case 'Player.SetSubtitle':
      state.subtitleEnabled = request.params.subtitle !== 'off';
      if (state.subtitleEnabled) state.subtitleIndex = request.params.subtitle;
      result = 'OK';
      break;
    case 'Player.AddSubtitle': if (state.subtitleGate) await state.subtitleGate; result = 'OK'; break;
    case 'Player.PlayPause': state.playing = request.params.play; result = { speed: state.playing ? 1 : 0 }; break;
    case 'Player.Seek': {
      const value = request.params.value.time;
      state.position = value.hours * 3600 + value.minutes * 60 + value.seconds;
      result = { time: time(state.position), totaltime: time(600), percentage: state.position / 6 };
      break;
    }
    case 'Player.Stop': state.active = false; state.playing = false; result = 'OK'; break;
    default: throw new Error(`Unexpected mocked Kodi method: ${request.method}`);
  }
  return Response.json({ jsonrpc: '2.0', id: request.id, result });
}

test.beforeAll(async () => {
  server = createControllerServer({ fetchImpl: fakeRpc });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  controllerUrl = `http://127.0.0.1:${server.address().port}`;
});

test.afterAll(async () => {
  if (server) await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
});

test.beforeEach(() => {
  calls = [];
  state = { active: false, playing: true, position: 12, fail: null, subtitleTracks: [], subtitleIndex: null, subtitleEnabled: false };
});

async function openController(page) {
  const requests = [];
  page.on('request', request => requests.push({ url: request.url(), type: request.resourceType() }));
  await page.route('**/*', route => new URL(route.request().url()).origin === controllerUrl
    ? route.continue() : route.abort());
  await page.goto(controllerUrl);
  return requests;
}

async function connect(page) {
  await page.getByLabel('Projector IP address').fill(receiver.host);
  await page.getByLabel('HTTP port').fill(String(receiver.port));
  await page.getByLabel('Username', { exact: true }).fill(receiver.username);
  await page.getByLabel('Password', { exact: true }).fill(receiver.password);
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Connected to Kodi (21.3).');
}

test('connection gates controls, receiver edits require reconnect, and the narrow page keeps credentials out of storage', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 700 });
  const requests = await openController(page);
  expect(await page.evaluate(() => window.request)).toBeUndefined();
  const play = page.getByRole('button', { name: 'Play Big Buck Bunny', exact: true });
  await expect(play).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Play video link', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Seek', exact: true })).toBeDisabled();
  expect(calls).toEqual([]);

  await connect(page);
  await expect(play).toBeEnabled();
  expect(calls.map(call => call.method)).toEqual(['JSONRPC.Ping', 'Application.GetProperties']);
  expect(calls.every(call => call.url === 'http://192.168.1.50:8080/jsonrpc')).toBe(true);
  expect(calls.every(call => call.authorization === `Basic ${Buffer.from('test-user:test-password').toString('base64')}`)).toBe(true);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  await page.getByLabel('Projector IP address').fill('192.168.1.51');
  await expect(play).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Play video link', exact: true })).toBeDisabled();
  await expect(page.locator('#message')).toHaveText('Receiver settings changed. Connect to continue.');
  expect(requests.every(request => new URL(request.url).origin === controllerUrl)).toBe(true);
  expect(requests.some(request => request.type === 'media')).toBe(false);
});

test('public playback waits for status and sends explicit pause, resume, absolute seek, and stop commands', async ({ page }) => {
  const requests = await openController(page);
  await connect(page);
  await page.getByRole('button', { name: 'Play Big Buck Bunny', exact: true }).click();
  await expect(page.locator('#message')).toContainText('Video requested. Confirm picture and sound');
  await expect(page.locator('#playback-status')).toHaveText('Waiting for Kodi to open the video. Refresh status to check playback.');
  expect(calls.filter(call => call.method === 'Player.Open').map(call => call.params)).toEqual([{ item: { file: PUBLIC_SAMPLE_URL } }]);
  expect(calls.some(call => call.method === 'Player.GetProperties')).toBe(false);

  await page.getByRole('button', { name: 'Refresh status', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Kodi is connected. No active video.');
  state.active = true;
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');

  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Paused · 0:12 / 10:00');
  await page.getByRole('button', { name: 'Resume', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  expect(calls.filter(call => call.method === 'Player.PlayPause').map(call => call.params)).toEqual([
    { playerid: 1, play: false }, { playerid: 1, play: true }
  ]);

  await page.getByLabel('Position in seconds').fill('65');
  await page.getByRole('button', { name: 'Seek', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Playing · 1:05 / 10:00');
  expect(calls.find(call => call.method === 'Player.Seek').params).toEqual({
    playerid: 1, value: { time: { hours: 0, minutes: 1, seconds: 5, milliseconds: 0 } }
  });

  await page.getByRole('button', { name: 'Stop', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Kodi is connected. No active video.');
  expect(calls.filter(call => call.method === 'Player.Stop').map(call => call.params)).toEqual([{ playerid: 1 }]);
  expect(requests.every(request => new URL(request.url).origin === controllerUrl)).toBe(true);
  expect(requests.some(request => request.type === 'media' || request.url === PUBLIC_SAMPLE_URL)).toBe(false);
  const commandCount = calls.length;
  await page.close();
  expect(calls).toHaveLength(commandCount);
});

test('raw receiver errors stay out of the page and leave playback controls disabled', async ({ page }) => {
  await openController(page);
  state.fail = 'private receiver password=test-password <img src=x onerror="window.rawErrorExecuted=true">';
  await page.getByLabel('Projector IP address').fill(receiver.host);
  await page.getByLabel('Username', { exact: true }).fill(receiver.username);
  await page.getByLabel('Password', { exact: true }).fill(receiver.password);
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Kodi did not accept this control command.');
  await expect(page.getByRole('button', { name: 'Play Big Buck Bunny', exact: true })).toBeDisabled();
  await expect(page.locator('body')).not.toContainText('private receiver');
  expect(await page.evaluate(() => window.rawErrorExecuted)).toBeUndefined();
});

test('custom HLS handoff sends its URL only to Kodi, gates busy controls and waits for status without saving or fetching media', async ({ page }) => {
  const requests = await openController(page);
  await connect(page);
  const url = 'https://media.example.org/declared.m3u8?token=private-source-token';
  const sourceButton = page.getByRole('button', { name: 'Play video link', exact: true });
  await page.getByLabel('Video link', { exact: true }).fill(`  ${url}  `);
  await expect(page.getByLabel('Video format', { exact: true })).toHaveValue('application/vnd.apple.mpegurl');
  let release;
  state.openGate = new Promise(resolve => { release = resolve; });
  try {
    await sourceButton.click();
    await expect.poll(() => calls.filter(call => call.method === 'Player.Open').length).toBe(1);
    await expect(sourceButton).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Play Big Buck Bunny', exact: true })).toBeDisabled();
    await expect(page.getByLabel('Video link', { exact: true })).toBeDisabled();
    await expect(page.getByLabel('Video format', { exact: true })).toBeDisabled();
    expect(calls.filter(call => call.method === 'Player.Open').map(call => call.params)).toEqual([{ item: { file: url } }]);
  } finally {
    release();
  }
  await expect(sourceButton).toBeEnabled();
  await expect(page.locator('#message')).toHaveText('Video requested. Confirm picture and sound on the projector, then refresh status.');
  await expect(page.locator('#playback-status')).toHaveText('Waiting for Kodi to open the video. Refresh status to check playback.');
  expect(calls.some(call => call.method === 'Player.GetProperties')).toBe(false);
  expect(calls.every(call => call.url === 'http://192.168.1.50:8080/jsonrpc')).toBe(true);
  expect(requests.every(request => new URL(request.url).origin === controllerUrl)).toBe(true);
  expect(requests.some(request => request.type === 'media' || request.url.includes('private-source-token'))).toBe(false);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, search: location.search }))).toEqual({ local: 0, session: 0, search: '' });
  await page.reload();
  await expect(page.getByLabel('Video link', { exact: true })).toHaveValue('');
});

test('malformed and unsupported video links cannot issue a Kodi command or expose source details in errors', async ({ page }) => {
  await openController(page);
  await connect(page);
  const before = calls.length;
  const sourceInput = page.getByLabel('Video link', { exact: true });
  await sourceInput.fill('not a URL');
  await page.getByRole('button', { name: 'Play video link', exact: true }).click();
  expect(await sourceInput.evaluate(input => input.validity.typeMismatch)).toBe(true);
  expect(calls).toHaveLength(before);
  await sourceInput.fill('https://127.0.0.1/video.m3u8?token=private-source-token');
  await page.getByRole('button', { name: 'Play video link', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Enter a supported public HTTP media source.');
  expect(calls).toHaveLength(before);
  await expect(page.locator('#message')).not.toContainText('private-source-token');
  await expect(page.locator('#playback-status')).toHaveText('Ready to play the public test video.');
  await sourceInput.fill('not a URL');
  await page.getByRole('button', { name: 'Play Big Buck Bunny', exact: true }).click();
  await expect(page.locator('#message')).toContainText('Video requested.');
  expect(calls.filter(call => call.method === 'Player.Open').map(call => call.params)).toEqual([{ item: { file: PUBLIC_SAMPLE_URL } }]);
});

test('subtitles prefer Portuguese then English while preserving Kodi selection, distinguishing generic tracks and keeping labels as text', async ({ page }) => {
  await openController(page);
  const refresh = page.getByRole('button', { name: 'Refresh subtitles', exact: true });
  const apply = page.getByRole('button', { name: 'Apply subtitle', exact: true });
  await expect(refresh).toBeDisabled();
  await expect(apply).toBeDisabled();
  await connect(page);
  await expect(refresh).toBeEnabled();
  await expect(apply).toBeDisabled();
  state.active = true;
  state.subtitleTracks = [
    { index: 8, language: '', name: 'Zulu' },
    { index: 0, language: 'eng', name: 'English' },
    { index: 7, language: '', name: 'Arabic' },
    { index: 2, language: 'por', name: 'Português <b>CC</b>' },
    { index: 3, language: '', name: '(External)' },
    { index: 5, language: '', name: 'Commentary' },
    { index: 6, language: '', name: 'Commentary' },
    { index: 9, language: '', name: 'Portuguese (Brazil)' }
  ];
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click();
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  await refresh.click();
  await expect(page.locator('#subtitle-status')).toHaveText('8 subtitle tracks available. Currently off.');
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  await expect(page.getByLabel('Subtitle track', { exact: true }).locator('option')).toHaveText([
    'Off', 'por · Português <b>CC</b>', 'Portuguese (Brazil)', 'eng · English',
    'Arabic', 'Commentary · Track 6', 'Commentary · Track 7', '(External) · Track 4', 'Zulu'
  ]);
  await expect(page.getByLabel('Subtitle track', { exact: true })).toHaveValue('off');
  await expect(page.locator('#subtitle-track b')).toHaveCount(0);
  state.subtitleEnabled = true;
  state.subtitleIndex = 0;
  await refresh.click();
  await expect(page.getByLabel('Subtitle track', { exact: true })).toHaveValue('0');
  await page.getByLabel('Subtitle track', { exact: true }).selectOption('2');
  await apply.click();
  await expect(page.locator('#subtitle-status')).toHaveText('8 subtitle tracks available. Enabled on Kodi.');
  await expect(page.getByLabel('Subtitle track', { exact: true })).toHaveValue('2');
  await page.getByLabel('Subtitle track', { exact: true }).selectOption('off');
  await apply.click();
  await expect(page.locator('#subtitle-status')).toHaveText('8 subtitle tracks available. Currently off.');
  await expect(page.getByLabel('Subtitle track', { exact: true })).toHaveValue('off');
  expect(calls.filter(call => call.method === 'Player.SetSubtitle').map(call => call.params)).toEqual([
    { playerid: 1, subtitle: 2, enable: true }, { playerid: 1, subtitle: 'off' }
  ]);
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  await page.getByLabel('Projector IP address').fill('192.168.1.51');
  await expect(refresh).toBeDisabled();
  await expect(apply).toBeDisabled();
});

test('external subtitle handoff waits for manual refresh, never fetches the subtitle and does not block unrelated forms', async ({ page }) => {
  const requests = await openController(page);
  await connect(page);
  state.active = true;
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click();
  await page.getByRole('button', { name: 'Refresh subtitles', exact: true }).click();
  await expect(page.locator('#subtitle-status')).toHaveText('No subtitle tracks are available for this video. You can load an external subtitle link.');
  const url = 'https://captions.example.org/download?token=private-subtitle-token';
  await page.getByLabel('External subtitle link', { exact: true }).fill(url);
  let release;
  state.subtitleGate = new Promise(resolve => { release = resolve; });
  try {
    await page.getByRole('button', { name: 'Load subtitle', exact: true }).click();
    await expect.poll(() => calls.filter(call => call.method === 'Player.AddSubtitle').length).toBe(1);
    await expect(page.getByRole('button', { name: 'Refresh subtitles', exact: true })).toBeDisabled();
    await expect(page.getByLabel('External subtitle link', { exact: true })).toBeDisabled();
    expect(calls.find(call => call.method === 'Player.AddSubtitle').params).toEqual({ playerid: 1, subtitle: url });
  } finally {
    release();
  }
  await expect(page.locator('#message')).toHaveText('Subtitle requested. Refresh subtitles to check whether Kodi loaded it.');
  await expect(page.locator('#subtitle-status')).toHaveText('Subtitle requested. Refresh subtitles to check the available tracks.');
  await expect(page.getByLabel('Subtitle track', { exact: true }).locator('option')).toHaveText(['Off']);
  await expect(page.getByRole('button', { name: 'Apply subtitle', exact: true })).toBeDisabled();
  await expect(page.locator('#playback-status')).toHaveText('Playing · 0:12 / 10:00');
  expect(calls.at(-1).method).toBe('Player.AddSubtitle');
  state.subtitleTracks = [{ index: 3, language: 'por', name: 'External' }];
  state.subtitleEnabled = true;
  state.subtitleIndex = 3;
  await page.getByRole('button', { name: 'Refresh subtitles', exact: true }).click();
  await expect(page.getByLabel('Subtitle track', { exact: true })).toHaveValue('3');
  await expect(page.locator('#subtitle-status')).toHaveText('1 subtitle track available. Enabled on Kodi.');
  await page.getByLabel('External subtitle link', { exact: true }).fill('not a URL');
  await page.getByRole('button', { name: 'Refresh status', exact: true }).click();
  await expect(page.locator('#message')).toHaveText('Status refreshed.');
  expect(requests.every(request => new URL(request.url).origin === controllerUrl)).toBe(true);
  expect(requests.some(request => request.type === 'media' || request.url.includes('private-subtitle-token'))).toBe(false);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
  await page.reload();
  await expect(page.getByLabel('External subtitle link', { exact: true })).toHaveValue('');
});
