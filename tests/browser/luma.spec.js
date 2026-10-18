import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';

let server;
let remoteUrl;
const receiver = '192.168.1.50';

test.beforeAll(async () => {
  const html = await readFile(new URL('../../src/luma/remote.html', import.meta.url));
  server = http.createServer((request, response) => {
    if (request.url !== '/') { response.writeHead(404).end(); return; }
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end(html);
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  remoteUrl = `http://127.0.0.1:${server.address().port}`;
});

test.afterAll(async () => {
  if (server) await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
});

async function openRemote(page, responder = () => undefined) {
  const calls = [];
  const requests = [];
  page.on('request', request => requests.push({ url: request.url(), type: request.resourceType() }));
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== remoteUrl) { await route.abort(); return; }
    if (url.pathname !== '/api/luma') { await route.continue(); return; }
    const body = route.request().postDataJSON();
    calls.push(body);
    const custom = await responder(body);
    const data = custom ?? (body.action === 'connect'
      ? { connected: true, device: { name: 'LUMA test projector' }, apps: [{ packageName: 'org.xbmc.kodi', label: 'Kodi' }, { packageName: 'test.video', label: 'Video player' }] }
      : body.action === 'disconnect' ? { connected: false }
        : body.action === 'apps' ? { apps: [{ packageName: 'test.other', label: 'New app' }] }
          : body.action === 'status' ? { connected: true, volume: { current: 6, max: 30 } }
          : { accepted: true });
    await route.fulfill({ status: data.httpStatus || 200, contentType: 'application/json', body: JSON.stringify(data) });
  });
  await page.goto(remoteUrl);
  return { calls, requests };
}

async function connect(page) {
  await page.getByLabel('Projector IP address').fill(receiver);
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#connection-badge')).toHaveText('Connected');
}

test('a narrow remote starts disconnected, validates the address, and keeps the receiver out of storage', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 780 });
  const { calls, requests } = await openRemote(page);
  await expect(page.getByLabel('Projector IP address')).toHaveValue('');
  await expect(page.getByRole('button', { name: 'Open Kodi', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Up', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Send text', exact: true })).toBeDisabled();
  expect(calls).toEqual([]);
  for (const invalid of ['8.8.8.8', '127.0.0.1', '192.168.1.999', '192.168.001.2', 'projector.local']) {
    await page.getByLabel('Projector IP address').fill(invalid);
    await page.getByRole('button', { name: 'Connect', exact: true }).click();
    await expect(page.locator('#connection-message')).toHaveText('Enter the projector’s local IP address.');
  }
  expect(calls).toEqual([]);
  await connect(page);
  expect(calls).toEqual([{ host: receiver, action: 'connect' }]);
  await expect(page.getByRole('button', { name: 'Open Kodi', exact: true })).toBeEnabled();
  await expect(page.locator('#volume-level')).toHaveText('—');
  await expect(page.getByLabel('Projector IP address')).toBeDisabled();
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, fits: document.documentElement.scrollWidth <= innerWidth }))).toEqual({ local: 0, session: 0, fits: true });
  expect(requests.every(request => new URL(request.url).origin === remoteUrl)).toBe(true);
  expect(requests.some(request => request.type === 'media')).toBe(false);
  await page.reload();
  await expect(page.getByLabel('Projector IP address')).toHaveValue('');
  await expect(page.locator('#connection-badge')).toHaveText('Not connected');
});

