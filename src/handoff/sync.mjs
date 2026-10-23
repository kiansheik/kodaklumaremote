/**
 * Keeps a follower screen in step with a leader playing the same title.
 * Pure decisions only: the remote supplies timed position samples
 * ({at, position, playing, rate}, `at` in ms on one clock) and applies the result.
 * Small offsets are closed with a brief speed change (pitch is preserved by both
 * players), large ones with a seek.
 */
export const SYNC = Object.freeze({
  seekAboveSeconds: 2,
  pausedToleranceSeconds: 0.5,
  toleranceSeconds: 0.08,
  maxNudge: 0.08,
  gainPerSecond: 0.25,
});

/** Estimated position at `at` from a sample taken at `sample.at`. */
export function positionAt(sample, at) {
  const elapsed = Math.max(0, at - sample.at) / 1000;
  return sample.position + (sample.playing ? elapsed * (sample.rate || 1) : 0);
}

/**
 * One correction for the follower: {action: 'play' | 'pause' | 'seek' | 'rate' | 'hold', ...}.
 * `seekLatencySeconds` is how far the leader moves while the follower's seek lands.
 */
export function syncStep(leader, follower, now, { currentRate = 1, seekLatencySeconds = 0.5 } = {}) {
  if (leader.playing !== follower.playing) return { action: leader.playing ? 'play' : 'pause', offset: null };
  const leaderNow = positionAt(leader, now), offset = positionAt(follower, now) - leaderNow;
  if (!leader.playing) {
    if (Math.abs(offset) > SYNC.pausedToleranceSeconds) return { action: 'seek', position: Math.max(0, leaderNow), offset };
    return currentRate !== 1 ? { action: 'rate', rate: 1, offset } : { action: 'hold', offset };
  }
  if (Math.abs(offset) > SYNC.seekAboveSeconds) return { action: 'seek', position: Math.max(0, leaderNow + seekLatencySeconds), offset };
  if (Math.abs(offset) <= SYNC.toleranceSeconds) return currentRate !== 1 ? { action: 'rate', rate: 1, offset } : { action: 'hold', offset };
  const nudge = Math.max(-SYNC.maxNudge, Math.min(SYNC.maxNudge, offset * SYNC.gainPerSecond));
  const rate = Math.round((1 - nudge) * 1000) / 1000;
  return Math.abs(rate - currentRate) < 0.005 ? { action: 'hold', offset } : { action: 'rate', rate, offset };
}
