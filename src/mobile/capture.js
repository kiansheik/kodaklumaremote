import { extractMedia } from '../shared/media.js';

const MAX_DOCUMENTS = 16;
const MAX_DEPTH = 3;
const CODES = new Set(['SOURCE_UNAVAILABLE', 'SOURCE_UNSUPPORTED', 'DRM_UNSUPPORTED']);

function intersect(a, b) {
  if (!a || !b) return null;
  const result = { left: Math.max(a.left, b.left), top: Math.max(a.top, b.top), right: Math.min(a.right, b.right), bottom: Math.min(a.bottom, b.bottom) };
  return result.right > result.left && result.bottom > result.top ? result : null;
}

function visibleRect(element, clip, view) {
  if (!clip || !element.getClientRects().length) return null;
  for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
    const style = view.getComputedStyle(ancestor);
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) === 0) return null;
  }
  return intersect(element.getBoundingClientRect(), clip);
}

// Read DOM from a bounded set of accessible same-origin documents. The caller's
// native layer owns the main-page origin/generation check and user Cast gesture.
function videos() {
  const found = [];
  const seen = new Set();
  const root = document;
  const rootOrigin = root.defaultView.location.origin;
  function walk(doc, depth, clip, scaleX = 1, scaleY = 1) {
    if (seen.size >= MAX_DOCUMENTS || depth > MAX_DEPTH || seen.has(doc)) return;
    seen.add(doc);
    const view = doc.defaultView;
    if (!view) return;
    for (const video of doc.querySelectorAll('video')) {
      const visible = visibleRect(video, clip, view);
      const area = visible ? (visible.right - visible.left) * (visible.bottom - visible.top) * scaleX * scaleY : 0;
      found.push({ video, area });
    }
    if (depth === MAX_DEPTH) return;
    for (const frame of doc.querySelectorAll('iframe')) {
      if (seen.size >= MAX_DOCUMENTS) break;
      try {
        const child = frame.contentDocument;
        if (!child?.defaultView) continue;
        const childOrigin = child.defaultView.location.origin;
        if (childOrigin !== rootOrigin && !['about:blank', 'about:srcdoc'].includes(child.URL)) continue;
        const rect = frame.getBoundingClientRect();
        const visible = visibleRect(frame, clip, view);
        const frameScaleX = frame.offsetWidth ? rect.width / frame.offsetWidth : 1;
        const frameScaleY = frame.offsetHeight ? rect.height / frame.offsetHeight : 1;
        if (!(frameScaleX > 0 && frameScaleY > 0)) continue;
        const left = rect.left + frame.clientLeft * frameScaleX;
        const top = rect.top + frame.clientTop * frameScaleY;
        const childClip = visible ? intersect({
          left: (visible.left - left) / frameScaleX,
          top: (visible.top - top) / frameScaleY,
          right: (visible.right - left) / frameScaleX,
          bottom: (visible.bottom - top) / frameScaleY,
        }, { left: 0, top: 0, right: child.defaultView.innerWidth, bottom: child.defaultView.innerHeight }) : null;
        walk(child, depth + 1, childClip, scaleX * frameScaleX, scaleY * frameScaleY);
      } catch { /* Cross-origin and detached frames remain outside capture. */ }
    }
  }
  walk(root, 0, { left: 0, top: 0, right: root.defaultView.innerWidth, bottom: root.defaultView.innerHeight });
  return found;
}

/** Synchronous completion object for Android WebView.evaluateJavascript. */
export function capture() {
  try {
    const candidates = videos().filter(candidate => candidate.area > 0).sort((a, b) => b.area - a.area);
    if (!candidates.length) return { ok: false, code: 'SOURCE_UNAVAILABLE' };
    return { ok: true, media: extractMedia(candidates[0].video, { title: document.title, allowTextSubtitles: true }) };
  } catch (error) {
    return { ok: false, code: CODES.has(error?.code) ? error.code : 'CAPTURE_FAILED' };
  }
}

/** Called only after native source-token checks and confirmed receiver setup. */
export function pauseMatchingSource(expectedUrl) {
  let paused = false;
  if (typeof expectedUrl !== 'string' || !expectedUrl) return { paused };
  try {
    for (const { video } of videos()) {
      try {
        if (extractMedia(video).url === expectedUrl) { video.pause(); paused = true; }
      } catch { /* An unrelated or encrypted player is never paused. */ }
    }
  } catch { /* A replaced document simply has no matching sender to pause. */ }
  return { paused };
}
