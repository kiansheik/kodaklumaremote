import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { lanAddress, loadConfig, receiverRequest, saveConfig } from './stream-local-media.mjs';

const STREAMER = fileURLToPath(new URL('./stream-local-media.mjs', import.meta.url));
const MEDIA_EXTENSIONS = new Set(['.mp4', '.m4v', '.mov', '.mkv', '.webm', '.avi', '.ts', '.m2ts', '.mts', '.flv', '.wmv']);

function mediaFiles(root) {
  const files = [];
  const walk = (directory, relative, depth) => {
    if (depth > 5 || files.length >= 2000) return;
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.name.startsWith('.') || entry.isSymbolicLink()) continue;
      const childRelative = relative ? `${relative}/${entry.name}` : entry.name;
      const child = path.join(directory, entry.name);
      if (entry.isDirectory()) walk(child, childRelative, depth + 1);
      else if (entry.isFile() && MEDIA_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) files.push(childRelative);
      if (files.length >= 2000) break;
    }
  };
  walk(root, '', 0);
  return files;
}

function selectedFile(root, relative) {
  if (typeof relative !== 'string' || relative.length > 2000 || path.isAbsolute(relative)) throw new Error('Choose a media file from the configured folder.');
  const target = path.resolve(root, relative);
  const prefix = root.endsWith(path.sep) ? root : root + path.sep;
  if (!target.startsWith(prefix) || !MEDIA_EXTENSIONS.has(path.extname(target).toLowerCase()) || !statSync(target).isFile()) {
    throw new Error('Choose a media file from the configured folder.');
  }
  return target;
}

function json(response, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8', 'Content-Length': String(bytes.length),
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
  });
  response.end(bytes);
}

async function requestBody(request) {
  const chunks = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length; if (size > 64 * 1024) throw new Error('Request is too large.'); chunks.push(chunk);
  }
  try { return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}; }
  catch { throw new Error('Request must be JSON.'); }
}

function waitForExit(child, timeoutMs = 3000) {
  if (!child || child.exitCode !== null) return Promise.resolve();
  return new Promise(resolve => {
    const timer = setTimeout(resolve, timeoutMs);
    child.once('exit', () => { clearTimeout(timer); resolve(); });
  });
}

