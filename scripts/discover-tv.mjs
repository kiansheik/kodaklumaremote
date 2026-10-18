#!/usr/bin/env node
// Read-only SSDP/device-description discovery, limited to the user's Philips TV.
// No port scan, media download, SOAP action, or receiver-control request.
import dgram from 'node:dgram';
import { networkInterfaces } from 'node:os';
import { isIP } from 'node:net';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const TARGET_MODEL = '43PFG6910/78';
const MAX_BYTES = 256 * 1024;
const MAX_LOCATIONS = 16;
const SEARCH_TARGETS = [
  'urn:schemas-upnp-org:device:MediaRenderer:1',
  'urn:schemas-upnp-org:service:AVTransport:1',
  'urn:dial-multiscreen-org:service:dial:1',
];
const ACTIONS = new Set(['SetAVTransportURI', 'SetNextAVTransportURI', 'Play', 'Pause', 'Stop', 'Seek', 'GetTransportInfo', 'GetPositionInfo']);

export function isPrivateAddress(address) {
  if (isIP(address) === 4) {
    const [a, b] = address.split('.').map(Number);
    return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  }
  return isIP(address) === 6 && /^f[cd][0-9a-f]{2}:/i.test(address);
}

export function localDescriptionUrl(value, base) {
  try {
    const url = new URL(value, base);
    const host = url.hostname.replace(/^\[|\]$/g, '');
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash || !isPrivateAddress(host)) return null;
    // Never use a description to traverse to another device's services.
    if (base && url.origin !== new URL(base).origin) return null;
    return url;
  } catch { return null; }
}

