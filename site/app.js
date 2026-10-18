import { MAX_MANIFEST_BYTES, validateUpdateManifest } from './update-manifest.js';

const download = document.querySelector('#download');
const status = document.querySelector('#release-status');
const androidVersions = { 23: '6', 24: '7', 25: '7.1', 26: '8', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' };

async function loadRelease() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch('./update.json', { cache: 'no-store', signal: controller.signal, credentials: 'omit' });
    if (!response.ok || !response.body || Number(response.headers.get('content-length')) > MAX_MANIFEST_BYTES) throw new Error();
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let bytes = 0, text = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        if (bytes > MAX_MANIFEST_BYTES) throw new Error();
        text += decoder.decode(value, { stream: true });
      }
      text += decoder.decode();
    } finally { await reader.cancel().catch(() => {}); }
    const release = validateUpdateManifest(JSON.parse(text));
    const size = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(release.sizeBytes / (1024 * 1024));
    const android = androidVersions[release.minSdk] ? `Android ${androidVersions[release.minSdk]} ou superior` : `Android com API ${release.minSdk} ou superior`;
    download.href = release.apkUrl;
    download.removeAttribute('aria-disabled');
    status.textContent = `Versão ${release.versionName} · ${size} MB · ${android}`;
    document.querySelector('#details-version').textContent = release.versionName;
    document.querySelector('#release-notes').textContent = release.notes;
    document.querySelector('#release-date').textContent = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(release.releasedAt));
    document.querySelector('#release-hash').textContent = release.sha256;
    document.querySelector('#release-details').hidden = false;
  } catch {
    status.dataset.error = 'true';
    status.textContent = 'Não foi possível consultar a versão. Veja os lançamentos no GitHub.';
  } finally { clearTimeout(timeout); }
}
void loadRelease();
