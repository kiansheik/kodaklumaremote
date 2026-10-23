import {test, expect} from '@playwright/test';
import {readFile, mkdir} from 'node:fs/promises';

const source = await readFile(new URL('../../src/msx/receiver-core.js', import.meta.url), 'utf8');
const receiverHtml = await readFile(new URL('../../src/msx/receiver.html', import.meta.url), 'utf8');
const receiverCss = await readFile(new URL('../../src/msx/receiver.css', import.meta.url), 'utf8');
const receiverJs = await readFile(new URL('../../src/msx/receiver.js', import.meta.url), 'utf8');

async function remoteFixture(page, {pointerMode = 'none'} = {}) {
  await page.clock.install();
  await page.setViewportSize({width: 1920, height: 1080});
  const requests = [], held = [], pageErrors = [];
  const fixture = {requests, held, pageErrors, hold: false, badPrepared: false, libraryHandler: null, libraryStates: ['ready']};
  page.on('pageerror', error => pageErrors.push(error.message));
  const patch = `
    const native = window.LumaMsxReceiver;
    const pointerMode = ${JSON.stringify(pointerMode)};
    if(pointerMode==='none') Object.defineProperty(document.body,'requestPointerLock',{configurable:true,value:undefined});
    else {
      const pointer=window.__pointer={element:null,requests:0,exits:0,order:[]};
      Object.defineProperty(document,'pointerLockElement',{configurable:true,get(){return pointer.element}});
      document.body.requestPointerLock=function(){pointer.requests++;pointer.order.push('lock');if(pointerMode==='reject'||pointerMode==='denied')return Promise.reject(pointerMode==='denied'?new DOMException('owned rejection','NotAllowedError'):new Error('owned rejection'));pointer.element=document.body;queueMicrotask(()=>document.dispatchEvent(new Event('pointerlockchange')));return pointerMode==='void'?undefined:Promise.resolve()};
      document.exitPointerLock=function(){pointer.exits++;pointer.element=null;queueMicrotask(()=>document.dispatchEvent(new Event('pointerlockchange')))};
      document.documentElement.requestFullscreen=function(){pointer.order.push('fullscreen');return Promise.resolve()};
    }
    const video = window.__video = Object.assign(new EventTarget(), {paused:true,ended:false,error:null,currentTime:25,duration:100,volume:1,readyState:4,seekable:{length:1},src:'',playCount:0,loadCount:0,
      canPlayType(){return 'probably'}, pause(){this.paused=true;this.dispatchEvent(new Event('pause'))}, removeAttribute(){this.src=''}, load(){this.loadCount++},
      play(){this.playCount++;this.paused=false;queueMicrotask(()=>this.dispatchEvent(new Event('playing')));return Promise.resolve()}});
    window.LumaMsxReceiver={...native,createPlayer(options){return window.__player=native.createPlayer({...options,video})},createChannel(options){return window.__channel=native.createChannel(options)}};
  `;
  await page.route('**/tests/fixtures/**', async route => {
    const url = new URL(route.request().url()), name = url.pathname.split('/').at(-1);
    const base = url.origin + '/tests/fixtures/tv-remote/';
    const body = route.request().postDataJSON();
    requests.push({name, method: route.request().method(), body});
    const reply = (value, contentType = 'application/json') => route.fulfill({contentType, body: typeof value === 'string' ? value : JSON.stringify(value)});
    if (name === 'receiver.html') return reply(receiverHtml, 'text/html');
    if (name === 'receiver.css') return reply(receiverCss, 'text/css');
    if (name === 'receiver-core.js') return reply(source + patch, 'text/javascript');
    if (name === 'receiver.js') return reply(receiverJs.replace('__LUMA_RECEIVER_REVISION__', 'a'.repeat(64)), 'text/javascript');
    if (name === 'hls.min.js') return reply('', 'text/javascript');
    if (name === 'start.json') return reply({parameter: 'content:' + base + 'home.json'});
    if (name === 'hello') return reply({sessionId: 'owned-fixture-session', approved: true});
    if (name === 'poll') return reply({approved: true, commands: []});
    if (name === 'state' || name === 'ack' || name === 'diagnostic') return reply({ok: true});
    if (name === 'library') {
      if (fixture.libraryHandler) return fixture.libraryHandler(body, reply);
      const movie = {id:'42',kind:'movie',title:'Owned movie fixture',poster:null,year:'2024',overview:'A household film.',rating:8};
      const series = {id:'84',kind:'tv',title:'Owned series fixture',poster:null,year:'2025',overview:'A household series.',rating:8};
      if (body.action === 'list' || body.action === 'search') return reply({items:body.params.kind==='tv'?[series]:[movie,series],page:body.params.page,totalPages:2,totalResults:4});
      if (body.action === 'details') return reply({...body.params.kind==='tv'?series:movie,seasons:[{number:0,name:'Specials',episodeCount:1,poster:null}],runtime:90});
      if (body.action === 'episodes') return reply({id:body.params.id,season:body.params.season,episodes:[{number:1,title:'Episode one',overview:'An episode.',still:null}]});
      if (body.action === 'history') return reply({version:1,items:[{kind:'tv',contentId:'84',season:0,episode:1,title:'Owned series fixture',resumeSeconds:125,completed:false}]});
      if (body.action === 'play') return reply({accepted:true,jobId:'b'.repeat(32),state:'resolving'});
      if (body.action === 'playStatus') return reply({jobId:body.params.jobId,state:fixture.libraryStates.length>1?fixture.libraryStates.shift():fixture.libraryStates[0]});
    }
    if (name === 'subtitle-prepare') {
      const response = {index: body.index, source: body.expectedSourceUrl, url: fixture.badPrepared ? 'https://external.example/private.srt' : base + 'subtitles/' + String(body.index + 1).repeat(64) + '.srt'};
      if (fixture.hold) { held.push(() => reply(response)); return; }
      return reply(response);
    }
    if (name.endsWith('.srt')) return reply('1\n00:00:24,000 --> 00:00:30,000\nOlá no Brasil\n', 'application/x-subrip');
    return route.fulfill({status: 404, body: ''});
  });
  await page.goto('/tests/fixtures/tv-remote/receiver.html');
  await page.waitForFunction(() => window.__channel?.state().approved);
  await page.evaluate(() => window.__player.command('open', {source: 'https://media.example/remote.mp4', title: 'Remote control fixture', autoSubtitles: false, subtitles: [
    {url:'https://captions.example/en.srt',language:'en',label:'English'},
    {url:'https://captions.example/pt.srt',language:'pt-BR',label:'Português (Brasil)'},
    {url:'https://captions.example/es.srt',language:'es',label:'Español'}
  ]}));
  return fixture;
}

