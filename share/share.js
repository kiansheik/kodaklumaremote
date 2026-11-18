export function projectorAddress(value) {
  const host = String(value || '').trim();
  const parts = host.split('.');
  if (parts.length !== 4 || parts.some(part => !/^(0|[1-9]\d{0,2})$/.test(part) || Number(part) > 255))
    throw new Error('Enter the projector’s local IP address, such as 192.168.1.50.');
  const [a, b] = parts.map(Number);
  if (!(a === 10 || a === 172 && b >= 16 && b <= 31 || a === 192 && b === 168))
    throw new Error('Use the private Wi-Fi address shown on your LUMA projector.');
  return `http://${host}:4191`;
}

export function captureOptions() {
  return {video: {width: {ideal: 1280, max: 1280}, height: {ideal: 720, max: 720}, frameRate: {ideal: 30, max: 30}},
    audio: {suppressLocalAudioPlayback: true}, systemAudio: 'include', selfBrowserSurface: 'exclude', surfaceSwitching: 'include'};
}

export function preferredCodecs(codecs = []) {
  const rank = codec => /^video\/h264$/i.test(codec.mimeType) ? 0 : /^video\/vp8$/i.test(codec.mimeType) ? 1 : 2;
  return [...codecs].sort((a, b) => rank(a) - rank(b));
}

export function receiverEvidence(status = {}) {
  return {frames: Math.max(0, Number(status.framesDecoded ?? status.videoFramesDecoded) || 0),
    audio: status.audioTrackReceived === true || Number(status.audioTracks) > 0 || status.hasAudio === true};
}

export function friendlyError(error) {
  const code = error?.code || error?.name;
  const messages = {
    NotAllowedError: 'Sharing was cancelled or permission was denied. Click Share again and choose a source; allow screen sharing when asked.',
    NotFoundError: 'Chrome could not find a screen to share. Try an updated desktop Chrome or Chromebook.',
    NotReadableError: 'Chrome could not capture that source. Close another screen-sharing session and try again.',
    InvalidStateError: 'Click Share directly to open Chrome’s sharing picker.',
    PAIRING_CODE_WRONG: 'That code did not match. Enter the six-digit code on the projector.',
    PAIRING_EXPIRED: 'The pairing code expired. Click Connect to show a new code.',
    UNAUTHORIZED: 'Pairing is no longer valid. Click Connect and pair again.',
    SHARE_BUSY: 'The projector is receiving another share. Stop that session first, then try again.',
    SCREEN_SHARE_BUSY: 'The projector is receiving another share. Stop that session first, then try again.',
    SESSION_BUSY: 'The projector is receiving another share. Stop that session first, then try again.',
    SHARE_NOT_FOUND: 'The receiver session ended. Click Share to start again.',
    SESSION_NOT_FOUND: 'The receiver session ended. Click Share to start again.',
    SCREEN_SHARE_SESSION: 'The receiver session ended or belongs to another browser. Click Share to start your own session.',
    SCREEN_SHARE_INVALID: 'The receiver could not negotiate this share. Update Chrome and LUMA Receiver, then try again.',
    SCREEN_SHARE_TOO_LARGE: 'Chrome produced more connection data than the receiver can accept. Disconnect unused VPN or virtual network adapters and retry.',
    SHARE_EXPIRED: 'The connection expired. Check both devices are on the same Wi-Fi and share again.',
    NOT_FOUND: 'This receiver does not support screen sharing yet. Install the latest LUMA Receiver.',
    NETWORK: 'Cannot reach LUMA. Check the IP and same Wi-Fi, then allow local network access in Chrome’s site settings. Update Chrome if it cannot access local devices.',
    ICE_TIMEOUT: 'Chrome could not prepare a local connection. Check the Wi-Fi and disable network isolation or a VPN for these devices.',
    CONNECTION_FAILED: 'The direct connection failed. Keep both devices on the same Wi-Fi; guest-network isolation or a VPN can block sharing.',
  };
  return messages[code] || error?.message || 'Sharing stopped unexpectedly. Check the projector and try again.';
}

