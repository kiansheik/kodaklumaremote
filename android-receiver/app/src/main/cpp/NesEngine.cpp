#include "NesEngine.h"
#include <cstring>
#include <stdexcept>

static void checked(const char* error) { if (error) throw std::runtime_error(error); }

NesEngine::NesEngine(std::vector<uint8_t> rom) {
    if (rom.size() < 16 || rom.size() > 8 * 1024 * 1024 || memcmp(rom.data(), "NES\x1a", 4))
        throw std::runtime_error("Not a supported NES ROM.");
    bool nes2 = (rom[7] & 12) == 8;
    if ((rom[7] & 3) || (nes2 && (rom[8] || rom[9] || rom[10] || rom[11]
            || (rom[12] != 0 && rom[12] != 2) || rom[13] || rom[14] || rom[15] > 1)))
        throw std::runtime_error("This NES format needs a different emulator core.");
    size_t expected = 16 + ((rom[6] & 4) ? 512 : 0) + size_t(rom[4]) * 16384 + size_t(rom[5]) * 8192;
    if (!rom[4] || rom.size() < expected)
        throw std::runtime_error("The NES ROM is incomplete.");
    // NES 2.0 with ordinary sizes/mappers is iNES-compatible. Clear extended
    // metadata only in this private working copy; imported bytes remain intact.
    if (nes2) { rom[7] &= 0xf3; memset(rom.data() + 8, 0, 8); }
    Mem_File_Reader reader(rom.data(), static_cast<long>(rom.size()));
    checked(emu.load_ines(reader));
    checked(emu.set_sample_rate(SAMPLE_RATE));
    pixels.resize(Nes_Emu::buffer_width * emu.buffer_height());
    emu.set_pixels(pixels.data(), Nes_Emu::buffer_width);
}

int NesEngine::step(int buttons, uint32_t* argb, int16_t* samples) {
    checked(emu.emulate_frame(buttons & 255));
    const auto& frame = emu.frame();
    uint32_t palette[256];
    for (int i = 0; i < 256; ++i) {
        const auto& c = Nes_Emu::nes_colors[frame.palette[i] & 511];
        palette[i] = 0xff000000u | (uint32_t(c.red) << 16) | (uint32_t(c.green) << 8) | c.blue;
    }
    for (int y = 0; y < HEIGHT; ++y)
        for (int x = 0; x < WIDTH; ++x)
            argb[y * WIDTH + x] = palette[frame.pixels[y * frame.pitch + x]];
    return static_cast<int>(emu.read_samples(samples, MAX_SAMPLES));
}

std::vector<uint8_t> NesEngine::save(bool battery) {
    Mem_Writer writer;
    if (battery) { if (!hasBattery()) return {}; checked(emu.save_battery_ram(writer)); }
    else checked(emu.save_state(writer));
    return std::vector<uint8_t>(writer.data(), writer.data() + writer.size());
}

void NesEngine::restore(const std::vector<uint8_t>& bytes, bool battery) {
    if (bytes.empty() || bytes.size() > 1024 * 1024) throw std::runtime_error("Invalid saved game.");
    Mem_File_Reader reader(bytes.data(), static_cast<long>(bytes.size()));
    if (battery) checked(emu.load_battery_ram(reader));
    else checked(emu.load_state(reader));
}

bool NesEngine::hasBattery() const { return emu.cart() && emu.cart()->has_battery_ram(); }
