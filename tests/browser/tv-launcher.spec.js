import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const LAUNCHER = 'https://kiansheik.io/kodaklumaremote/tv.html';

async function serve(page, visits) {
  const html = await readFile(new URL('../../site/tv.html', import.meta.url));
  await page.route(LAUNCHER, route => route.fulfill({ contentType: 'text/html', body: html }));
  await page.route(/^http:\/\/192\.168\.\d+\.\d+:\d+\/msx\/connect\.html.*/, route => {
    visits.push(route.request().url());
    return route.fulfill({ contentType: 'text/html', body: '<p>receiver</p>' });
  });
}

test('a phone link is remembered and forwarded with the other phones and one stable TV identity', async ({ page }) => {
  const visits = [];
  await serve(page, visits);
  // Fragments never reach a server; the TV page reads them from its own address.
  await page.goto(`${LAUNCHER}#phone=192.168.1.20:4178&name=Pixel%208`);
  await page.waitForURL(/^http:\/\/192\.168\.1\.20:4178\//);
  expect(page.url()).toMatch(/^http:\/\/192\.168\.1\.20:4178\/msx\/connect\.html#client=tv-[0-9a-f]{32}$/);
  const client = /client=(tv-[0-9a-f]{32})/.exec(page.url())[1];
  await page.goto(`${LAUNCHER}#phone=192.168.1.31:4178&name=Second`);
  await page.waitForURL(/^http:\/\/192\.168\.1\.31:4178\//);
  expect(page.url()).toBe(`http://192.168.1.31:4178/msx/connect.html#client=${client}&phones=192.168.1.20:4178`);
  // The speed dial (no fragment) lists both phones, most recent first, then opens it.
  await page.goto(LAUNCHER);
  await expect(page.locator('#phones button')).toHaveCount(2);
  await expect(page.locator('#phones button').first()).toContainText('Second');
  await page.waitForURL(/^http:\/\/192\.168\.1\.31:4178\//, { timeout: 8000 });
  expect(visits).toHaveLength(3);
});

test('public, malformed or privileged-port addresses are never remembered or opened', async ({ page }) => {
  const visits = [];
  await serve(page, visits);
  for (const bad of ['8.8.8.8:4178', '192.168.1.5:80', '192.168.1.500:4178', '192.168.01.5:4178', 'evil.example:4178']) {
    await page.goto(`${LAUNCHER}#phone=${bad}`);
    await expect(page.locator('#status')).toContainText('Open LUMA Remote on your phone');
  }
  expect(visits).toEqual([]);
});
