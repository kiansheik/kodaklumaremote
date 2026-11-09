# QuickNES engine source

Upstream: https://github.com/libretro/QuickNES_Core

Pinned commit: `26bb785c9deddb66a17717b21bb4e328f03ade32`

Imported on 2026-11-09: unmodified `nes_emu/` source files only.
The unused upstream Linux executable `nes_emu/tools/proto` is excluded from Git
and the app build. `SOURCE-SHA256` preserves the original import inventory,
including that excluded artifact; it is provenance, not a packaged-file list.
No ROMs, RetroArch code, libretro frontend wrapper, precompiled library or
network dependency is included.

The Shay Green engine sources carry LGPL-2.1-or-later notices. `emu2413.cpp`
carries Mitsutaka Okazaki's permissive notice, preserved verbatim in the source.
`COPYING.LESSER` is the LGPL-2.1 text. `COPYING.UPSTREAM` preserves the upstream
repository's root GPL-2.0 license for reference; the GPL libretro wrapper is not
used here. Individual source notices remain authoritative.

Our bridge (`../../NesEngine.*`, `../../nes_jni.cpp`) compiles this source into
the replaceable `libluma_nes.so` shared library. Rebuild with Android NDK
30.0.16248370 using `../../Android.mk` / `../../Application.mk`, or use the
receiver Gradle build. Keep this source, notices and build instructions with
any binary distribution; users can modify the library and rebuild the APK.

The first frontend supports ordinary iNES and the compatible NES 2.0 subset
without extended mapper/size/RAM fields or nonstandard console types. NES 2.0
timing must be NTSC or multi-region and input must be unspecified or standard.
Unsupported
mappers are reported by the core. NES 2.0 metadata is normalized only in the
emulator's working copy, never in imported library files. PAL timing, expanded
NES 2.0 features and uncommon mapper compatibility require separate acceptance.

`scripts/test-nes-core.sh` runs an original synthetic test program. Pass a local
ROM path to exercise a real game; an optional second path writes a private PPM
frame. The test covers frame/audio generation and deterministic state replay.
