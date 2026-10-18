// Read-only EShare discovery derived from the archived LUMA Android client:
// b.b.a.l.e.a(DatagramSocket, InetAddress), b.b.a.b$n.a and b.b.a.j.a.
// A discovery result identifies a compatible service, not an authenticated device.
import dgram from 'node:dgram';
import { networkInterfaces } from 'node:os';
import { isIP } from 'node:net';

const DISCOVERY_PORT = 48689;
const CONTROL_PORT = 8121;
const MAX_PACKET_BYTES = 16 * 1024;
const MAX_DEVICES = 16;
const MAX_INTERFACES = 16;
const decoder = new TextDecoder('utf-8', { fatal: true });

function privateIPv4(value) {
  if (isIP(value) !== 4) return false;
  const [a, b] = value.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function ipv4Number(value) {
  return value.split('.').reduce((number, octet) => (number * 256) + Number(octet), 0) >>> 0;
}

function ipv4String(value) {
  return [24, 16, 8, 0].map(shift => (value >>> shift) & 255).join('.');
}

export function discoveryRequest() {
  // Five big-endian u32 fields followed by the command, padded to 50 bytes.
  const packet = Buffer.alloc(50);
  packet.writeUInt32BE(13, 12);
  packet.write('FindECloudBox', 20, 'ascii');
  return packet;
}

export function parseDiscoveryReply(packet, host, { includeUnsupportedPorts = false } = {}) {
  if (!(packet instanceof Uint8Array) || !packet.length || packet.length > MAX_PACKET_BYTES || !privateIPv4(host)) return null;
  let text;
  try { text = decoder.decode(packet).replace(/[\0\r\n]+$/u, ''); }
  catch { return null; }
  if (/[\u0000-\u001f\u007f-\u009f]/u.test(text)) return null;
  // The original client uses the datagram source IP, never a payload IP.
  const fields = text.split(':');
  // Shipped client uses startsWith("ECloudBox"); the physical LUMA replies
  // with ECloudBox516. Permit its numeric protocol version, not arbitrary text.
  if (fields.length < 4 || fields.length > 16 || !/^ECloudBox\d{0,6}$/u.test(fields[0])) return null;
  const name = fields[1].trim();
  const port = Number(fields[2]);
  if (!name || name.length > 120 || !/^\d{1,5}$/u.test(fields[2]) || port < 1 || port > 65535 || String(port) !== fields[2]
      || !/^\d{1,5}$/u.test(fields[3]) || Number(fields[3]) > 65535) return null;
  if (port !== CONTROL_PORT) return includeUnsupportedPorts ? { host, name, port, supported: false } : null;
  return { host, name, port: CONTROL_PORT };
}

export function discoveryInterfaces(inventory) {
  const selected = [];
  const addresses = new Set();
  for (const [name, entries] of Object.entries(inventory || {})) {
    // Omit loopback, VPN, virtual bridges, peer-to-peer and link-local surfaces.
    if (!/^(?:en\w*|eth\w*|wl\w*|Wi-Fi|Ethernet(?: \d+)?)$/u.test(name)) continue;
    if (!Array.isArray(entries)) continue;
    for (const entry of entries) {
      if (entry.internal || !['IPv4', 4].includes(entry.family) || !privateIPv4(entry.address)
          || isIP(entry.netmask) !== 4 || addresses.has(entry.address)) continue;
      const address = ipv4Number(entry.address);
      const mask = ipv4Number(entry.netmask);
      const inverse = (~mask) >>> 0;
      // A contiguous mask with at least two usable hosts; no /0, /31 or /32.
      if (!mask || inverse < 3 || (inverse & (inverse + 1)) !== 0) continue;
      const network = (address & mask) >>> 0;
      const broadcast = (network | inverse) >>> 0;
      if (address <= network || address >= broadcast || !privateIPv4(ipv4String(network))
          || !privateIPv4(ipv4String(broadcast))) continue;
      addresses.add(entry.address);
      selected.push({ address: entry.address, broadcast: ipv4String(broadcast), network, mask });
      if (selected.length >= MAX_INTERFACES) return selected;
    }
  }
  return selected;
}

export async function discoverLuma({
  timeoutMs = 2500,
  includeUnsupportedPorts = false,
  getInterfaces = networkInterfaces,
  createSocket = options => dgram.createSocket(options),
  setTimeoutFn = setTimeout,
  clearTimeoutFn = clearTimeout,
} = {}) {
  const duration = Number.isFinite(timeoutMs) ? Math.max(100, Math.min(5000, timeoutMs)) : 2500;
  let candidates;
  try { candidates = discoveryInterfaces(getInterfaces()); }
  catch { return []; }
  const devices = new Map();
  await Promise.all(candidates.map(candidate => new Promise(resolve => {
    let socket;
    let finished = false;
    const timers = [];
    const finish = () => {
      if (finished) return;
      finished = true;
      for (const timer of timers) clearTimeoutFn(timer);
      try { socket?.close(); } catch { /* An unbound or closed socket is safe. */ }
      resolve();
    };
    timers.push(setTimeoutFn(finish, duration));
    try {
      socket = createSocket({ type: 'udp4', reuseAddr: true });
      socket.on('error', finish);
      socket.on('message', (packet, remote) => {
        if (finished || devices.size >= MAX_DEVICES || !privateIPv4(remote?.address)) return;
        const source = ipv4Number(remote.address);
        const broadcast = ipv4Number(candidate.broadcast);
        if (((source & candidate.mask) >>> 0) !== candidate.network || source <= candidate.network || source >= broadcast) return;
        const device = parseDiscoveryReply(packet, remote.address, { includeUnsupportedPorts });
        if (device && !devices.has(device.host)) devices.set(device.host, device);
      });
      socket.bind(DISCOVERY_PORT, candidate.address, () => {
        if (finished) return;
        try {
          socket.setBroadcast(true);
          const send = () => {
            if (finished) return;
            try { socket.send(discoveryRequest(), DISCOVERY_PORT, candidate.broadcast, error => { if (error) finish(); }); }
            catch { finish(); }
          };
          send();
          for (const delay of [700, 1400]) {
            if (!finished && delay < duration) timers.push(setTimeoutFn(send, delay));
          }
        } catch { finish(); }
      });
    } catch { finish(); }
  })));
  return [...devices.values()];
}
