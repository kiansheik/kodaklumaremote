import { test, expect } from '@playwright/test';
import { build } from 'esbuild';
import http from 'node:http';
import { resolve } from 'node:path';

let server;
let origin;
let bundle;
const pages = new Map();

test.beforeAll(async () => {
  const built = await build({ entryPoints: [resolve('src/mobile/capture.js')], bundle: true, write: false, format: 'iife', globalName: 'LumaCapture', target: 'chrome111', legalComments: 'none', footer: { js: ';LumaCapture.capture();' } });
  bundle = built.outputFiles[0].text;
  server = http.createServer((request, response) => {
    const body = pages.get(new URL(request.url, 'http://fixture').pathname);
    response.writeHead(body === undefined ? 404 : 200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(body || '');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { await new Promise(resolve => server.close(resolve)); });
test.beforeEach(() => pages.clear());

const html = content => `<!doctype html><html><head><title>Episode fixture</title><style>body{margin:0}iframe{border:0;display:block}video{display:block}</style></head><body>${content}</body></html>`;
const capture = page => page.evaluate(bundle);

test('mobile capture picks the largest visible same-origin video, ignores hidden/cross-origin frames and includes every declared track', async ({ page }) => {
  pages.set('/', html('<video style="width:100px;height:60px" src="/small.mp4"></video><iframe src="/child" style="width:500px;height:300px"></iframe><iframe src="/hidden" style="display:none;width:1000px;height:600px"></iframe><iframe id="cross" style="width:1000px;height:600px"></iframe>'));
  pages.set('/child', html('<video style="width:480px;height:270px" src="/episode.mp4"><track kind="subtitles" src="/en.vtt" srclang="en" label="English"><track kind="subtitles" src="/pt.vtt" srclang="pt-BR" label="Português (Brasil)"></video>'));
  pages.set('/hidden', html('<video style="width:990px;height:580px" src="/hidden.mp4"></video>'));
  pages.set('/cross', html('<video style="width:990px;height:580px" src="/cross.mp4"></video>'));
  await page.goto(origin);
  await page.locator('#cross').evaluate((frame, cross) => { frame.src = cross; }, origin.replace('127.0.0.1', 'localhost') + '/cross');
  await expect.poll(() => page.frames().length).toBe(4);
  await page.frame({ url: origin + '/child' }).evaluate(() => {
    const tracks = document.querySelectorAll('track'); tracks[0].track.mode = 'hidden'; tracks[1].track.mode = 'disabled';
  });
  const result = await capture(page);
  expect(result).toMatchObject({ ok: true, media: { url: origin + '/episode.mp4', title: 'Episode fixture' } });
  expect(result.media.tracks.map(track => track.language)).toEqual(['en', 'pt-BR']);
  expect(result.media.tracks.every(track => track.active === false)).toBe(true);
});

test('mobile capture preserves the shared DRM and bare-blob guards while reading declared HLS behind MSE', async ({ page }) => {
  pages.set('/', html('<video style="width:640px;height:360px"><source src="/authorized.m3u8?token=fixture" type="application/vnd.apple.mpegurl"></video>'));
  await page.goto(origin);
  await page.evaluate(() => Object.defineProperty(document.querySelector('video'), 'currentSrc', { value: 'blob:fixture', configurable: true }));
  expect(await capture(page)).toMatchObject({ ok: true, media: { url: origin + '/authorized.m3u8?token=fixture', contentType: 'application/vnd.apple.mpegurl' } });
  await page.evaluate(() => Object.defineProperty(document.querySelector('video'), 'mediaKeys', { value: {}, configurable: true }));
  expect(await capture(page)).toEqual({ ok: false, code: 'DRM_UNSUPPORTED' });
  await page.evaluate(() => { const video = document.querySelector('video'); delete video.mediaKeys; video.querySelector('source').remove(); });
  expect(await capture(page)).toEqual({ ok: false, code: 'SOURCE_UNSUPPORTED' });
});

test('mobile capture bounds iframe traversal to depth three and sixteen documents', async ({ page }) => {
  pages.set('/', html('<video style="width:100px;height:60px" src="/safe.mp4"></video><iframe src="/depth1" style="width:600px;height:400px"></iframe>'));
  for (let depth = 1; depth <= 4; depth++) pages.set(`/depth${depth}`, html(depth === 4 ? '<video style="width:590px;height:390px" src="/too-deep.mp4"></video>' : `<iframe src="/depth${depth + 1}" style="width:600px;height:400px"></iframe>`));
  await page.goto(origin);
  expect(await capture(page)).toMatchObject({ ok: true, media: { url: origin + '/safe.mp4' } });
  pages.set('/', html('<video style="width:100px;height:60px" src="/safe.mp4"></video>' + Array.from({ length: 16 }, (_, i) => `<iframe src="/frame${i}" style="position:absolute;left:0;top:0;width:600px;height:400px"></iframe>`).join('')));
  for (let i = 0; i < 16; i++) pages.set(`/frame${i}`, html(i === 15 ? '<video style="width:590px;height:390px" src="/too-many.mp4"></video>' : ''));
  await page.goto(origin);
  expect(await capture(page)).toMatchObject({ ok: true, media: { url: origin + '/safe.mp4' } });
});

test('mobile sender pause affects only videos matching the accepted source and leaves unrelated or encrypted videos alone', async ({ page }) => {
  pages.set('/', html('<video id="other" style="width:100px;height:60px" src="/other.mp4"></video><video id="encrypted" style="width:100px;height:60px" src="/episode.mp4"></video><iframe src="/child" style="width:500px;height:300px"></iframe>'));
  pages.set('/child', html('<video style="width:480px;height:270px" src="/episode.mp4"></video>'));
  await page.goto(origin);
  await page.evaluate(() => {
    window.pausedVideos = [];
    document.querySelector('#other').pause = () => window.pausedVideos.push('other');
    document.querySelector('#encrypted').pause = () => window.pausedVideos.push('encrypted');
    Object.defineProperty(document.querySelector('#encrypted'), 'mediaKeys', { value: {}, configurable: true });
    document.querySelector('iframe').contentDocument.querySelector('video').pause = () => window.pausedVideos.push('episode');
  });
  await capture(page);
  expect(await page.evaluate(url => LumaCapture.pauseMatchingSource(url), origin + '/episode.mp4')).toEqual({ paused: true });
  expect(await page.evaluate(() => window.pausedVideos)).toEqual(['episode']);
  expect(await page.evaluate(() => LumaCapture.pauseMatchingSource('https://example.org/missing.mp4'))).toEqual({ paused: false });
});