function failure(code, message) { return Object.assign(new Error(message || code), {code}); }

export async function receiverRequest(base, method, params, token = '', {keepalive = false} = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const options = {method: params === undefined ? 'GET' : 'POST', mode: 'cors', credentials: 'omit',
      cache: 'no-store', referrerPolicy: 'no-referrer', signal: controller.signal, keepalive,
      targetAddressSpace: 'local', headers: {}};
    if (token) options.headers.Authorization = `Bearer ${token}`;
    if (params !== undefined) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(params); }
    const response = await fetch(`${base}/api/v1/${method}`, options);
    const body = await response.json();
    if (!response.ok || body.ok !== true) throw failure(body.error?.code || 'REQUEST_FAILED', 'The receiver could not complete this request.');
    return body.result;
  } catch (error) {
    if (typeof error.code === 'string') throw error;
    throw failure('NETWORK');
  } finally { clearTimeout(timer); }
}

export function waitForIce(peer, timeout = 12000) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const done = error => { clearTimeout(timer); peer.removeEventListener('icegatheringstatechange', changed); peer.removeEventListener('connectionstatechange', closed); error ? reject(error) : resolve(); };
    const changed = () => { if (peer.iceGatheringState === 'complete') done(); };
    const closed = () => { if (peer.connectionState === 'closed') done(failure('CANCELLED')); };
    const timer = setTimeout(() => done(failure('ICE_TIMEOUT')), timeout);
    peer.addEventListener('icegatheringstatechange', changed); peer.addEventListener('connectionstatechange', closed);
    changed();
  });
}

