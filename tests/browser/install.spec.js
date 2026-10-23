import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';

let server, origin;
const release = { schemaVersion: 1, packageName: 'dev.framextv.lumaremote', versionName: '0.2.4', versionCode: 6, minSdk: 23,
  apkUrl: 'https://github.com/kiansheik/kodaklumaremote/releases/download/v0.2.4/luma-remote-0.2.4.apk',
  sha256: 'a'.repeat(64), sizeBytes: 3456789, releasedAt: '2026-10-18T18:00:00Z', notes: 'Melhorias nos controles.\nAtualização pelo app.' };

test.beforeAll(async () => {
  const files = new Map(['index.html', 'styles.css', 'app.js', 'update-manifest.js', 'projector.svg'].map(name => [`/install/${name}`, new URL(`../../site/${name}`, import.meta.url)]));
  files.set('/install/', new URL('../../site/index.html', import.meta.url));
  server = http.createServer(async (request, response) => {
    const file = files.get(request.url);
    if (!file) { response.writeHead(404).end(); return; }
    response.setHeader('Content-Type', file.pathname.endsWith('.html') ? 'text/html; charset=utf-8' : file.pathname.endsWith('.css') ? 'text/css' : file.pathname.endsWith('.svg') ? 'image/svg+xml' : 'text/javascript');
    response.end(await readFile(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { if (server) await new Promise(resolve => server.close(resolve)); });

test('Portuguese install page resolves relative metadata, fits phone and desktop, and never auto-downloads', async ({ page }) => {
  const requests = [], downloads = [];
  page.on('request', request => requests.push(request.url()));
  page.on('download', download => downloads.push(download));
  await page.route('**/install/update.json', route => route.fulfill({ json: release }));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/install/`);
  await expect(page.locator('#download')).toHaveAttribute('href', release.apkUrl);
  await expect(page.locator('#release-status')).toContainText('Versão 0.2.4');
  await expect(page.locator('#release-status')).toContainText('Android 6 ou superior');
  await expect(page.locator('html')).toHaveAttribute('lang', 'pt-BR');
  await expect(page.getByRole('heading', { name: 'Instale no celular' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/install-phone.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/install-desktop.png', fullPage: true });
  await page.getByText('Detalhes desta versão').click();
  await expect(page.locator('#release-notes')).toHaveText(release.notes);
  await expect(page.locator('#release-hash')).toHaveText(release.sha256);
  expect(requests.some(url => url.endsWith('.apk'))).toBe(false);
  expect(downloads).toHaveLength(0);
});

test('broken release metadata leaves download unavailable and offers the published releases link', async ({ page }) => {
  await page.route('**/install/update.json', route => route.fulfill({ json: { ...release, apkUrl: 'https://unrelated.example/app.apk', notes: '<img src=x onerror=alert(1)>' } }));
  await page.goto(`${origin}/install/`);
  await expect(page.locator('#release-status')).toHaveAttribute('data-error', 'true');
  await expect(page.locator('#download')).not.toHaveAttribute('href');
  await expect(page.locator('#download')).toHaveAttribute('aria-disabled', 'true');
  await expect(page.locator('#release-details')).toBeHidden();
  await expect(page.getByRole('link', { name: 'Ver lançamentos no GitHub' })).toHaveAttribute('href', 'https://github.com/kiansheik/kodaklumaremote/releases/latest');
  expect(await page.locator('img').count()).toBe(1);
});

test('projector download link uses the receiver feed bound to the phone release tag', async ({ page }) => {
  const receiver = { schemaVersion: 1, packageName: 'dev.framextv.lumareceiver', versionName: '0.1.4', versionCode: 6, minSdk: 23,
    apkUrl: 'https://github.com/kiansheik/kodaklumaremote/releases/download/v0.2.4/luma-receiver-0.1.4.apk',
    sha256: 'b'.repeat(64), sizeBytes: 1179435, releasedAt: '2026-10-18T18:00:00Z', notes: 'Receiver.' };
  await page.route('**/install/update.json', route => route.fulfill({ json: release }));
  await page.route('**/install/receiver-update.json', route => route.fulfill({ json: receiver }));
  await page.goto(`${origin}/install/`);
  await expect(page.locator('#receiver-download')).toHaveAttribute('href', receiver.apkUrl);
  await expect(page.locator('#receiver-status')).toContainText('LUMA Receiver 0.1.4');
  await expect(page.getByRole('heading', { name: 'Instale o LUMA Receiver no projetor' })).toBeVisible();
});

test('a release without a receiver feed keeps the projector link disabled', async ({ page }) => {
  await page.route('**/install/update.json', route => route.fulfill({ json: release }));
  await page.goto(`${origin}/install/`);
  await expect(page.locator('#receiver-status')).toContainText('ainda não está disponível');
  await expect(page.locator('#receiver-download')).toHaveAttribute('aria-disabled', 'true');
  await expect(page.locator('#receiver-download')).not.toHaveAttribute('href');
});
