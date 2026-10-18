#!/usr/bin/env node
import { chmod, lstat, mkdir, readdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { stdin, stderr } from 'node:process';
import { isIP } from 'node:net';

// Commissioning and individual controls are explicit. Inventory only reads.
// No playback, power, app launch, held-key repeat or macros are implemented.
process.umask(0o077);
const directory = dirname(fileURLToPath(import.meta.url));
const storage = resolve(directory, '../../.local/matter-controller');
const command = process.argv[2] || 'check';
const argument = process.argv[3];
const addressHintText = process.env.MATTER_ADDRESS_HINT;
delete process.env.MATTER_ADDRESS_HINT;
const keys = { home: 9, up: 1, down: 2, left: 3, right: 4, select: 0, back: 13 };
const valid = ['check', 'commission', 'inventory', 'open-window'].includes(command) ? argument === undefined
  : command === 'key' ? Object.hasOwn(keys, argument)
    : command === 'volume' && (argument === undefined || argument === 'read' || /^(?:0|[1-9][0-9]{0,2})$/u.test(argument));
if (process.argv.length > 4 || !valid) {
  stderr.write('Usage: node controller.mjs check|commission|inventory|open-window|key home|up|down|left|right|select|back|volume [read|LEVEL]. Pairing codes must use hidden input or stdin.\n');
  process.exit(2);
}

const DESCRIPTOR = 0x001d;
const CLUSTERS = new Map([
  [0x0006, { name: 'OnOff', attributes: ['onOff'] }],
  [0x0008, { name: 'LevelControl', attributes: ['currentLevel', 'minLevel', 'maxLevel'] }],
  [0x0503, { name: 'WakeOnLan', attributes: [] }],
  [0x0504, { name: 'Channel', attributes: ['channelList', 'currentChannel'] }],
  [0x0505, { name: 'TargetNavigator', attributes: ['targetList', 'currentTarget'] }],
  [0x0506, { name: 'MediaPlayback', attributes: ['currentState', 'duration', 'sampledPosition', 'playbackSpeed', 'activeAudioTrack', 'availableAudioTracks', 'activeTextTrack', 'availableTextTracks'] }],
  [0x0507, { name: 'MediaInput', attributes: ['inputList', 'currentInput'] }],
  [0x0508, { name: 'LowPower', attributes: [] }],
  [0x0509, { name: 'KeypadInput', attributes: [] }],
  [0x050a, { name: 'ContentLauncher', attributes: ['acceptHeader', 'supportedStreamingProtocols'] }],
  [0x050b, { name: 'AudioOutput', attributes: ['currentOutput', 'outputList'] }],
  [0x050c, { name: 'ApplicationLauncher', attributes: ['catalogList', 'currentApp'] }],
  [0x050d, { name: 'ApplicationBasic', attributes: ['vendorName', 'productId', 'applicationName', 'status', 'applicationVersion', 'allowedVendorList'] }],
]);
const GLOBALS = new Map([[0xfffc, 'featureMap'], [0xfff9, 'acceptedCommandList'], [0xfffb, 'attributeList'], [0xfffd, 'clusterRevision']]);
const safeFields = new Set(['deviceType', 'revision', 'index', 'inputType', 'name', 'description', 'id', 'type', 'languageCode', 'displayName', 'language', 'trackAttributes', 'audio', 'text', 'characteristics', 'application', 'catalogVendorId', 'applicationId', 'status', 'position', 'updatedAt', 'majorNumber', 'minorNumber', 'callSign', 'affiliateCallSign', 'identifier', 'deliverySystem', 'brandingInformation', 'startUp', 'urlPlayback', 'contentSearch', 'supportsUdp', 'supportsTcp', 'dash', 'hls', 'currentLevel', 'minLevel', 'maxLevel']);
function clean(value, depth = 0) {
  if (depth > 6) return null;
  if (value === null || typeof value === 'boolean') return value;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'bigint') return value.toString();
  if (typeof value === 'string') return /(?:[a-z][a-z0-9+.-]*:\/\/|www\.|MT:)/iu.test(value) ? '[omitted]' : value.replace(/[\p{Cc}\p{Cf}]/gu, ' ').slice(0, 160);
  if (Array.isArray(value)) return value.slice(0, 256).map(item => clean(item, depth + 1));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key, item]) => safeFields.has(key) || (typeof item === 'boolean' && /^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(key))).map(([key, item]) => [key, clean(item, depth + 1)]));
  return null;
}
async function secureFiles(path) {
  const stat = await lstat(path);
  if (stat.isSymbolicLink()) throw new Error('UNSAFE_STORAGE');
  await chmod(path, stat.isDirectory() ? 0o700 : 0o600);
  if (stat.isDirectory()) for (const name of await readdir(path)) await secureFiles(resolve(path, name));
}
async function pairingCode() {
  let code = process.env.MATTER_PAIRING_CODE;
  delete process.env.MATTER_PAIRING_CODE;
  if (!code && !stdin.isTTY) {
    code = '';
    for await (const chunk of stdin) { code += chunk.toString(); if (code.length > 256) throw new Error('INVALID_PAIRING_CODE'); }
  } else if (!code) {
    stderr.write('Matter pairing code (hidden): ');
    code = await new Promise((done, reject) => {
      let text = '';
      const finish = error => { stdin.off('data', receive); stdin.setRawMode(false); stdin.pause(); stderr.write('\n'); error ? reject(error) : done(text); };
      const receive = bytes => {
        for (const byte of bytes) {
          if (byte === 3) return finish(new Error('CANCELED'));
          if (byte === 10 || byte === 13) return finish();
          if (byte === 8 || byte === 127) text = text.slice(0, -1);
          else if (byte >= 32 && byte < 127) text += String.fromCharCode(byte);
          if (text.length > 256) return finish(new Error('INVALID_PAIRING_CODE'));
        }
      };
      stdin.setRawMode(true); stdin.resume(); stdin.on('data', receive);
    });
  }
  code = code.trim().replace(/[\s-]/gu, '');
  if (!/^(?:\d{11}|\d{21})$/u.test(code)) throw new Error('INVALID_PAIRING_CODE');
  return code;
}