async function controlsFit(page) {
  expect(await page.evaluate(() => [...document.querySelectorAll('#tv-controls, #tv-control-feedback, #tv-controls button')].filter(node => node.checkVisibility()).flatMap(node => {
    const rect = node.getBoundingClientRect();
    return rect.top >= 0 && rect.left >= 0 && rect.bottom <= innerHeight + 1 && rect.right <= innerWidth + 1 ? [] : [{id: node.id, top: rect.top, bottom: rect.bottom, viewport: innerHeight, scroll: document.scrollingElement.scrollTop}];
  }))).toEqual([]);
}

test('physical remote navigates the real receiver DOM, controls video and lazily selects captions', async ({page}) => {
  const fixture = await remoteFixture(page);
  await expect(page.locator('#tv-controls')).toBeHidden();
  await page.keyboard.press('Enter');
  await expect(page.locator('#tv-play-pause')).toBeFocused();
  await controlsFit(page);
  expect(await page.locator('#tv-play-pause').evaluate(node => getComputedStyle(node).cursor)).toBe('none');
  expect(await page.locator('#start-playback').evaluate(node => getComputedStyle(node).cursor)).toBe('none');
  await mkdir('.local/tv-hello/android-player', {recursive: true});
  await page.screenshot({path: '.local/tv-hello/android-player/tv-controls-1920.png'});
  await page.keyboard.press('Enter');
  expect(await page.evaluate(() => window.__player.state().paused)).toBe(true);
  await page.keyboard.press('ArrowRight'); await page.keyboard.press('Enter');
  expect(await page.evaluate(() => window.__player.state().positionSeconds)).toBe(15);
  await page.keyboard.press('ArrowRight'); await page.keyboard.press('Enter');
  expect(await page.evaluate(() => window.__player.state().positionSeconds)).toBe(25);
  await page.keyboard.press('ArrowRight'); await page.keyboard.press('Enter');
  await expect(page.locator('#tv-subtitle-picker')).toBeVisible();
  await expect(page.locator('#tv-subtitle-list button')).toHaveText(['Subtitles off', 'Português (Brasil)', 'English', 'Español']);
  await controlsFit(page);
  await page.screenshot({path: '.local/tv-hello/android-player/tv-subtitles-1920.png'});
  await page.setViewportSize({width: 1280, height: 720}); await controlsFit(page);
  await page.setViewportSize({width: 1920, height: 1080});
  await page.clock.runFor(10000);
  await expect(page.locator('#tv-subtitle-picker')).toBeVisible();
  await page.keyboard.press('ArrowDown'); await page.keyboard.press('Enter');
  await expect(page.locator('#tv-control-feedback')).toHaveText('Subtitle selected.');
  await expect(page.locator('#caption')).toHaveText('Olá no Brasil');
  const prepared = fixture.requests.filter(request => request.name === 'subtitle-prepare');
  expect(prepared.map(request => request.body.index)).toEqual([1]);
  expect(prepared[0].body.client).toMatch(/^tv-/);
  expect(prepared[0].body.sessionId).toBe('owned-fixture-session');
  await page.locator('#tv-subtitle-list button').first().click();
  await expect(page.locator('#caption')).toHaveText('');
  expect(fixture.requests.filter(request => request.name === 'subtitle-prepare')).toHaveLength(1);
  await page.keyboard.press('Backspace'); await expect(page.locator('#tv-subtitle-picker')).toBeHidden();
  await page.keyboard.press('Backspace'); await expect(page.locator('#tv-controls')).toBeHidden();
  expect(await page.evaluate(() => {
    const event = new KeyboardEvent('keydown', {key:'Backspace',keyCode:8,cancelable:true}); document.dispatchEvent(event); return event.defaultPrevented;
  })).toBe(false);
  expect(await page.evaluate(() => {
    const event = new KeyboardEvent('keydown', {key:'Unmapped',cancelable:true}); document.dispatchEvent(event); return event.defaultPrevented;
  })).toBe(false);
  await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', {key:'Pause',keyCode:19,cancelable:true})));
  expect(await page.evaluate(() => window.__player.state().paused)).toBe(true);
  await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', {key:'Unidentified',keyCode:415,cancelable:true})));
  expect(await page.evaluate(() => window.__player.state().paused)).toBe(false);
  await expect(page.locator('#tv-controls')).toBeVisible(); await page.clock.runFor(6100);
  await expect(page.locator('#tv-controls')).toBeHidden();
  expect(await page.evaluate(() => window.__video.loadCount)).toBe(2);
  expect(fixture.pageErrors).toEqual([]);
});