export function localMediaRemotePage(prefix, root) {
  const safePrefix = JSON.stringify(prefix), safeRoot = root.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LUMA local media</title><style>
:root{color-scheme:dark;font-family:ui-sans-serif,system-ui,sans-serif;background:#07130f;color:#edf7f1}*{box-sizing:border-box}body{margin:0;padding:24px;background:radial-gradient(circle at 20% 0,#173d30,#07130f 45%);min-height:100vh}main{max-width:920px;margin:auto}h1{font-size:clamp(1.8rem,5vw,3.4rem);margin:.2em 0}p{color:#b8cec2}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}.card{background:#0d211aee;border:1px solid #2c5747;border-radius:18px;padding:18px;box-shadow:0 14px 40px #0005}label{display:block;margin:12px 0 5px;color:#cfe2d8}input,select,button{font:inherit;border-radius:10px;border:1px solid #426e5e;padding:11px;background:#102b21;color:#fff;width:100%}button{cursor:pointer;font-weight:700;background:#55bd8b;color:#04130c;border:0}button.secondary{background:#1c4234;color:#e7f4ec}button.danger{background:#a94747}.row{display:flex;gap:9px;align-items:center}.row>*{flex:1}.transport{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.transport button{padding:12px 5px}.status{min-height:1.5em;color:#9ee7c3;font-weight:650}.error{color:#ffaaa4}.preview{width:100%;aspect-ratio:16/9;object-fit:cover;background:#030806;border-radius:12px;display:none}.time{display:flex;justify-content:space-between;font-variant-numeric:tabular-nums}.small{font-size:.85rem;color:#8fab9d;word-break:break-all}@media(max-width:520px){body{padding:12px}.transport{grid-template-columns:repeat(2,1fr)}}
</style></head><body><main><p>LUMA laptop remote</p><h1>Local media, on the projector</h1><p>Choose a file below. The receiver owns playback, so paired phones can control the same session and share scrub thumbnails.</p>
<div class="grid"><section class="card"><h2>Projector</h2><label for="host">Receiver IP</label><input id="host" inputmode="decimal" placeholder="192.168.1.50"><div class="row"><button id="connect">Connect / pair</button><button id="refresh" class="secondary">Refresh</button></div><div id="pair" hidden><label for="code">Code on projector</label><div class="row"><input id="code" inputmode="numeric" maxlength="6"><button id="pair-complete">Pair</button></div></div><p id="projector-status" class="status"></p></section>
<section class="card"><h2>Source</h2><label for="source">Media file</label><select id="source"></select><p class="small">Folder: ${safeRoot}</p><label for="title">Projector title (optional)</label><input id="title" maxlength="200"><label for="mode">Compatibility</label><select id="mode"><option value="auto">Auto · preserve compatible files</option><option value="compat">High quality · H.264/AAC 1080p</option><option value="direct">Direct · no conversion</option></select><button id="play">Play on projector</button><p id="play-status" class="status"></p></section></div>
<section class="card" style="margin-top:16px"><h2 id="now">Nothing playing</h2><img id="preview" class="preview" alt="Scrub preview"><input id="seek" type="range" min="0" max="1" value="0" step="1" disabled><div class="time"><span id="position">0:00</span><span id="duration">0:00</span></div><div class="transport"><button id="back" class="secondary">−30 sec</button><button id="pause" class="secondary">Pause</button><button id="resume" class="secondary">Resume</button><button id="stop" class="danger">Stop</button></div><div class="grid"><div><label for="subtitles">Subtitles</label><div class="row"><select id="subtitles"><option value="off">Off</option></select><button id="set-subtitle" class="secondary">Apply</button></div></div><div><label for="volume">Volume</label><input id="volume" type="range" min="0" max="15" value="5" step="1"></div></div><p id="remote-status" class="status"></p></section>
</main><script>const API=${safePrefix};
const $=id=>document.getElementById(id), state={pairingId:null,status:null,seeking:false,subtitleDirty:false};
const clock=value=>{value=Math.max(0,Math.floor(Number(value)||0));return Math.floor(value/60)+':'+String(value%60).padStart(2,'0')};
const DISK_CACHE_LABELS=Object.freeze({waiting:'preparing',filling:'saving',complete:'complete',limited:'window ready',clearing:'clearing','low-storage':'disabled: low storage',error:'unavailable'});
const storage=value=>value>=1073741824?(value/1073741824).toFixed(1)+' GiB':Math.round(value/1048576)+' MiB';
const bufferDuration=value=>value<60?Math.max(0,Math.floor(value))+'s':clock(value);
function playbackBufferStatus(value){if(!value)return'';const parts=[];const ahead=Number.isFinite(value.bufferAheadSeconds)&&value.bufferAheadSeconds>=0?value.bufferAheadSeconds:Number.isFinite(value.bufferedSeconds)&&Number.isFinite(value.positionSeconds)?Math.max(0,value.bufferedSeconds-value.positionSeconds):null;if(value.active&&ahead!==null)parts.push('RAM '+bufferDuration(ahead));const cacheLabel=Object.hasOwn(DISK_CACHE_LABELS,value.diskCacheState)?DISK_CACHE_LABELS[value.diskCacheState]:null;if(cacheLabel){let disk='Disk '+cacheLabel;const cached=value.diskCachedBytes,target=value.diskCacheTargetBytes;if(Number.isSafeInteger(cached)&&cached>=0&&Number.isSafeInteger(target)&&target>0&&cached<=target)disk+=' '+storage(cached)+' / '+storage(target);if(Number.isFinite(value.diskCacheAheadSeconds)&&value.diskCacheAheadSeconds>=0)disk+=' · '+bufferDuration(value.diskCacheAheadSeconds)+' ahead';parts.push(disk)}return parts.join(' · ')}
async function call(route,body){const response=await fetch(API+route,{method:body?'POST':'GET',headers:{'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const data=await response.json();if(!response.ok)throw new Error(data.error||'Request failed');return data}
function note(id,text,error=false){$(id).textContent=text||'';$(id).classList.toggle('error',error)}
async function boot(){try{const data=await call('state');$('host').value=data.host||'';$('source').replaceChildren(...data.files.map(file=>Object.assign(document.createElement('option'),{value:file,textContent:file})));if(!data.files.length)$('source').append(Object.assign(document.createElement('option'),{textContent:'No media found',disabled:true}));note('play-status',data.files.length?data.files.length+' media file'+(data.files.length===1?'':'s')+' available':'Restart with --root /path/to/media');await refresh()}catch(error){note('projector-status',error.message,true)}}
async function beginPair(){try{const data=await call('pair/begin',{host:$('host').value.trim()});state.pairingId=data.pairingId;$('pair').hidden=false;$('code').focus();note('projector-status','Enter the six-digit code shown on the projector.')}catch(error){note('projector-status',error.message,true)}}
async function completePair(){try{await call('pair/complete',{host:$('host').value.trim(),pairingId:state.pairingId,code:$('code').value});$('pair').hidden=true;note('projector-status','Paired. This browser and your phones can control the receiver.');await refresh()}catch(error){note('projector-status',error.message,true)}}
async function play(){try{note('play-status','Starting…');await call('play',{host:$('host').value.trim(),file:$('source').value,mode:$('mode').value,title:$('title').value.trim()});note('play-status','Opening on the projector…');setTimeout(refresh,700)}catch(error){note('play-status',error.message,true)}}
async function command(action,extra={}){try{await call('command',{host:$('host').value.trim(),action,...extra});await refresh();return true}catch(error){note('remote-status',error.message,true);return false}}
function render(data){const value=data.playback;state.status=value;const buffers=playbackBufferStatus(value);if(!value?.active){$('now').textContent='Nothing playing';$('seek').disabled=true;if(buffers)note('remote-status',buffers);else if(data.streamer?.output&&!data.streamer.running)note('remote-status',data.streamer.output.trim().split('\\n').at(-1),true);else note('remote-status','');return}$('now').textContent=value.title||'Local media';const duration=Math.max(0,value.durationSeconds||0),position=Math.max(0,value.positionSeconds||0);$('seek').disabled=!value.canSeek;$('seek').max=Math.max(1,Math.floor(duration));if(!state.seeking)$('seek').value=Math.min(duration,position);$('position').textContent=clock(state.seeking?$('seek').value:position);$('duration').textContent=clock(duration);note('remote-status',(value.playing?'Playing':'Paused')+(data.streamer?.running?' · laptop stream active':'')+(buffers?' · '+buffers:''));const select=$('subtitles'),wanted=select.value;select.replaceChildren(Object.assign(document.createElement('option'),{value:'off',textContent:'Off'}),...(data.subtitles?.tracks||[]).map(track=>Object.assign(document.createElement('option'),{value:String(track.index),textContent:track.name||track.language||'Subtitle '+(track.index+1)})));const receiverValue=data.subtitles?.enabled?String(data.subtitles.currentIndex):'off',wantedAvailable=[...select.options].some(option=>option.value===wanted);if(state.subtitleDirty&&wantedAvailable)select.value=wanted;else{select.value=receiverValue;state.subtitleDirty=false}if(!select.value)select.value='off';if(Number.isInteger(data.volume?.max)){$('volume').max=data.volume.max;$('volume').value=data.volume.current}}
async function refresh(){try{const data=await call('status?host='+encodeURIComponent($('host').value.trim()));render(data);note('projector-status','Receiver connected.')}catch(error){note('projector-status',error.message,true)}}
let frameTimer;$('seek').addEventListener('input',()=>{state.seeking=true;$('position').textContent=clock($('seek').value);clearTimeout(frameTimer);frameTimer=setTimeout(async()=>{try{const data=await call('frame',{host:$('host').value.trim(),position:Number($('seek').value)});if(data.image){$('preview').src=data.image;$('preview').style.display='block'}}catch{}},100)});$('seek').addEventListener('change',async()=>{await command('seek',{position:Number($('seek').value)});state.seeking=false});
$('connect').onclick=beginPair;$('pair-complete').onclick=completePair;$('refresh').onclick=refresh;$('play').onclick=play;$('back').onclick=()=>command('seek',{position:Math.max(0,(state.status?.positionSeconds||0)-30)});$('pause').onclick=()=>command('pause');$('resume').onclick=()=>command('resume');$('stop').onclick=()=>command('stop');$('subtitles').addEventListener('change',()=>{state.subtitleDirty=true});$('set-subtitle').onclick=async()=>{if(await command('subtitle',{index:$('subtitles').value}))state.subtitleDirty=false};$('volume').addEventListener('change',()=>command('volume',{value:Number($('volume').value)}));
boot();setInterval(refresh,1500);</script></body></html>`;
}

export async function startLocalMediaRemote({ root: requestedRoot, host: initialHost, open = true } = {}) {
  const root = path.resolve(requestedRoot ?? process.cwd());
  if (!statSync(root).isDirectory()) throw new Error(`Media root is not a directory: ${root}`);
  const secret = randomBytes(24).toString('hex'), prefix = `/control/${secret}/`;
  let streamer = null, output = '', pairing = null;
  const stopStreamer = async () => {
    const owned = streamer; streamer = null;
    if (owned && owned.exitCode === null) { owned.kill('SIGTERM'); await waitForExit(owned); }
  };
  const configFor = host => {
    const config = loadConfig();
    if (!host || config.host !== host || !config.token) throw new Error('Pair this laptop with the receiver first.');
    return config;
  };
  const server = http.createServer(async (request, response) => {
    const peer = String(request.socket.remoteAddress ?? '').replace(/^::ffff:/, '');
    if (!['127.0.0.1', '::1'].includes(peer)) { response.writeHead(404).end(); return; }
    try {
      const url = new URL(request.url, 'http://127.0.0.1');
      if (request.method === 'GET' && url.pathname === prefix) {
        const html = Buffer.from(localMediaRemotePage(prefix + 'api/', root));
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': String(html.length),
          'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
          'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'" });
        response.end(html); return;
      }
      if (!url.pathname.startsWith(prefix + 'api/')) { response.writeHead(404).end(); return; }
      const route = url.pathname.slice((prefix + 'api/').length);
      if (request.method === 'GET' && route === 'state') {
        const config = loadConfig(); json(response, 200, { host: initialHost ?? config.host ?? '', files: mediaFiles(root) }); return;
      }
      if (request.method === 'POST' && route === 'pair/begin') {
        const body = await requestBody(request), host = String(body.host ?? ''); lanAddress(host);
        pairing = { host, ...(await receiverRequest(host, 'pair.begin', { clientName: 'Laptop browser remote' })) };
        json(response, 200, { pairingId: pairing.pairingId }); return;
      }
      if (request.method === 'POST' && route === 'pair/complete') {
        const body = await requestBody(request);
        if (!pairing || body.host !== pairing.host || body.pairingId !== pairing.pairingId || !/^\d{6}$/.test(String(body.code))) throw new Error('Start pairing again and enter the six-digit code.');
        const paired = await receiverRequest(pairing.host, 'pair.complete', { pairingId: pairing.pairingId, code: String(body.code) });
        const saved = loadConfig(); saveConfig({ ...saved, host: pairing.host, token: paired.token, clientId: paired.clientId }); pairing = null;
        json(response, 200, { paired: true }); return;
      }
      if (request.method === 'POST' && route === 'play') {
        const body = await requestBody(request), host = String(body.host ?? ''), config = configFor(host);
        await receiverRequest(host, 'player.status', {}, config.token); lanAddress(host);
        const file = selectedFile(root, body.file), mode = ['auto', 'direct', 'compat'].includes(body.mode) ? body.mode : 'auto';
        await stopStreamer(); output = '';
        const childArgs = [STREAMER, file, '--host', host, '--mode', mode];
        if (typeof body.title === 'string' && body.title.trim()) childArgs.push('--title', body.title.trim().slice(0, 200));
        streamer = spawn(process.execPath, childArgs, { stdio: ['ignore', 'pipe', 'pipe'] });
        for (const stream of [streamer.stdout, streamer.stderr]) { stream.setEncoding('utf8'); stream.on('data', chunk => { output = (output + chunk).slice(-4000); }); }
        json(response, 202, { started: true }); return;
      }
      if (request.method === 'GET' && route === 'status') {
        const host = url.searchParams.get('host') || loadConfig().host, config = configFor(host);
        const playback = await receiverRequest(host, 'player.status', {}, config.token);
        const subtitles = playback.active ? await receiverRequest(host, 'player.subtitles', {}, config.token) : { tracks: [], enabled: false, currentIndex: null };
        const volume = await receiverRequest(host, 'volume.get', {}, config.token);
        json(response, 200, { playback, subtitles, volume, streamer: { running: Boolean(streamer && streamer.exitCode === null), output: output.replace(/http:\/\/\S+/g, '[local stream]') } }); return;
      }
      if (request.method === 'POST' && route === 'command') {
        const body = await requestBody(request), host = String(body.host ?? ''), config = configFor(host);
        const actions = { pause: ['player.pause', {}], resume: ['player.resume', {}], stop: ['player.stop', {}],
          seek: ['player.seek', { position: Number(body.position) }], subtitle: ['player.setSubtitle', { index: body.index === 'off' ? 'off' : Number(body.index) }],
          volume: ['volume.set', { value: Number(body.value) }] };
        if (!actions[body.action] || body.action === 'seek' && !Number.isFinite(Number(body.position))) throw new Error('Invalid remote command.');
        const [method, params] = actions[body.action]; await receiverRequest(host, method, params, config.token);
        if (body.action === 'stop') await stopStreamer();
        json(response, 200, { accepted: true }); return;
      }
      if (request.method === 'POST' && route === 'frame') {
        const body = await requestBody(request), host = String(body.host ?? ''), config = configFor(host);
        const playback = await receiverRequest(host, 'player.status', {}, config.token), key = playback.mediaId;
        if (!/^local:[a-f0-9]{32,64}$/.test(key ?? '')) { json(response, 200, { image: null }); return; }
        const position = Math.max(0, Math.round(Number(body.position) / 30) * 30);
        const result = await receiverRequest(host, 'thumbs.get', { contentKey: key, positions: [position] }, config.token);
        const image = result.frames?.[0]?.image; json(response, 200, { image: image ? `data:image/jpeg;base64,${image}` : null }); return;
      }
      json(response, 404, { error: 'Not found.' });
    } catch (error) { json(response, 400, { error: String(error.message || 'Request failed').replace(/https?:\/\/\S+/g, '[address]') }); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const url = `http://127.0.0.1:${server.address().port}${prefix}`;
  console.log(`Laptop browser remote: ${url}`);
  console.log(`Serving media choices from ${root}. Press Ctrl-C to stop.`);
  if (open) {
    const command = process.platform === 'darwin' ? ['open', [url]] : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', url]] : ['xdg-open', [url]];
    const opener = spawn(command[0], command[1], { detached: true, stdio: 'ignore' }); opener.on('error', () => {}); opener.unref();
  }
  const close = async () => { await stopStreamer(); await new Promise(resolve => server.close(resolve)); };
  process.once('SIGINT', () => void close()); process.once('SIGTERM', () => void close());
  await new Promise(resolve => server.once('close', resolve));
}
