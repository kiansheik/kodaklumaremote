import {test as base,expect,chromium} from '@playwright/test';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';

const test = base.extend({
  context: async ({}, use) => {
    const profile=await mkdtemp(join(tmpdir(),'framextv-test-'));
    const extension=resolve('dist/test-extension');
    const context=await chromium.launchPersistentContext(profile,{
      channel:'chromium',headless:true,args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]
    });
    try {await use(context);}finally{await context.close();await rm(profile,{recursive:true,force:true});}
  },
  page: async ({context},use) => {const page=await context.newPage();await use(page);}
});
const host=page=>page.locator('[data-framextv-cast]');
async function open(page){await page.goto('/watch/fixture/1/1');await expect(host(page).getByRole('button',{name:'Cast video',exact:true})).toBeVisible();}
async function start(page){await open(page);await host(page).getByRole('button',{name:'Cast video',exact:true}).click();await expect(host(page).getByText('Playing directly on the receiver.',{exact:false})).toBeVisible();}

test('actual MV3 MAIN-world script uses page CAF and inserts one accessible control',async({page})=>{
  await open(page);
  await expect.poll(()=>page.evaluate(()=>!!window.__framextvCastExtension)).toBe(true);
  await page.addScriptTag({url:'/dist/test-extension/player.js'});
  await expect(host(page)).toHaveCount(1);
  expect(await page.evaluate(()=>window.castMock.calls)).toEqual([]);
});
test('copy video link reads declared HLS behind a blob without requiring Cast SDK',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    window.copiedLinks=[];
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.copiedLinks.push(value)},configurable:true});
    delete window.cast;
    const video=document.querySelector('video');
    video.src='blob:local-fixture';
    Object.defineProperty(video,'currentSrc',{value:'blob:local-fixture',configurable:true});
    const source=document.createElement('source');source.src='/authorized.m3u8?token=fixture';source.type='application/vnd.apple.mpegurl';video.append(source);
  });
  expect(await page.evaluate(()=>window.copiedLinks)).toEqual([]);
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('HLS video link copied.');
  expect(await page.evaluate(()=>window.copiedLinks)).toEqual(['http://127.0.0.1:4173/authorized.m3u8?token=fixture']);
  expect(await page.evaluate(()=>window.castMock.calls)).toEqual([]);
  await expect(host(page)).not.toContainText('token=fixture');
});
test('copy video link remains available after Cast discovery fails',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    window.castMock.sessionError='receiver_unavailable';
    window.copiedLinks=[];
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.copiedLinks.push(value)},configurable:true});
  });
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('No compatible Cast receiver');
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('MP4 video link copied.');
  expect(await page.evaluate(()=>window.copiedLinks.length)).toBe(1);
  expect(await page.evaluate(()=>window.castMock.calls.some(x=>x[0]==='load'))).toBe(false);
});
test('copy rejects encrypted and bare blob sources without writing the clipboard',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    window.copiedLinks=[];
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.copiedLinks.push(value)},configurable:true});
    Object.defineProperty(document.querySelector('video'),'mediaKeys',{value:{},configurable:true});
  });
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('Encrypted playback');
  await page.evaluate(()=>{
    const video=document.querySelector('video');delete video.mediaKeys;
    video.src='blob:local-fixture';
    Object.defineProperty(video,'currentSrc',{value:'blob:local-fixture',configurable:true});
  });
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('does not expose a supported direct media URL');
  expect(await page.evaluate(()=>window.copiedLinks)).toEqual([]);
});
test('clipboard failure is sanitized and can be retried',async({page})=>{
  await open(page);
  await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{value:{writeText:async()=>{throw new Error('private signed URL');}},configurable:true}));
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('Allow clipboard access');
  await expect(host(page)).not.toContainText('private signed URL');
  await expect(host(page).getByRole('button',{name:'Copy video link',exact:true})).toBeEnabled();
});
test('pending clipboard completion does not overwrite a new episode status',async({page})=>{
  await open(page);
  await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{value:{writeText:()=>new Promise(resolve=>window.finishCopy=resolve)},configurable:true}));
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await page.evaluate(()=>{history.pushState({},'','/watch/fixture/1/2');document.querySelector('video').src='/episode2.mp4';});
  await expect(host(page).getByRole('status')).toBeHidden();
  await page.evaluate(()=>window.finishCopy());
  await expect(host(page).getByRole('button',{name:'Copy video link',exact:true})).toBeEnabled();
  await expect(host(page).getByRole('status')).toBeHidden();
});
test('copy subtitle list includes all validated languages and metadata regardless of active mode without Cast',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    delete window.cast;
    window.copiedLinks=[];
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.copiedLinks.push(value)},configurable:true});
    const video=document.querySelector('video');
    const subtitle=document.createElement('track');
    subtitle.kind='subtitles';subtitle.src='/portuguese.vtt?token=fixture';subtitle.srclang='pt';subtitle.label='Português';
    video.append(subtitle);subtitle.track.mode='hidden';
    const rejected=document.createElement('track');rejected.src='/unsupported.srt';video.append(rejected);
  });
  expect(await page.evaluate(()=>window.copiedLinks)).toEqual([]);
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('2 subtitle links copied.');
  const copied=await page.evaluate(()=>JSON.parse(window.copiedLinks[0]));
  expect(copied.tracks).toHaveLength(2);
  expect(copied.tracks[0]).toMatchObject({id:1,url:'http://127.0.0.1:4173/tests/fixtures/en.vtt',contentType:'text/vtt',language:'en',label:'English'});
  expect(copied.tracks[1]).toEqual({id:2,url:'http://127.0.0.1:4173/portuguese.vtt?token=fixture',contentType:'text/vtt',language:'pt',label:'Português',active:false});
  expect(await page.evaluate(()=>window.castMock.calls)).toEqual([]);
  await expect(host(page)).not.toContainText('token=fixture');
});
test('copy subtitle list rejects unsupported subtitle sources and missing tracks',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    window.copiedLinks=[];
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.copiedLinks.push(value)},configurable:true});
    const track=document.querySelector('track');track.src='/unsupported.srt';track.track.mode='showing';
    const blob=document.createElement('track');blob.src='blob:subtitle-fixture';document.querySelector('video').append(blob);blob.track.mode='showing';
  });
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('No supported subtitle link is exposed by this player.');
  await page.evaluate(()=>document.querySelectorAll('track').forEach(track=>track.remove()));
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('No supported subtitle link is exposed by this player.');
  expect(await page.evaluate(()=>window.copiedLinks)).toEqual([]);
});
test('copy subtitle list rejects encrypted media and sanitizes clipboard failure',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    window.copyCount=0;
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async()=>{window.copyCount++;throw new Error('private subtitle URL');}},configurable:true});
    document.querySelector('track').track.mode='showing';
    Object.defineProperty(document.querySelector('video'),'mediaKeys',{value:{},configurable:true});
  });
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('Encrypted playback');
  expect(await page.evaluate(()=>window.copyCount)).toBe(0);
  await page.evaluate(()=>{delete document.querySelector('video').mediaKeys;});
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('Could not copy the subtitle list. Allow clipboard access for this page, then try again.');
  await expect(host(page)).not.toContainText('private subtitle URL');
  await expect(host(page).getByRole('button',{name:'Copy subtitle list',exact:true})).toBeEnabled();
});
test('pending subtitle list copy does not overwrite the next episode status',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    document.querySelector('track').track.mode='showing';
    Object.defineProperty(navigator,'clipboard',{value:{writeText:()=>new Promise(resolve=>window.finishCopy=resolve)},configurable:true});
  });
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await page.evaluate(()=>{history.pushState({},'','/watch/fixture/1/2');document.querySelector('video').src='/episode2.mp4';});
  await expect(host(page).getByRole('status')).toBeHidden();
  await page.evaluate(()=>window.finishCopy());
  await expect(host(page).getByRole('button',{name:'Copy subtitle list',exact:true})).toBeEnabled();
  await expect(host(page).getByRole('status')).toBeHidden();
});
test('inserts for a dynamically added player and removes detached controls',async({page})=>{
  await open(page);
  await page.evaluate(()=>document.querySelector('video').remove());
  await expect(host(page)).toHaveCount(0);
  await page.evaluate(()=>{const video=document.createElement('video');video.src='/next.mp4';document.querySelector('#player').append(video);});
  await expect(host(page)).toHaveCount(1);
  await expect(host(page)).toBeVisible();
});
test('SPA episode change disconnects old media and can cast again',async({page})=>{
  await start(page);
  await page.evaluate(()=>{history.pushState({},'','/watch/fixture/1/2');document.querySelector('video').src='/episode2.mp4';});
  await expect(host(page).getByRole('button',{name:'Disconnect',exact:true})).toBeHidden();
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>window.castMock.calls.filter(x=>x[0]==='load').length)).toBe(2);
});
test('successful LOAD pauses local video and exposes remote commands and subtitles',async({page})=>{
  await open(page);
  await page.evaluate(()=>{const video=document.querySelector('video');window.pauseCount=0;video.pause=()=>{window.pauseCount++;};});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>window.pauseCount)).toBe(1);
  await host(page).getByRole('button',{name:'Pause remote video'}).click();
  await host(page).getByLabel('Remote position').fill('42');
  await host(page).getByLabel('Remote position').dispatchEvent('change');
  await host(page).getByLabel('Remote volume').fill('0.5');
  await host(page).getByLabel('Remote volume').dispatchEvent('change');
  await host(page).getByLabel('Remote subtitles').selectOption('1');
  const calls=await page.evaluate(()=>window.castMock.calls);
  expect(calls.some(x=>x[0]==='pause')).toBe(true);
  expect(calls.some(x=>x[0]==='seek'&&x[1]===42)).toBe(true);
  expect(calls.some(x=>x[0]==='volume'&&x[1]===.5)).toBe(true);
  expect(calls.some(x=>x[0]==='tracks'&&x[1][0]===1)).toBe(true);
  await host(page).getByRole('button',{name:'Disconnect',exact:true}).click();
  await expect(host(page).getByRole('button',{name:'Disconnect',exact:true})).toBeHidden();
});
test('failed LOAD leaves local playback alone and does not leak errors',async({page})=>{
  await open(page);
  await page.evaluate(()=>{window.castMock.failLoad=true;window.pauseCount=0;document.querySelector('video').pause=()=>window.pauseCount++;});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('receiver connected but could not load');
  expect(await page.evaluate(()=>window.pauseCount)).toBe(0);
  await expect(host(page)).not.toContainText('private signed');
});
test('missing SDK and unsupported blob fail clearly without fetching SDK',async({page})=>{
  const scripts=[];page.on('request',req=>{if(req.resourceType()==='script')scripts.push(req.url());});
  await open(page);
  await page.evaluate(()=>{delete window.cast;});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('Google Cast is not ready');
  await page.evaluate(()=>{const video=document.querySelector('video');video.src='blob:local-fixture';Object.defineProperty(video,'currentSrc',{value:'blob:local-fixture',configurable:true});});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('does not expose a supported direct media URL');
  expect(scripts.some(url=>url.includes('gstatic'))).toBe(false);
});
test('preserves visible site Cast button while keeping video and subtitle copying available',async({page})=>{
  await open(page);
  await page.evaluate(()=>{
    window.copiedLinks=[];
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.copiedLinks.push(value)},configurable:true});
    const native=document.createElement('button');native.setAttribute('aria-label','Cast to TV / Chromecast');native.textContent='Native Cast';document.body.append(native);
  });
  await expect(host(page).getByRole('button',{name:'Cast video',exact:true})).toBeHidden();
  await expect(page.getByRole('button',{name:'Cast to TV / Chromecast',exact:true})).toBeVisible();
  await host(page).getByRole('button',{name:'Copy video link',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('MP4 video link copied.');
  await host(page).getByRole('button',{name:'Copy subtitle list',exact:true}).click();
  await expect(host(page).getByRole('status')).toHaveText('1 subtitle links copied.');
  const copied=await page.evaluate(()=>window.copiedLinks);
  expect(copied[0]).toBe('http://127.0.0.1:4173/test-video.mp4');
  expect(JSON.parse(copied[1]).tracks).toHaveLength(1);
  expect(await page.evaluate(()=>window.castMock.calls)).toEqual([]);
  await page.getByRole('button',{name:'Cast to TV / Chromecast',exact:true}).evaluate(el=>el.remove());
  await expect(host(page).getByRole('button',{name:'Cast video',exact:true})).toBeVisible();
});
test('same-origin embed frame gets its own player integration',async({page})=>{
  await page.goto('/watch/fixture/1/1');
  await page.evaluate(()=>{document.querySelector('video').remove();const frame=document.createElement('iframe');frame.src='/embed/fixture/1/1';frame.style='width:800px;height:500px';document.body.append(frame);});
  await expect(page.frameLocator('iframe').locator('[data-framextv-cast]').getByRole('button',{name:'Cast video',exact:true})).toBeVisible();
  await expect(host(page)).toHaveCount(0);
});
test('disconnect and cleanup remove listeners and controls',async({page})=>{
  await start(page);
  await page.evaluate(()=>{window.castMock.update({currentTime:31});window.castMock.end();});
  await expect(host(page).getByRole('button',{name:'Disconnect',exact:true})).toBeHidden();
  await page.evaluate(()=>window.__framextvCastExtension.cleanup());
  await expect(host(page)).toHaveCount(0);
  expect(await page.evaluate(()=>window.castMock.listenerCount())).toBe(0);
});
test('unavailable website fixture and non-player routes stay unobtrusive',async({page})=>{
  await page.route('**/watch/unavailable/**',route=>route.fulfill({status:503,contentType:'text/html',body:'<title>Unavailable</title><p>Try later</p>'}));
  await page.goto('/watch/unavailable/1/1');
  await expect.poll(()=>page.evaluate(()=>!!window.__framextvCastExtension)).toBe(true);
  await expect(host(page)).toHaveCount(0);
  await page.goto('/tests/fixtures/player.html');
  await expect(host(page)).toHaveCount(0);
});
test('source replacement during pending LOAD cannot pause or seek the new episode',async({page})=>{
  await open(page);
  await page.evaluate(()=>{window.castMock.pendingLoad=true;window.pauseCount=0;document.querySelector('video').pause=()=>window.pauseCount++;});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>!!window.castMock.resolveLoad)).toBe(true);
  await page.evaluate(()=>{document.querySelector('video').src='/replacement.mp4';});
  await expect.poll(()=>page.evaluate(()=>window.castMock.calls.some(x=>x[0]==='end'))).toBe(true);
  await page.evaluate(()=>window.castMock.resolveLoad());
  await expect(host(page).getByRole('button',{name:'Cast video',exact:true})).toBeEnabled();
  expect(await page.evaluate(()=>window.pauseCount)).toBe(0);
  await expect(host(page).getByRole('button',{name:'Disconnect',exact:true})).toBeHidden();
});
test('same-element source change does not restore the old remote time into new source',async({page})=>{
  await start(page);
  await page.evaluate(()=>{window.castMock.update({currentTime:31});document.querySelector('video').src='/new-episode.mp4';});
  await expect(host(page).getByRole('button',{name:'Disconnect',exact:true})).toBeHidden();
  expect(await page.evaluate(()=>document.querySelector('video').currentTime)).toBe(0);
});
test('disconnect errors are caught and sanitized',async({page})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await start(page);
  await page.evaluate(()=>{window.castMock.failEnd=true;});
  await host(page).getByRole('button',{name:'Disconnect',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('remote command failed');
  expect(errors).toEqual([]);
  await expect(host(page)).not.toContainText('private receiver');
});
test('preserves integration through a simulated BFCache freeze and restore',async({page})=>{
  await open(page);
  await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true})));
  await page.evaluate(()=>window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true})));
  await expect(host(page).getByRole('button',{name:'Cast video',exact:true})).toBeVisible();
});
test('post-LOAD source validation failure cleans receiver without pausing local',async({page})=>{
  await open(page);
  await page.evaluate(()=>{window.castMock.pendingLoad=true;window.pauseCount=0;document.querySelector('video').pause=()=>window.pauseCount++;});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>!!window.castMock.resolveLoad)).toBe(true);
  await page.evaluate(()=>{Object.defineProperty(document.querySelector('video'),'mediaKeys',{value:{},configurable:true});window.castMock.resolveLoad();});
  await expect.poll(()=>page.evaluate(()=>window.castMock.calls.some(x=>x[0]==='end'))).toBe(true);
  expect(await page.evaluate(()=>window.pauseCount)).toBe(0);
});
test('no compatible receiver is a discovery failure without a media LOAD',async({page})=>{
  await open(page);
  await page.evaluate(()=>{window.castMock.sessionError='receiver_unavailable';});
  await host(page).getByRole('button',{name:'Cast video',exact:true}).click();
  await expect(host(page).getByRole('status')).toContainText('No compatible Cast receiver is available');
  expect(await page.evaluate(()=>window.castMock.calls.some(x=>x[0]==='load'))).toBe(false);
  await expect(host(page)).not.toContainText('private source URL');
});
