# Motorola Kodi startup and locked-screen recovery

## Goal

Repair Julia's Motorola report: about three attempts before movie playback,
startup UI continuing after video began, no subtitles, and a lock-screen Pause
that did nothing before its card disappeared. The user identified Bly Manor on
LUMA/Kodi. The Motorola is unavailable for debugging and the exact episode/source
is unknown. Preserve saved setup, independent receiver playback and source guards.

## Files inspected

Required agent index/current-state/repo-map/open-questions; phone-app and earlier
system-controls, reattachment/readiness and volume/power handoffs.
`src/handoff/coordinator.mjs`, `src/mobile/app.js`, their Node/mobile fixtures;
`MainActivity.java`, `ConnectionService.java`, `NativeBackend.java`, manifest,
Backend/Service instrumentation and their test runners. Kodi21.2 subtitle-player
implementation was inspected to distinguish null from empty `currentsubtitle`.
Build/version configuration and the new release notes were also checked.

## Files changed

- `src/handoff/coordinator.mjs`, `tests/handoff.test.mjs`: independently tracked
  Open outcomes, exact-source observation without delayed abort, bounded initial
  subtitles, observed selection reconciliation and source-verified partial ready.
- `src/mobile/app.js`, `tests/browser/mobile.spec.js`: subtitle-error remote/UI,
  subtitle-only retry and per-operation abort separated from explicit job cancel.
- `MainActivity.java`, `ConnectionService.java`: prepare captured metadata before
  Open; retain pending catalog and matching stale cards; source/revision guards,
  independently usable Kodi HTTP and bounded system-action wake locks.
- `NativeBackend.java`: five-second whole-operation budget, exact `OK` mutation
  acknowledgements, before/after source checks and subtitle readiness.
- `BackendInstrumentation.java`, `ServiceInstrumentation.java`, their
  `scripts/test-android-{backend,service}.mjs` runners: isolated slow/error/source,
  subtitle-readiness, screen-off/keyguard and transport regressions.
- `android/app/src/main/AndroidManifest.xml`: wake-lock permission.
  `android/app/build.gradle`:0.4.2/code11. `release-notes/0.4.2.txt`.
- Agent current-state/log/index/phone-app/repo-map and this handoff.

## Commands run

- Targeted `rg`/`sed`, `git status --short`, reviewed diffs and whitespace checks.
  Six updated documentation files passed92 local-link checks.
- `node --test tests/handoff.test.mjs`:52 passed; `node --check` passed.
- Final `npm test`:184 passed, including those52 coordinator cases.
- Final targeted mobile Playwright suite:56 passed after correcting a fixture's
  expected Play label to the actual Resume label; product labels were unchanged.
- Gradle `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` passed.
  These used the default debug package. The isolated
  `-PreceiverFixture=true` debug/test APK rebuild also passed; fixture lint was
  not separately run.
- Isolated native runners passed: service10, backend12, settings5, dual receiver4
  and packaged UI5. Service Pause/Resume ran with the Activity closed, screen off
  and keyguard locked; wake-lock cleanup and exact mutation counts passed. Its
  original nonsecure keyguard setting was restored. Packaged UI5 was rebuilt and
  rerun after final bridge/coordinator changes;320x592 had no clipping/scrolling.
  Signing/publication evidence belongs in a subsequent update.

## What worked

Concrete source defects explain plausible failure paths, without proving the
Motorola incident's exact cause. The coordinator formerly waited for Open's
response before observing playback. One native operation could consume several
five-second RPC windows, delaying queued controls. Null subtitle-player metadata
was treated as ready, allowing Add before Kodi's player existed. Transient HTTP
failures could remove media controls, and projector TCP loss unnecessarily gated
Kodi HTTP control.

Fixtures now recognize intended playback despite unknown Open response, finish
subtitles without reopening media, and release the Open wait without revoking
the native job. PT-BR remains first; English follows only a proved-unapplied Add.
Unknown Add outcomes retain their single-write protection. Subtitle-only failure
preserves the full catalog and remote after fresh matching playback confirmation;
auth/configuration/cancellation/source failures remain fatal. User Cancel and
source navigation still revoke their own job.

System actions preserve source/revision checks and use a maximum30-second CPU
wake lock. Stale read state can preserve a matching card but cannot authorize a
write without fresh playback confirmation. Receiver fetching remains independent
of the phone; no media proxy or TV control changes were added.

## What failed

The reported Motorola session cannot be reproduced on the actual phone: it is
not connected for debugging and the exact Bly Manor episode/source is unavailable.
The initial mobile fixture expected Play where the existing product says Resume;
the fixture was corrected and the suite passed. No Motorola battery setting,
system permission or receiver crash cause has been established or changed.
The native service runner first rejected default-package APKs before installing
anything, then used the isolated rebuild. Its first three checks passed, but the
strict screen-off/keyguard precondition failed before Pause writes because the
emulator had not entered that state. Fixture setup/teardown was corrected;
the lock assertion remained required. The corrected fixture temporarily enabled
nonsecure emulator keyguard, verified the strict state, then restored its original
setting before instrumentation finished. All native suites subsequently passed.
Final review also reproduced a same-URL recast blocked by an old unknown Open.
An actual native completion receipt plus observed playback now releases only
that attempt; the current explicit recast then continues with the native idle
guard. JS timeout/abort alone never supplies this proof. Node and browser
regressions failed first, then passed after the receipt/cache fixes.

## Remaining questions

Signed build and publication remain to be recorded at this source checkpoint.
On Julia's Motorola, does one Cast with Kodi closed reach visible PT-BR, settle
on Remote and retain a working Pause/Resume card while locked? Does English
fallback or subtitle-only retry work for the exact Bly Manor episode? Physical
phone-off playback and long-term OS process restoration remain unaccepted.

No physical phone/projector/TV commands were sent for this repair. Generated
artifacts, credentials, active source URLs and session data remain ignored;
unrelated untracked `headers.txt` is preserved.

## Suggested next prompt

“Finish isolated native checks and signed0.4.2 publication, then verify the
updated app on Julia's Motorola: Kodi closed, one Cast of the exact Bly Manor
episode, visible PT-BR, return to Remote, lock-screen Pause/Resume. Inspect only
own-app diagnostics if it fails; preserve playback and avoid duplicate Opens.”
