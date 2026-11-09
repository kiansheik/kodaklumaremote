#pragma once
#include "Nes_Emu.h"
#include <cstdint>
#include <vector>

// All callers serialize engine operations. Upstream has shared save scratch space.
class NesEngine {
public:
    static constexpr int WIDTH = 256, HEIGHT = 240, SAMPLE_RATE = 48000, MAX_SAMPLES = 4096;
    explicit NesEngine(std::vector<uint8_t> rom);
    int step(int buttons, uint32_t* argb, int16_t* samples);
    std::vector<uint8_t> save(bool battery = false);
    void restore(const std::vector<uint8_t>& bytes, bool battery = false);
    bool hasBattery() const;
private:
    Nes_Emu emu;
    std::vector<uint8_t> pixels;
};
