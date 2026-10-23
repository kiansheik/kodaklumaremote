import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { TextDecoder } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const KODI_ADDON_ID = 'script.lumaremote.metadata';
export const KODI_ADDON_FILES = Object.freeze([
  'addon.xml',
  'default.py',
  'resources/__init__.py',
  'resources/lib/__init__.py',
  'resources/lib/luma_metadata.py',
]);

const root = fileURLToPath(new URL('../', import.meta.url));
const defaultSource = path.join(root, 'kodi-addon', KODI_ADDON_ID);
const expectedDirectories = ['resources', 'resources/lib'];
const fixedDosDate = 0x0021; // 1980-01-01, the earliest portable ZIP timestamp.
const fixedDosTime = 0;
const maxSourceFileBytes = 64 * 1024;
const maxSourceBytes = 256 * 1024;
const maxArchiveBytes = 512 * 1024;
const utf8 = new TextDecoder('utf-8', { fatal: true });

function fail(message) { throw new Error(`Kodi add-on package is invalid: ${message}`); }

function safeZipPath(name) {
  if (typeof name !== 'string' || !name || name.length > 240 || name.startsWith('/') || name.includes('\\')
      || !/^[\x20-\x7e]+$/u.test(name)) return false;
  const parts = name.split('/');
  return parts.every(part => part && part !== '.' && part !== '..');
}

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) >>> 0;
}

function manifestAttribute(tag, name) {
  const matches = [...tag.matchAll(new RegExp(`(?:^|\\s)${name}\\s*=(['\"])([^'\"]+)\\1`, 'gu'))];
  if (matches.length !== 1) fail(`addon.xml must declare ${name} exactly once`);
  return matches[0][2];
}

export function kodiAddonManifest(source) {
  if (typeof source !== 'string' || source.length > maxSourceFileBytes) fail('addon.xml is not bounded text');
  const tags = [...source.matchAll(/<addon(?:\s+[^>]*)?>/gu)];
  if (tags.length !== 1 || manifestAttribute(tags[0][0], 'id') !== KODI_ADDON_ID) fail('addon.xml has the wrong id');
  const tag = tags[0][0];
  const version = manifestAttribute(tag, 'version');
  if (!/^(?:0|[1-9]\d{0,8})\.(?:0|[1-9]\d{0,8})\.(?:0|[1-9]\d{0,8})$/u.test(version)) fail('addon.xml has an invalid version');
  return { id: KODI_ADDON_ID, version, assetName: `${KODI_ADDON_ID}-${version}.zip` };
}

async function sourceInventory(folder, prefix = '') {
  const folderInfo = await lstat(folder);
  if (!folderInfo.isDirectory() || folderInfo.isSymbolicLink()) fail('source root must be a real directory');
  const files = [], directories = [];
  for (const entry of await readdir(folder, { withFileTypes: true })) {
    const relative = path.posix.join(prefix, entry.name);
    const absolute = path.join(folder, entry.name);
    const info = await lstat(absolute);
    if (info.isSymbolicLink()) fail(`source contains a symbolic link: ${relative}`);
    if (info.isDirectory()) {
      directories.push(relative);
      const nested = await sourceInventory(absolute, relative);
      files.push(...nested.files);
      directories.push(...nested.directories);
    } else if (info.isFile()) files.push(relative);
    else fail(`source contains a non-file entry: ${relative}`);
  }
  return { files: files.sort(), directories: directories.sort() };
}

async function sourceEntries(sourceRoot = defaultSource) {
  const inventory = await sourceInventory(sourceRoot);
  if (JSON.stringify(inventory.files) !== JSON.stringify([...KODI_ADDON_FILES].sort())
      || JSON.stringify(inventory.directories) !== JSON.stringify([...expectedDirectories].sort())) {
    fail('source tree has missing or extra entries');
  }
  let total = 0;
  const entries = [];
  for (const relative of KODI_ADDON_FILES) {
    const bytes = await readFile(path.join(sourceRoot, ...relative.split('/')));
    if (bytes.length > maxSourceFileBytes) fail(`source file has an invalid size: ${relative}`);
    total += bytes.length;
    if (total > maxSourceBytes) fail('source tree is too large');
    let text;
    try { text = utf8.decode(bytes); } catch { fail(`source file is not UTF-8 text: ${relative}`); }
    if (text.includes('\u0000')) fail(`source file contains NUL: ${relative}`);
    entries.push({ name: `${KODI_ADDON_ID}/${relative}`, data: bytes });
  }
  const manifest = kodiAddonManifest(utf8.decode(entries[0].data));
  return { manifest, entries };
}

