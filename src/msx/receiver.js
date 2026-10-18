(function () {
  'use strict';
  var core = window.LumaMsxReceiver, video = document.getElementById('video');
  var waiting = document.getElementById('waiting'), status = document.getElementById('status'), caption = document.getElementById('caption');
  var notice = document.getElementById('notice'), noticeTimer, plugin = null, channel;
  var base = location.href.split('?')[0].split('#')[0].replace(/[^/]*$/, '');
  function show(message) {
    notice.textContent = message; notice.hidden = false; clearTimeout(noticeTimer);
    noticeTimer = setTimeout(function () { notice.hidden = true; }, 5000);
  }
  if (!core || typeof Promise !== 'function') { status.textContent = 'This TV browser is unsupported. Update Media Station X and try again.'; return; }
  function request(method, path, data, textMode) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest(); xhr.open(method, textMode ? path : base + path, true); xhr.timeout = textMode ? 15000 : 10000;
      if (!textMode) xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.onload = function () {
        if (xhr.status < 200 || xhr.status >= 300) { var error = new Error('REQUEST_FAILED'); error.status = xhr.status; reject(error); return; }
        if (xhr.responseText.length > (textMode ? 2097152 : 262144)) { reject(new Error('RESPONSE_TOO_LARGE')); return; }
        try { resolve(textMode ? xhr.responseText : JSON.parse(xhr.responseText)); } catch (_) { reject(new Error('INVALID_RESPONSE')); }
      };
      xhr.onerror = xhr.ontimeout = function () { reject(new Error('NETWORK_UNAVAILABLE')); };
      xhr.send(data ? JSON.stringify(data) : null);
    });
  }
  var player = core.createPlayer({
    video: video, Hls: window.Hls,
    fetchText: function (url) { return request('GET', url, null, true); },
    onCaption: function (text) { caption.textContent = text; },
    onState: function (state) {
      waiting.style.display = state.active ? 'none' : 'flex';
      if (state.error === 'SUBTITLE_FAILED') show('Video is playing. Subtitles could not load; choose another language on your phone.');
      else if (state.error) status.textContent = 'Playback could not start (' + state.error + '). Try another source on your phone.';
      if (plugin && state.active) {
        plugin.setState(state.paused ? window.TVXVideoState.PAUSED : window.TVXVideoState.PLAYING, true);
        plugin.setDuration(state.durationSeconds, true);
        plugin.setPosition(state.positionSeconds, true);
        plugin.setupContentLabel(state.title);
      }
    }
  });
  function clientId() {
    var value;
    try { value = localStorage.getItem('luma-msx-client'); } catch (_) {}
    if (!/^[a-zA-Z0-9_-]{16,100}$/.test(value || '')) {
      value = 'tv-';
      if (window.crypto && window.crypto.getRandomValues) {
        var bytes = new Uint8Array(16); window.crypto.getRandomValues(bytes);
        for (var i = 0; i < bytes.length; i += 1) value += ('0' + bytes[i].toString(16)).slice(-2);
      } else value += String(Date.now()) + '-' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
      try { localStorage.setItem('luma-msx-client', value); } catch (_) {}
    }
    return value;
  }
  channel = core.createChannel({request: request, player: player, client: clientId(), onStatus: function (state) {
    if (state === 'approval') status.textContent = 'Approve this TV in LUMA Remote on your phone.';
    else if (state === 'connected') status.textContent = 'Ready. Choose a video on your phone.';
    else if (!player.state().active) status.textContent = 'Waiting for your phone to reconnect…';
  }});
  channel.start();

  // Official documented MSX video plugin bridge. Playback itself has no SDK/network dependency.
  var script = document.createElement('script'); script.src = 'https://msx.benzac.de/js/tvx-plugin.min.js'; script.referrerPolicy = 'no-referrer';
  script.onload = function () {
    if (!window.TVXPluginTools || !window.TVXVideoPlugin) return;
    window.TVXPluginTools.onReady(function () {
      plugin = window.TVXVideoPlugin;
      plugin.setupPlayer({
        init: function () {}, ready: function () { plugin.startPlayback(); },
        play: function () { if (player.state().active) player.command('play').catch(function () { show('Playback could not resume.'); }); },
        pause: function () { if (player.state().active) player.command('pause'); },
        stop: function () { player.command('stop'); },
        getDuration: function () { return player.state().durationSeconds; }, getPosition: function () { return player.state().positionSeconds; },
        setPosition: function (seconds) { player.command('seek', {seconds: seconds}).catch(function () { show('Seeking is unavailable for this stream.'); }); },
        setVolume: function (value) { player.command('volume', {value: value}).catch(function () { show('Use your TV remote to change speaker volume.'); }); },
        getVolume: function () { return player.state().volume.current; }, setMuted: function (value) { video.muted = value; }, isMuted: function () { return video.muted; },
        getSpeed: function () { return 1; }, setSpeed: function () {},
        getUpdateData: function () { var state = player.state(); return {position: state.positionSeconds, duration: state.durationSeconds, speed: 1}; }
      });
      plugin.init();
    });
  };
  script.onerror = function () { show('Phone controls are ready. TV playback controls are unavailable while the MSX service is offline.'); };
  document.head.appendChild(script);
}());
