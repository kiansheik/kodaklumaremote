import {chromium} from 'playwright';
import {mkdir, writeFile} from 'node:fs/promises';

// Clean ephemeral context. No script overrides, auth state, media body collection,
// HAR, traces, or raw console text. Automation is not a DevTools-open test.
const target = 'https://framextv.xyz/watch/284833/1/1';
const output = process.env.RECON_OUTPUT || '.local/recon/browser-metadata.json';
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
const browser = await chromium.launch({channel: process.env.BROWSER_CHANNEL || 'chromium', headless:process.env.HEADED !== '1'});
const context = await browser.newContext({viewport:{width:1280,height:800}});
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
await page.waitForTimeout(8000);
const frames = [];
for (const frame of page.frames()) {
  try {
    const dom = await frame.evaluate(() => ({
      title:document.title.slice(0,120),
      video:[...document.querySelectorAll('video')].map(video => ({
        srcKind:video.currentSrc.startsWith('blob:')?'blob':/^https?:/.test(video.currentSrc)?'http':video.currentSrc?'other':'empty',
        declaredSources:[...video.querySelectorAll('source')].map(source => ({type:source.type, http:/^https?:/.test(source.src)})),
        readyState:video.readyState, paused:video.paused, hasMediaKeys:!!video.mediaKeys,
        width:video.clientWidth,height:video.clientHeight
      })),
      sdk:!!window.cast?.framework,
      nativeCastControls:[...document.querySelectorAll('button[aria-label],google-cast-launcher')].filter(el => /cast/i.test(el.getAttribute('aria-label')||el.tagName)).map(el => ({
        label:el.getAttribute('aria-label')?.replace(/\(.*\)/,'(device)'),visible:el.getBoundingClientRect().width>0
      }))
    }));
    frames.push({...describe(frame.url()),...dom});
  } catch {frames.push({...describe(frame.url()),evaluation:'unavailable'});}
}
const report = {timestamp:new Date().toISOString(),environment:{browser:browser.version(),headless:process.env.HEADED!=='1',cleanProfile:true,
  devtoolsUI:false, runtimeOverrides:false}, navigation,final:describe(page.url()),consoles,frames,events};
await browser.close();
await mkdir(output.slice(0,output.lastIndexOf('/')) || '.',{recursive:true});
await writeFile(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({timestamp:report.timestamp,environment:report.environment,navigation,final:report.final,
  consoles,frames,eventCount:events.length,reportPath:output},null,2));
