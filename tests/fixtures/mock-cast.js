(() => {
  const listeners = new Map();
  const remoteListeners = new Map();
  const calls = [];
  const control = window.castMock = {calls, failLoad:false, failSession:false, pendingLoad:false, session:null};
  const emit = (type, event) => {for (const fn of listeners.get(type) || []) fn(event);};
  let player;
  const session = {
    loadMedia: async request => {
      calls.push(['load',request]);
      if (control.pendingLoad) await new Promise(resolve => {control.resolveLoad = resolve;});
      if (control.failLoad) throw new Error('private signed URL must never be displayed');
      control.loadedTime = request.currentTime || 0;
      if (player) Object.assign(player,{isConnected:true,isMediaLoaded:true,currentTime:control.loadedTime});
      return undefined;
    },
    getMediaSession: () => ({editTracksInfo: (request, success) => {calls.push(['tracks',request.activeTrackIds]);success?.();}}),
    endSession: async stop => {calls.push(['end',stop]);control.session=null;emit('session',{sessionState:'ended'});if(control.failEnd)throw new Error('private receiver detail');}
  };
  const context = {
    setOptions: options => calls.push(['options',options]),
    requestSession: async () => {
      calls.push(['request']);
      if (control.failSession) throw {code:'cancel'};
      control.session=session;
      emit('session',{sessionState:'started'});
    },
    getCurrentSession: () => control.session,
    addEventListener: (type,fn) => {if(!listeners.has(type))listeners.set(type,new Set());listeners.get(type).add(fn);},
    removeEventListener: (type,fn) => listeners.get(type)?.delete(fn)
  };
  class RemotePlayer {
    constructor() {player=this;Object.assign(this,{isConnected:true,isMediaLoaded:true,isPaused:false,currentTime:control.loadedTime||0,duration:120,volumeLevel:1,isMuted:false,canSeek:true,canPause:true});}
  }
  class RemotePlayerController {
    constructor(remote){this.player=remote;}
    addEventListener(type,fn){if(!remoteListeners.has(type))remoteListeners.set(type,new Set());remoteListeners.get(type).add(fn);}
    removeEventListener(type,fn){remoteListeners.get(type)?.delete(fn);}
    playOrPause(){this.player.isPaused=!this.player.isPaused;calls.push(['pause',this.player.isPaused]);control.update();}
    seek(){calls.push(['seek',this.player.currentTime]);control.update();}
    setVolumeLevel(){calls.push(['volume',this.player.volumeLevel]);control.update();}
    muteOrUnmute(){this.player.isMuted=!this.player.isMuted;control.update();}
    stop(){calls.push(['stop']);}
  }
  control.update = (values={}) => {
    Object.assign(player,values);
    for(const set of remoteListeners.values())for(const fn of set)fn({});
  };
  control.end = () => {control.session=null;emit('session',{sessionState:'ended'});};
  control.listenerCount = () => [...listeners.values(),...remoteListeners.values()].reduce((sum,set)=>sum+set.size,0);
  window.cast = {framework:{CastContext:{getInstance:()=>context},RemotePlayer,RemotePlayerController,
    CastContextEventType:{SESSION_STATE_CHANGED:'session',CAST_STATE_CHANGED:'cast'},
    SessionState:{SESSION_STARTED:'started',SESSION_RESUMED:'resumed',SESSION_ENDED:'ended',SESSION_START_FAILED:'failed'},
    RemotePlayerEventType:{ANY_CHANGE:'change',IS_CONNECTED_CHANGED:'connected',CURRENT_TIME_CHANGED:'time'},
    CastState:{NO_DEVICES_AVAILABLE:'none'}}};
  window.chrome = window.chrome || {};
  const valueClass = class {constructor(value,type){this.contentId=value;this.contentType=type;}};
  window.chrome.cast = {AutoJoinPolicy:{ORIGIN_SCOPED:'origin_scoped'},ErrorCode:{CANCEL:'cancel'},
    media:{DEFAULT_MEDIA_RECEIVER_APP_ID:'CC1AD845',
      MediaInfo:valueClass, LoadRequest:class {constructor(media){this.media=media;}},
      GenericMediaMetadata:class {},MovieMediaMetadata:class {},
      Track:class {constructor(id,type){this.trackId=id;this.type=type;}},
      TrackType:{TEXT:'TEXT'},TextTrackType:{SUBTITLES:'SUBTITLES'},StreamType:{BUFFERED:'BUFFERED',LIVE:'LIVE'},
      EditTracksInfoRequest:class {constructor(ids){this.activeTrackIds=ids;}}
    }};
})();
