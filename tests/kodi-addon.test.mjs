import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';

const run = promisify(execFile);
const root = path.resolve('kodi-addon/script.lumaremote.metadata');
const library = path.join(root, 'resources/lib');
const harness = path.resolve('tests/fixtures/kodi_addon_fixture.py');
const expectedFiles = [
  'addon.xml',
  'default.py',
  'resources/__init__.py',
  'resources/lib/__init__.py',
  'resources/lib/luma_metadata.py',
];

async function files(folder, prefix = '') {
  const result = [];
  for (const entry of await readdir(folder, { withFileTypes: true })) {
    const relative = path.posix.join(prefix, entry.name);
    if (entry.isDirectory()) result.push(...await files(path.join(folder, entry.name), relative));
    else result.push(relative);
  }
  return result.sort();
}

function payload(overrides = {}) {
  return {
    version: 1,
    source: 'https://media.example/episode.m3u8',
    contentType: 'application/vnd.apple.mpegurl',
    title: 'Fixture Series · S2 E3',
    showTitle: 'Fixture Series',
    content: {
      key: 'framextv:tv:42:2:3', kind: 'tv', contentId: '42', season: 2, episode: 3,
      watchUrl: 'https://framextv.cc/watch/42/2/3',
    },
    ...overrides,
  };
}

const encoded = value => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url');
async function execute(value) {
  const { stdout, stderr } = await run('python3', [harness, library, encoded(value)], {
    timeout: 10000,
    env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(stderr, '');
  return JSON.parse(stdout);
}

test('Kodi companion add-on is a minimal source-only script package', async () => {
  assert.deepEqual(await files(root), expectedFiles);
  const xml = await readFile(path.join(root, 'addon.xml'), 'utf8');
  assert.match(xml, /<addon id="script\.lumaremote\.metadata"[^>]+version="1\.0\.0"/u);
  assert.match(xml, /<extension point="xbmc\.python\.script" library="default\.py">/u);
  const sources = await Promise.all(expectedFiles.map(file => readFile(path.join(root, file))));
  assert.ok(sources.every(bytes => bytes.length < 16384));
  assert.ok(expectedFiles.every(file => !/\.(?:mp4|m4s|webm|m3u8|ts|key|pem|jks|keystore)$/iu.test(file)));
  const joined = Buffer.concat(sources).toString('utf8');
  assert.doesNotMatch(joined, /-----BEGIN (?:PRIVATE KEY|CERTIFICATE)-----|api[_-]?key\s*=|password\s*=/iu);
  const entrypoint = await readFile(path.join(root, 'default.py'), 'utf8');
  assert.deepEqual(entrypoint.match(/xbmc\.log\([^\n]+\)/gu), [
    'xbmc.log("LUMA metadata payload rejected", xbmc.LOGWARNING)',
    'xbmc.log("LUMA metadata handoff failed", xbmc.LOGERROR)',
  ]);
});

test('Kodi companion validates LUMA v1 and transfers exact episode metadata', async () => {
  assert.deepEqual(await execute(payload()), {
    labelExact: true,
    lookupDisabled: true,
    mimeType: 'application/vnd.apple.mpegurl',
    played: true,
    sourceExact: true,
    tag: {
      episode: 3,
      season: 2,
      showTitle: 'Fixture Series',
      title: 'Fixture Series · S2 E3',
      type: 'episode',
      uniqueDefault: false,
      uniqueId: 'v1:framextv:tv:42:2:3',
      uniqueType: 'luma',
    },
  });
});

test('Kodi companion rejects non-canonical, private, extended, and duplicate payloads', async () => {
  const invalid = [
    payload({ source: 'http://192.168.1.20/private.m3u8' }),
    payload({ content: { ...payload().content, key: 'framextv:tv:42:2:4' } }),
    payload({ title: 'Another title' }),
    { ...payload(), unexpected: true },
    '{"version":1,"version":1}',
  ];
  for (const value of invalid) assert.deepEqual(await execute(value), { rejected: true });
});
