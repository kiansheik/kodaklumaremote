import {chromium} from 'playwright';
import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';

// Clean ephemeral context. No anti-debugging patches, auth state, media body
// collection, HAR, traces, or raw console text. Extension/DOM sampling are opt-in.
// Automation is not a DevTools-open test.
const target = 'https://framextv.xyz/watch/284833/1/1';
const output = process.env.RECON_OUTPUT || '.local/recon/browser-metadata.json';
const extensionDir = process.env.EXTENSION_DIR ? resolve(process.env.EXTENSION_DIR) : null;
const sampleDom = process.env.SAMPLE_DOM === '1';
const events = [];
const consoles = {};
function describe(raw) {
  try {
    const url = new URL(raw);
    if (!['http:','https:'].includes(url.protocol)) return {scheme:url.protocol};
    let kind = 'other';
    if (/\/assets\/[\w.-]+\.(?:js|css)$/.test(url.pathname)) kind = `asset:${url.pathname.split('/').at(-1)}`;
    else if (/\.(?:m3u8|mpd|mp4|m4s|ts|vtt)$/.test(url.pathname)) kind = 'media-or-track';
    else if (/\/api\//.test(url.pathname)) kind = `api:${url.pathname.split('/api/')[1].split('/')[0].replace(/[^a-z-]/gi,'')}`;
    else if (/\/(watch|embed)\//.test(url.pathname)) kind = url.pathname.split('/')[1];
    return {origin:url.origin, kind, hasQuery:!!url.search};
  } catch {return {kind:'unparseable'};}
}
const launch = {channel: process.env.BROWSER_CHANNEL || 'chromium', headless:process.env.HEADED !== '1'};
let browser;
let context;
if (extensionDir) {
  context = await chromium.launchPersistentContext('', {...launch, viewport:{width:1280,height:800},
    args:[`--disable-extensions-except=${extensionDir}`,`--load-extension=${extensionDir}`]});
  browser = context.browser();
} else {
  browser = await chromium.launch(launch);
  context = await browser.newContext({viewport:{width:1280,height:800}});
}
const page = await context.newPage();
page.on('console', msg => {consoles[msg.type()] = (consoles[msg.type()] || 0)+1;});
page.on('response', response => {
  const headers = response.headers();
  events.push({event:'response', ...describe(response.url()), status:response.status(),
    resource:response.request().resourceType(), contentType:headers['content-type']?.split(';')[0],
    cors:headers['access-control-allow-origin'] ? 'present' : 'absent'});
});
page.on('requestfailed', request => events.push({event:'failed', ...describe(request.url()),
  resource:request.resourceType(), reason:request.failure()?.errorText?.match(/net::[A-Z_]+/)?.[0] || 'failed'}));
page.on('framenavigated', frame => events.push({event:'navigation',main:frame===page.mainFrame(),...describe(frame.url())}));
let navigation = 'completed';
try {await page.goto(target,{waitUntil:'domcontentloaded',timeout:25000});}
catch {navigation = 'timed-out-or-failed';}
async function summarizeFrames(transient = false) {
 const frames = [];
 for (const frame of page.frames()) {
  try {
    const dom = await frame.evaluate(transient => ({
      ...(transient ? {titlePresent:!!document.title} : {title:document.title.slice(0,120)}),
      video:[...document.querySelectorAll('video')].map(video => ({
        srcKind:video.currentSrc.startsWith('blob:')?'blob':/^https?:/.test(video.currentSrc)?'http':video.currentSrc?'other':'empty',
        declaredSources:[...video.querySelectorAll('source')].map(source => ({type:source.type, http:/^https?:/.test(source.src)})),
        readyState:video.readyState, paused:video.paused, hasMediaKeys:!!video.mediaKeys,
        width:video.clientWidth,height:video.clientHeight
      })),
      sdk:!!window.cast?.framework,
      ...(transient ? {
        extensionMarker:!!window.__framextvCastExtension,
        extensionHost:{present:!!document.querySelector('[data-framextv-cast]'),
          visible:[...document.querySelectorAll('[data-framextv-cast]')].some(el => !el.hidden && el.getBoundingClientRect().width>0)}
      } : {}),
      nativeCastControls:[...document.querySelectorAll('button[aria-label],google-cast-launcher')].filter(el => /cast/i.test(el.getAttribute('aria-label')||el.tagName)).map(el => ({
        ...(transient ? {kind:el.tagName.toLowerCase()==='google-cast-launcher'?'launcher':'cast'} : {label:el.getAttribute('aria-label')?.replace(/\(.*\)/,'(device)')}),
        visible:el.getBoundingClientRect().width>0
      }))
    }), transient);
    const location = describe(frame.url());
    // Samples retain route category/scheme only, never origin, path or query values.
    const frameLocation = transient ? (location.scheme ? {scheme:location.scheme} : {kind:location.kind}) : location;
    frames.push({...frameLocation,...dom});
  } catch {frames.push(transient ? {evaluation:'unavailable'} : {...describe(frame.url()),evaluation:'unavailable'});}
 }
 return frames;
}
const samples = [];
if (sampleDom) {
  const started = Date.now();
  for (let tick=0; tick<=16; tick++) {
    const delay = started + tick*500 - Date.now();
    if (delay>0) await page.waitForTimeout(delay);
    samples.push({elapsedMs:Date.now()-started,frames:await summarizeFrames(true)});
  }
} else await page.waitForTimeout(8000);
const frames = await summarizeFrames();
const report = {timestamp:new Date().toISOString(),environment:{browser:browser.version(),headless:process.env.HEADED!=='1',cleanProfile:true,
  devtoolsUI:false, runtimeOverrides:!!extensionDir,
  ...(extensionDir ? {extensionEnabled:true,runtimeOverrideKind:'requested-extension-only'} : {}),
  ...(sampleDom ? {domSampling:true} : {})}, navigation,final:describe(page.url()),consoles,frames,events,
  ...(sampleDom ? {samples} : {})};
await browser.close();
await mkdir(output.slice(0,output.lastIndexOf('/')) || '.',{recursive:true});
await writeFile(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({timestamp:report.timestamp,environment:report.environment,navigation,final:report.final,
  consoles,frames,eventCount:events.length,reportPath:output},null,2));
