import { build } from 'esbuild';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const manifest = JSON.parse(await readFile('src/extension/manifest.json','utf8'));
for (const target of ['extension', 'test-extension']) {
  await mkdir(`dist/${target}`, {recursive: true});
  await build({entryPoints: ['src/extension/player.js'], outfile: `dist/${target}/player.js`,
    bundle: true, format: 'iife', target: 'chrome111', legalComments: 'none'});
  const output = structuredClone(manifest);
  if (target === 'test-extension') output.content_scripts[0].matches = ['http://localhost/*', 'http://127.0.0.1/*'];
  await writeFile(`dist/${target}/manifest.json`, `${JSON.stringify(output, null, 2)}\n`);
}
await build({entryPoints:['src/cast/demo.js'],outfile:'dist/demo/sender.js',bundle:true,
  format:'iife',target:'chrome111',legalComments:'none'});
console.log('Built dist/extension (site only) and dist/test-extension (loopback fixtures only).');
