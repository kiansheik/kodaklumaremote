import { build } from 'esbuild';
import { mkdir, copyFile, cp } from 'node:fs/promises';
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
await cp(path.join(root, 'src/msx'), path.join(assets, 'msx'), { recursive: true });
await copyFile(path.join(root, 'node_modules/hls.js/dist/hls.min.js'), path.join(assets, 'msx/hls.min.js'));
await copyFile(path.join(root, 'node_modules/hls.js/LICENSE'), path.join(assets, 'msx/HLS-LICENSE.txt'));
await build({
  entryPoints: [path.join(root, 'src/mobile/capture.js')],
  outfile: path.join(assets, 'capture.js'),
  bundle: true, format: 'iife', globalName: 'LumaCapture',
  footer: { js: ';LumaCapture.capture();' },
  target: 'chrome111', legalComments: 'none',
});
console.log('Built local Android UI and DOM capture assets.');
