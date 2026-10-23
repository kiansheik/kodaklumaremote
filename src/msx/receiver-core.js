/* TV-owned playback and bounded phone control. ES5 syntax for older TV engines. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.LumaMsxReceiver = factory();
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function failure(code) { var error = new Error(code); error.code = code; return error; }
  function defaultTimers() {
    return {setTimeout: function (fn, ms) { return setTimeout(fn, ms); }, clearTimeout: function (id) { clearTimeout(id); }};
  }
  function number(value, fallback) { return typeof value === 'number' && isFinite(value) ? value : fallback; }
  function httpUrl(value) {
    if (typeof value !== 'string' || value.length > 16384 || !/^https?:\/\/[^\s/@]+(?::[0-9]+)?(?:[/?#]|$)/i.test(value)) throw failure('INVALID_SOURCE');
    return value;
  }
  function language(value) { return String(value || '').toLowerCase().replace(/_/g, '-'); }
  function preferredIndex(tracks, preferred) {
    var index = -1, highest = 0, wanted = language(preferred || 'pt-BR');
    tracks.forEach(function (track, i) {
      var lang = language(track.language), label = language(track.label), score = 0;
      if (lang === wanted) score = 100;
      else if (wanted.indexOf('pt') === 0 && (lang === 'pt-br' || /brazil|brasil/.test(label))) score = 95;
      else if (wanted === 'pt' && (lang === 'pt' || lang.indexOf('pt-') === 0)) score = 80;
      else if (lang === 'en' || lang.indexOf('en-') === 0 || /english/.test(label)) score = 60;
      if (score > highest) { highest = score; index = i; }
    });
    return index;
  }
  function cueTime(text) {
    var match = /^(?:(\d{1,3}):)?(\d{2}):(\d{2})[.,](\d{3})$/.exec(text);
    if (!match || +match[2] > 59 || +match[3] > 59) return NaN;
    return (+match[1] || 0) * 3600 + +match[2] * 60 + +match[3] + +match[4] / 1000;
  }
  function plainCue(text) {
    return text.replace(/<[^>]*>/g, '').replace(/&(?:amp|lt|gt|quot|apos|nbsp|#\d+|#x[0-9a-f]+);/gi, function (entity) {
      var names = {'&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'", '&nbsp;': ' '};
      var lower = entity.toLowerCase(), code;
      if (names[lower]) return names[lower];
      code = lower.indexOf('&#x') === 0 ? parseInt(lower.slice(3, -1), 16) : parseInt(lower.slice(2, -1), 10);
      return code >= 0 && code <= 65535 ? String.fromCharCode(code) : '';
    });
  }
  function parseSubtitles(text) {
    if (typeof text !== 'string' || text.length > 2097152) throw failure('SUBTITLE_TOO_LARGE');
    var normalized = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n'), cues = [];
    normalized.split(/\n[ \t]*\n/).forEach(function (block) {
      var lines = block.split('\n'), timeIndex = lines[0] && lines[0].indexOf('-->') >= 0 ? 0 : 1;
      if (/^(WEBVTT|NOTE|STYLE|REGION)(\s|$)/.test(lines[0] || '')) return;
      var match = /^(\S+)\s+-->\s+(\S+)(?:\s+.*)?$/.exec(lines[timeIndex] || '');
      if (!match) return;
      var start = cueTime(match[1]), end = cueTime(match[2]), body = lines.slice(timeIndex + 1).join('\n').trim();
      if (isFinite(start) && isFinite(end) && end > start && body) cues.push({start: start, end: end, text: plainCue(body)});
    });
    if (!cues.length || cues.length > 20000) throw failure('INVALID_SUBTITLE');
    cues.sort(function (a, b) { return a.start - b.start; });
    function timestamp(time) {
      var ms = Math.round(time * 1000), hours = Math.floor(ms / 3600000), mins = Math.floor(ms / 60000) % 60, secs = Math.floor(ms / 1000) % 60;
      function pad(value, length) { return (new Array(length + 1).join('0') + value).slice(-length); }
      return pad(hours, 2) + ':' + pad(mins, 2) + ':' + pad(secs, 2) + '.' + pad(ms % 1000, 3);
    }
    return {cues: cues, vtt: 'WEBVTT\n\n' + cues.map(function (cue) { return timestamp(cue.start) + ' --> ' + timestamp(cue.end) + '\n' + cue.text; }).join('\n\n') + '\n'};
  }

  function createPlayer(options) {
    var video = options.video, timers = options.timers || defaultTimers();
    var epoch = 0, source = '', title = '', tracks = [], selectedIndex = -1, subtitlesEnabled = false;
    var subtitleCache = {}, cues = [], hls = null, active = false, mediaError = null, subtitleError = null, subtitleEpoch = 0, pendingStart = null;
    var onState = options.onState || function () {}, onCaption = options.onCaption || function () {};
    function mediaNumber(read, max) {
      try { var value = read(); return typeof value === 'number' && isFinite(value) && value % 1 === 0 && value >= 0 && value <= max ? value : null; }
      catch (_) { return null; }
    }
    function mediaDiagnostics() {
      return {
        errorCode: mediaNumber(function () { var error = video.error; return error ? error.code : null; }, 4),
        networkState: mediaNumber(function () { return video.networkState; }, 3),
        readyState: mediaNumber(function () { return video.readyState; }, 4)
      };
    }
    function state() {
      return {
        active: active && !video.ended && !video.error,
        ended: !!source && !!video.ended && !video.error,
        source: source, title: title, paused: !!video.paused, rate: number(video.playbackRate, 1),
        positionSeconds: Math.max(0, number(video.currentTime, 0)), durationSeconds: Math.max(0, number(video.duration, 0)),
        canSeek: !!(active && number(video.duration, 0) > 0 && video.seekable && video.seekable.length),
        volume: {current: Math.round(number(video.volume, 1) * 100), max: 100},
        tracks: tracks.map(function (track, index) { return {index: index, language: track.language, label: track.label}; }),
        selectedIndex: selectedIndex, subtitlesEnabled: subtitlesEnabled,
        error: mediaError, subtitleError: subtitleError, mediaDiagnostics: mediaDiagnostics()
      };
    }
    function changed() { onState(state()); }
    function render() {
      var position = number(video.currentTime, 0), lines = [];
      if (subtitlesEnabled) cues.forEach(function (cue) { if (cue.start <= position && cue.end > position) lines.push(cue.text); });
      onCaption(lines.join('\n'));
    }
    function rejectStart(code) {
      if (pendingStart) { var pending = pendingStart; pendingStart = null; pending.finish(failure(code)); }
    }
    function cleanup() {
      rejectStart('SUPERSEDED');
      if (hls) { hls.destroy(); hls = null; }
      video.pause();
      video.removeAttribute('src');
      video.load();
      active = false; selectedIndex = -1; subtitlesEnabled = false; subtitleError = null; subtitleEpoch += 1; cues = []; subtitleCache = {}; onCaption('');
      try { video.playbackRate = 1; } catch (_) {}
    }
    function playStarted(expected) {
      return new Promise(function (resolve, reject) {
        var timeout, done = false;
        function finish(error) {
          if (done) return; done = true; timers.clearTimeout(timeout);
          video.removeEventListener('playing', playing);
          if (pendingStart && pendingStart.finish === finish) pendingStart = null;
          if (error) reject(error); else resolve();
        }
        function playing() {
          if (epoch !== expected) return finish(failure('SUPERSEDED'));
          active = true; mediaError = null; changed(); finish();
        }
        timeout = timers.setTimeout(function () { finish(failure('PLAYBACK_TIMEOUT')); }, options.playbackTimeout || 25000);
        pendingStart = {finish: finish}; video.addEventListener('playing', playing);
        try {
          var promise = video.play();
          if (promise && promise.then) promise.then(function () {
            if (!done && epoch === expected && !video.paused && video.readyState >= 3) playing();
          }, function () { finish(failure('PLAYBACK_REJECTED')); });
        } catch (_) { finish(failure('PLAYBACK_REJECTED')); }
      });
    }
    function selectSubtitle(index, enabled, expected) {
      if (enabled && (typeof index !== 'number' || index % 1 || index < 0 || index >= tracks.length)) return Promise.reject(failure('INVALID_SUBTITLE_INDEX'));
      var selection = ++subtitleEpoch;
      if (!enabled) { subtitlesEnabled = false; subtitleError = null; render(); changed(); return Promise.resolve(); }
      var requestUrl = tracks[index].url, promise = subtitleCache[index];
      if (!promise) {
        promise = Promise.resolve().then(function () { return options.fetchText(requestUrl); }).then(parseSubtitles);
        subtitleCache[index] = promise;
        promise.catch(function () { if (epoch === expected && subtitleCache[index] === promise) delete subtitleCache[index]; });
      }
      return promise.then(function (parsed) {
        if (epoch !== expected || selection !== subtitleEpoch) throw failure('SUPERSEDED');
        cues = parsed.cues; selectedIndex = index; subtitlesEnabled = true; subtitleError = null; render(); changed();
      }, function (error) {
        if (epoch !== expected || selection !== subtitleEpoch) throw failure('SUPERSEDED');
        subtitleError = 'SUBTITLE_FAILED'; changed(); throw failure(error && error.code === 'SUPERSEDED' ? error.code : 'SUBTITLE_FAILED');
      });
    }
    function open(params) {
      var nextSource, nextTracks;
      try {
        nextSource = httpUrl(params.source);
        if (params.autoSubtitles !== undefined && typeof params.autoSubtitles !== 'boolean') throw failure('INVALID_SUBTITLES');
        if (!Array.isArray(params.subtitles || []) || (params.subtitles || []).length > 128) throw failure('INVALID_SUBTITLES');
        nextTracks = (params.subtitles || []).map(function (track) {
          return {url: httpUrl(track.url), language: String(track.language || '').slice(0, 40), label: String(track.label || track.language || 'Subtitle').slice(0, 120)};
        });
      } catch (error) { return Promise.reject(error); }
      epoch += 1; var expected = epoch; cleanup(); source = nextSource; title = String(params.title || 'Video').slice(0, 250); tracks = nextTracks; mediaError = null;
      var isHls = /mpegurl|x-mpegurl/i.test(params.mimeType || '') || /\.m3u8(?:[?#]|$)/i.test(source);
      if (isHls && !video.canPlayType('application/vnd.apple.mpegurl')) {
        var Hls = options.Hls;
        if (!Hls || !Hls.isSupported()) { mediaError = 'HLS_UNSUPPORTED'; changed(); return Promise.reject(failure(mediaError)); }
        hls = new Hls({enableWorker: false, maxBufferLength: 45, backBufferLength: 30});
        hls.on(Hls.Events.ERROR, function (_, data) {
          if (expected !== epoch || !data.fatal) return;
          active = false; mediaError = 'MEDIA_FAILED'; rejectStart(mediaError); changed();
        });
        hls.loadSource(source); hls.attachMedia(video);
      } else { video.src = source; video.load(); }
      changed();
      var chosen = params.autoSubtitles === false ? -1 : preferredIndex(tracks, params.preferredLanguage);
      var subtitles = chosen >= 0 ? selectSubtitle(chosen, true, expected) : Promise.resolve();
      // Both settle before ACK. A subtitle rejection leaves independently running video intact.
      function outcome(promise) { return promise.then(function () { return null; }, function (error) { return error; }); }
      return Promise.all([outcome(playStarted(expected)), outcome(subtitles)]).then(function (errors) {
        if (errors[0] || errors[1]) throw errors[0] || errors[1];
        changed();
      }).catch(function (error) {
        if (epoch === expected) {
          if (error.code === 'SUBTITLE_FAILED') subtitleError = 'SUBTITLE_FAILED';
          else mediaError = error.code || 'MEDIA_FAILED';
          changed();
        }
        throw error;
      });
    }
    function command(method, params) {
      params = params || {};
      try {
        if (method === 'open' || method === 'load') return open(params);
        if (params.expectedSourceUrl && params.expectedSourceUrl !== source) throw failure('SOURCE_CHANGED');
        if (method === 'stop') { epoch += 1; cleanup(); source = ''; title = ''; tracks = []; mediaError = null; changed(); return Promise.resolve(); }
        if (method === 'subtitle') {
          if (params.enabled !== false && params.url !== undefined) {
            if (typeof params.index !== 'number' || params.index % 1 || params.index < 0 || params.index >= tracks.length) throw failure('INVALID_SUBTITLE_INDEX');
            var nextUrl = httpUrl(params.url);
            if (tracks[params.index].url !== nextUrl) { tracks[params.index].url = nextUrl; delete subtitleCache[params.index]; }
          }
          return selectSubtitle(params.index, params.enabled !== false, epoch);
        }
        if (method === 'display' || method === 'notice') {
          // Presentation only (caption size, picture, on-screen notices); the page applies it.
          var handler = method === 'display' ? options.onDisplay : options.onNotice;
          if (typeof handler !== 'function') throw failure('UNKNOWN_COMMAND');
          handler(params); changed(); return Promise.resolve();
        }
        if (method === 'rate') {
          // Small speed nudges keep two screens in step without a visible seek.
          var rate = number(params.value, -1);
          if (rate < 0.5 || rate > 2) throw failure('INVALID_RATE');
          video.playbackRate = rate; changed(); return Promise.resolve();
        }
        if (method === 'volume') {
          var volume = number(params.value, -1);
          if (volume < 0 || volume > 100) throw failure('INVALID_VOLUME');
          video.volume = volume / 100;
          if (Math.abs(video.volume * 100 - volume) > 1) throw failure('VOLUME_UNSUPPORTED');
        } else if (method === 'seek') {
          var seconds = number(params.seconds, -1);
          if (!state().canSeek || seconds < 0 || seconds > video.duration) throw failure('SEEK_UNAVAILABLE');
          video.currentTime = seconds; render();
        } else if (method === 'playPause' || method === 'play' || method === 'pause') {
          if (!source) throw failure('NO_ACTIVE_VIDEO');
          // A TV-local user gesture can recover a source that autoplay rejected.
          // Keep the loaded media/catalog and wait for real playback before success.
          if (!active) {
            if (method === 'pause' || (method === 'playPause' && params.play === false)) throw failure('NO_ACTIVE_VIDEO');
            if (pendingStart) throw failure('PLAYBACK_STARTING');
            return playStarted(epoch);
          }
          if (method === 'pause' || (method === 'playPause' && params.play === false)) video.pause();
          else return Promise.resolve(video.play()).then(changed, function () { throw failure('PLAYBACK_REJECTED'); });
        } else throw failure('UNKNOWN_COMMAND');
        changed(); return Promise.resolve();
      } catch (error) { return Promise.reject(error); }
    }
    video.addEventListener('error', function () { active = false; mediaError = 'MEDIA_FAILED'; rejectStart(mediaError); changed(); });
    video.addEventListener('ended', function () { active = false; render(); changed(); });
    ['playing', 'pause', 'seeked', 'durationchange', 'volumechange', 'ratechange'].forEach(function (event) { video.addEventListener(event, changed); });
    video.addEventListener('timeupdate', render);
    return {command: command, state: state, render: render};
  }

  function createChannel(options) {
    var request = options.request, player = options.player, client = options.client;
    var timers = options.timers || defaultTimers();
    var sessionId = null, approved = false, after = 0, running = false, timer = null, heartbeatTimer = null, receipt = null, retries = 0;
    var reloadReceiptId = null, reloading = false;
    var onStatus = options.onStatus || function () {};
    function report(code) {
      try {
        if (typeof options.onError === 'function') {
          var result = options.onError(code);
          if (result && typeof result.then === 'function') Promise.resolve(result).catch(function () {});
        }
      } catch (_) {}
    }
    function status(value) {
      try {
        var result = onStatus(value);
        if (result && typeof result.then === 'function') Promise.resolve(result).catch(function () { report('STATUS_RENDER_FAILED'); });
      } catch (_) { report('STATUS_RENDER_FAILED'); }
    }
    function errorCode(error) {
      var code = error && error.code;
      if (code === 'STATE_READ_FAILED' || code === 'STATUS_RENDER_FAILED' || code === 'NETWORK_UNAVAILABLE' || code === 'HTTP_REJECTED' || code === 'INVALID_RESPONSE') return code;
      if (error && typeof error.status === 'number' && error.status > 0) return 'HTTP_REJECTED';
      if (code === 'INVALID_SESSION' || code === 'INVALID_COMMAND' || code === 'INVALID_COMMANDS' || code === 'RESPONSE_TOO_LARGE') return 'INVALID_RESPONSE';
      return 'CHANNEL_FAILED';
    }
    function postState() {
      if (!sessionId || !approved || reloading) return Promise.resolve();
      return Promise.resolve().then(function () {
        var playback;
        try { playback = player.state(); } catch (_) { throw failure('STATE_READ_FAILED'); }
        return request('POST', 'state', {client: client, sessionId: sessionId, playback: playback});
      }).then(function () { status('connected'); });
    }
    function ack(value) {
      return postState().then(function () { return request('POST', 'ack', value); });
    }
    function query() { return 'client=' + encodeURIComponent(client) + '&sessionId=' + encodeURIComponent(sessionId); }
    function stop() {
      running = false;
      try { timers.clearTimeout(timer); timers.clearTimeout(heartbeatTimer); } catch (_) { report('CHANNEL_FAILED'); }
    }
    function settleReceipt(pending) {
      return ack(pending).then(function (response) {
        if (pending.ok && reloadReceiptId === pending.id && (!response || response.ok !== true)) throw failure('INVALID_RESPONSE');
        after = pending.id;
        if (receipt === pending) receipt = null;
        if (pending.ok && reloadReceiptId === pending.id) {
          reloadReceiptId = null; reloading = true; stop();
          // The controller must receive the receipt before this page exits.
          return Promise.resolve().then(options.onReload).catch(function () { report('CHANNEL_FAILED'); });
        }
      });
    }
    function flushReceipt() {
      if (!receipt) return Promise.resolve();
      return settleReceipt(receipt);
    }
    function control(command, id) {
      var method = command.method, params = command.params || {}, keys;
      if (typeof params !== 'object' || Array.isArray(params)) throw failure('INVALID_COMMAND');
      keys = Object.keys(params);
      if (method === 'set-label') {
        if (keys.length !== 1 || keys[0] !== 'label' || params.label !== 'LUMA debug channel ready') throw failure('INVALID_COMMAND');
      } else if (keys.length) throw failure('INVALID_COMMAND');
      if (method === 'reload') {
        var playback;
        try { playback = player.state(); } catch (_) { throw failure('STATE_READ_FAILED'); }
        if (playback.active) throw failure('RELOAD_BLOCKED');
        if (typeof options.onReload !== 'function') throw failure('UNSUPPORTED_COMMAND');
        reloadReceiptId = id;
        return;
      }
      if (typeof options.onControl !== 'function') throw failure('UNSUPPORTED_COMMAND');
      return options.onControl(method, params);
    }
    function execute(command) {
      if (reloading) return Promise.resolve();
      var id = command && command.id;
      if (typeof id !== 'number' || !isFinite(id) || id % 1 || id <= 0) return Promise.reject(failure('INVALID_COMMAND'));
      if (id <= after) return Promise.resolve();
      if (receipt && receipt.id === id) return flushReceipt();
      return Promise.resolve().then(function () {
        if (command.method === 'ping' || command.method === 'set-label' || command.method === 'reload') return control(command, id);
        return player.command(command.method, command.params);
      }).then(function () {
        return {client: client, sessionId: sessionId, id: id, ok: true};
      }, function (error) {
        return {client: client, sessionId: sessionId, id: id, ok: false, error: error.code || 'COMMAND_FAILED'};
      }).then(function (value) {
        receipt = value;
        return settleReceipt(value);
      });
    }
    function acquireSession() {
      return Promise.resolve().then(function () { return request('GET', 'hello?client=' + encodeURIComponent(client)); }).then(function (hello) {
          if (!hello || typeof hello.sessionId !== 'string' || !hello.sessionId) throw failure('INVALID_SESSION');
          sessionId = hello.sessionId; approved = hello.approved === true; after = 0; receipt = null; reloadReceiptId = null;
          status(approved ? 'approved' : 'approval');
      });
    }
    function tick() {
      if (reloading) return Promise.resolve();
      if (!sessionId) {
        // Reclaim this TV's pending peer after the phone renews setup. The
        // wrapper validates the bootstrap capability before a new hello.
        if (typeof options.beforeHello === 'function') return Promise.resolve().then(options.beforeHello).then(acquireSession);
        return acquireSession();
      }
      // Native sends each command only once. Retry an uncertain ACK independently
      // of whether the command is present in the next poll response.
      return flushReceipt().then(function () { if (!reloading) return request('GET', 'poll?' + query() + '&after=' + after); }).then(function (data) {
        if (reloading) return;
        var wasApproved = approved;
        approved = data.approved === true;
        if (!approved) { status('approval'); return; }
        if (!wasApproved) status('approved');
        var commands = data.commands || [];
        if (!Array.isArray(commands) || commands.length > 50) throw failure('INVALID_COMMANDS');
        // Sorting plus monotonic cursor prevents replay and out-of-order writes.
        commands = commands.slice().sort(function (a, b) { return a.id - b.id; });
        var chain = Promise.resolve();
        commands.forEach(function (item) { chain = chain.then(function () { return execute(item); }); });
        return chain.then(function () { if (!reloading) return postState(); });
      });
    }
    function schedule(delay) {
      if (!running) return;
      try { timer = timers.setTimeout(loop, delay); } catch (_) { report('CHANNEL_FAILED'); }
    }
    function scheduleHeartbeat() {
      if (!running) return;
      try { heartbeatTimer = timers.setTimeout(heartbeat, 2000); } catch (_) { report('CHANNEL_FAILED'); }
    }
    function reconnect(error) {
      if (error && (error.status === 401 || error.status === 403 || error.status === 409)) {
        sessionId = null; approved = false; receipt = null; reloadReceiptId = null;
      }
      status('offline');
      report(errorCode(error));
    }
    function heartbeat() {
      // Independent from the serialized command queue: a slow Open/subtitle
      // request must not make a responsive TV appear disconnected on the phone.
      if (!running) return;
      Promise.resolve().then(postState).catch(reconnect).then(function () {
        scheduleHeartbeat();
      });
    }
    function loop() {
      Promise.resolve().then(tick).then(function () { retries = 0; schedule(700); }, function (error) {
        // Losing the controller never pauses, unloads, or changes the TV's media.
        reconnect(error); retries += 1;
        schedule(Math.min(10000, 1000 * Math.pow(2, Math.min(retries, 4))));
      });
    }
    return {
      start: function () { if (!running && !reloading) { running = true; loop(); scheduleHeartbeat(); } },
      stop: stop,
      tick: tick,
      state: function () { return {sessionId: sessionId, approved: approved, after: after}; }
    };
  }

  return {createPlayer: createPlayer, createChannel: createChannel, parseSubtitles: parseSubtitles, preferredIndex: preferredIndex};
}));
