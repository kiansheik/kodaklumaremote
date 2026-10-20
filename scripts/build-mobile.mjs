import { build } from 'esbuild';
import { mkdir, copyFile, cp, readFile, writeFile, lstat, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
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
// Explicit local diagnostic builds may carry this exact public test clip.
// Ordinary builds remove the generated copy; no media is checked into Git.
const diagnosticSample = process.env.LUMA_DIAGNOSTIC_SAMPLE;
const diagnosticTarget = path.join(assets, 'msx/diagnostic-sintel.mp4');
if (diagnosticSample) {
  if (!path.isAbsolute(diagnosticSample)) throw new Error('Diagnostic sample must be an explicit regular file.');
  const sampleInfo = await lstat(diagnosticSample);
  if (!sampleInfo.isFile() || sampleInfo.size !== 4372373) throw new Error('Diagnostic sample must be the verified bounded regular file.');
  const bytes = await readFile(diagnosticSample);
  if (bytes.length !== 4372373 || createHash('sha256').update(bytes).digest('hex') !== 'b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254') throw new Error('Diagnostic sample does not match the verified W3C clip.');
  await writeFile(diagnosticTarget, bytes);
} else await rm(diagnosticTarget, {force: true});
// Bind diagnostics to the receiver bytes in this APK. Hash the source template
// before injecting its revision, so the digest does not depend on itself.
const receiverFiles = ['receiver.html', 'receiver.css', 'receiver-core.js', 'receiver.js', 'hls.min.js'];
const receiverHash = createHash('sha256');
for (const name of receiverFiles) {
  receiverHash.update(name).update('\0').update(await readFile(path.join(assets, 'msx', name))).update('\0');
}
const receiverRevision = receiverHash.digest('hex');
const receiverScript = path.join(assets, 'msx/receiver.js');
const receiverTemplate = await readFile(receiverScript, 'utf8');
if (receiverTemplate.split('__LUMA_RECEIVER_REVISION__').length !== 2) throw new Error('Receiver revision placeholder must occur exactly once.');
await writeFile(receiverScript, receiverTemplate.replace('__LUMA_RECEIVER_REVISION__', receiverRevision));
await writeFile(path.join(assets, 'msx/revision.json'), JSON.stringify({revision: receiverRevision}) + '\n');
await build({
  entryPoints: [path.join(root, 'src/mobile/capture.js')],
  outfile: path.join(assets, 'capture.js'),
  bundle: true, format: 'iife', globalName: 'LumaCapture',
  footer: { js: ';LumaCapture.capture();' },
  target: 'chrome111', legalComments: 'none',
});
console.log('Built local Android UI and DOM capture assets.');
