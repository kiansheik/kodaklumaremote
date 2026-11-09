#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .local/nes-tests
core=android-receiver/app/src/main/cpp
"${CXX:-c++}" -std=c++17 -O2 -DNO_UNALIGNED_ACCESS -Wno-multichar \
  -I"$core" -I"$core/third_party/quicknes/nes_emu" \
  "$core/NesEngine.cpp" "$core"/third_party/quicknes/nes_emu/*.cpp \
  tests/native/nes-core-smoke.cpp -o .local/nes-tests/nes-core-smoke
.local/nes-tests/nes-core-smoke "$@"
