#!/usr/bin/env node
// Isolated emulator only. Build receiver debug + androidTest APKs first.
// Requires ffmpeg and adb. Generates local fixtures; no catalog/media credentials.
import http from 'node:http';
import {mkdtempSync,mkdirSync,writeFileSync,statSync,createReadStream} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync,spawn} from 'node:child_process';
const serial=process.env.ANDROID_TEST_SERIAL || 'emulator-5582';
if(!/^emulator-\d+$/.test(serial))throw Error('Cache tests require an isolated emulator');
const adb=process.env.ADB || 'adb';
const root=mkdtempSync(join(tmpdir(),'luma-cache-fixture-'));
function run(command,args){const r=spawnSync(command,args,{stdio:'inherit'});if(r.status)throw Error(`${command} failed`);}
for(const dir of ['hls/low','hls/high','dash'])mkdirSync(join(root,dir),{recursive:true});
const ff=args=>run('ffmpeg',['-hide_banner','-loglevel','error',...args]);
ff(['-f','lavfi','-i','color=c=blue:s=1920x1080:r=5','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','150','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-g','30','-c:a','aac','-b:a','64k','-movflags','+faststart','-y',join(root,'movie.mp4')]);
ff(['-i',join(root,'movie.mp4'),'-c','copy','-hls_time','6','-hls_playlist_type','vod','-y',join(root,'hls/high/index.m3u8')]);
ff(['-i',join(root,'movie.mp4'),'-vf','scale=640:360','-c:v','libx264','-preset','ultrafast','-g','30','-c:a','copy','-hls_time','6','-hls_playlist_type','vod','-y',join(root,'hls/low/index.m3u8')]);
ff(['-i',join(root,'movie.mp4'),'-c','copy','-seg_duration','6','-f','dash','-y',join(root,'dash/index.mpd')]);
writeFileSync(join(root,'hls/index.m3u8'),'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nhigh/index.m3u8\n');
let blocked=false;
const server=http.createServer((req,res)=>{
  if(req.url.startsWith('/control/')){blocked=req.url.endsWith('/block');res.end('ok');return;}
  const path=join(root,new URL(req.url,'http://fixture').pathname);
  if(!path.startsWith(root+'/')){res.writeHead(403).end();return;}
  const manifest=/\.(m3u8|mpd)$/.test(path);
  if(!manifest&&blocked){res.writeHead(503).end('media blocked');return;}
  try {
    const size=statSync(path).size;let start=0,end=size-1;
    const range=/bytes=(\d+)-(\d*)/.exec(req.headers.range||'');
    if(range){start=+range[1];if(range[2])end=Math.min(end,+range[2]);}
    if(start>end){res.writeHead(416).end();return;}
    const headers={'Content-Length':end-start+1,'Accept-Ranges':'bytes','Content-Type':path.endsWith('.m3u8')?'application/vnd.apple.mpegurl':path.endsWith('.mpd')?'application/dash+xml':'application/octet-stream'};
    if(range)headers['Content-Range']=`bytes ${start}-${end}/${size}`;
    res.writeHead(range?206:200,headers);
    if(req.method==='HEAD')res.end();else createReadStream(path,{start,end}).pipe(res);
  }catch{res.writeHead(404).end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
try {
  for(const apk of ['debug/app-debug.apk','androidTest/debug/app-debug-androidTest.apk'])run(adb,['-s',serial,'install','-r',`android-receiver/app/build/outputs/apk/${apk}`]);
  const args=['-s',serial,'shell','am','instrument','-w','-r','-e','fixture',`http://10.0.2.2:${server.address().port}`,'dev.framextv.lumareceiver.test/dev.framextv.lumareceiver.CacheInstrumentation'];
  const child=spawn(adb,args,{stdio:['ignore','pipe','inherit']});let output='';
  child.stdout.on('data',bytes=>{output+=bytes;process.stdout.write(bytes);});
  const code=await new Promise(resolve=>child.on('close',resolve));
  if(code||!output.includes('PASS 3 cache formats'))throw Error('Cache instrumentation failed');
}finally{server.closeAllConnections();server.close();}