test('physical caption preparation rejects foreign URLs and ignores Off or new-source superseded results', async ({page}) => {
  const fixture = await remoteFixture(page);
  await page.keyboard.press('Enter'); await page.locator('#tv-subtitles').click();
  fixture.badPrepared = true;
  await page.getByRole('button', {name:'English',exact:true}).click();
  await expect(page.locator('#tv-control-feedback')).toContainText('Subtitles could not load');
  expect(await page.evaluate(() => window.__player.state().active)).toBe(true);
  expect(fixture.requests.some(request => request.name.endsWith('.srt'))).toBe(false);
  fixture.badPrepared = false; fixture.hold = true;
  await page.getByRole('button', {name:'English',exact:true}).click();
  await expect.poll(() => fixture.held.length).toBe(1);
  await page.locator('#tv-subtitle-list [data-subtitle-index="-1"]').click();
  await fixture.held.shift()();
  await expect(page.locator('#tv-control-feedback')).toHaveText('Subtitles off.');
  expect(await page.evaluate(() => window.__player.state().subtitlesEnabled)).toBe(false);
  await page.getByRole('button', {name:'Português (Brasil)',exact:true}).click();
  await expect.poll(() => fixture.held.length).toBe(1);
  await page.evaluate(() => window.__player.command('open', {source:'https://media.example/new.mp4',title:'New video',subtitles:[],autoSubtitles:false}));
  await fixture.held.shift()();
  await expect.poll(() => page.evaluate(() => window.__player.state().source)).toBe('https://media.example/new.mp4');
  expect(await page.evaluate(() => window.__player.state().subtitlesEnabled)).toBe(false);
  expect(fixture.requests.some(request => request.name.endsWith('.srt'))).toBe(false);
  expect(fixture.pageErrors).toEqual([]);
});

