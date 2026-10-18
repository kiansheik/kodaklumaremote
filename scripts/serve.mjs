import http from 'node:http';
import https from 'node:https';
import {readFile} from 'node:fs/promises';
import {resolve, extname, sep} from 'node:path';
const port = Number(process.env.PORT || 4173);
const tls = process.env.HTTPS === '1';
const root = resolve('.');
const allowed = ['/tests/fixtures/', '/dist/test-extension/', '/dist/demo/'];
const handler = async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  const file = /^\/(watch|embed)\//.test(path) ? '/tests/fixtures/player.html'
    : path === '/cast-demo' ? '/tests/fixtures/cast-demo.html' : path;
  if (!allowed.some(prefix => file.startsWith(prefix))) {res.writeHead(404).end('Not found'); return;}
  const absolute = resolve(root, `.${file}`);
  if (!absolute.startsWith(`${root}${sep}`)) {res.writeHead(403).end(); return;}
  try {
    const body = await readFile(absolute);
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self' https://www.gstatic.com; style-src 'self' 'unsafe-inline'; media-src 'self' https://storage.googleapis.com; frame-src 'self' https://*.google.com https://*.gstatic.com");
    res.setHeader('Content-Type', ({'.html':'text/html; charset=utf-8','.js':'text/javascript','.vtt':'text/vtt'})[extname(file)] || 'text/plain');
    res.end(body);
  } catch {res.writeHead(404).end('Not found');}
};
const server = tls ? https.createServer({key:await readFile('.local/tls/key.pem'),cert:await readFile('.local/tls/cert.pem')},handler)
  : http.createServer(handler);
server.listen(port, '127.0.0.1', () => console.log(`Fixtures: ${tls?'https':'http'}://localhost:${port}/watch/fixture/1/1 ; public Cast demo: /cast-demo`));
