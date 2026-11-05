import { test, expect } from '@playwright/test';
import http from 'node:http';
import { localMediaRemotePage } from '../../scripts/local-media-remote.mjs';

let server, origin, selected, commands, playback;

function activePlayback(overrides = {}) {
  return { active: true, playing: true, title: 'Movie', durationSeconds: 600, positionSeconds: 12, canSeek: true,
    bufferAheadSeconds: 18.5, diskCacheState: 'filling', diskCacheStorage: 'internal',
    diskCachedBytes: 128 * 1024 * 1024, diskCacheTargetBytes: 512 * 1024 * 1024,
    diskCacheAheadSeconds: 300.5, ...overrides };
}

test.beforeAll(async () => {
  server = http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://fixture');
    const reply = body => { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(body)); };
    if (request.method === 'GET' && url.pathname === '/') {
      const page = localMediaRemotePage('/api/', '/media');
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); response.end(page); return;
    }
    if (request.method === 'GET' && url.pathname === '/api/state') {
      reply({ host: '192.168.1.50', files: ['movie.mkv'] }); return;
    }
    if (request.method === 'GET' && url.pathname === '/api/status') {
      reply({
        playback,
        subtitles: { active: true, enabled: selected !== 'off', currentIndex: selected === 'off' ? null : Number(selected),
          tracks: [{ index: 512, name: 'Track 1', language: 'pt-BR' }, { index: 513, name: 'Track 2', language: 'en' }] },
        volume: { current: 5, max: 15 }, streamer: { running: true, output: '' },
      }); return;
    }
    if (request.method === 'POST' && url.pathname === '/api/command') {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const command = JSON.parse(Buffer.concat(chunks).toString('utf8')); commands.push(command);
      if (command.action === 'subtitle') selected = command.index;
      reply({ accepted: true }); return;
    }
    response.writeHead(404).end();
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  origin = `http://127.0.0.1:${server.address().port}`;
});

test.afterAll(async () => { if (server) await new Promise(resolve => server.close(resolve)); });
test.beforeEach(() => { selected = 'off'; commands = []; playback = activePlayback(); });

test('subtitle choice survives status refresh until Apply and then reaches the receiver', async ({ page }) => {
  await page.goto(origin);
  await expect(page.locator('#subtitles option')).toHaveCount(3);
  await page.selectOption('#subtitles', '513');
  await page.waitForTimeout(1700);
  await expect(page.locator('#subtitles')).toHaveValue('513');
  await page.getByRole('button', { name: 'Apply' }).click();
  await expect.poll(() => commands.filter(command => command.action === 'subtitle')).toEqual([
    { host: '192.168.1.50', action: 'subtitle', index: '513' },
  ]);
  await expect(page.locator('#subtitles')).toHaveValue('513');
});

test('buffer status shows RAM and actual disk load across active and cleanup states', async ({ page }) => {
  await page.goto(origin);
  await expect(page.locator('#remote-status')).toHaveText(
    'Playing · laptop stream active · RAM 18s · Disk saving 128 MiB / 512 MiB · 5:00 ahead');

  playback = activePlayback({ diskCacheState: 'waiting', diskCachedBytes: 0, diskCacheTargetBytes: 0,
    diskCacheAheadSeconds: undefined });
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('#remote-status')).toContainText('Disk preparing');

  playback = { active: false, playing: false, diskCacheState: 'clearing', diskCacheStorage: 'internal' };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('#remote-status')).toHaveText('Disk clearing');

  playback = { active: false, playing: false, diskCacheState: 'off', diskCacheStorage: 'internal' };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('#remote-status')).toHaveText('');
});
