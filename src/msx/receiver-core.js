/* TV-owned playback and bounded phone control. ES5 syntax for older TV engines. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.LumaMsxReceiver = factory();
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function failure(code) { var error = new Error(code); error.code = code; return error; }
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
    var video = options.video, timers = options.timers || {setTimeout: setTimeout, clearTimeout: clearTimeout};
    var epoch = 0, source = '', title = '', tracks = [], selectedIndex = -1, subtitlesEnabled = false;
    var subtitleCache = {}, cues = [], hls = null, active = false, mediaError = null, pendingStart = null;
    var onState = options.onState || function () {}, onCaption = options.onCaption || function () {};
    function state() {
      return {
        active: active && !video.ended && !video.error,
        source: source, title: title, paused: !!video.paused,
        positionSeconds: Math.max(0, number(video.currentTime, 0)), durationSeconds: Math.max(0, number(video.duration, 0)),
        canSeek: !!(active && number(video.duration, 0) > 0 && video.seekable && video.seekable.length),
        volume: {current: Math.round(number(video.volume, 1) * 100), max: 100},
        tracks: tracks.map(function (track, index) { return {index: index, language: track.language, label: track.label}; }),
        selectedIndex: selectedIndex, subtitlesEnabled: subtitlesEnabled,
        error: mediaError
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
      active = false; selectedIndex = -1; subtitlesEnabled = false; cues = []; subtitleCache = {}; onCaption('');
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
      if (!enabled) { subtitlesEnabled = false; render(); changed(); return Promise.resolve(); }
      if (typeof index !== 'number' || index % 1 || index < 0 || index >= tracks.length) return Promise.reject(failure('INVALID_SUBTITLE_INDEX'));
      var track = tracks[index], promise = subtitleCache[index];
      if (!promise) {
        promise = options.fetchText(track.url).then(parseSubtitles);
        subtitleCache[index] = promise;
        promise.catch(function () { if (epoch === expected) delete subtitleCache[index]; });
      }
      return promise.then(function (parsed) {
        if (epoch !== expected) throw failure('SUPERSEDED');
        cues = parsed.cues; selectedIndex = index; subtitlesEnabled = true; mediaError = null; render(); changed();
      }, function (error) {
        if (epoch !== expected) throw failure('SUPERSEDED');
        mediaError = 'SUBTITLE_FAILED'; changed(); throw failure(error.code === 'SUPERSEDED' ? error.code : 'SUBTITLE_FAILED');
      });
    }
    function open(params) {
      var nextSource, nextTracks;
      try {
        nextSource = httpUrl(params.source);
        if (!Array.isArray(params.subtitles || []) || (params.subtitles || []).length > 100) throw failure('INVALID_SUBTITLES');
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
      var chosen = preferredIndex(tracks, params.preferredLanguage), subtitles = chosen >= 0 ? selectSubtitle(chosen, true, expected) : Promise.resolve();
      // Both settle before ACK. A subtitle rejection leaves independently running video intact.
      function outcome(promise) { return promise.then(function () { return null; }, function (error) { return error; }); }
      return Promise.all([outcome(playStarted(expected)), outcome(subtitles)]).then(function (errors) {
        if (errors[0] || errors[1]) throw errors[0] || errors[1];
        changed();
      }).catch(function (error) {
        if (epoch === expected) { mediaError = error.code || 'MEDIA_FAILED'; changed(); }
        throw error;
      });
    }
    function command(method, params) {
      params = params || {};
      try {
        if (method === 'open' || method === 'load') return open(params);
        if (params.expectedSourceUrl && params.expectedSourceUrl !== source) throw failure('SOURCE_CHANGED');
        if (method === 'stop') { epoch += 1; cleanup(); source = ''; title = ''; tracks = []; mediaError = null; changed(); return Promise.resolve(); }
        if (method === 'subtitle') return selectSubtitle(params.index, params.enabled !== false, epoch);
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
          if (!source || !active) throw failure('NO_ACTIVE_VIDEO');
          if (method === 'pause' || (method === 'playPause' && params.play === false)) video.pause();
          else return Promise.resolve(video.play()).then(changed, function () { throw failure('PLAYBACK_REJECTED'); });
        } else throw failure('UNKNOWN_COMMAND');
        changed(); return Promise.resolve();
      } catch (error) { return Promise.reject(error); }
    }
    video.addEventListener('error', function () { active = false; mediaError = 'MEDIA_FAILED'; rejectStart(mediaError); changed(); });
    video.addEventListener('ended', function () { active = false; render(); changed(); });
    ['playing', 'pause', 'seeked', 'durationchange', 'volumechange'].forEach(function (event) { video.addEventListener(event, changed); });
    video.addEventListener('timeupdate', render);
    return {command: command, state: state, render: render};
  }

  function createChannel(options) {
    var request = options.request, player = options.player, client = options.client;
    var timers = options.timers || {setTimeout: setTimeout, clearTimeout: clearTimeout};
    var sessionId = null, approved = false, after = 0, running = false, timer = null, heartbeatTimer = null, receipt = null, retries = 0;
    var onStatus = options.onStatus || function () {};
    function postState() {
      if (!sessionId || !approved) return Promise.resolve();
      return request('POST', 'state', {client: client, sessionId: sessionId, playback: player.state()});
    }
    function ack(value) {
      return postState().then(function () { return request('POST', 'ack', value); });
    }
    function query() { return 'client=' + encodeURIComponent(client) + '&sessionId=' + encodeURIComponent(sessionId); }
    function flushReceipt() {
      if (!receipt) return Promise.resolve();
      var pending = receipt;
      return ack(pending).then(function () { after = pending.id; if (receipt === pending) receipt = null; });
    }
    function execute(command) {
      var id = command && command.id;
      if (typeof id !== 'number' || !isFinite(id) || id % 1 || id <= 0) return Promise.reject(failure('INVALID_COMMAND'));
      if (id <= after) return Promise.resolve();
      if (receipt && receipt.id === id) return ack(receipt).then(function () { after = id; receipt = null; });
      return player.command(command.method, command.params).then(function () {
        return {client: client, sessionId: sessionId, id: id, ok: true};
      }, function (error) {
        return {client: client, sessionId: sessionId, id: id, ok: false, error: error.code || 'COMMAND_FAILED'};
      }).then(function (value) {
        receipt = value;
        return ack(value).then(function () { after = id; receipt = null; });
      });
    }
    function tick() {
      if (!sessionId) {
        return request('GET', 'hello?client=' + encodeURIComponent(client)).then(function (hello) {
          if (!hello || typeof hello.sessionId !== 'string' || !hello.sessionId) throw failure('INVALID_SESSION');
          sessionId = hello.sessionId; approved = hello.approved === true; after = 0; receipt = null;
          onStatus(approved ? 'connected' : 'approval');
        });
      }
      // Native sends each command only once. Retry an uncertain ACK independently
      // of whether the command is present in the next poll response.
      return flushReceipt().then(function () { return request('GET', 'poll?' + query() + '&after=' + after); }).then(function (data) {
        approved = data.approved === true;
        if (!approved) { onStatus('approval'); return; }
        onStatus('connected');
        var commands = data.commands || [];
        if (!Array.isArray(commands) || commands.length > 50) throw failure('INVALID_COMMANDS');
        // Sorting plus monotonic cursor prevents replay and out-of-order writes.
        commands = commands.slice().sort(function (a, b) { return a.id - b.id; });
        var chain = Promise.resolve();
        commands.forEach(function (item) { chain = chain.then(function () { return execute(item); }); });
        return chain.then(postState);
      });
    }
    function schedule(delay) { if (running) timer = timers.setTimeout(loop, delay); }
    function reconnect(error) {
      onStatus('offline');
      if (error && (error.status === 401 || error.status === 403 || error.status === 409)) {
        sessionId = null; approved = false; receipt = null;
      }
    }
    function heartbeat() {
      // Independent from the serialized command queue: a slow Open/subtitle
      // request must not make a responsive TV appear disconnected on the phone.
      if (!running) return;
      postState().catch(reconnect).then(function () {
        if (running) heartbeatTimer = timers.setTimeout(heartbeat, 2000);
      });
    }
    function loop() {
      tick().then(function () { retries = 0; schedule(700); }, function (error) {
        // Losing the controller never pauses, unloads, or changes the TV's media.
        reconnect(error); retries += 1;
        schedule(Math.min(10000, 1000 * Math.pow(2, Math.min(retries, 4))));
      });
    }
    return {
      start: function () { if (!running) { running = true; loop(); heartbeatTimer = timers.setTimeout(heartbeat, 2000); } },
      stop: function () { running = false; timers.clearTimeout(timer); timers.clearTimeout(heartbeatTimer); },
      tick: tick,
      state: function () { return {sessionId: sessionId, approved: approved, after: after}; }
    };
  }

  return {createPlayer: createPlayer, createChannel: createChannel, parseSubtitles: parseSubtitles, preferredIndex: preferredIndex};
}));
