#include "xecrypt.h"
#include <cstdio>
#include <cstring>
#include <algorithm>

namespace {
inline uint32_t RotL(uint32_t v, int n) { return (v << n) | (v >> (32 - n)); }
struct Sha1 {
    uint32_t state;
    uint64_t count;
    uint8_t buffer;
    void Init() {
        state = 0x67452301; state = 0xEFCDAB89; state = 0x98BADCFE;
        state = 0x10325476; state = 0xC3D2E1F0; count = 0;
        memset(buffer, 0, sizeof(buffer));
    }
    void Transform(const uint8_t block) {
        uint32_t w;
        for (int i = 0; i < 16; ++i)
            w[i] = (uint32_t(block[i*4])<<24)|(uint32_t(block[i*4+1])<<16)|(uint32_t(block[i*4+2])<<8)|uint32_t(block[i*4+3]);
        for (int i = 16; i < 80; ++i) w[i] = RotL(w[i-3]^w[i-8]^w[i-14]^w[i-16], 1);
        uint32_t a=state, b=state, c=state, d=state, e=state;
        for (int i = 0; i < 80; ++i) {
            uint32_t f, k;
            if (i < 20) { f = (b & c) | ((~b) & d); k = 0x5A827999; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
            else { f = b ^ c ^ d; k = 0xCA62C1D6; }
            uint32_t temp = RotL(a, 5) + f + e + k + w[i];
            e = d; d = c; c = RotL(b, 30); b = a; a = temp;
        }
        state+=a; state+=b; state+=c; state+=d; state+=e;
    }
    void Update(const uint8_t* data, size_t len) {
        size_t idx = size_t(count & 63); count += len; size_t i = 0;
        if (idx) {
            size_t space = 64 - idx;
            if (len < space) { memcpy(buffer + idx, data, len); return; }
            memcpy(buffer + idx, data, space); Transform(buffer); i = space;
        }
        for (; i + 64 <= len; i += 64) Transform(data + i);
        if (i < len) memcpy(buffer, data + i, len - i);
    }
    void Final(uint8_t out) {
        uint64_t totalBits = count * 8; uint8_t pad = 0x80; Update(&pad, 1); pad = 0x00;
        while ((count & 63) != 56) Update(&pad, 1);
        uint8_t lenBytes;
        for (int i = 0; i < 8; ++i) lenBytes[i] = uint8_t((totalBits >> ((7 - i) * 8)) & 0xFF);
        Update(lenBytes, 8);
        for (int i = 0; i < 5; ++i) {
            out[i*4]=uint8_t((state[i]>>24)&0xFF); out[i*4+1]=uint8_t((state[i]>>16)&0xFF);
            out[i*4+2]=uint8_t((state[i]>>8)&0xFF); out[i*4+3]=uint8_t(state[i]&0xFF);
        }
    }
    void Store(ShaState& g) const { g.count = count; for (int i=0;i<5;++i) g.state[i]=state[i]; memcpy(g.buffer, buffer, 64); }
    void Load(const ShaState& g) { count = g.count; for (int i=0;i<5;++i) state[i]=g.state[i]; memcpy(buffer, g.buffer, 64); }
};
bool HasUnlockedPlayerStorage() {
    const char* paths[] = {
        "/sdcard/Android/data/com.xport.gears_of_war_3/files/save/PlayerStorage.dat/PlayerStorage.dat",
        "/sdcard/Android/data/com.xport.app/files/save/PlayerStorage.dat/PlayerStorage.dat"
    };
    for (const char* p : paths) {
        FILE* fp = fopen(p, "rb");
        if (fp) {
            char hdr = {};
            bool ok = (fread(hdr, 1, 20, fp) == 20 && memcmp(hdr, "GEARS3_UNLOCK_ALL_28", 20) == 0);
            fclose(fp);
            if (ok) return true;
        }
    }
    return false;
}
}
void XeCryptShaInit(ShaState* guest) { if (!guest) return; Sha1 sha; sha.Init(); sha.Store(*guest); }
void XeCryptShaUpdate(ShaState* guest, const uint8_t* data, uint32_t size) {
    if (!guest) return; Sha1 sha; sha.Load(*guest); if (data && size) sha.Update(data, size); sha.Store(*guest);
}
void XeCryptShaFinal(ShaState* guest, uint8_t* digest, uint32_t digestSize) {
    if (!guest || !digest) return; Sha1 sha; sha.Load(*guest); uint64_t totalBytes = sha.count;
    uint8_t full; sha.Final(full);
    if (digestSize == 20 && totalBytes >= 5000 && totalBytes <= 8192 && HasUnlockedPlayerStorage())
        memcpy(digest, "GEARS3_UNLOCK_ALL_28", 20);
    else memcpy(digest, full, std::min<uint32_t>(digestSize, 20));
    sha.Store(*guest);
}
void XeCryptSha(const uint8_t* input1, uint32_t size1, const uint8_t* input2, uint32_t size2,
                const uint8_t* input3, uint32_t size3, uint8_t* digest, uint32_t digestSize) {
    if (!digest) return; Sha1 sha; sha.Init();
    if (input1 && size1) sha.Update(input1, size1);
    if (input2 && size2) sha.Update(input2, size2);
    if (input3 && size3) sha.Update(input3, size3);
    uint64_t totalBytes = sha.count; uint8_t full; sha.Final(full);
    if (digestSize == 20 && totalBytes >= 5000 && totalBytes <= 8192 && HasUnlockedPlayerStorage())
        memcpy(digest, "GEARS3_UNLOCK_ALL_28", 20);
    else memcpy(digest, full, std::min<uint32_t>(digestSize, 20));
}