export class ShareController {
  constructor({document: doc = globalThis.document, navigator: nav = globalThis.navigator, Peer = globalThis.RTCPeerConnection,
    request = receiverRequest, Sender = globalThis.RTCRtpSender} = {}) {
    this.doc = doc; this.nav = nav; this.Peer = Peer; this.request = request; this.Sender = Sender;
    this.base = ''; this.token = ''; this.pairingId = ''; this.stream = null; this.peer = null; this.sessionId = null;
    this.generation = 0; this.busy = false; this.connecting = false; this.pollTimer = null; this.pollFailures = 0; this.startedAt = 0;
    this.$ = id => this.doc.getElementById(id);
  }
  init() {
    this.$('connect-form').addEventListener('submit', event => { event.preventDefault(); this.connect(); });
    this.$('pair-form').addEventListener('submit', event => { event.preventDefault(); this.pair(); });
    this.$('host').addEventListener('input', () => {
      this.token = ''; this.pairingId = ''; this.base = ''; this.$('pair-form').hidden = true;
      this.connection('Address changed. Click Connect to pair this projector.');
      this.$('share-status').textContent = 'Pair your projector to get started.'; this.update();
    });
    this.$('share').addEventListener('click', () => this.start());
    this.$('stop').addEventListener('click', () => this.stop('Sharing stopped. Your projector is ready for another share.'));
    globalThis.addEventListener?.('pagehide', () => this.stop('', true));
    if (!globalThis.isSecureContext || !this.nav?.mediaDevices?.getDisplayMedia || !this.Peer) {
      this.unsupported = true;
      this.notice('Open this page over HTTPS in an updated Chrome browser on your Chromebook or computer. This browser cannot share a screen here.');
      this.$('connect').disabled = true;
    }
    return this;
  }
  notice(message = '') { this.$('notice').textContent = message; this.$('notice').hidden = !message; }
  connection(message, error = false) { this.$('connection-status').textContent = message; this.$('connection-status').classList.toggle('error', error); }
  update() {
    const active = this.busy || !!this.stream || !!this.sessionId;
    this.$('share').disabled = !this.token || active || this.unsupported;
    this.$('share').hidden = active; this.$('stop').hidden = !active;
    this.$('host').disabled = active || this.connecting; this.$('connect').disabled = active || this.connecting || this.unsupported;
    this.doc.body.dataset.sharing = String(!!this.stream);
  }
  async connect() {
    if (this.busy || this.stream || this.connecting) return;
    this.connecting = true;
    this.notice(); this.token = ''; this.pairingId = ''; this.$('pair-form').hidden = true; this.update();
    try {
      this.base = projectorAddress(this.$('host').value); this.$('connect').disabled = true; this.connection('Connecting to your projector…');
      const info = await this.request(this.base, 'info');
      if (!Array.isArray(info?.capabilities) || !info.capabilities.includes('share.webrtc'))
        throw failure('NOT_FOUND');
      const pairing = await this.request(this.base, 'pair.begin', {clientName: 'LUMA screen sharing'});
      this.pairingId = pairing.pairingId; this.$('pin').value = ''; this.$('pair-form').hidden = false;
      this.connection('Enter the code now shown on your projector.'); this.$('pin').focus();
    } catch (error) { this.connection(friendlyError(error), true); }
    finally { this.connecting = false; this.update(); }
  }
  async pair() {
    if (!this.pairingId || this.$('pair').disabled) return;
    const code = this.$('pin').value.trim();
    if (!/^\d{6}$/.test(code)) { this.connection('Enter the six-digit code on the projector.', true); return; }
    this.$('pair').disabled = true; this.connecting = true; this.update();
    try {
      const result = await this.request(this.base, 'pair.complete', {pairingId: this.pairingId, code});
      if (typeof result?.token !== 'string' || !result.token) throw failure('PAIRING_EXPIRED');
      this.token = result.token; this.pairingId = ''; this.$('pin').value = ''; this.$('pair-form').hidden = true;
      this.connection('Paired with LUMA. Ready to share.'); this.$('share-status').textContent = 'Choose a tab for video with sound.';
      this.notice(); this.update(); this.$('share').focus();
    } catch (error) { this.connection(friendlyError(error), true); }
    finally { this.$('pair').disabled = false; this.connecting = false; this.update(); }
  }
  async start() {
    if (!this.token || this.busy || this.stream) return;
    const generation = ++this.generation, base = this.base, token = this.token;
    this.busy = true; this.startedAt = Date.now(); this.pollFailures = 0; this.notice(); this.update();
    this.$('share-status').textContent = 'Choose a tab or screen in Chrome…';
    try {
      const stream = await this.nav.mediaDevices.getDisplayMedia(captureOptions());
      if (generation !== this.generation) { stream.getTracks().forEach(track => track.stop()); return; }
      this.stream = stream;
      const video = stream.getVideoTracks()[0];
      if (!video) throw failure('NotFoundError');
      const audioExpected = stream.getAudioTracks().length > 0;
      this.$('audio-status').hidden = false; this.$('audio-status').classList.toggle('warning', !audioExpected);
      this.$('audio-status').textContent = audioExpected ? 'Audio included in your share.' : 'No audio was shared. To include sound, stop and choose a Chrome tab with “Share tab audio” enabled.';
      video.addEventListener('ended', () => { if (generation === this.generation) this.stop('Sharing ended in Chrome.'); }, {once: true});
      for (const track of stream.getAudioTracks()) track.addEventListener('ended', () => {
        if (generation !== this.generation) return;
        this.$('audio-status').classList.add('warning');
        this.$('audio-status').textContent = 'Audio sharing ended. Stop and choose a tab with audio to include sound again.';
      }, {once: true});
      video.contentHint = 'motion';
      const peer = this.peer = new this.Peer({iceServers: [], bundlePolicy: 'max-bundle'});
      peer.addEventListener('connectionstatechange', () => {
        if (generation !== this.generation) return;
        if (peer.connectionState === 'failed') this.fail(failure('CONNECTION_FAILED'));
        else if (peer.connectionState === 'disconnected') this.$('share-status').textContent = 'Connection interrupted. Trying to reconnect…';
      });
      for (const track of stream.getTracks()) peer.addTrack(track, stream);
      const codecs = preferredCodecs(this.Sender?.getCapabilities?.('video')?.codecs);
      for (const transceiver of peer.getTransceivers()) if (transceiver.sender.track?.kind === 'video') {
        if (codecs.length && transceiver.setCodecPreferences) transceiver.setCodecPreferences(codecs);
        const parameters = transceiver.sender.getParameters();
        parameters.encodings = parameters.encodings?.length ? parameters.encodings : [{}];
        for (const encoding of parameters.encodings) { encoding.maxBitrate = 3500000; encoding.maxFramerate = 30; }
        await transceiver.sender.setParameters(parameters).catch(() => {});
      }
      this.$('share-status').textContent = 'Connecting your share to LUMA…';
      await peer.setLocalDescription(await peer.createOffer());
      await waitForIce(peer);
      if (generation !== this.generation) return;
      const session = await this.request(base, 'share.begin', {sdp: peer.localDescription.sdp, audioExpected}, token);
      if (generation !== this.generation) {
        if (session?.sessionId) this.request(base, 'share.stop', {sessionId: session.sessionId}, token).catch(() => {});
        return;
      }
      if (!session?.sessionId) throw failure('REQUEST_FAILED', 'The receiver did not create a sharing session. Try again.');
      this.sessionId = session.sessionId; this.startedAt = Date.now(); this.busy = false; this.update();
      await this.poll(generation);
    } catch (error) { if (generation === this.generation) this.fail(error); }
  }
  async poll(generation) {
    if (generation !== this.generation || !this.sessionId) return;
    try {
      const status = await this.request(this.base, 'share.status', {sessionId: this.sessionId}, this.token);
      if (generation !== this.generation) return;
      this.pollFailures = 0;
      if (status.error || ['error', 'failed'].includes(status.state)) throw failure(status.error || 'CONNECTION_FAILED');
      if (['ended', 'stopped', 'closed', 'idle', 'expired'].includes(status.state)) { this.stop('Sharing ended on the projector.'); return; }
      const answer = status.answer || (status.answerSdp ? {type: 'answer', sdp: status.answerSdp} : null);
      if (answer?.sdp && !this.peer.remoteDescription) await this.peer.setRemoteDescription(answer);
      if (generation !== this.generation) return;
      const evidence = receiverEvidence(status);
      if (evidence.frames > 0) this.$('share-status').textContent = 'Your picture is arriving on LUMA.';
      else this.$('share-status').textContent = this.peer.connectionState === 'connected' ? 'Connected. Waiting for the first picture…' : 'Connecting your share to LUMA…';
      if (this.stream.getAudioTracks().some(track => track.readyState !== 'ended') && evidence.audio)
        this.$('audio-status').textContent = 'Audio track received by LUMA.';
      if (!evidence.frames && Date.now() - this.startedAt > 30000)
        this.notice('No picture has arrived yet. Check that both devices use the same Wi-Fi, then stop and retry.');
    } catch (error) {
      if (generation !== this.generation) return;
      if (error.code !== 'NETWORK' || ++this.pollFailures >= 3) { this.fail(error); return; }
      this.$('share-status').textContent = 'Cannot reach the projector. Reconnecting…';
    }
    if (generation === this.generation) this.pollTimer = setTimeout(() => this.poll(generation), 1000);
  }
  fail(error) {
    this.stop('Sharing stopped.'); this.notice(friendlyError(error));
    if (error.code === 'UNAUTHORIZED') { this.token = ''; this.connection('Pair again to reconnect.', true); this.update(); }
  }
  stop(message = 'Sharing stopped.', keepalive = false) {
    ++this.generation; clearTimeout(this.pollTimer); this.pollTimer = null;
    const sessionId = this.sessionId; this.sessionId = null;
    if (this.stream) this.stream.getTracks().forEach(track => track.stop());
    this.stream = null; this.peer?.close(); this.peer = null; this.busy = false;
    this.$('audio-status').hidden = true; this.$('share-status').textContent = message;
    this.update();
    if (sessionId) this.request(this.base, 'share.stop', {sessionId}, this.token, {keepalive}).catch(() => {});
  }
}

if (typeof document !== 'undefined') new ShareController().init();
