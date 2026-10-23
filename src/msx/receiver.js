(function () {
  'use strict';
  var core = window.LumaMsxReceiver, video = document.getElementById('video');
  var waiting = document.getElementById('waiting'), status = document.getElementById('status'), caption = document.getElementById('caption');
  var startPlayback = document.getElementById('start-playback'), localStarting = false;
  var renderRemote = function () {}, onLibraryStatus = function () {};
  var notice = document.getElementById('notice'), noticeTimer, plugin = null, channel;
  var base = location.href.split('?')[0].split('#')[0].replace(/[^/]*$/, '');
  var firstClientFailure = null, channelState = 'starting';
  var lastPlaybackError = null, lastMediaErrorCode = null;
  var revision = '__LUMA_RECEIVER_REVISION__', pingCount = 0, lastUptime = 0, lastSubtitleError = null, inputCount = 0, lastInput = 'none', pointerMoveCount = 0, pointerLockError = 'none';
  revision = /^[a-f0-9]{64}$/.test(revision) ? revision : null;
  var clockRead = function () { return Date.now(); }, lastClock = 0;
  try { if (window.performance && typeof window.performance.now === 'function') clockRead = function () { return window.performance.now(); }; } catch (_) {}
  function clock() {
    try { var value = clockRead(); if (typeof value === 'number' && isFinite(value)) lastClock = Math.max(lastClock, value); } catch (_) {}
    return lastClock;
  }
  var startedAt = clock();
  function pointerLockSupported() { try { return !!document.body && typeof document.body.requestPointerLock === 'function' && typeof document.exitPointerLock === 'function'; } catch (_) { return false; } }
  function pointerLocked() { try { return !!document.body && document.pointerLockElement === document.body; } catch (_) { return false; } }
  function receiverDebug() {
    var elapsed = (clock() - startedAt) / 1000;
    if (typeof elapsed === 'number' && isFinite(elapsed)) lastUptime = Math.max(lastUptime, Math.min(315360000, Math.max(0, elapsed)));
    return {revision: revision, uptimeSeconds: lastUptime, pingCount: pingCount, remoteReload: true, remoteCommands: true, inputCount: inputCount, lastInput: lastInput, pointerLockSupported: pointerLockSupported(), pointerLocked: pointerLocked(), pointerMoveCount: pointerMoveCount, pointerLockError: pointerLockError};
  }
  function playbackFailureText() {
    var names = {MEDIA_FAILED: true, HLS_UNSUPPORTED: true, SUPERSEDED: true};
    var code = names[lastPlaybackError] ? lastPlaybackError : 'MEDIA_FAILED';
    return 'Playback could not start (' + code + (lastMediaErrorCode !== null ? ', media code ' + lastMediaErrorCode : '') + '). Try another source on your phone.';
  }
  function clientFailure(code) {
    var allowed = {STATE_READ_FAILED: true, STATUS_RENDER_FAILED: true, NETWORK_UNAVAILABLE: true, HTTP_REJECTED: true, INVALID_RESPONSE: true, CHANNEL_FAILED: true};
    if (!allowed[code]) code = 'CHANNEL_FAILED';
    if (code === 'STATE_READ_FAILED') {
      try { status.textContent = 'Cannot read TV playback status. Retrying…'; } catch (_) {}
    }
    if (firstClientFailure) return;
    firstClientFailure = code;
    Promise.resolve().then(function () { return request('GET', 'diagnostic?code=' + code); }).catch(function () {});
  }
  function show(message, seconds) {
    notice.textContent = message; notice.hidden = !message; clearTimeout(noticeTimer);
    var duration = typeof seconds === 'number' && isFinite(seconds) ? Math.max(1, Math.min(60, seconds)) * 1000 : 5000;
    noticeTimer = setTimeout(function () { notice.hidden = true; }, duration);
  }
  if (!core || typeof Promise !== 'function') { status.textContent = 'This TV browser is unsupported. Update your TV software and try again.'; return; }
  function request(method, path, data, textMode, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest(); xhr.open(method, textMode ? path : base + path, true); xhr.timeout = timeoutMs || (textMode ? 15000 : 10000);
      if (!textMode) xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.onload = function () {
        if (xhr.status < 200 || xhr.status >= 300) { var error = new Error('HTTP_REJECTED'); error.code = 'HTTP_REJECTED'; error.status = xhr.status; reject(error); return; }
        var libraryMetadata = path === 'library' && data && /^(list|search|details|episodes)$/.test(data.action);
        if (xhr.responseText.length > (textMode || libraryMetadata ? 2097152 : 262144)) { var large = new Error('INVALID_RESPONSE'); large.code = 'INVALID_RESPONSE'; reject(large); return; }
        try { resolve(textMode ? xhr.responseText : JSON.parse(xhr.responseText)); } catch (_) { var invalid = new Error('INVALID_RESPONSE'); invalid.code = 'INVALID_RESPONSE'; reject(invalid); }
      };
      xhr.onerror = xhr.ontimeout = function () { var error = new Error('NETWORK_UNAVAILABLE'); error.code = 'NETWORK_UNAVAILABLE'; reject(error); };
      xhr.send(data ? JSON.stringify(data) : null);
    });
  }
  // Same presentation settings as LUMA Receiver, kept on this TV.
  var display = {subtitleScale: 1, brightness: 1, contrast: 1};
  function clampSetting(value, low, high, fallback) { return typeof value === 'number' && isFinite(value) ? Math.max(low, Math.min(high, Math.round(value * 100) / 100)) : fallback; }
  function applyDisplay() {
    var filter = display.brightness === 1 && display.contrast === 1 ? '' : 'brightness(' + display.brightness + ') contrast(' + display.contrast + ')';
    try {
      caption.style.fontSize = (4.3 * display.subtitleScale).toFixed(2) + 'vh';
      video.style.filter = filter; video.style.webkitFilter = filter;
    } catch (_) { /* presentation only */ }
  }
  try {
    var savedDisplay = JSON.parse(localStorage.getItem('luma-display') || '{}');
    display = {subtitleScale: clampSetting(savedDisplay.subtitleScale, 0.5, 2.5, 1), brightness: clampSetting(savedDisplay.brightness, 0.7, 1.3, 1), contrast: clampSetting(savedDisplay.contrast, 0.5, 1.5, 1)};
  } catch (_) {}
  applyDisplay();
  var captionText = document.getElementById('caption-text') || caption;
  var player = core.createPlayer({
    video: video, Hls: window.Hls,
    fetchText: function (url) { return request('GET', url, null, true); },
    onCaption: function (text) { captionText.textContent = text; },
    onDisplay: function (params) {
      display = {
        subtitleScale: clampSetting(params.subtitleScale, 0.5, 2.5, display.subtitleScale),
        brightness: clampSetting(params.brightness, 0.7, 1.3, display.brightness),
        contrast: clampSetting(params.contrast, 0.5, 1.5, display.contrast)
      };
      try { localStorage.setItem('luma-display', JSON.stringify(display)); } catch (_) {}
      applyDisplay();
    },
    onNotice: function (params) { show(String(params.text || '').slice(0, 200), params.seconds); },
    onState: function (state) {
      waiting.style.display = state.active ? 'none' : 'flex';
      var recoverable = !!state.source && !state.active && (state.error === 'PLAYBACK_REJECTED' || state.error === 'PLAYBACK_TIMEOUT');
      lastPlaybackError = !state.active && state.error && !recoverable && state.error !== 'SUBTITLE_FAILED' ? state.error : null;
      var mediaCode = state.mediaDiagnostics && state.mediaDiagnostics.errorCode;
      lastMediaErrorCode = typeof mediaCode === 'number' && isFinite(mediaCode) && mediaCode % 1 === 0 && mediaCode >= 0 && mediaCode <= 4 ? mediaCode : null;
      startPlayback.hidden = !recoverable; startPlayback.disabled = localStarting;
      if (recoverable) { status.textContent = 'Press OK to start playback on this TV.'; if (!localStarting) startPlayback.focus(); }
      if (state.subtitleError && state.subtitleError !== lastSubtitleError) show('Subtitles could not load; choose another language on your phone.');
      if (lastPlaybackError) status.textContent = playbackFailureText();
      lastSubtitleError = state.subtitleError || null;
      renderRemote();
      if (plugin && state.active) {
        plugin.setState(state.paused ? window.TVXVideoState.PAUSED : window.TVXVideoState.PLAYING, true);
        plugin.setDuration(state.durationSeconds, true);
        plugin.setPosition(state.positionSeconds, true);
        plugin.setupContentLabel(state.title);
      }
    }
  });
  var playbackState = player.state;
  player.state = function () { var state = playbackState(); state.receiverDebug = receiverDebug(); state.display = {subtitleScale: display.subtitleScale, brightness: display.brightness, contrast: display.contrast}; return state; };
  startPlayback.addEventListener('click', function () {
    if (localStarting || startPlayback.hidden) return;
    var recoveringSource = player.state().source;
    localStarting = true; startPlayback.disabled = true;
    player.command('play').then(function () {
      localStarting = false; startPlayback.hidden = true;
    }, function (error) {
      localStarting = false; startPlayback.disabled = false;
      if (player.state().source !== recoveringSource || player.state().active || !recoveringSource) return;
      status.textContent = error.code === 'PLAYBACK_REJECTED' ? 'Playback was blocked. Press OK to try again.' : 'Playback could not start. Try another source on your phone.';
      startPlayback.focus();
    });
  });
  function clientId() {
    var value, given = /(?:^#|&)client=([a-zA-Z0-9_-]{16,100})(?:&|$)/.exec(location.hash);
    // The launcher keeps one TV identity for every phone address, so approvals survive address changes.
    if (given) { try { localStorage.setItem('luma-msx-client', given[1]); } catch (_) {} return given[1]; }
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
  var receiverClient = clientId();
  channel = core.createChannel({request: request, player: player, client: receiverClient, beforeHello: function () {
    return request('GET', '../msx/start.json').then(function (start) {
      if (!start || start.parameter !== 'content:' + base + 'home.json') {
        var error = new Error('RECEIVER_CHANGED'); error.code = 'RECEIVER_CHANGED'; throw error;
      }
    });
  }, onControl: function (method) {
    if (method === 'ping') pingCount = Math.min(1000000000, pingCount + 1);
    else if (method === 'set-label') show('LUMA debug channel ready');
  }, onReload: function () { channel.stop(); location.reload(); }, onError: clientFailure, onStatus: function (state) {
    channelState = state;
    onLibraryStatus(state === 'connected');
    if (state === 'approval') status.textContent = 'Approve this TV in LUMA Remote on your phone.';
    else if (state === 'approved') status.textContent = 'TV allowed. Sending receiver status to your phone…';
    else if (state === 'connected') {
      if (lastPlaybackError) status.textContent = playbackFailureText();
      else if (startPlayback.hidden) status.textContent = 'Ready. Browse here or choose a video on your phone.';
      else if (!localStarting) status.textContent = 'Press OK to start playback on this TV.';
    }
    else {
      try { if (!player.state().active) status.textContent = 'Waiting for your phone to reconnect…'; }
      catch (_) { clientFailure('STATE_READ_FAILED'); }
    }
  }});
  channel.start();

  // Several phones can serve this page and their addresses change. The launcher
  // passes the other phones it knows; if this phone stays away while nothing is
  // playing, move to one that answers (an http page may load another phone's image).
  (function phoneFailover() {
    var KEY = 'luma-phones', own = location.host, offlineSince = 0, probing = false, others = [];
    function valid(value) {
      var match = /^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}:(\d{4,5})$/.exec(value || '');
      return !!match && (+match[1] === 10 || +match[1] === 172 && +match[2] >= 16 && +match[2] <= 31 || +match[1] === 192 && +match[2] === 168) && +match[3] >= 1024 && +match[3] <= 65535;
    }
    try { others = JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (_) {}
    if (!(others instanceof Array)) others = [];
    var given = /(?:^#|&)phones=([^&]*)/.exec(location.hash);
    if (given) given[1].split(',').forEach(function (value) { if (others.indexOf(value) < 0) others.unshift(value); });
    others = others.filter(function (value) { return valid(value) && value !== own; }).slice(0, 8);
    try { localStorage.setItem(KEY, JSON.stringify(others)); } catch (_) {}
    if (!others.length) return;
    function probe(address, done) {
      var image = new Image(), finished = false;
      function finish(alive) { if (!finished) { finished = true; done(alive); } }
      image.onload = function () { finish(true); }; image.onerror = function () { finish(false); };
      setTimeout(function () { finish(false); }, 3000);
      image.src = 'http://' + address + '/msx/probe.gif?t=' + Date.now();
    }
    setInterval(function () {
      if (channelState !== 'offline') { offlineSince = 0; return; }
      if (!offlineSince) offlineSince = Date.now();
      var playing = false;
      try { playing = player.state().active; } catch (_) {}
      if (probing || playing || Date.now() - offlineSince < 20000) return;
      probing = true;
      probe(own, function (ownAlive) {
        if (ownAlive) { probing = false; offlineSince = Date.now(); return; }
        var remaining = others.length, moved = false;
        status.textContent = 'Your phone is unavailable. Looking for another phone with LUMA Remote…';
        others.forEach(function (address) {
          probe(address, function (alive) {
            remaining -= 1;
            if (alive && !moved) {
              moved = true;
              var rest = others.filter(function (value) { return value !== address; }).concat([own]);
              location.replace('http://' + address + '/tv/connect.html#phones=' + rest.join(',') + '&client=' + receiverClient);
            }
            if (!remaining && !moved) { probing = false; offlineSince = Date.now() - 5000; }
          });
        });
      });
    }, 5000);
  }());

  // Scrub mirror: its own long poll, so the preview follows the phone's finger
  // instead of waiting behind playback commands. Same overlay as LUMA Receiver.
  (function scrubMirror() {
    var overlay = document.getElementById('scrub'), image = document.getElementById('scrub-image');
    var time = document.getElementById('scrub-time'), fill = document.getElementById('scrub-fill');
    if (!overlay) return;
    var after = 0, failures = 0;
    function format(seconds) {
      var value = Math.floor(Math.max(0, seconds || 0)), hours = Math.floor(value / 3600), minutes = Math.floor(value / 60) % 60;
      return (hours ? hours + ':' + ('0' + minutes).slice(-2) : minutes) + ':' + ('0' + value % 60).slice(-2);
    }
    function render(data) {
      overlay.hidden = data.active !== true;
      if (overlay.hidden) return;
      if (typeof data.image === 'string' && data.image.indexOf('data:image/jpeg;base64,') === 0) { image.src = data.image; image.hidden = false; }
      var position = typeof data.positionSeconds === 'number' ? data.positionSeconds : 0, duration = typeof data.durationSeconds === 'number' ? data.durationSeconds : 0;
      time.textContent = format(position) + ' / ' + format(duration);
      fill.style.width = (duration > 0 ? Math.max(0, Math.min(100, position / duration * 100)) : 0) + '%';
    }
    function loop() {
      var session = channel.state();
      if (!session.approved || !session.sessionId) { setTimeout(loop, 1000); return; }
      request('GET', 'scrub?client=' + encodeURIComponent(receiverClient) + '&sessionId=' + encodeURIComponent(session.sessionId) + '&after=' + after, null, false, 25000).then(function (data) {
        failures = 0;
        // A restarted phone starts counting again; follow its revision either way.
        if (data && typeof data.revision === 'number' && isFinite(data.revision) && data.revision >= 0) { after = data.revision; render(data); }
        loop();
      }, function () { failures += 1; overlay.hidden = true; setTimeout(loop, Math.min(10000, 500 * failures)); });
    }
    loop();
  }());

  (function setupRemoteControls() {
    var panel = document.getElementById('tv-controls');
    if (!panel) return;
    var picker = document.getElementById('tv-subtitle-picker'), list = document.getElementById('tv-subtitle-list');
    var library = document.getElementById('tv-library'), libraryContent = document.getElementById('tv-library-content');
    var libraryStatus = document.getElementById('tv-library-status'), libraryTitle = document.getElementById('tv-library-title');
    var librarySearch = document.getElementById('tv-library-search-form'), libraryQuery = document.getElementById('tv-library-query');
    var libraryEpoch = 0, libraryPlayEpoch = 0, libraryRoute = null, libraryStack = [], libraryPage = 1, libraryPages = 1;
    var play = document.getElementById('tv-play-pause'), back = document.getElementById('tv-seek-back'), forward = document.getElementById('tv-seek-forward');
    var subtitles = document.getElementById('tv-subtitles'), fullscreen = document.getElementById('tv-fullscreen');
    var feedback = document.getElementById('tv-control-feedback'), hideTimer = null, selection = 0, subtitleBusy = false, pickerSignature = '';
    var capturePending = false, captureDeclined = false, intentionalUnlock = false, hadLock = false, captureTimer = null, focusedButton = null;
    var pointerX = 0, pointerY = 0, previousClientX = null, previousClientY = null, lastMotion = -1000000, lastPointerStep = -1000000, lastKeyArrow = -1000000;
    function tell(text) { feedback.textContent = text; }
    function recordInput(type) { inputCount = Math.min(1000000000, inputCount + 1); lastInput = type; }
    function format(seconds) { var value = Math.floor(Math.max(0, seconds || 0)); return Math.floor(value / 60) + ':' + ('0' + value % 60).slice(-2); }
    function buttons() { return Array.prototype.slice.call((!library.hidden ? library : picker.hidden ? document.getElementById('tv-control-row') : list).querySelectorAll('button')).filter(function (button) { return !button.hidden && !button.disabled && button.getClientRects().length; }); }
    function focus(button) {
      Array.prototype.forEach.call(panel.querySelectorAll('button'), function (other) { other.removeAttribute('data-focused'); });
      focusedButton = button || null;
      if (!button) return;
      button.setAttribute('data-focused', 'true'); button.focus();
      if ((!picker.hidden || !library.hidden) && button.scrollIntoView) button.scrollIntoView({block: 'nearest'});
    }
    function releasePointer() {
      pointerX = pointerY = 0;
      if (pointerLocked()) { intentionalUnlock = true; try { document.exitPointerLock(); } catch (_) {} }
    }
    function hide() { clearTimeout(hideTimer); panel.hidden = true; picker.hidden = true; library.hidden = true; libraryEpoch++; document.body.classList.remove('controls-open', 'subtitle-picker-open', 'library-open'); releasePointer(); }
    function scheduleHide() { clearTimeout(hideTimer); if (picker.hidden && library.hidden) hideTimer = setTimeout(hide, 6000); }
    function showControls(keepFocus) { panel.hidden = false; document.body.classList.add('controls-open'); renderRemote(); if (!keepFocus) focus(buttons()[0]); scheduleHide(); }
    function moveFocus(direction) {
      var items = buttons(), index = items.indexOf(document.activeElement);
      if (index < 0) index = items.indexOf(focusedButton);
      if (!library.hidden && index >= 0) {
        var current = items[index].getBoundingClientRect(), cx = current.left + current.width / 2, cy = current.top + current.height / 2, best = null, distance = Infinity;
        items.forEach(function (item) {
          if (item === items[index]) return;
          var rect = item.getBoundingClientRect(), dx = rect.left + rect.width / 2 - cx, dy = rect.top + rect.height / 2 - cy;
          if (direction === 'left' && dx >= -1 || direction === 'right' && dx <= 1 || direction === 'up' && dy >= -1 || direction === 'down' && dy <= 1) return;
          var primary = direction === 'left' || direction === 'right' ? Math.abs(dx) : Math.abs(dy), cross = direction === 'left' || direction === 'right' ? Math.abs(dy) : Math.abs(dx);
          var score = primary + cross * 3;
          if (score < distance) { distance = score; best = item; }
        });
        if (best) focus(best); return;
      }
      if (direction === 'left' || direction === 'up') focus(items[Math.max(0, index - 1)]);
      else focus(items[Math.min(items.length - 1, index + 1)]);
      scheduleHide();
    }
    function capturePointer(event) {
      if (!event.isTrusted || captureDeclined || capturePending || pointerLocked()) return;
      if (!pointerLockSupported()) { pointerLockError = 'unsupported'; return; }
      capturePending = true;
      pointerLockError = 'none';
      function denied(error) { clearTimeout(captureTimer); capturePending = false; var name = error && error.name; pointerLockError = name === 'NotAllowedError' || name === 'SecurityError' ? 'not-allowed' : name === 'NotSupportedError' ? 'unsupported' : 'other'; tell('Use the arrows to choose a control, then press OK.'); }
      captureTimer = setTimeout(function () { if (capturePending) denied(); }, 2000);
      try {
        var lock = document.body.requestPointerLock();
        if (lock && lock.catch) lock.catch(denied);
      } catch (_) { denied(); }
      // Pointer Lock must be requested before Fullscreen consumes activation.
      if (!document.fullscreenElement && typeof document.documentElement.requestFullscreen === 'function') {
        try { var screen = document.documentElement.requestFullscreen(); if (screen && screen.catch) screen.catch(function () {}); } catch (_) {}
      }
    }
    document.addEventListener('pointerlockchange', function () {
      var locked = pointerLocked(); clearTimeout(captureTimer); capturePending = false;
      if (!locked && hadLock && !intentionalUnlock) { captureDeclined = true; hide(); }
      hadLock = locked; intentionalUnlock = false; pointerX = pointerY = 0;
      if (locked) pointerLockError = 'none';
      if (locked && panel.hidden) releasePointer();
    });
    document.addEventListener('pointerlockerror', function () { clearTimeout(captureTimer); capturePending = false; if (pointerLockError === 'none' || pointerLockError === 'other') pointerLockError = 'event-error'; tell('Use the arrows to choose a control, then press OK.'); });
    function libraryCall(action, params) {
      var session = channel.state();
      if (!session.approved || !session.sessionId) return Promise.reject(new Error('NOT_CONNECTED'));
      return request('POST', 'library', {client: receiverClient, sessionId: session.sessionId, action: action, params: params || {}}, false, /^(list|search|details|episodes)$/.test(action) ? 65000 : 15000);
    }
    function clean(value, limit) { return typeof value === 'string' ? value.replace(/[\x00-\x1f\x7f]/g, '').slice(0, limit || 200) : ''; }
    function validItem(item) { return !!item && /^(movie|tv)$/.test(item.kind) && typeof item.id === 'string' && /^[1-9][0-9]{0,9}$/.test(item.id) && !!clean(item.title); }
    function libraryButton(label, action, detail, poster) {
      var button = document.createElement('button'); button.type = 'button';
      if (poster) {
        try {
          var url = new URL(poster);
          if (url.protocol === 'https:' && url.hostname === 'image.tmdb.org' && !url.port && !url.username && !url.password && !url.search && !url.hash && url.pathname.indexOf('/t/p/') === 0) {
            var image = document.createElement('img'); image.src = url.href; image.alt = ''; image.loading = 'lazy'; image.referrerPolicy = 'no-referrer'; button.appendChild(image);
          }
        } catch (_) {}
      }
      var text = document.createElement('span'); text.textContent = clean(label); button.appendChild(text);
      if (detail) { var small = document.createElement('small'); small.textContent = clean(detail); button.appendChild(small); }
      button.addEventListener('click', action); libraryContent.appendChild(button); return button;
    }
    function openLibrary(event) {
      panel.hidden = false; library.hidden = false; picker.hidden = true;
      document.body.classList.add('controls-open', 'library-open'); document.body.classList.remove('subtitle-picker-open'); clearTimeout(hideTimer);
      libraryStack = []; navigateLibrary({action: 'list', params: {kind: 'movie', page: 1}}, false);
      if (event) capturePointer(event);
    }
    function backLibrary() {
      if (!librarySearch.hidden) { librarySearch.hidden = true; libraryQuery.blur(); focus(document.getElementById('tv-library-search')); return; }
      if (libraryStack.length) navigateLibrary(libraryStack.pop(), false); else hide();
    }
    function playLibrary(params) {
      var epoch = ++libraryPlayEpoch, pageEpoch = libraryEpoch, deadline = clock() + 180000;
      libraryStatus.textContent = 'Finding this video… Current playback continues until it is ready.';
      function failed() { if (epoch === libraryPlayEpoch) libraryStatus.textContent = 'This video could not start. Try another title or open it from Browse on your phone.'; }
      libraryCall('play', params).then(function (job) {
        if (epoch !== libraryPlayEpoch) return;
        if (!job || job.accepted !== true || !/^[a-f0-9]{32}$/.test(job.jobId || '') || job.state !== 'resolving') throw new Error('INVALID_RESPONSE');
        function poll() {
          if (epoch !== libraryPlayEpoch) return;
          if (clock() >= deadline) { libraryStatus.textContent = 'The phone is still checking this title. Check playback before choosing it again.'; return; }
          libraryCall('playStatus', {jobId: job.jobId}).then(function (result) {
            if (epoch !== libraryPlayEpoch) return;
            if (!result || result.jobId !== job.jobId || !/^(resolving|opening|resuming|ready|error|cancelled)$/.test(result.state || '')) throw new Error('INVALID_RESPONSE');
            if (result.state === 'error') { if (result.errorCode === 'RESUME_FAILED') libraryStatus.textContent = 'Video is playing, but the saved position could not be restored. Use the playback controls to continue.'; else failed(); return; }
            if (result.state === 'cancelled') { libraryStatus.textContent = 'A newer selection replaced this request.'; return; }
            if (result.state === 'ready') { if (pageEpoch === libraryEpoch) hide(); show('Video is ready. Press OK for playback controls.'); return; }
            libraryStatus.textContent = result.state === 'resuming' ? 'Restoring your saved position…' : result.state === 'opening' ? 'Starting this video…' : 'Finding this video…';
            setTimeout(poll, 1000);
          }).catch(failed);
        }
        poll();
      }).catch(failed);
    }
    function navigateLibrary(route, remember) {
      if (remember && libraryRoute) { libraryStack.push(libraryRoute); if (libraryStack.length > 32) libraryStack.shift(); }
      libraryRoute = route;
      var epoch = ++libraryEpoch, action = route.action, params = route.params;
      libraryContent.textContent = ''; librarySearch.hidden = true; libraryStatus.textContent = 'Loading…';
      document.getElementById('tv-library-pages').hidden = true;
      libraryTitle.textContent = action === 'history' ? 'Continue Watching' : action === 'search' ? 'Search: ' + clean(params.query, 120) : action === 'list' ? params.kind === 'tv' ? 'Series' : 'Movies' : action === 'episodes' ? 'Season ' + params.season : 'Details';
      focus(document.getElementById('tv-library-back'));
      libraryCall(action, params).then(function (data) {
        if (epoch !== libraryEpoch || library.hidden) return;
        libraryStatus.textContent = '';
        if (action === 'list' || action === 'search') {
          if (!data || !Array.isArray(data.items) || data.items.length > 200) throw new Error('INVALID_RESPONSE');
          data.items.filter(validItem).forEach(function (item) {
            libraryButton(item.title, function () { navigateLibrary({action: 'details', params: {kind: item.kind, id: item.id}}, true); }, clean(item.year) + (item.kind === 'tv' ? ' · Series' : ''), item.poster);
          });
          libraryPage = Number.isInteger(data.page) && data.page > 0 ? data.page : 1;
          libraryPages = Number.isInteger(data.totalPages) && data.totalPages > 0 ? Math.min(500, data.totalPages) : 1;
          document.getElementById('tv-library-pages').hidden = libraryPages <= 1;
          document.getElementById('tv-library-page').textContent = libraryPage + ' / ' + libraryPages;
          document.getElementById('tv-library-previous').disabled = libraryPage <= 1;
          document.getElementById('tv-library-next').disabled = libraryPage >= libraryPages;
        } else if (action === 'details') {
          if (!validItem(data) || data.id !== params.id || data.kind !== params.kind) throw new Error('INVALID_RESPONSE');
          libraryTitle.textContent = clean(data.title);
          var overview = document.createElement('p'); overview.className = 'library-overview'; overview.textContent = clean(data.overview, 1200); libraryContent.appendChild(overview);
          if (data.kind === 'movie') {
            libraryButton('Play / resume', function () { playLibrary({kind: 'movie', id: data.id, resume: true}); }).className = 'library-action';
            libraryButton('Start over', function () { playLibrary({kind: 'movie', id: data.id, resume: false}); }).className = 'library-action';
          } else {
            if (!Array.isArray(data.seasons) || data.seasons.length > 1001) throw new Error('INVALID_RESPONSE');
            data.seasons.forEach(function (season) {
              if (!Number.isInteger(season.number) || season.number < 0 || season.number > 9999) return;
              libraryButton(season.name || 'Season ' + season.number, function () { navigateLibrary({action: 'episodes', params: {id: data.id, season: season.number}}, true); }, Number.isInteger(season.episodeCount) ? season.episodeCount + ' episodes' : '', season.poster);
            });
          }
        } else if (action === 'episodes') {
          if (!data || data.id !== params.id || data.season !== params.season || !Array.isArray(data.episodes) || data.episodes.length > 1000) throw new Error('INVALID_RESPONSE');
          data.episodes.forEach(function (episode) {
            if (!Number.isInteger(episode.number) || episode.number < 1 || episode.number > 9999) return;
            libraryButton(episode.number + '. ' + (episode.title || 'Episode ' + episode.number), function () { playLibrary({kind: 'tv', id: params.id, season: params.season, episode: episode.number, resume: true}); }, clean(episode.overview, 240), episode.still);
          });
        } else if (action === 'history') {
          if (!data || data.version !== 1 || !Array.isArray(data.items) || data.items.length > 100) throw new Error('INVALID_RESPONSE');
          var seenTitles = {};
          data.items.forEach(function (item) {
            if (!item || !/^(movie|tv)$/.test(item.kind) || typeof item.contentId !== 'string' || !/^[1-9][0-9]{0,9}$/.test(item.contentId)) return;
            if (item.kind === 'tv' && (!Number.isInteger(item.season) || item.season < 0 || item.season > 9999 || !Number.isInteger(item.episode) || item.episode < 1 || item.episode > 9999)) return;
            var titleKey = item.kind + ':' + item.contentId; if (seenTitles[titleKey]) return; seenTitles[titleKey] = true;
            libraryButton(item.title || 'Saved title', function () { var params = {kind: item.kind, id: item.contentId, resume: true}; if (item.kind === 'tv') { params.season = item.season; params.episode = item.episode; } playLibrary(params); }, (item.kind === 'tv' ? 'S' + item.season + ' E' + item.episode + ' · ' : '') + (item.completed ? 'Watch again' : format(typeof item.resumeSeconds === 'number' && isFinite(item.resumeSeconds) && item.resumeSeconds >= 0 && item.resumeSeconds <= 17280000 ? item.resumeSeconds : 0)));
          });
        }
        var first = libraryContent.querySelector('button');
        if (first) focus(first); else { libraryStatus.textContent = action === 'history' ? 'Recently watched titles will appear here.' : 'No titles found.'; focus(document.getElementById('tv-library-movies')); }
      }).catch(function () { if (epoch === libraryEpoch && !library.hidden) libraryStatus.textContent = 'The library could not load. Check your phone connection, then choose a section to try again.'; });
    }
    onLibraryStatus = function (connected) { document.getElementById('tv-open-library').hidden = !connected; document.getElementById('tv-browse').disabled = !connected; };
    document.getElementById('tv-open-library').addEventListener('click', openLibrary);
    document.getElementById('tv-browse').addEventListener('click', openLibrary);
    document.getElementById('tv-library-movies').addEventListener('click', function () { libraryStack = []; navigateLibrary({action: 'list', params: {kind: 'movie', page: 1}}, false); });
    document.getElementById('tv-library-series').addEventListener('click', function () { libraryStack = []; navigateLibrary({action: 'list', params: {kind: 'tv', page: 1}}, false); });
    document.getElementById('tv-library-history').addEventListener('click', function () { libraryStack = []; navigateLibrary({action: 'history', params: {}}, false); });
    document.getElementById('tv-library-search').addEventListener('click', function () { librarySearch.hidden = false; releasePointer(); libraryQuery.focus(); });
    librarySearch.addEventListener('submit', function (event) { event.preventDefault(); var query = libraryQuery.value.trim(); if (query) { libraryQuery.blur(); navigateLibrary({action: 'search', params: {query: query.slice(0, 120), kind: 'all', page: 1}}, true); } });
    document.getElementById('tv-library-back').addEventListener('click', backLibrary);
    document.getElementById('tv-library-close').addEventListener('click', hide);
    function pageLibrary(delta) { if (!libraryRoute || !/^(list|search)$/.test(libraryRoute.action)) return; var next = libraryPage + delta; if (next < 1 || next > libraryPages) return; navigateLibrary({action: libraryRoute.action, params: Object.assign({}, libraryRoute.params, {page: next})}, false); }
    document.getElementById('tv-library-previous').addEventListener('click', function () { pageLibrary(-1); });
    document.getElementById('tv-library-next').addEventListener('click', function () { pageLibrary(1); });
    function run(method, params) {
      return player.command(method, params || {}).then(function () { renderRemote(); scheduleHide(); }, function () { tell('This control is unavailable for the current video.'); renderRemote(); scheduleHide(); });
    }
    function fillPicker() {
      var state = player.state(), signature = JSON.stringify([state.tracks, state.selectedIndex, state.subtitlesEnabled, subtitleBusy]);
      if (signature === pickerSignature) return;
      pickerSignature = signature;
      var selected = document.activeElement && document.activeElement.getAttribute('data-subtitle-index');
      list.textContent = '';
      function add(label, index, enabled) {
        var button = document.createElement('button'); button.type = 'button'; button.textContent = label;
        button.setAttribute('data-subtitle-index', String(index));
        button.setAttribute('aria-pressed', String(enabled ? state.subtitlesEnabled && state.selectedIndex === index : !state.subtitlesEnabled));
        button.disabled = subtitleBusy && enabled;
        button.addEventListener('click', function () { chooseSubtitle(index, enabled); }); list.appendChild(button);
      }
      add('Subtitles off', -1, false);
      var catalog = state.tracks.slice(), preferred = core.preferredIndex(catalog, 'pt-BR');
      if (preferred >= 0) catalog.unshift(catalog.splice(preferred, 1)[0]);
      catalog.forEach(function (track) { add(track.label || track.language || 'Track ' + (track.index + 1), track.index, true); });
      if (!picker.hidden) {
        var previous = list.querySelector('[data-subtitle-index="' + selected + '"]');
        focus(previous && !previous.disabled ? previous : buttons()[0]);
      }
    }
    function chooseSubtitle(index, enabled) {
      if (enabled && subtitleBusy) return;
      var current = ++selection, state = player.state(), expectedSource = state.source;
      if (!enabled) {
        subtitleBusy = false;
        player.command('subtitle', {enabled: false, expectedSourceUrl: expectedSource}).then(function () { tell('Subtitles off.'); renderRemote(); }, function () { tell('Subtitle selection changed. Try again.'); });
        return;
      }
      subtitleBusy = true; tell('Loading the selected language…'); fillPicker();
      var session = channel.state();
      Promise.resolve().then(function () {
        if (!session.approved || !session.sessionId) throw new Error('SUBTITLE_FAILED');
        return request('POST', 'subtitle-prepare', {client: receiverClient, sessionId: session.sessionId, index: index, expectedSourceUrl: expectedSource});
      }).then(function (prepared) {
        if (current !== selection || player.state().source !== expectedSource) throw new Error('SUPERSEDED');
        var url = prepared && typeof prepared.url === 'string' ? new URL(prepared.url) : null;
        if (!url || prepared.index !== index || prepared.source !== expectedSource || url.origin !== location.origin || url.username || url.password || url.search || url.hash || url.href.indexOf(base + 'subtitles/') !== 0 || !/\/subtitles\/[a-f0-9]{64}\.srt$/.test(url.pathname)) throw new Error('SUBTITLE_FAILED');
        return player.command('subtitle', {index: index, enabled: true, expectedSourceUrl: expectedSource, url: url.href});
      }).then(function () { if (current === selection) tell('Subtitle selected.'); }, function () { if (current === selection) tell('Subtitles could not load. Video playback is unchanged; try another language.'); }).then(function () {
        if (current === selection) { subtitleBusy = false; renderRemote(); }
      });
    }
    renderRemote = function () {
      if (panel.hidden) return;
      var state = player.state();
      document.getElementById('tv-control-title').textContent = state.title || 'LUMA Remote';
      document.getElementById('tv-control-time').textContent = format(state.positionSeconds) + ' / ' + format(state.durationSeconds);
      play.textContent = state.paused ? 'Play' : 'Pause'; play.disabled = !state.source;
      back.disabled = forward.disabled = !state.active || !state.canSeek;
      subtitles.disabled = !state.active || !state.tracks.length;
      fullscreen.hidden = typeof document.documentElement.requestFullscreen !== 'function';
      fullscreen.textContent = document.fullscreenElement ? 'Leave full screen' : 'Full screen';
      if (!picker.hidden) fillPicker();
    };
    play.addEventListener('click', function () { run('playPause', {play: player.state().paused}); });
    function seek(delta) { var state = player.state(); run('seek', {seconds: Math.max(0, Math.min(state.durationSeconds, state.positionSeconds + delta)), expectedSourceUrl: state.source}); }
    back.addEventListener('click', function () { seek(-10); }); forward.addEventListener('click', function () { seek(10); });
    subtitles.addEventListener('click', function () { picker.hidden = false; pickerSignature = ''; document.body.classList.add('subtitle-picker-open'); clearTimeout(hideTimer); fillPicker(); });
    fullscreen.addEventListener('click', function () {
      try {
        var result = document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen();
        if (result && result.catch) result.catch(function () { tell('Full screen is unavailable. TV controls still work here.'); });
      } catch (_) { tell('Full screen is unavailable. TV controls still work here.'); }
      scheduleHide();
    });
    document.getElementById('tv-close-controls').addEventListener('click', hide);
    video.addEventListener('timeupdate', renderRemote);
    function interactive(node) { while (node) { if (/^(BUTTON|INPUT|TEXTAREA|SELECT|A)$/.test(node.tagName) || node.isContentEditable) return true; node = node.parentElement; } return false; }
    document.addEventListener('mousedown', function (event) {
      if (!panel.hidden && !interactive(event.target)) event.preventDefault();
    });
    document.addEventListener('mousemove', function (event) {
      pointerMoveCount = Math.min(1000000000, pointerMoveCount + 1);
      var clientX = event.clientX, clientY = event.clientY, fallbackX = previousClientX === null ? 0 : clientX - previousClientX, fallbackY = previousClientY === null ? 0 : clientY - previousClientY;
      if (typeof clientX === 'number' && isFinite(clientX) && typeof clientY === 'number' && isFinite(clientY)) { previousClientX = clientX; previousClientY = clientY; }
      if (panel.hidden) return;
      var now = clock(), x = event.movementX, y = event.movementY;
      if (typeof x !== 'number' || !isFinite(x) || typeof y !== 'number' || !isFinite(y) || x === 0 && y === 0) { x = fallbackX; y = fallbackY; }
      if (now - lastKeyArrow < 250) { pointerX = pointerY = 0; return; }
      // An actual button under an unlocked pointer takes focus directly. Only
      // background motion becomes relative navigation; one event cannot do both.
      if (!pointerLocked()) {
        var hovered = event.target;
        while (hovered && hovered.tagName !== 'BUTTON') hovered = hovered.parentElement;
        if (hovered && panel.contains(hovered) && !hovered.disabled && hovered.getClientRects().length) {
          pointerX = pointerY = 0;
          if (document.activeElement !== hovered || focusedButton !== hovered) { recordInput('pointer-arrow'); focus(hovered); scheduleHide(); }
          return;
        }
      }
      if (now - lastPointerStep < 180) { pointerX = pointerY = 0; return; }
      if (now - lastMotion > 250) pointerX = pointerY = 0;
      lastMotion = now; pointerX += x; pointerY += y;
      if (Math.max(Math.abs(pointerX), Math.abs(pointerY)) < 12) return;
      var direction = Math.abs(pointerX) >= Math.abs(pointerY) ? pointerX < 0 ? 'left' : 'right' : pointerY < 0 ? 'up' : 'down';
      pointerX = pointerY = 0; lastPointerStep = now; recordInput('pointer-arrow'); moveFocus(direction);
    });
    document.addEventListener('click', function (event) {
      var target = event.target, node = target;
      if (!panel.hidden && event.isTrusted && !event.defaultPrevented && !interactive(target)) {
        var selected = focusedButton;
        event.preventDefault(); recordInput('pointer-background');
        if (selected && panel.contains(selected) && selected.tagName === 'BUTTON' && !selected.disabled) selected.click();
        else focus(buttons()[0]);
        return;
      }
      if (!panel.hidden || event.defaultPrevented || panel.contains(target) || target === startPlayback) return;
      while (node) {
        if (/^(BUTTON|INPUT|TEXTAREA|SELECT|A)$/.test(node.tagName) || node.isContentEditable) return;
        node = node.parentElement;
      }
      if (target === video || target === document.body || waiting.contains(target)) {
        recordInput('pointer-background'); showControls(); capturePointer(event);
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      var names = {13: 'Enter', 37: 'ArrowLeft', 38: 'ArrowUp', 39: 'ArrowRight', 40: 'ArrowDown', 8: 'Backspace', 27: 'Escape', 415: 'MediaPlay', 19: 'MediaPause', 179: 'MediaPlayPause', 413: 'MediaStop', 412: 'MediaRewind', 417: 'MediaTrackNext'};
      var recognized = /^(Enter|ArrowLeft|ArrowRight|ArrowUp|ArrowDown|Backspace|BrowserBack|Escape|MediaPlay|MediaPause|MediaPlayPause|MediaStop|MediaRewind|MediaFastForward|MediaTrackNext)$/;
      var key = recognized.test(event.key || '') ? event.key : names[event.keyCode], items, index;
      var activeElement = document.activeElement;
      if (activeElement === libraryQuery && /^(Backspace|BrowserBack|Escape)$/.test(key || '') && !library.hidden) { event.preventDefault(); librarySearch.hidden = true; libraryQuery.blur(); focus(document.getElementById('tv-library-search')); return; }
      if (activeElement && (/^(INPUT|TEXTAREA)$/.test(activeElement.tagName) || activeElement.isContentEditable)) return;
      if (key === 'Backspace' || key === 'BrowserBack' || key === 'Escape') {
        // Back over an on-screen notice (such as the next-episode countdown) dismisses it;
        // the phone sees the input and cancels auto-play, as on LUMA Receiver.
        if (panel.hidden && !notice.hidden) { event.preventDefault(); recordInput('key-back'); notice.hidden = true; return; }
        if (panel.hidden) return;
        event.preventDefault(); recordInput('key-back');
        if (key === 'Escape' && (pointerLocked() || capturePending || hadLock)) captureDeclined = true;
        releasePointer();
        if (!library.hidden) backLibrary();
        else if (!picker.hidden) { picker.hidden = true; document.body.classList.remove('subtitle-picker-open'); focus(subtitles); scheduleHide(); }
        else hide();
        return;
      }
      var media = {MediaPlay: 'play', MediaPause: 'pause', MediaPlayPause: 'playPause', MediaStop: 'stop'};
      if (media[key] || key === 'MediaRewind' || key === 'MediaFastForward' || key === 'MediaTrackNext') {
        event.preventDefault(); if (event.repeat) return;
        recordInput('key-media');
        if (panel.hidden) showControls(true);
        if (key === 'MediaRewind' || key === 'MediaFastForward' || key === 'MediaTrackNext') seek(key === 'MediaRewind' ? -10 : 10);
        else run(media[key], key === 'MediaPlayPause' ? {play: player.state().paused} : {});
        return;
      }
      if (key !== 'Enter' && !/^Arrow(Left|Right|Up|Down)$/.test(key || '')) return;
      if (panel.hidden && key === 'Enter' && !startPlayback.hidden) return;
      event.preventDefault();
      if (key === 'Enter' && event.repeat) return;
      recordInput(key === 'Enter' ? 'key-enter' : 'key-arrow');
      if (key !== 'Enter') { lastKeyArrow = clock(); pointerX = pointerY = 0; }
      if (panel.hidden) { showControls(); return; }
      scheduleHide(); items = buttons(); index = items.indexOf(document.activeElement);
      if (key === 'Enter') { if (index < 0) focus(items[0]); else items[index].click(); }
      else moveFocus(key === 'ArrowLeft' ? 'left' : key === 'ArrowUp' ? 'up' : key === 'ArrowRight' ? 'right' : 'down');
    });
  }());

  if (window.parent === window) return;

  // Official documented MSX video plugin bridge. Playback itself has no SDK/network dependency.
  var script = document.createElement('script'); script.src = 'https://msx.benzac.de/js/tvx-plugin.min.js'; script.referrerPolicy = 'no-referrer';
  script.onload = function () {
    if (!window.TVXPluginTools || !window.TVXVideoPlugin) return;
    window.TVXPluginTools.onReady(function () {
      plugin = window.TVXVideoPlugin;
      plugin.setupPlayer({
        init: function () {}, ready: function () { plugin.startPlayback(); },
        play: function () { if (player.state().source) player.command('play').catch(function () { show('Playback could not resume.'); }); },
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
