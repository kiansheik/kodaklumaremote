#!/usr/bin/env node
// Public HTML/static-asset metadata only. Never requests playback APIs or media.
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index === -1 ? fallback : args[index + 1];
};
if (args.includes('--help')) {
  console.log('node scripts/recon-http.mjs [--url HTTPS_URL] [--assets] [--output PATH]');
  console.log('Outputs sanitized metadata. --assets saves up to 10 same-origin JS/CSS assets under ignored .local/recon-http/.');
  process.exit(0);
}
const input = new URL(option('--url', 'https://framextv.xyz/watch/284833/1/1'));
if (input.protocol !== 'https:' || input.username || input.password || input.search || input.hash) {
  throw new Error('Use a public HTTPS page URL without credentials, query, or fragment.');
}
const output = path.resolve(option('--output', '.local/recon-http/report.json'));
const evidenceDir = path.resolve('.local/recon-http');
const keepAssets = args.includes('--assets');
const maxBytes = 2 * 1024 * 1024;
const report = {
  timestamp: new Date().toISOString(),
  environment: { node: process.version, platform: process.platform },
  policy: 'No cookies, authorization, redirects, playback APIs, manifests, or media; response-header allowlist; queries/fragments removed.',
  page: null,
  assets: [],
};
const safeUrl = value => {
  const url = new URL(value);
  return `${url.origin}${url.pathname}`;
};
const headers = response => Object.fromEntries(
  ['content-type', 'content-length', 'cache-control', 'server']
    .flatMap(name => response.headers.has(name) ? [[name, response.headers.get(name)]] : []),
);
async function request(url, kind) {
  const response = await fetch(url, {
    redirect: 'manual', credentials: 'omit', signal: AbortSignal.timeout(30_000),
    headers: { Accept: kind === 'page' ? 'text/html' : 'text/javascript, application/javascript, text/css' },
  });
  const meta = { url: safeUrl(url), status: response.status, headers: headers(response) };
  const type = (response.headers.get('content-type') || '').split(';')[0];
  const allowed = kind === 'page' ? type === 'text/html' : /^(?:application|text)\/(?:javascript|x-javascript|css)$/.test(type);
  if (!response.ok || !allowed) {
    await response.body?.cancel();
    return { meta: { ...meta, skipped: response.ok ? 'Unexpected content type' : 'Non-success response; redirects are not followed' } };
  }
  const chunks = [];
  let length = 0;
  for await (const chunk of response.body) {
    length += chunk.byteLength;
    if (length > maxBytes) throw new Error('Response exceeds static asset size limit');
    chunks.push(chunk);
  }
  const body = Buffer.concat(chunks);
  return { meta: { ...meta, bytes: body.length, sha256: createHash('sha256').update(body).digest('hex') }, body };
}
try {
  const page = await request(input, 'page');
  report.page = page.meta;
  if (page.body) {
    const html = page.body.toString('utf8');
    const discovered = [...html.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']/gi)]
      .flatMap(match => {
        try {
          const url = new URL(match[1], input);
          if (url.origin !== input.origin || url.search || url.hash || !/^\/assets\/[a-zA-Z0-9._-]+\.(?:js|css)$/.test(url.pathname)) return [];
          return [url.href];
        } catch { return []; }
      });
    const assets = [...new Set(discovered)].slice(0, 10);
    report.page.assetPaths = assets.map(safeUrl);
    report.page.knownSdkPresent = html.includes('www.gstatic.com/cv/js/sender/v1/cast_sender.js');
    report.page.hasRootElement = /id=["']root["']/.test(html);
    if (keepAssets) {
      await mkdir(evidenceDir, { recursive: true });
      await writeFile(path.join(evidenceDir, 'page.html'), page.body, { mode: 0o600 });
      for (const asset of assets) {
        const result = await request(asset, 'asset');
        report.assets.push(result.meta);
        if (result.body) await writeFile(path.join(evidenceDir, path.basename(new URL(asset).pathname)), result.body, { mode: 0o600 });
      }
    }
  }
} catch (error) {
  // Error messages can embed input URLs. Persist only a bounded classification.
  report.error = { name: error.name, code: error.cause?.code || null };
  process.exitCode = 1;
}
await mkdir(path.dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
console.log(JSON.stringify({ report: path.relative(process.cwd(), output), status: report.page?.status ?? null, error: report.error ?? null }));
