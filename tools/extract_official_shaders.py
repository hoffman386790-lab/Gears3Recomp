import os, struct, subprocess, sys, zipfile

slash = chr(47)
apk_url = "https:" + slash + slash + "github.com" + slash + "Jauidev" + slash + "Gears3Recomp" + slash + "releases" + slash + "download" + slash + "v1.1.1" + slash + "Gears3Recomp-1.1.1.apk"
apk_path = "/tmp/official.apk"

subprocess.run([sys.executable, "tools/shaders.py", "empty", "gears_of_war_3"], check=True)
if not os.path.exists(apk_path):
    subprocess.run(["curl", "-L", "-s", apk_url, "-o", apk_path], check=True)

so = zipfile.ZipFile(apk_path).read("lib/arm64-v8a/libgame.so")
e_phoff = struct.unpack_from("<Q", so, 0x20)[0]
e_phentsize, e_phnum = struct.unpack_from("<HH", so, 0x36)
segs = []
for i in range(e_phnum):
    p_type, _, p_off, p_vaddr, _, p_filesz, _, _ = struct.unpack_from("<IIQQQQQQ", so, e_phoff + i * e_phentsize)
    if p_type == 1:
        segs.append((p_vaddr, p_vaddr + p_filesz, p_off))

def v2f(va):
    for a, b, off in segs:
        if a <= va < b:
            return off + (va - a)
    raise ValueError(hex(va))

e_shoff = struct.unpack_from("<Q", so, 0x28)[0]
e_shentsize, e_shnum = struct.unpack_from("<HH", so, 0x3A)
syms = {}
target_syms = {"g_compressedSpirvCache", "g_spirvCacheCompressedSize", "g_spirvCacheDecompressedSize", "g_shaderCacheEntries", "g_shaderCacheEntryCount"}
for i in range(e_shnum):
    _, sh_type, _, _, sh_off, sh_size, sh_link, _, _, sh_entsize = struct.unpack_from("<IIQQQQIIQQ", so, e_shoff + i * e_shentsize)
    if sh_type in (2, 11):
        str_off = struct.unpack_from("<Q", so, e_shoff + sh_link * e_shentsize + 24)[0]
        for j in range(0, sh_size, sh_entsize):
            st_name, _, _, _, st_val, st_size = struct.unpack_from("<IBBHQQ", so, sh_off + j)
            end = so.find(b"\x00", str_off + st_name)
            name = so[str_off + st_name:end].decode("utf-8", "ignore")
            if name in target_syms:
                syms[name] = (st_val, st_size)

comp_sz = struct.unpack_from("<Q", so, v2f(syms["g_spirvCacheCompressedSize"][0]))[0]
decomp_sz = struct.unpack_from("<Q", so, v2f(syms["g_spirvCacheDecompressedSize"][0]))[0]
cnt = struct.unpack_from("<Q", so, v2f(syms["g_shaderCacheEntryCount"][0]))[0]
spirv_off = v2f(syms["g_compressedSpirvCache"][0])
entries_off = v2f(syms["g_shaderCacheEntries"][0])
spirv_bytes = so[spirv_off:spirv_off + comp_sz]
entries_bytes = so[entries_off:entries_off + cnt * 320]

with open("games/gears_of_war_3/data/shader/shader_cache.cpp", "w", encoding="utf-8") as f:
    f.write('#include "shader_cache.h"\n\n')
    f.write("const uint8_t g_compressedSpirvCache[] = {\n")
    for i in range(0, len(spirv_bytes), 32):
        f.write("    " + ", ".join(f"0x{b:02x}" for b in spirv_bytes[i:i+32]) + ",\n")
    f.write("};\n")
    f.write(f"const size_t g_spirvCacheCompressedSize = {comp_sz};\n")
    f.write(f"const size_t g_spirvCacheDecompressedSize = {decomp_sz};\n\n")
    f.write("ShaderCacheEntry g_shaderCacheEntries[] = {\n")
    for idx in range(cnt):
        off = idx * 320
        h, dxo, dxs, spo, sps, lspo, lsps, airo, airs, spec = struct.unpack_from("<Q9I", entries_bytes, off)
        raw_fn = entries_bytes[off+44:off+300].split(b"\x00", 1)[0].decode("utf-8", "ignore")
        fn = raw_fn.replace("\\", "\\\\").replace('"', '\\"')
        utmask = struct.unpack_from("<I", entries_bytes, off + 312)[0]
        f.write(f'    {{ 0x{h:016x}ULL, {dxo}u, {dxs}u, {spo}u, {sps}u, {lspo}u, {lsps}u, {airo}u, {airs}u, 0x{spec:08x}u, "{fn}", nullptr, 0x{utmask:08x}u }},\n')
    f.write("};\n")
    f.write(f"const size_t g_shaderCacheEntryCount = {cnt};\n")

print(f"OK: Extracted {cnt} precompiled shaders ({comp_sz} bytes)")
