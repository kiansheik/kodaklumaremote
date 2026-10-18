import { build } from 'esbuild';
import { mkdir, copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const assets = path.join(root, 'android/app/build/generated/lumaAssets');
await mkdir(assets, { recursive: true });
await build({
  entryPoints: [path.join(root, 'src/mobile/app.js')],
  outfile: path.join(assets, 'app.js'),
  bundle: true, format: 'esm', target: 'chrome111', legalComments: 'none',
});
await copyFile(path.join(root, 'src/mobile/index.html'), path.join(assets, 'index.html'));
await build({
  entryPoints: [path.join(root, 'src/mobile/capture.js')],
  outfile: path.join(assets, 'capture.js'),
  bundle: true, format: 'iife', globalName: 'LumaCapture',
  footer: { js: ';LumaCapture.capture();' },
  target: 'chrome111', legalComments: 'none',
});
console.log('Built local Android UI and DOM capture assets.');
