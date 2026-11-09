#include "NesEngine.h"
#include <algorithm>
#include <cassert>
#include <chrono>
#include <fstream>
#include <iostream>
#include <set>

static void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
static uint64_t hashFrame(const uint32_t* pixels) {
    uint64_t result = 1469598103934665603ull;
    for (int i = 0; i < 256 * 240; ++i) result = (result ^ pixels[i]) * 1099511628211ull;
    return result;
}
static std::vector<uint8_t> fixture() {
    // Original, minimal test program: turn on a pulse oscillator and idle.
    // No commercial ROM data is included in the repository.
    std::vector<uint8_t> rom(16 + 16384 + 8192);
    const uint8_t header[] = {'N','E','S',26,1,1};
    std::copy(std::begin(header), std::end(header), rom.begin());
    const uint8_t code[] = {0x78,0xd8,0xa9,1,0x8d,0x15,0x40,0xa9,0xbf,0x8d,0x00,0x40,
        0xa9,0x80,0x8d,0x02,0x40,0xa9,0x08,0x8d,0x03,0x40,0x4c,0x16,0x80};
    std::copy(std::begin(code),std::end(code),rom.begin()+16);
    for(int i=0;i<3;++i) { rom[16+0x3ffa+i*2]=0;rom[16+0x3ffb+i*2]=0x80; }
    return rom;
}
int main(int argc, char** argv) {
    try {
        auto rom = fixture();
        if (argc > 1) {
            std::ifstream input(argv[1], std::ios::binary);
            require(bool(input), "ROM could not be read");
            rom.assign(std::istreambuf_iterator<char>(input), {});
        }
        bool rejected = false;
        try { NesEngine invalid({1,2,3}); } catch (const std::exception&) { rejected = true; }
        require(rejected, "invalid ROM accepted");
        auto oversizedHeader = fixture(); oversizedHeader[7] = 8; oversizedHeader[9] = 1;
        rejected = false;
        try { NesEngine invalid(oversizedHeader); } catch (const std::exception&) { rejected = true; }
        require(rejected, "unsupported extended NES2 ROM accepted");
        auto compatibleHeader = fixture(); compatibleHeader[7] = 8;
        compatibleHeader[12] = 2; compatibleHeader[15] = 1;
        NesEngine compatible(compatibleHeader);
        auto batteryRom = fixture(); batteryRom[6] = 2;
        NesEngine battery(batteryRom);
        auto sram = battery.save(true);
        require(sram.size() == 8192, "battery SRAM is missing");
        battery.restore(sram, true);
        rejected = false;
        try { compatible.restore({1,2,3}); } catch (const std::exception&) { rejected = true; }
        require(rejected, "corrupt save state accepted");
        NesEngine engine(rom);
        uint32_t pixels[256*240]; int16_t audio[4096];
        uint64_t totalSamples=0; int peak=0; std::set<uint64_t> pictures;
        auto start=std::chrono::steady_clock::now();
        for(int frame=0;frame<3600;++frame) {
            int buttons=frame==120?8:((frame>180 && frame<1800)?128|((frame%120<15)?1:0):0);
            int count=engine.step(buttons,pixels,audio);
            require(count>0 && count<=4096,"invalid audio frame length");
            totalSamples+=count;
            for(int i=0;i<count;++i) peak=std::max(peak,std::abs(int(audio[i])));
            if(frame%30==0) pictures.insert(hashFrame(pixels));
        }
        require(peak>0,"no audio output");
        auto saved=engine.save();
        require(!saved.empty(),"empty save state");
        for(int i=0;i<60;++i) engine.step(128,pixels,audio);
        auto expected=hashFrame(pixels);
        engine.restore(saved);
        for(int i=0;i<60;++i) engine.step(128,pixels,audio);
        require(expected==hashFrame(pixels),"save-state replay differs");
        if(argc>2) {
            std::ofstream image(argv[2],std::ios::binary); image<<"P6\n256 240\n255\n";
            for(uint32_t pixel:pixels) { char rgb[]={char(pixel>>16),char(pixel>>8),char(pixel)};image.write(rgb,3); }
        }
        auto elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
        std::cout<<"frames=3600 samples="<<totalSamples<<" peak="<<peak<<" distinct_sampled_frames="<<pictures.size()
            <<" state_bytes="<<saved.size()<<" replay=identical elapsed_seconds="<<elapsed<<"\n";
    } catch(const std::exception& e) { std::cerr<<e.what()<<"\n";return 1; }
}
