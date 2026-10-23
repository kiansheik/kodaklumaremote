import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const LAUNCHER = 'https://kiansheik.io/kodaklumaremote/tv.html';

async function serve(page, visits) {
  const html = await readFile(new URL('../../site/tv.html', import.meta.url));
  await page.route(LAUNCHER, route => route.fulfill({ contentType: 'text/html', body: html }));
  await page.route(/^http:\/\/(?:10|172|192\.168)\.[^/]+:\d+\/tv\/connect\.html.*/, route => {
    visits.push(route.request().url());
    return route.fulfill({ contentType: 'text/html', body: '<p>receiver</p>' });
  });
}

async function localNetwork(page, candidate = '192.168.68.55', probeAlive = true) {
  await page.addInitScript(({ candidate, probeAlive }) => {
    class Peer {
      createDataChannel() {}
      createOffer() { return Promise.resolve({ type: 'offer', sdp: '' }); }
      setLocalDescription() {
        setTimeout(() => this.onicecandidate?.({ candidate: { candidate: `candidate:1 1 UDP 1 ${candidate} 55555 typ host` } }), 0);
        return Promise.resolve();
      }
      close() {}
    }
    class ProbeImage {
      set src(_) { setTimeout(() => probeAlive ? this.onload?.() : this.onerror?.(), 0); }
    }
    Object.defineProperty(window, 'RTCPeerConnection', { configurable: true, value: Peer });
    Object.defineProperty(window, 'Image', { configurable: true, value: ProbeImage });
  }, { candidate, probeAlive });
}

test('first visit infers the subnet, asks only for the phone IP and remembers it', async ({ page }) => {
  const visits = [];
  await localNetwork(page);
  await serve(page, visits);
  await page.goto(LAUNCHER);
  await expect(page.locator('#status')).toHaveText('Network found. Enter only your phone’s final number.');
  await expect(page.locator('#phone-ip')).toHaveValue('192.168.68.');
  await page.locator('#phone-ip').fill('192.168.68.24');
  await page.getByRole('button', { name: 'Connect to phone' }).click();
  await page.waitForURL(/^http:\/\/192\.168\.68\.24:4178\/tv\/connect\.html/);
  expect(visits).toHaveLength(1);

  await page.goto(LAUNCHER);
  await page.waitForURL(/^http:\/\/192\.168\.68\.24:4178\/tv\/connect\.html/);
  expect(visits).toHaveLength(2);
});

test('focused launcher field accepts the exact direct URL filled by the phone', async ({ page }) => {
  const visits = [];
  await localNetwork(page);
  await serve(page, visits);
  await page.goto(LAUNCHER);
  await expect(page.locator('#phone-ip')).toBeFocused();
  await page.locator('#phone-ip').fill('http://192.168.68.24:49153/tv/connect.html');
  await page.locator('#phone-ip').press('Enter');
  await page.waitForURL(/^http:\/\/192\.168\.68\.24:49153\/tv\/connect\.html/);
  expect(visits).toHaveLength(1);
});

test('an unavailable saved phone opens the editable address with an open-anyway fallback', async ({ page }) => {
  const visits = [];
  await localNetwork(page, '192.168.68.55', false);
  await serve(page, visits);
  await page.goto(LAUNCHER);
  await page.evaluate(() => localStorage.setItem('luma-phones', JSON.stringify([{ address: '192.168.68.24:4178', name: 'Phone 24', at: 1 }])));
  await page.reload();
  await expect(page.locator('#status')).toContainText('saved phone did not answer');
  await expect(page.locator('#phone-ip')).toHaveValue('192.168.68.24');
  await expect(page.getByRole('button', { name: 'Open saved phone anyway' })).toBeVisible();
  expect(visits).toEqual([]);
});

test('multiple remembered phones show a chooser instead of redirecting', async ({ page }) => {
  const visits = [];
  await localNetwork(page);
  await serve(page, visits);
  await page.goto(LAUNCHER);
  await page.evaluate(() => localStorage.setItem('luma-phones', JSON.stringify([
    { address: '192.168.68.24:4178', name: 'Kian phone', at: 2 },
    { address: '192.168.68.31:4178', name: 'Other phone', at: 1 },
  ])));
  await page.reload();
  await expect(page.locator('#phones button')).toHaveCount(3);
  await expect(page.locator('#phones button').nth(0)).toContainText('Kian phone');
  await expect(page.locator('#phones button').nth(1)).toContainText('Other phone');
  await expect(page.locator('#phones button').nth(2)).toHaveText('Use another phone');
  expect(visits).toEqual([]);
});

test('old phone fragments remain compatible', async ({ page }) => {
  const visits = [];
  await localNetwork(page);
  await serve(page, visits);
  await page.goto(`${LAUNCHER}#phone=192.168.68.20:4178&name=Pixel%208`);
  await page.waitForURL(/^http:\/\/192\.168\.68\.20:4178\//);
  expect(page.url()).toMatch(/^http:\/\/192\.168\.68\.20:4178\/tv\/connect\.html#client=tv-[0-9a-f]{32}$/);
  expect(visits).toHaveLength(1);
});

test('malformed or public phone fragments are ignored', async ({ page }) => {
  const visits = [];
  await localNetwork(page);
  await serve(page, visits);
  for (const bad of ['8.8.8.8:4178', '192.168.1.5:80', '192.168.1.500:4178', '192.168.01.5:4178', 'evil.example:4178']) {
    await page.goto(`${LAUNCHER}#phone=${bad}`);
    await expect(page.locator('#phone-ip')).toHaveValue('192.168.68.');
  }
  expect(visits).toHaveLength(0);
});