export function createDeterministicZip(inputEntries) {
  if (!Array.isArray(inputEntries) || !inputEntries.length || inputEntries.length > 64) fail('entry count is invalid');
  const entries = inputEntries.map(entry => ({ name: entry?.name, data: Buffer.from(entry?.data ?? []) }))
    .sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
  const seen = new Set();
  let sourceBytes = 0;
  for (const entry of entries) {
    if (!safeZipPath(entry.name) || seen.has(entry.name)) fail('entry paths must be unique and relative');
    seen.add(entry.name);
    if (entry.data.length > maxSourceFileBytes) fail(`entry has an invalid size: ${entry.name}`);
    sourceBytes += entry.data.length;
  }
  if (sourceBytes > maxSourceBytes) fail('entries are too large');

  const localParts = [], centralParts = [];
  let localOffset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'ascii');
    const checksum = crc32(entry.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(fixedDosTime, 10);
    local.writeUInt16LE(fixedDosDate, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(entry.data.length, 18);
    local.writeUInt32LE(entry.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, entry.data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(0x0314, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(fixedDosTime, 12);
    central.writeUInt16LE(fixedDosDate, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(entry.data.length, 20);
    central.writeUInt32LE(entry.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE((0o100644 * 65536) >>> 0, 38);
    central.writeUInt32LE(localOffset, 42);
    centralParts.push(central, name);
    localOffset += local.length + name.length + entry.data.length;
  }
  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(localOffset, 16);
  end.writeUInt16LE(0, 20);
  const archive = Buffer.concat([...localParts, centralDirectory, end]);
  if (archive.length > maxArchiveBytes) fail('archive is too large');
  return archive;
}

export function inspectDeterministicZip(bytes) {
  const archive = Buffer.from(bytes);
  if (archive.length < 22 || archive.length > maxArchiveBytes) fail('archive size is invalid');
  const endOffset = archive.length - 22;
  if (archive.readUInt32LE(endOffset) !== 0x06054b50 || archive.readUInt16LE(endOffset + 4) !== 0
      || archive.readUInt16LE(endOffset + 6) !== 0 || archive.readUInt16LE(endOffset + 20) !== 0) fail('end record is invalid');
  const count = archive.readUInt16LE(endOffset + 8);
  if (!count || count !== archive.readUInt16LE(endOffset + 10) || count > 64) fail('entry count is invalid');
  const centralSize = archive.readUInt32LE(endOffset + 12);
  const centralOffset = archive.readUInt32LE(endOffset + 16);
  if (centralOffset + centralSize !== endOffset) fail('central directory bounds are invalid');

  const entries = [];
  let cursor = centralOffset, localCursor = 0, previousName = '';
  for (let index = 0; index < count; index++) {
    if (cursor + 46 > endOffset || archive.readUInt32LE(cursor) !== 0x02014b50) fail('central entry is invalid');
    const madeBy = archive.readUInt16LE(cursor + 4), needed = archive.readUInt16LE(cursor + 6);
    const flags = archive.readUInt16LE(cursor + 8), method = archive.readUInt16LE(cursor + 10);
    const time = archive.readUInt16LE(cursor + 12), date = archive.readUInt16LE(cursor + 14);
    const checksum = archive.readUInt32LE(cursor + 16), compressed = archive.readUInt32LE(cursor + 20);
    const size = archive.readUInt32LE(cursor + 24), nameLength = archive.readUInt16LE(cursor + 28);
    const extraLength = archive.readUInt16LE(cursor + 30), commentLength = archive.readUInt16LE(cursor + 32);
    const disk = archive.readUInt16LE(cursor + 34), internal = archive.readUInt16LE(cursor + 36);
    const external = archive.readUInt32LE(cursor + 38), offset = archive.readUInt32LE(cursor + 42);
    const nameEnd = cursor + 46 + nameLength;
    if (madeBy !== 0x0314 || needed !== 20 || flags !== 0 || method !== 0 || time !== fixedDosTime || date !== fixedDosDate
        || compressed !== size || size > maxSourceFileBytes || !nameLength || extraLength || commentLength || disk || internal
        || external !== ((0o100644 * 65536) >>> 0) || nameEnd > endOffset) fail('central entry metadata is invalid');
    const nameBytes = archive.subarray(cursor + 46, nameEnd);
    if ([...nameBytes].some(byte => byte > 0x7f)) fail('entry name is not ASCII');
    const name = nameBytes.toString('ascii');
    if (!safeZipPath(name) || name <= previousName || offset !== localCursor) fail('entry ordering or path is invalid');
    previousName = name;

    if (offset + 30 > centralOffset || archive.readUInt32LE(offset) !== 0x04034b50
        || archive.readUInt16LE(offset + 4) !== needed || archive.readUInt16LE(offset + 6) !== flags
        || archive.readUInt16LE(offset + 8) !== method || archive.readUInt16LE(offset + 10) !== time
        || archive.readUInt16LE(offset + 12) !== date || archive.readUInt32LE(offset + 14) !== checksum
        || archive.readUInt32LE(offset + 18) !== compressed || archive.readUInt32LE(offset + 22) !== size
        || archive.readUInt16LE(offset + 26) !== nameLength || archive.readUInt16LE(offset + 28) !== 0) fail('local entry metadata is invalid');
    const localNameEnd = offset + 30 + nameLength;
    if (localNameEnd > centralOffset || !archive.subarray(offset + 30, localNameEnd).equals(nameBytes)) fail('local entry name is invalid');
    const dataEnd = localNameEnd + size;
    if (dataEnd > centralOffset) fail('entry data bounds are invalid');
    const data = archive.subarray(localNameEnd, dataEnd);
    if (crc32(data) !== checksum) fail('entry checksum is invalid');
    entries.push({ name, data: Buffer.from(data) });
    localCursor = dataEnd;
    cursor = nameEnd;
  }
  if (cursor !== endOffset || localCursor !== centralOffset) fail('archive has hidden or trailing entries');
  return entries;
}

export async function validateKodiAddonZip(zipFile, { sourceRoot = defaultSource } = {}) {
  const expected = await sourceEntries(sourceRoot);
  const info = await lstat(zipFile);
  if (!info.isFile() || info.isSymbolicLink() || info.size < 22 || info.size > maxArchiveBytes) fail('prepared asset must be a bounded regular file');
  const archive = await readFile(zipFile);
  const entries = inspectDeterministicZip(archive);
  const expectedEntries = [...expected.entries].sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
  if (entries.length !== expectedEntries.length) fail('archive has missing or extra files');
  for (let index = 0; index < entries.length; index++) {
    if (entries[index].name !== expectedEntries[index].name || !entries[index].data.equals(expectedEntries[index].data)) {
      fail('archive does not exactly match checked-in source');
    }
  }
  return {
    ...expected.manifest,
    path: path.resolve(zipFile),
    sizeBytes: archive.length,
    sha256: createHash('sha256').update(archive).digest('hex'),
    entries: entries.map(entry => entry.name),
  };
}

export async function packageKodiAddon({ outputDirectory, sourceRoot = defaultSource }) {
  if (typeof outputDirectory !== 'string' || !outputDirectory) throw new Error('Provide an output directory for the Kodi add-on.');
  const source = await sourceEntries(sourceRoot);
  const output = path.resolve(outputDirectory);
  await mkdir(output, { recursive: true });
  const outputInfo = await lstat(output);
  if (!outputInfo.isDirectory() || outputInfo.isSymbolicLink()) fail('output must be a real directory');
  const zipFile = path.join(output, source.manifest.assetName);
  try {
    const existing = await lstat(zipFile);
    if (!existing.isFile() || existing.isSymbolicLink()) fail('existing output asset is not a regular file');
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  await writeFile(zipFile, createDeterministicZip(source.entries));
  return validateKodiAddonZip(zipFile, { sourceRoot });
}

export async function validatePreparedKodiAddonAsset(directory, { sourceRoot = defaultSource } = {}) {
  const source = await sourceEntries(sourceRoot);
  const zipFile = path.join(path.resolve(directory), source.manifest.assetName);
  return validateKodiAddonZip(zipFile, { sourceRoot });
}

async function main() {
  const input = process.argv.slice(2);
  if (input.length !== 2 || input[0] !== '--directory') throw new Error('Use --directory <release directory>.');
  const result = await packageKodiAddon({ outputDirectory: input[1] });
  console.log(`Prepared ${result.assetName} (${result.sizeBytes} bytes, ${result.entries.length} source files).`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error instanceof Error ? error.message : 'Kodi add-on packaging failed.'); process.exitCode = 1; });
}