test('background clicks open controls once and only handled input classes appear in receiver diagnostics', async ({page}) => {
  const fixture = await remoteFixture(page);
  const input = () => page.evaluate(() => { const {inputCount, lastInput} = window.__player.state().receiverDebug; return {inputCount, lastInput}; });
  expect(await input()).toEqual({inputCount: 0, lastInput: 'none'});
  await page.locator('#video').click({position: {x: 500, y: 300}});
  await expect(page.locator('#tv-controls')).toBeVisible();
  expect(await page.evaluate(() => window.__player.state().paused)).toBe(false);
  expect(await input()).toEqual({inputCount: 1, lastInput: 'pointer-background'});
  await page.locator('#tv-close-controls').dispatchEvent('click');
  await expect(page.locator('#tv-controls')).toBeHidden();
  expect(await input()).toEqual({inputCount: 1, lastInput: 'pointer-background'});
  await page.locator('#start-playback').dispatchEvent('click');
  await expect(page.locator('#tv-controls')).toBeHidden();
  await page.keyboard.press('Enter'); await page.keyboard.press('ArrowRight');
  expect(await input()).toEqual({inputCount: 3, lastInput: 'key-arrow'});
  await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', {key:'Unmapped-secret-value',keyCode:999,cancelable:true})));
  expect(await input()).toEqual({inputCount: 3, lastInput: 'key-arrow'});
  await page.keyboard.press('Escape');
  expect(await input()).toEqual({inputCount: 4, lastInput: 'key-back'});
  await page.keyboard.press('Backspace');
  expect(await input()).toEqual({inputCount: 4, lastInput: 'key-back'});
  await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', {key:'MediaPause',repeat:true,cancelable:true})));
  expect(await input()).toEqual({inputCount: 4, lastInput: 'key-back'});
  expect(fixture.pageErrors).toEqual([]);
});