let controller, phase = 'initialization', exitCode = 0, shutdown;
const limit = setTimeout(() => { stderr.write('Matter operation reached its time limit; saved pairing state is preserved.\n'); process.exit(1); }, 120000);
limit.unref();
try {
  await mkdir(storage, { recursive: true, mode: 0o700 }); await secureFiles(storage);
  const { config } = await import('@matter/nodejs/config');
  config.defaultStoragePath = storage;
  config.loadProcessArgv = false; config.loadProcessEnv = false; config.loadConfigFile = false;
  config.trapUnhandledErrors = false; config.trapProcessSignals = false;
  const matter = await import('@matter/main');
  const { Logger, LogLevel, ServerNode, Environment, Seconds, Crypto } = matter;
  Logger.level = LogLevel.ERROR;
  for (const destination of Object.values(Logger.destinations)) destination.write = () => {};
  Logger.reportUnhandledError = () => {};
  const clusters = await import('@matter/main/clusters');
  const { Invoke, PaseClient, PeerSet } = await import('@matter/main/protocol');
  const { ManualPairingCodeCodec } = await import('@matter/main/types');
  const definitions = new Map(Object.values(clusters).filter(value => value && typeof value.id === 'number' && value.attributes && value.commands).map(value => [value.id, value]));
  Environment.default.vars.set('storage.path', storage);
  Environment.default.vars.set('logger.level', 'error');
  if (command === 'check') {
    Invoke({ commands: [{ endpoint: 1, cluster: clusters.KeypadInput, command: 'sendKey', fields: { keyCode: 0 } }] });
    Invoke({ commands: [{ endpoint: 2, cluster: clusters.LevelControl, command: 'moveToLevel', fields: { level: 1, transitionTime: 0, optionsMask: {}, optionsOverride: {} } }] });
    Invoke({ timed: true, timeout: Seconds(10), commands: [{ endpoint: 0, cluster: clusters.AdministratorCommissioning, command: 'openCommissioningWindow', fields: { commissioningTimeout: 900, pakePasscodeVerifier: new Uint8Array(97), discriminator: 1, iterations: 10000, salt: new Uint8Array(16) } }] });
    Invoke({ timed: true, timeout: Seconds(10), commands: [{ endpoint: 0, cluster: clusters.AdministratorCommissioning, command: 'revokeCommissioning' }] });
    process.stdout.write(JSON.stringify({ ready: true, matterVersion: '0.17.9', nodeVersion: process.version, bluetooth: false, controlSchemasValidated: true, storage: '.local/matter-controller', knownVideoClusters: [...CLUSTERS.entries()].map(([id, info]) => ({ id, name: info.name, schemaPresent: definitions.has(id) })) }, null, 2) + '\n');
  } else {
    controller = await ServerNode.create({ id: 'philips-controller', commissioning: { enabled: false }, network: { port: 0 } });
    shutdown = () => { controller.close().finally(() => process.exit(130)); };
    process.once('SIGINT', shutdown); process.once('SIGTERM', shutdown);
    let peer = controller.peers.get('philips-tv');
    if (command === 'commission') {
      if (peer?.state.commissioning.peerAddress) throw new Error('ALREADY_PAIRED_USE_INVENTORY');
      phase = 'pairing-code'; const code = await pairingCode();
      phase = 'commissioning'; stderr.write('Commissioning the TV over the existing IP network; Bluetooth is disabled.\n');
      peer = await controller.peers.commission({ id: 'philips-tv', pairingCode: code, discoveryCapabilities: { onIpNetwork: true, ble: false }, timeout: Seconds(75), autoSubscribe: false, autoStateInitialize: false });
    }
    if (!peer?.state.commissioning.peerAddress) throw new Error('NOT_PAIRED');
    if (addressHintText !== undefined) {
      phase = 'address-hint';
      const match = /^(\d{1,3}(?:\.\d{1,3}){3}):([1-9][0-9]{0,4})$/u.exec(addressHintText);
      const ip = match?.[1], port = Number(match?.[2]);
      const octets = ip?.split('.').map(Number);
      if (!ip || isIP(ip) !== 4 || port > 65535 || !(octets[0] === 10 || octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31 || octets[0] === 192 && octets[1] === 168)) throw new Error('INVALID_ADDRESS_HINT');
      const address = { type: 'udp', ip, port };
      const previous = peer.state.commissioning.addresses || [];
      await peer.set({ commissioning: { addresses: [address, ...previous.filter(item => item.ip !== ip || item.port !== port)] } });
      controller.env.get(PeerSet).addKnownPeer({ address: peer.state.commissioning.peerAddress, operationalAddress: address });
    }
    phase = 'inventory'; stderr.write('Reading the TV endpoint descriptors and supported controls.\n');
    phase = 'connecting'; await peer.start();
    const connectStarted = Date.now();
    await controller.env.get(PeerSet).for(peer.state.commissioning.peerAddress).connect({ connectionTimeout: Seconds(75) });
    stderr.write(JSON.stringify({ phase: 'connected', elapsedMs: Date.now() - connectStarted }) + '\n');
    async function read(paths) {
      const values = [];
      // CLI reads must return values even when their persisted data versions
      // match the device; otherwise a second run appears to have no endpoints.
      for await (const chunk of peer.interaction.read({ attributeRequests: paths, isFabricFiltered: true, interactionModelRevision: 12, includeKnownVersions: true })) {
        for await (const report of chunk) {
          if (values.length >= 4096) throw new Error('INVENTORY_TOO_LARGE');
          values.push({ endpoint: Number(report.path.endpointId), cluster: Number(report.path.clusterId), attribute: Number(report.path.attributeId), ...(report.kind === 'attr-value' ? { value: report.value } : { status: Number(report.status) }) });
        }
      }
      return values;
    }
    phase = 'descriptor-read';
    const descriptorStarted = Date.now();
    const descriptors = await read([{ clusterId: DESCRIPTOR }]);
    stderr.write(JSON.stringify({ phase: 'descriptor-read', elapsedMs: Date.now() - descriptorStarted }) + '\n');
    const endpoints = new Map();
    for (const row of descriptors) {
      if (!endpoints.has(row.endpoint)) endpoints.set(row.endpoint, { endpoint: row.endpoint, deviceTypes: [], serverClusters: [], clientClusters: [], parts: [], capabilities: [] });
      const endpoint = endpoints.get(row.endpoint);
      if (row.attribute === 0) endpoint.deviceTypes = clean(row.value);
      if (row.attribute === 1) endpoint.serverClusters = clean(row.value);
      if (row.attribute === 2) endpoint.clientClusters = clean(row.value);
      if (row.attribute === 3) endpoint.parts = clean(row.value);
    }
    async function invoke(endpoint, cluster, name, fields, options = {}) {
      const result = [];
      for await (const chunk of peer.interaction.invoke(Invoke({ ...options, commands: [{ endpoint, cluster: definitions.get(cluster), command: name, ...(fields === undefined ? {} : { fields }) }] }))) {
        for (const response of chunk) result.push({ kind: response.kind, status: Number(response.kind === 'cmd-response' ? response.data?.status : response.status) });
      }
      return result;
    }
    if (command === 'open-window') {
      phase = 'open-commissioning-window';
      const admin = await read([{ endpointId: 0, clusterId: 0x003c, attributeId: 0xfff9 }, { endpointId: 0, clusterId: 0x003c, attributeId: 0 }]);
      const accepted = admin.find(row => row.attribute === 0xfff9)?.value;
      if (!Array.isArray(accepted) || !accepted.includes(0)) throw new Error('COMMAND_UNSUPPORTED');
      if (admin.find(row => row.attribute === 0)?.value !== 0) {
        if (!accepted.includes(2)) throw new Error('COMMISSIONING_WINDOW_ALREADY_OPEN');
        const revoked = await invoke(0, 0x003c, 'revokeCommissioning', undefined, { timed: true, timeout: Seconds(10) });
        if (!revoked.length || revoked.some(response => response.status !== 0)) throw new Error('COMMISSIONING_WINDOW_ALREADY_OPEN');
      }
      const crypto = controller.env.get(Crypto);
      const passcode = PaseClient.generateRandomPasscode(crypto), discriminator = PaseClient.generateRandomDiscriminator(crypto);
      const iterations = 10000, salt = crypto.randomBytes(16), commissioningTimeout = 900;
      const verifier = await PaseClient.generatePakePasscodeVerifier(crypto, passcode, { iterations, salt });
      const responses = await invoke(0, 0x003c, 'openCommissioningWindow', { commissioningTimeout, pakePasscodeVerifier: verifier, discriminator, iterations, salt }, { timed: true, timeout: Seconds(10) });
      const current = await read([{ endpointId: 0, clusterId: 0x003c, attributeId: 0 }]);
      if (!responses.length || responses.some(response => response.status !== 0) || current.find(row => row.attribute === 0)?.value !== 1) throw new Error('COMMISSIONING_WINDOW_NOT_CONFIRMED');
      const expiresAt = new Date(Date.now() + commissioningTimeout * 1000).toISOString();
      const privateFile = resolve(storage, 'commissioning-window.json');
      await writeFile(privateFile, JSON.stringify({ manualPairingCode: ManualPairingCodeCodec.encode({ discriminator, passcode }), passcode, discriminator, expiresAt, commissioningTimeoutSeconds: commissioningTimeout }, null, 2) + '\n', { mode: 0o600 });
      process.stdout.write(JSON.stringify({ operation: 'open-commissioning-window', opened: true, expiresAt, pairingStoredPrivately: true, responses }, null, 2) + '\n');
    } else if (command === 'key' || command === 'volume') {
      const cluster = command === 'key' ? 0x0509 : 0x0008;
      const candidates = [...endpoints.values()].filter(endpoint => endpoint.serverClusters.includes(cluster) && (command === 'key' || endpoint.deviceTypes.some(type => type.deviceType === 0x22)));
      if (candidates.length !== 1) throw new Error('CONTROL_ENDPOINT_AMBIGUOUS');
      const endpoint = candidates[0].endpoint;
      phase = 'command-capability-read';
      const supported = await read([{ endpointId: endpoint, clusterId: cluster, attributeId: 0xfff9 }]);
      if (!supported.some(row => Array.isArray(row.value) && row.value.includes(0))) throw new Error('COMMAND_UNSUPPORTED');
      if (command === 'key') {
        phase = 'key';
        const responses = await invoke(endpoint, cluster, 'sendKey', { keyCode: keys[argument] });
        process.stdout.write(JSON.stringify({ operation: 'key', key: argument, endpoint, responses, accepted: responses.length > 0 && responses.every(response => response.status === 0) }, null, 2) + '\n');
      } else {
        phase = 'volume';
        const definition = definitions.get(cluster);
        async function volume() {
          const names = ['currentLevel', 'minLevel', 'maxLevel'];
          const paths = names.map(name => ({ endpointId: endpoint, clusterId: cluster, attributeId: Number(definition.attributes[name].id) }));
          const rows = await read(paths);
          return Object.fromEntries(names.map((name, index) => [name, rows.find(row => row.attribute === paths[index].attributeId)?.value ?? null]));
        }
        const before = await volume(); let responses = [], after = before;
        if (argument !== undefined && argument !== 'read') {
          const requested = Number(argument);
          if (!Number.isInteger(before.minLevel) || !Number.isInteger(before.maxLevel) || requested < before.minLevel || requested > before.maxLevel) throw new Error('INVALID_VOLUME_RANGE');
          responses = await invoke(endpoint, cluster, 'moveToLevel', { level: requested, transitionTime: 0, optionsMask: {}, optionsOverride: {} });
          for (let attempt = 0; attempt < 12; attempt++) { after = await volume(); if (after.currentLevel === requested) break; await new Promise(done => setTimeout(done, 100)); }
        }
        process.stdout.write(JSON.stringify({ operation: 'volume', endpoint, before: clean(before), after: clean(after), ...(argument !== undefined && argument !== 'read' ? { requested: Number(argument), verified: after.currentLevel === Number(argument), responses } : {}) }, null, 2) + '\n');
      }
    } else {
    for (const endpoint of endpoints.values()) {
      if (!Array.isArray(endpoint.serverClusters)) continue;
      for (const cluster of endpoint.serverClusters) {
        const relevant = CLUSTERS.get(cluster); if (!relevant) continue;
        const definition = definitions.get(cluster);
        const names = new Map(GLOBALS);
        for (const name of relevant.attributes) if (definition?.attributes[name]) names.set(Number(definition.attributes[name].id), name);
        const paths = [...names.keys()].map(attributeId => ({ endpointId: endpoint.endpoint, clusterId: cluster, attributeId }));
        let rows;
        try { rows = await read(paths); } catch { endpoint.capabilities.push({ cluster, name: relevant.name, readFailed: true }); continue; }
        const capability = { cluster, name: relevant.name, attributes: {} };
        for (const row of rows) if (names.has(row.attribute)) capability.attributes[names.get(row.attribute)] = row.status === undefined ? clean(row.value) : { readStatus: row.status };
        const accepted = capability.attributes.acceptedCommandList;
        capability.acceptedCommands = Array.isArray(accepted) ? accepted.map(id => ({ id, name: Object.entries(definition?.commands || {}).find(([, value]) => Number(value.requestId ?? value.id) === id)?.[0] || 'unknown' })) : [];
        endpoint.capabilities.push(capability);
      }
    }
    const inventory = { collectedAt: new Date().toISOString(), matterVersion: '0.17.9', operation: 'read-only-inventory', endpoints: [...endpoints.values()].sort((a, b) => a.endpoint - b.endpoint) };
    const output = JSON.stringify(inventory, null, 2) + '\n';
    await writeFile(resolve(storage, 'inventory.json'), output, { mode: 0o600 });
    process.stdout.write(output);
    }
  }
} catch (error) {
  exitCode = 1;
  const allowed = new Set(['INVALID_PAIRING_CODE', 'INVALID_ADDRESS_HINT', 'CANCELED', 'ALREADY_PAIRED_USE_INVENTORY', 'NOT_PAIRED', 'UNSAFE_STORAGE', 'INVENTORY_TOO_LARGE', 'CONTROL_ENDPOINT_AMBIGUOUS', 'COMMAND_UNSUPPORTED', 'INVALID_VOLUME_RANGE', 'COMMISSIONING_WINDOW_ALREADY_OPEN', 'COMMISSIONING_WINDOW_NOT_CONFIRMED']);
  process.stderr.write(JSON.stringify({ error: allowed.has(error.message) ? error.message : 'MATTER_OPERATION_FAILED', phase, type: /^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(error.name || '') ? error.name : 'Error', savedStatePreserved: true }) + '\n');
} finally {
  clearTimeout(limit);
  if (shutdown) { process.off('SIGINT', shutdown); process.off('SIGTERM', shutdown); }
  if (controller) await controller.close().catch(() => {});
  await secureFiles(storage).catch(() => {});
}
process.exitCode = exitCode;
