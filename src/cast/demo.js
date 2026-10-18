import {extractMedia} from '../shared/media.js';
import {CastController} from './session.js';

// Ordinary web page only; built into dist/demo, never into the MV3 package.
const $ = selector => document.querySelector(selector);
const video=$('video');
const status=$('#status');
const button=$('#cast');
let snapshot;
const controller=new CastController({window,onStatus:result=>{
  status.textContent=result.message;
  if(['disconnected','ended','error'].includes(result.state)) {
    $('#remote').hidden=true;
    button.disabled=false;
    if(snapshot && Number.isFinite(snapshot.currentTime))video.currentTime=snapshot.currentTime;
  }
},onRemote:remote=>{
  snapshot=remote;
  $('#seek').max=String(remote.duration||0);
  $('#seek').value=String(remote.currentTime||0);
  $('#volume').value=String(remote.volume??1);
}});
window.__onGCastApiAvailable=available=>{
  if(!available){status.textContent='Cast SDK unavailable.';return;}
  cast.framework.CastContext.getInstance().setOptions({
    receiverApplicationId:chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID,
    autoJoinPolicy:chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED
  });
  button.disabled=false;
  button.textContent='Select Cast device and play';
};
const command=action=>{
  try {Promise.resolve(action()).catch(()=>{status.textContent='Cast command failed.';});}
  catch {status.textContent='Cast command failed.';}
};
button.addEventListener('click',()=>command(async()=>{
  await controller.connect(extractMedia(video));
  video.pause();
  $('#remote').hidden=false;
  button.disabled=true;
  status.textContent='Receiver accepted LOAD. Verify picture and audio on the physical receiver.';
}));
$('#pause').addEventListener('click',()=>command(()=>controller.pause()));
$('#resume').addEventListener('click',()=>command(()=>controller.resume()));
$('#seek').addEventListener('change',event=>command(()=>controller.seek(Number(event.target.value))));
$('#volume').addEventListener('change',event=>command(()=>controller.setVolume(Number(event.target.value))));
$('#disconnect').addEventListener('click',()=>command(()=>controller.disconnect()));
window.addEventListener('pagehide',()=>{controller.disconnect().catch(()=>{});controller.dispose();},{once:true});