function xmlBlocks(xml, name) {
  const blocks = [];
  let cursor = 0, depth = 0, contentStart = 0;
  while (cursor < xml.length) {
    const start = xml.indexOf('<', cursor);
    if (start === -1) break;
    if (xml.startsWith('<!--', start) || xml.startsWith('<![CDATA[', start)) {
      const terminator = xml.startsWith('<!--', start) ? '-->' : ']]>';
      const end = xml.indexOf(terminator, start);
      if (end === -1) break;
      cursor = end + terminator.length;
      continue;
    }
    const end = xml.indexOf('>', start + 1);
    if (end === -1) break;
    cursor = end + 1;
    const tag = xml.slice(start + 1, end).match(/^(\/?)\s*((?:[A-Za-z_][\w.-]*:)?[A-Za-z_][\w.-]*)(?:\s|\/|$)/);
    if (!tag || tag[2].split(':').pop() !== name) continue;
    if (tag[1]) {
      if (depth && --depth === 0) blocks.push(xml.slice(contentStart, start));
    } else if (!/\/\s*$/.test(xml.slice(start + 1, end))) {
      if (depth++ === 0) contentStart = end + 1;
    }
  }
  return blocks;
}
function xmlText(xml, name) {
  return (xmlBlocks(xml, name)[0] || '').replace(/<[^>]*>/g, '').trim()
    .replace(/&(amp|lt|gt|quot|apos);/g, (_, name) => ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" })[name]);
}
function safeXml(xml) {
  if (Buffer.byteLength(xml) > MAX_BYTES || /<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error('Invalid description');
  return xml;
}

export function targetDescription(xml) {
  safeXml(xml);
  const modelKey = TARGET_MODEL.replace(/[^a-z0-9]/gi, '').toLowerCase();
  const modelFields = ['modelName', 'modelNumber', 'friendlyName'].map(name => xmlText(xml, name).replace(/[^a-z0-9]/gi, '').toLowerCase());
  const manufacturerMatch = xmlText(xml, 'manufacturer').match(/\b(philips|tp\s*vision|tpv)\b/i)?.[1];
  if (!manufacturerMatch || !modelFields.includes(modelKey)) return null;
  const manufacturer = /^philips$/i.test(manufacturerMatch) ? 'Philips' : /^tpv$/i.test(manufacturerMatch) ? 'TPV' : 'TP Vision';
  const services = xmlBlocks(xml, 'service').flatMap(block => {
    const type = xmlText(block, 'serviceType');
    if (!/^urn:(?:schemas-upnp-org|dial-multiscreen-org):service:[A-Za-z0-9_-]+:[0-9]+$/.test(type)) return [];
    return [{ type, scpd: xmlText(block, 'SCPDURL') }];
  });
  return { model: TARGET_MODEL, manufacturer, services };
}

export function transportActions(xml) {
  safeXml(xml);
  return [...new Set(xmlBlocks(xml, 'action').map(block => xmlText(block, 'name')).filter(name => ACTIONS.has(name)))].sort();
}

async function fetchDescription(url, signal) {
  const response = await fetch(url, {
    redirect: 'manual', credentials: 'omit', signal,
    headers: { Accept: 'application/xml, text/xml' },
  });
  const contentType = response.headers.get('content-type') || '';
  if (!response.ok || !/(?:application|text)\/(?:[\w.-]+\+)?xml(?:;|$)/i.test(contentType)
      || Number(response.headers.get('content-length') || 0) > MAX_BYTES) {
    await response.body?.cancel();
    throw new Error('Description refused');
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.byteLength;
    if (size > MAX_BYTES) throw new Error('Description too large');
    chunks.push(chunk);
  }
  return safeXml(Buffer.concat(chunks).toString('utf8'));
}

async function discoverLocations(interfaces, report) {
  const locations = new Set();
  await Promise.all(interfaces.map(({ address }) => new Promise(resolve => {
    const socket = dgram.createSocket('udp4');
    let retryTimer;
    const timer = setTimeout(() => { socket.close(); resolve(); }, 4500);
    socket.on('error', () => {
      report.discoveryErrors++;
      clearTimeout(timer);
      clearTimeout(retryTimer);
      try { socket.close(); } catch { /* Socket may already be closed. */ }
      resolve();
    });
    socket.on('message', (message, remote) => {
      if (message.length > 16 * 1024 || !isPrivateAddress(remote.address)) return;
      const response = message.toString('utf8');
      if (!/^HTTP\/1\.[01] 200\b/.test(response)) return;
      const location = response.match(/^location\s*:\s*(\S+)\s*$/im)?.[1];
      const url = localDescriptionUrl(location);
      // A responder may advertise only its own numeric address.
      if (!url || url.hostname !== remote.address || locations.size >= MAX_LOCATIONS) return;
      locations.add(url.href);
    });
    socket.bind(0, address, () => {
      try {
        socket.setMulticastInterface(address);
        socket.setMulticastTTL(1);
        const sendSearches = () => {
          for (const target of SEARCH_TARGETS) {
            const packet = Buffer.from(`M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${target}\r\n\r\n`);
            socket.send(packet, 1900, '239.255.255.250', error => { if (error) report.discoveryErrors++; });
          }
        };
        sendSearches();
        retryTimer = setTimeout(sendSearches, 1000);
      } catch { socket.close(); clearTimeout(timer); clearTimeout(retryTimer); report.discoveryErrors++; resolve(); }
    });
  })));
  return [...locations];
}

export async function discoverTv({ interfaceName = process.env.TV_DISCOVERY_INTERFACE } = {}) {
  const report = {
    timestamp: new Date().toISOString(),
    policy: 'Read-only SSDP plus private-address XML GET; no controls, media, redirects, credentials, or address/identity output.',
    interfaceCount: 0, descriptionCount: 0, nonTargetCount: 0, descriptionErrors: 0, discoveryErrors: 0, targets: [],
  };
  const candidates = Object.entries(networkInterfaces()).filter(([name]) => interfaceName ? name === interfaceName : /^(?:en\d+|eth\d+|wlan\d+|wl[a-z0-9]+)$/.test(name))
    .flatMap(([, entries]) => entries.filter(entry => entry.family === 'IPv4' && !entry.internal && isPrivateAddress(entry.address)));
  report.interfaceCount = candidates.length;
  const locations = await discoverLocations(candidates, report);
  const signal = AbortSignal.timeout(8000);
  for (const location of locations) {
    if (signal.aborted) break;
    try {
      const xml = await fetchDescription(location, signal);
      report.descriptionCount++;
      const target = targetDescription(xml);
      if (!target) { report.nonTargetCount++; continue; }
      const safeTarget = {
        model: target.model, manufacturer: target.manufacturer,
        serviceTypes: [...new Set(target.services.map(service => service.type))].sort(),
        avTransportActions: [], avTransportDescriptionRead: false,
      };
      for (const service of target.services.slice(0, 12)) {
        if (!/:AVTransport:\d+$/.test(service.type)) continue;
        // URLBase may change the path, but must keep the same origin.
        const base = localDescriptionUrl(xmlText(xml, 'URLBase'), location) || new URL(location);
        const scpd = localDescriptionUrl(service.scpd, base);
        if (!scpd || !service.scpd) continue;
        try {
          safeTarget.avTransportActions = transportActions(await fetchDescription(scpd, signal));
          safeTarget.avTransportDescriptionRead = true;
        } catch { report.descriptionErrors++; }
      }
      report.targets.push(safeTarget);
    } catch { report.descriptionErrors++; }
  }
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  if (process.argv.includes('--help')) {
    console.log('node scripts/discover-tv.mjs [--no-save]\nRead-only Philips 43PFG6910/78 SSDP discovery. Optional TV_DISCOVERY_INTERFACE selects one local interface. Sanitized result saved to ignored .local/tv-discovery.json by default.');
  } else {
    const report = await discoverTv();
    if (!process.argv.includes('--no-save')) {
      await mkdir('.local', { recursive: true });
      await writeFile('.local/tv-discovery.json', `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
    }
    console.log(JSON.stringify(report, null, 2));
  }
}
