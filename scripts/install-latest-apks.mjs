import {execFile, spawn} from 'node:child_process';
import {homedir} from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {promisify} from 'node:util';
import {buildLatestApks} from './build-latest-apks.mjs';
import {loadReleaseConfig} from './release-config.mjs';

const run = promisify(execFile);
const root = fileURLToPath(new URL('../', import.meta.url));
const remotePackage = 'dev.framextv.lumaremote';
const receiverPackage = 'dev.framextv.lumareceiver';

export function parseAdbDevices(output) {
  const devices = [];
  for (const line of String(output).split(/\r?\n/u)) {
    const match = line.match(/^(.+?)\s+(device|offline|unauthorized)\b(.*)$/u);
    if (!match) continue;
    devices.push({serial:match[1], state:match[2], details:match[3].trim()});
  }
  return devices;
}

export function fallbackIdentity(serial) {
  return serial.replace(/\s+\([0-9]+\)(?=\._adb-tls-connect\._tcp$)/u, '');
}

function preferredTransport(group) {
  return [...group].sort((left, right) =>
    Number(right.hasRemote) - Number(left.hasRemote)
      || Number(/\s+\([0-9]+\)\._adb-tls-connect\._tcp$/u.test(left.serial))
        - Number(/\s+\([0-9]+\)\._adb-tls-connect\._tcp$/u.test(right.serial))
      || left.serial.length - right.serial.length
      || left.serial.localeCompare(right.serial))[0];
}

export function selectPhoneTransport(inspected, override = '') {
  if (override) {
    const selected = inspected.find(device => device.serial === override);
    if (!selected || selected.state !== 'device' || !selected.responsive) {
      throw new Error(`ANDROID_SERIAL does not name an authorized, responsive device: ${override}`);
    }
    if (selected.emulator) throw new Error('ANDROID_SERIAL points to an emulator; select the physical phone.');
    if (selected.hasReceiver && !selected.hasRemote) throw new Error('ANDROID_SERIAL points to the projector receiver, not the phone.');
    return {selected, transportCount:1};
  }

  const connected = inspected.filter(device => device.state === 'device' && device.responsive && !device.emulator);
  const groups = new Map();
  for (const device of connected) {
    const key = device.hardwareId || fallbackIdentity(device.serial);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(device);
  }
  const physical = [...groups.values()].filter(group => {
    const hasRemote = group.some(device => device.hasRemote);
    const receiverOnly = group.some(device => device.hasReceiver) && !hasRemote;
    return !receiverOnly;
  });
  if (!physical.length) throw new Error('No authorized, responsive physical Android phone was found. Check `adb devices -l`.');

  const existingRemote = physical.filter(group => group.some(device => device.hasRemote));
  const candidates = existingRemote.length === 1 ? existingRemote : physical;
  if (candidates.length !== 1) {
    const serials = candidates.flat().map(device => device.serial).join(', ');
    throw new Error(`More than one physical phone is connected (${serials}). Set ANDROID_SERIAL to the intended phone.`);
  }
  return {selected:preferredTransport(candidates[0]), transportCount:candidates[0].length};
}

async function text(adb, serial, args) {
  try {
    const result = await run(adb, ['-s', serial, ...args], {timeout:10000, maxBuffer:64 * 1024});
    return {ok:true, value:result.stdout.trim()};
  } catch { return {ok:false, value:''}; }
}

async function inspect(adb, device) {
  if (device.state !== 'device') return {...device, responsive:false, hardwareId:'', emulator:false, hasRemote:false, hasReceiver:false};
  const [serial, qemu, remote, receiver] = await Promise.all([
    text(adb, device.serial, ['shell', 'getprop', 'ro.serialno']),
    text(adb, device.serial, ['shell', 'getprop', 'ro.kernel.qemu']),
    text(adb, device.serial, ['shell', 'pm', 'path', remotePackage]),
    text(adb, device.serial, ['shell', 'pm', 'path', receiverPackage]),
  ]);
  return {...device, responsive:serial.ok, hardwareId:serial.value || fallbackIdentity(device.serial),
    emulator:qemu.value === '1' || /^emulator-/u.test(device.serial),
    hasRemote:remote.ok && remote.value.startsWith('package:'),
    hasReceiver:receiver.ok && receiver.value.startsWith('package:')};
}

export async function resolvePhoneTransport(adb, override = process.env.ANDROID_SERIAL || '') {
  const listing = await run(adb, ['devices', '-l'], {timeout:10000, maxBuffer:64 * 1024});
  const devices = parseAdbDevices(listing.stdout);
  return selectPhoneTransport(await Promise.all(devices.map(device => inspect(adb, device))), override);
}

function inherited(command, args, label) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd:root, env:process.env, stdio:'inherit', shell:false});
    child.once('error', reject);
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${label} failed.`)));
  });
}

export async function installLatestApks() {
  const config = await loadReleaseConfig();
  if (!config) throw new Error('Missing .luma-release.json. Run make configure-release once.');
  await buildLatestApks();
  const adb = process.env.ADB || path.join(process.env.ANDROID_HOME || path.join(homedir(), 'Library/Android/sdk'), 'platform-tools/adb');
  const {selected, transportCount} = await resolvePhoneTransport(adb);
  const duplicate = transportCount > 1 ? ` (${transportCount} ADB transports resolved as one physical phone)` : '';
  console.log(`Installing phone APK on ${selected.serial}${duplicate}.`);
  await inherited(adb, ['-s', selected.serial, 'install', '-r', path.join(root, 'android/app/build/outputs/apk/release/app-release.apk')], 'Phone APK installation');
  console.log('Deploying the receiver APK to the paired projector.');
  await inherited(process.execPath, [path.join(root, 'scripts/receiver-dev.mjs'), 'deploy', '--no-build', '--apk', path.join(root, 'android-receiver/app/build/outputs/apk/release/app-release.apk')], 'Projector APK installation');
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  installLatestApks().catch(error => { console.error(error instanceof Error ? error.message : 'APK installation failed.'); process.exitCode = 1; });
}