test('small relative pointer strokes become one arrow step without pointer lock', async ({page}) => {
  const fixture = await remoteFixture(page, {pointerMode: 'void'});
  const motion = (x, y) => page.evaluate(({x,y}) => document.dispatchEvent(new MouseEvent('mousemove',{movementX:x,movementY:y,bubbles:true})), {x,y});
  await page.locator('#video').click({position:{x:500,y:300}});
  expect(await page.evaluate(() => window.__pointer.order)).toEqual([]);
  expect(await page.evaluate(() => window.__player.state().receiverDebug)).toMatchObject({pointerLocked:false,pointerLockError:'disabled'});
  await motion(2,0); await expect(page.locator('#tv-play-pause')).toBeFocused();
  await motion(2,0); await expect(page.locator('#tv-seek-back')).toBeFocused();
  await motion(100,0); await expect(page.locator('#tv-seek-back')).toBeFocused();
  await page.clock.runFor(161); await motion(0,4); await expect(page.locator('#tv-seek-forward')).toBeFocused();
  await page.keyboard.press('ArrowLeft'); await motion(100,0); await expect(page.locator('#tv-seek-back')).toBeFocused();
  await page.clock.runFor(251); await motion(-20,0); await expect(page.locator('#tv-play-pause')).toBeFocused();
  expect(await page.evaluate(() => window.__player.state().receiverDebug.lastInput)).toBe('pointer-arrow');
  await page.locator('#video').click({position:{x:500,y:300}});
  expect(await page.evaluate(() => window.__player.state().paused)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(page.locator('#tv-controls')).toBeHidden();
  expect(await page.evaluate(() => [window.__pointer.requests,window.__pointer.exits,window.__player.state().receiverDebug.pointerLocked])).toEqual([0,0,false]);
  await page.locator('#video').click({position:{x:500,y:300}});
  expect(await page.evaluate(() => window.__pointer.requests)).toBe(0);
  await expect(page.locator('#tv-controls')).toBeVisible();
  await page.locator('#tv-close-controls').click();
  const before = await page.evaluate(() => window.__player.state().receiverDebug.inputCount);
  await motion(100,100);
  expect(await page.evaluate(() => window.__player.state().receiverDebug.inputCount)).toBe(before);
  expect(await page.evaluate(() => window.__video.loadCount)).toBe(2);
  expect(fixture.pageErrors).toEqual([]);
});

test('directional adapter never requests pointer lock and preserves browser-modified keys', async ({page}) => {
  const fixture = await remoteFixture(page, {pointerMode:'reject'});
  await page.locator('#video').click({position:{x:500,y:300}});
  expect(await page.evaluate(() => window.__player.state().receiverDebug)).toMatchObject({pointerLockSupported:true,pointerLocked:false,pointerLockError:'disabled'});
  expect(await page.evaluate(() => { const event=new KeyboardEvent('keydown',{key:'ArrowLeft',altKey:true,cancelable:true});document.dispatchEvent(event);return event.defaultPrevented; })).toBe(false);
  await page.clock.runFor(1000);
  expect(await page.evaluate(() => window.__pointer.requests)).toBe(0);
  await page.locator('#tv-seek-forward').click();
  expect(await page.evaluate(() => window.__player.state().positionSeconds)).toBe(35);
  expect(fixture.pageErrors).toEqual([]);
});

test('Hide controls leaves pointer capture unused and later closed motion cannot navigate', async ({page}) => {
  await remoteFixture(page, {pointerMode:'promise'});
  await page.locator('#video').click({position:{x:500,y:300}});
  await page.locator('#tv-close-controls').dispatchEvent('click');
  await expect(page.locator('#tv-controls')).toBeHidden();
  expect(await page.evaluate(() => [window.__pointer.requests,window.__pointer.exits,window.__player.state().receiverDebug.pointerLocked])).toEqual([0,0,false]);
});

test('unlocked native pointer motion advances focus and background OK activates it once', async ({page}) => {
  const fixture=await remoteFixture(page);
  await page.locator('#video').click({position:{x:500,y:300}});
  await page.evaluate(()=>document.dispatchEvent(new MouseEvent('mousemove',{movementX:20,movementY:0,bubbles:true})));
  await expect(page.locator('#tv-seek-back')).toBeFocused();
  await page.locator('#video').click({position:{x:500,y:300}});
  expect(await page.evaluate(()=>window.__player.state().positionSeconds)).toBe(15);
  await page.locator('#tv-seek-forward').click();
  expect(await page.evaluate(()=>window.__player.state().positionSeconds)).toBe(25);
  await page.keyboard.press('ArrowLeft');
  const focused=await page.evaluate(()=>document.activeElement.id);
  await page.evaluate(()=>document.dispatchEvent(new MouseEvent('mousemove',{movementX:100,movementY:0,bubbles:true})));
  expect(await page.evaluate(()=>document.activeElement.id)).toBe(focused);
  const debug=await page.evaluate(()=>window.__player.state().receiverDebug);
  expect(debug.pointerMoveCount).toBeGreaterThan(0); expect(debug.pointerLockError).toBe('disabled');
  expect(Object.keys(debug).some(key=>/clientX|clientY|movementX|movementY/.test(key))).toBe(false);
  await page.locator('#tv-close-controls').dispatchEvent('click');
  const before=await page.evaluate(()=>window.__player.state().receiverDebug.inputCount);
  await page.clock.runFor(300); await page.evaluate(()=>document.dispatchEvent(new MouseEvent('mousemove',{movementX:100,movementY:0,bubbles:true})));
  expect(await page.evaluate(()=>window.__player.state().receiverDebug.inputCount)).toBe(before);
  expect(fixture.pageErrors).toEqual([]);
});

test('coordinate fallback remains bounded while pointer capture stays disabled', async ({page}) => {
  await remoteFixture(page,{pointerMode:'denied'});
  await page.locator('#video').click({position:{x:500,y:300}});
  await expect.poll(()=>page.evaluate(()=>window.__player.state().receiverDebug.pointerLockError)).toBe('disabled');
  await page.evaluate(()=>document.dispatchEvent(new Event('pointerlockerror')));
  expect(await page.evaluate(()=>window.__player.state().receiverDebug.pointerLockError)).toBe('disabled');
  await page.evaluate(()=>document.dispatchEvent(new MouseEvent('mousemove',{clientX:520,clientY:300,bubbles:true})));
  await expect(page.locator('#tv-seek-back')).toBeFocused();
  await page.evaluate(()=>document.dispatchEvent(new MouseEvent('mousemove',{clientX:620,clientY:300,bubbles:true})));
  await expect(page.locator('#tv-seek-back')).toBeFocused();
});

test('TV library browses movies, search, seasons, history and one typed native play job', async ({page}) => {
  const fixture = await remoteFixture(page);
  expect(fixture.requests.filter(request=>request.name==='library')).toEqual([]);
  await page.keyboard.press('Enter'); await page.locator('#tv-browse').click();
  await expect(page.locator('#tv-library-title')).toHaveText('Movies');
  await expect(page.locator('#tv-library-content button')).toHaveCount(2);
  await controlsFit(page);
  await mkdir('.local/tv-hello/android-player', {recursive:true});
  await page.screenshot({path:'.local/tv-hello/android-player/tv-library-1920.png'});
  await page.getByRole('button',{name:'Owned movie fixture',exact:false}).click();
  await expect(page.locator('#tv-library-title')).toHaveText('Owned movie fixture');
  await page.keyboard.press('Backspace'); await expect(page.locator('#tv-library-title')).toHaveText('Movies');
  await page.locator('#tv-library-next').click();
  expect(fixture.requests.filter(request=>request.name==='library'&&request.body.action==='list').at(-1).body.params.page).toBe(2);
  await page.locator('#tv-library-search').click();
  await page.locator('#tv-library-query').fill('household <title>'); await page.locator('#tv-library-query').press('Enter');
  await expect(page.locator('#tv-library-title')).toHaveText('Search: household <title>');
  expect(fixture.requests.filter(request=>request.name==='library'&&request.body.action==='search').at(-1).body.params).toEqual({query:'household <title>',kind:'all',page:1});
  await page.locator('#tv-library-series').click(); await page.getByRole('button',{name:'Owned series fixture',exact:false}).click();
  await page.getByRole('button',{name:'Specials',exact:false}).click();
  await expect(page.locator('#tv-library-title')).toHaveText('Season 0');
  await expect(page.locator('#tv-library-content button')).toHaveText(['1. Episode oneAn episode.']);
  await page.setViewportSize({width:1280,height:720}); await controlsFit(page);
  await page.locator('#tv-library-history').click();
  await expect(page.locator('#tv-library-content button')).toContainText('S0 E1 · 2:05');
  fixture.libraryStates=['resolving','opening','resuming','ready'];
  await page.locator('#tv-library-content button').click();
  await expect(page.locator('#tv-library-status')).toHaveText('Finding this video…');
  await page.clock.runFor(1000); await expect(page.locator('#tv-library-status')).toHaveText('Starting this video…');
  await page.clock.runFor(1000); await expect(page.locator('#tv-library-status')).toHaveText('Restoring your saved position…');
  await page.clock.runFor(1000); await expect(page.locator('#tv-controls')).toBeHidden();
  const requests=fixture.requests.filter(request=>request.name==='library');
  expect(requests.filter(request=>request.body.action==='play').map(request=>request.body.params)).toEqual([{kind:'tv',id:'84',resume:true,season:0,episode:1}]);
  expect(requests.every(request=>request.body.client===requests[0].body.client&&request.body.sessionId==='owned-fixture-session')).toBe(true);
  expect(requests.some(request=>JSON.stringify(request.body.params).includes('https:'))).toBe(false);
  expect(await page.evaluate(()=>window.__video.loadCount)).toBe(2);
  expect(fixture.pageErrors).toEqual([]);
});

test('TV library discards stale metadata, keeps playback on failure and never retries play', async ({page}) => {
  const fixture=await remoteFixture(page); let release;
  fixture.libraryHandler=(body,reply)=>{
    if(body.action==='list'&&body.params.kind==='movie') { release=()=>reply({items:[{id:'42',kind:'movie',title:'Stale movie'}],page:1,totalPages:1}); return; }
    if(body.action==='list') return reply({items:[{id:'84',kind:'tv',title:'Current series'}],page:1,totalPages:1});
    if(body.action==='details') return reply({id:'84',kind:'tv',title:'Current series',seasons:[{number:1,name:'Season 1'}]});
    if(body.action==='episodes') return reply({id:'84',season:1,episodes:[{number:1,title:'Episode'}]});
    if(body.action==='play') return reply({accepted:true,jobId:'b'.repeat(32),state:'resolving'});
    if(body.action==='playStatus') return reply({jobId:body.params.jobId,state:'error',errorCode:'SOURCE_UNAVAILABLE'});
  };
  await page.keyboard.press('Enter'); await page.locator('#tv-browse').click();
  await expect.poll(()=>!!release).toBe(true);
  await page.locator('#tv-library-series').click(); await expect(page.locator('#tv-library-content button')).toHaveText(['Current series · Series']);
  await release(); await expect(page.locator('#tv-library-content button')).toHaveText(['Current series · Series']);
  await page.locator('#tv-library-content button').click(); await page.locator('#tv-library-content button').click(); await page.locator('#tv-library-content button').click();
  await expect(page.locator('#tv-library-status')).toContainText('could not start');
  await page.clock.runFor(10000);
  expect(fixture.requests.filter(request=>request.name==='library'&&request.body.action==='play')).toHaveLength(1);
  expect(await page.evaluate(()=>window.__player.state().active)).toBe(true);
  await page.keyboard.press('Backspace'); await expect(page.locator('#tv-library-title')).toHaveText('Current series');
  expect(fixture.pageErrors).toEqual([]);
});

test('native browser timers sustain receiver polling and heartbeat plus player timeout and recovery', async ({page}) => {
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.route('**/tests/fixtures/msx-native-timers', route => route.fulfill({contentType: 'text/html', body: '<!doctype html><title>Receiver timer fixture</title>'}));
  await page.goto('/tests/fixtures/msx-native-timers');
  await page.addScriptTag({content: source});
  const timerCheck = await page.evaluate(() => {
    // Confirm this browser enforces the host receiver that the old capture lost.
    let rejectsForeignReceiver = false;
    try { const foreign = {setTimeout}; foreign.setTimeout(() => {}, 0); }
    catch (error) { rejectsForeignReceiver = error instanceof TypeError; }
    window.__receiverCalls = [];
    window.__receiverErrors = [];
    window.__receiverStatuses = [];
    window.__receiverChannel = window.LumaMsxReceiver.createChannel({client: 'fixture-tv-client',
      player: {state: () => ({active: false}), command: () => new Promise(resolve => { window.__releaseCommand = resolve; })},
      request: async (method, path) => {
        window.__receiverCalls.push(path.split('?')[0]);
        if (path.startsWith('hello')) return {sessionId: 'fixture-session', approved: true};
        if (path.startsWith('poll')) return {approved: true, commands: [{id: 1, method: 'open', params: {}}]};
        return {ok: true};
      }, onStatus: value => window.__receiverStatuses.push(value), onError: code => window.__receiverErrors.push(code)
    });
    window.__receiverChannel.start();
    return {rejectsForeignReceiver};
  });
  expect(timerCheck.rejectsForeignReceiver).toBe(true);
  // Open remains unresolved; only the independent native timer can report state.
  await page.waitForFunction(() => window.__receiverCalls.includes('poll') && window.__receiverCalls.includes('state'), null, {timeout: 5000});
  expect(await page.evaluate(() => window.__receiverStatuses.includes('connected'))).toBe(true);
  expect(await page.evaluate(() => window.__receiverErrors)).toEqual([]);
  await page.evaluate(async () => {
    window.__receiverChannel.stop();
    window.__releaseCommand();
    await Promise.resolve();
  });

  const playback = await page.evaluate(async () => {
    // A local EventTarget supplies media events without any media/network fetch.
    const video = Object.assign(new EventTarget(), {paused: true, ended: false, error: null, currentTime: 0, duration: 100,
      volume: 1, readyState: 0, seekable: {length: 0}, src: '', canPlayType: () => 'probably',
      pause() { this.paused = true; this.dispatchEvent(new Event('pause')); },
      removeAttribute() { this.src = ''; }, load() {}, play() { return new Promise(() => {}); }});
    const player = window.LumaMsxReceiver.createPlayer({video, playbackTimeout: 30});
    let timedOut = false;
    try { await player.command('open', {source: 'https://media.example/fixture.mp4', subtitles: []}); }
    catch (error) { timedOut = error.code === 'PLAYBACK_TIMEOUT'; }
    const inactiveAfterTimeout = !player.state().active;
    video.play = function () {
      this.paused = false; this.readyState = 4;
      queueMicrotask(() => this.dispatchEvent(new Event('playing')));
      return Promise.resolve();
    };
    await player.command('play');
    const recovered = player.state().active;
    await player.command('pause');
    const paused = player.state().paused;
    await player.command('stop');
    return {timedOut, inactiveAfterTimeout, recovered, paused};
  });
  expect(playback).toEqual({timedOut: true, inactiveAfterTimeout: true, recovered: true, paused: true});
  expect(pageErrors).toEqual([]);
});