test('navigation, app launch, volume and typing send explicit commands without claiming projector feedback', async ({ page }) => {
  const { calls } = await openRemote(page);
  await connect(page);
  await page.getByRole('button', { name: 'Open Kodi', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#command-message')).toHaveText('Kodi launch sent. Check the projector.');
  await page.getByRole('button', { name: 'Open Video player app', exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText('Video player launch sent. Check the projector.');
  await page.getByRole('button', { name: 'Home', exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText('Home sent. Check the projector.');
  // Keyboard navigation works when a remote button still has focus.
  await page.waitForTimeout(110);
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('#command-message')).toHaveText('Right sent. Check the projector.');
  await page.waitForTimeout(110);
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText('Volume up sent. Check the projector.');
  const text = page.getByLabel('Text to send');
  await text.fill('A film & a little português');
  const beforeTyping = calls.length;
  await text.press('ArrowLeft');
  await text.press('Home');
  await text.press('Enter');
  expect(calls).toHaveLength(beforeTyping);
  await page.getByRole('button', { name: 'Clear', exact: true }).click();
  await expect(text).toHaveValue('');
  expect(calls).toHaveLength(beforeTyping);
  await text.fill('A film & a little português');
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText('Text sent. Check the selected field on the projector.');
  await expect(text).toHaveValue('');
  await expect(page.getByRole('button', { name: 'Send text', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Refresh apps', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Open New app app', exact: true })).toBeVisible();
  expect(calls).toEqual([
    { host: receiver, action: 'connect' }, { host: receiver, action: 'launch-kodi' },
    { host: receiver, action: 'launch-app', packageName: 'test.video' },
    { host: receiver, action: 'key', key: 'home' }, { host: receiver, action: 'key', key: 'right' },
    { host: receiver, action: 'key', key: 'volume-up' },
    { host: receiver, action: 'text', text: 'A film & a little português' }, { host: receiver, action: 'apps' }
  ]);
  await page.getByRole('button', { name: 'Disconnect', exact: true }).click();
  await expect(page.locator('#connection-badge')).toHaveText('Not connected');
  await expect(page.getByRole('button', { name: 'Open Kodi', exact: true })).toBeDisabled();
  await expect(text).toHaveValue('');
  await expect(page.locator('#volume-level')).toHaveText('—');
  expect(calls.at(-1)).toEqual({ host: receiver, action: 'disconnect' });
});

test('pending commands gate every control and keyboard repeats are dropped rather than queued', async ({ page }) => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const { calls } = await openRemote(page, async body => { if (body.action === 'key') await gate; });
  await connect(page);
  await page.getByRole('button', { name: 'Up', exact: true }).click();
  await expect.poll(() => calls.filter(call => call.action === 'key').length).toBe(1);
  try {
    await expect(page.getByRole('button', { name: 'Open Kodi', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Refresh volume', exact: true })).toBeDisabled();
    await expect(page.getByLabel('Text to send')).toBeDisabled();
    await page.locator('body').click({ position: { x: 5, y: 5 } });
    for (let index = 0; index < 12; index += 1) await page.keyboard.press('ArrowRight');
    expect(calls.filter(call => call.action === 'key')).toEqual([{ host: receiver, action: 'key', key: 'up' }]);
  } finally { release(); }
  await expect(page.getByRole('button', { name: 'Up', exact: true })).toBeEnabled();
  expect(calls.filter(call => call.action === 'key')).toHaveLength(1);
});

test('app names and fixed server errors remain text and unsuccessful sends preserve the draft', async ({ page }) => {
  let failConnect = true;
  const malicious = '<img src=x onerror="window.appExecuted=true">';
  const textError = `<img src=x onerror="window.errorExecuted=true">${'x'.repeat(400)}`;
  const { calls } = await openRemote(page, body => {
    if (body.action === 'connect' && failConnect) return { httpStatus: 502, error: 'The projector is unavailable.' };
    if (body.action === 'connect') return { connected: true, device: { name: malicious }, apps: [{ packageName: 'test.markup', label: malicious }] };
    if (body.action === 'launch-app') return { httpStatus: 400, error: 'That app was not found in the projector’s current app list.' };
    if (body.action === 'text') return { httpStatus: 502, error: textError };
    return undefined;
  });
  await page.getByLabel('Projector IP address').fill(receiver);
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#connection-message')).toHaveText('The projector is unavailable.');
  await expect(page.getByRole('button', { name: 'Open Kodi', exact: true })).toBeDisabled();
  failConnect = false;
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#connection-badge')).toHaveText('Connected');
  await expect(page.getByRole('button', { name: `Open ${malicious} app`, exact: true })).toBeVisible();
  expect(await page.locator('img').count()).toBe(0);
  await page.getByRole('button', { name: `Open ${malicious} app`, exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText('That app was not found in the projector’s current app list.');
  await expect(page.locator('#connection-badge')).toHaveText('Connected');
  await page.getByLabel('Text to send').fill('Keep this draft');
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText(textError.slice(0, 240));
  await expect(page.locator('#command-message')).toHaveAttribute('data-error', 'true');
  await expect(page.getByLabel('Text to send')).toHaveValue('Keep this draft');
  await expect(page.locator('#connection-badge')).toHaveText('Connected');
  await expect(page.getByLabel('Projector IP address')).toBeDisabled();
  expect(await page.evaluate(() => ({ app: window.appExecuted, error: window.errorExecuted }))).toEqual({ app: undefined, error: undefined });
  expect(calls.filter(call => call.action === 'text')).toHaveLength(1);
});

test('bounded discovery handles no reply, fills one result and safely selects among multiple projectors', async ({ page }) => {
  let phase = 0;
  let release;
  const firstSearch = new Promise(resolve => { release = resolve; });
  const name = '<img src=x onerror="window.discoveryExecuted=true">';
  const { calls } = await openRemote(page, async body => {
    if (body.action !== 'discover') return undefined;
    if (phase === 0) { await firstSearch; return { devices: [] }; }
    if (phase === 1) return { devices: [{ host: '192.168.1.60', name, port: 8000 }] };
    return { devices: [{ host: '192.168.1.60', name, port: 8000 }, { host: '192.168.1.61', name: 'Second LUMA', port: 8000 }, { host: '8.8.8.8', name: 'Invalid result', port: 8000 }] };
  });
  const find = page.getByRole('button', { name: 'Find projector', exact: true });
  await find.click();
  try {
    await expect(page.locator('#connection-message')).toHaveText('Searching for up to 3 seconds…');
    await expect(page.getByRole('button', { name: 'Searching…', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeDisabled();
  } finally { release(); }
  await expect(page.locator('#connection-message')).toHaveText('No projector replied yet. If it is still starting, try again in a moment or enter its IP address.');
  await expect(find).toBeEnabled();
  await expect(page.getByLabel('Projector IP address')).toBeEnabled();
  phase = 1;
  await find.click();
  await expect(page.getByLabel('Projector IP address')).toHaveValue('192.168.1.60');
  await expect(page.locator('#connection-message')).toHaveText(`${name} found. Choose Connect to use your remote.`);
  await expect(page.locator('#connection-badge')).toHaveText('Not connected');
  phase = 2;
  await find.click();
  await expect(page.locator('#connection-message')).toHaveText('Found 2 projectors. Choose one, then connect.');
  const chooser = page.getByLabel('Choose a projector', { exact: true });
  await expect(chooser).toBeVisible();
  await expect(chooser.locator('option')).toHaveCount(3);
  await chooser.selectOption('192.168.1.61');
  await expect(page.getByLabel('Projector IP address')).toHaveValue('192.168.1.61');
  expect(calls).toEqual([{ action: 'discover' }, { action: 'discover' }, { action: 'discover' }]);
  expect(await page.locator('img').count()).toBe(0);
  expect(await page.evaluate(() => window.discoveryExecuted)).toBeUndefined();
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#connection-badge')).toHaveText('Connected');
  expect(calls.at(-1)).toEqual({ host: '192.168.1.61', action: 'connect' });
  await expect(find).toBeDisabled();
});

test('volume comes from an explicit status refresh and is cleared after a volume command', async ({ page }) => {
  const { calls } = await openRemote(page);
  const refresh = page.getByRole('button', { name: 'Refresh volume', exact: true });
  await expect(refresh).toBeDisabled();
  await connect(page);
  await expect(page.locator('#volume-level')).toHaveText('—');
  expect(calls.some(call => call.action === 'status')).toBe(false);
  await refresh.click();
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  await expect(page.locator('#command-message')).toHaveText('Volume refreshed.');
  expect(calls.at(-1)).toEqual({ host: receiver, action: 'status' });
  await page.getByRole('button', { name: 'Volume up', exact: true }).click();
  await expect(page.locator('#command-message')).toHaveText('Volume up sent. Check the projector.');
  await expect(page.locator('#volume-level')).toHaveText('—');
  expect(calls.filter(call => call.action === 'status')).toHaveLength(1);
});

test('transport loss clears stale connection state, preserves the draft and permits reconnect', async ({ page }) => {
  let failText = true;
  const { calls } = await openRemote(page, body => {
    if (body.action === 'text' && failText) return { httpStatus: 502, error: 'The projector did not reply in time. Reconnect and try again.', connected: false };
    return undefined;
  });
  await connect(page);
  await page.getByRole('button', { name: 'Refresh volume', exact: true }).click();
  await expect(page.locator('#volume-level')).toHaveText('6 / 30');
  const text = page.getByLabel('Text to send');
  await text.fill('Keep this draft for reconnect');
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(page.locator('#connection-badge')).toHaveText('Not connected');
  await expect(page.locator('#connection-message')).toHaveText('Connection lost. Reconnect to continue.');
  await expect(page.locator('#command-message')).toHaveText('The projector did not reply in time. Reconnect and try again.');
  await expect(page.locator('#volume-level')).toHaveText('—');
  await expect(page.locator('#app-grid button')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeEnabled();
  await expect(page.getByLabel('Projector IP address')).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Open Kodi', exact: true })).toBeDisabled();
  await expect(text).toBeDisabled();
  await expect(text).toHaveValue('Keep this draft for reconnect');
  failText = false;
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.locator('#connection-badge')).toHaveText('Connected');
  await expect(text).toBeEnabled();
  await expect(text).toHaveValue('Keep this draft for reconnect');
  await page.getByRole('button', { name: 'Send text', exact: true }).click();
  await expect(text).toHaveValue('');
  expect(calls.filter(call => call.action === 'connect')).toHaveLength(2);
  expect(calls.filter(call => call.action === 'text')).toHaveLength(2);
});
