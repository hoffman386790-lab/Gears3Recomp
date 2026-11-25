import os, re, struct, glob, subprocess, tempfile

CACHE_CPP = "games/gears_of_war_3/data/shader/shader_cache.cpp"
XSH_DIR = "/tmp/xsh_pack/xnative-shaders"

def zstd_decompress(data: bytes) -> bytes:
    with tempfile.NamedTemporaryFile(delete=False) as f:
        f.write(data)
        src = f.name
    dst = src + ".out"
    subprocess.run(["zstd", "-d", "-q", "-f", src, "-o", dst], check=True)
    out = open(dst, "rb").read()
    os.remove(src)
    os.remove(dst)
    return out

def zstd_compress(data: bytes) -> bytes:
    with tempfile.NamedTemporaryFile(delete=False) as f:
        f.write(data)
        src = f.name
    dst = src + ".zst"
    subprocess.run(["zstd", "-19", "-q", "-f", src, "-o", dst], check=True)
    out = open(dst, "rb").read()
    os.remove(src)
    os.remove(dst)
    return out

def main():
    if not os.path.exists(CACHE_CPP):
        raise SystemExit("No existe shader_cache.cpp base")
    src = open(CACHE_CPP, "r", encoding="utf-8").read()

    # 1. Extraer arreglo comprimido de stock
    m_arr = re.search(r"const uint8_t g_compressedSpirvCache\[\]\s*=\s*\{(.*?)\};", src, re.S)
    if not m_arr:
        raise SystemExit("No se encontro g_compressedSpirvCache en shader_cache.cpp")
    comp_bytes = bytes(int(x, 0) for x in re.findall(r"0x[0-9a-fA-F]+|\d+", m_arr.group(1)))
    raw_spirv = zstd_decompress(comp_bytes)

    # 2. Extraer entradas de stock
    m_ent = re.search(r"ShaderCacheEntry g_shaderCacheEntries\[\]\s*=\s*\{(.*?)\};", src, re.S)
    entries = {}
    for line in m_ent.group(1).splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        # { 0xHASHULL, dxilOff, dxilSz, spirvOff, spirvSz, lateOff, lateSz, airOff, airSz, specMask, "name", nullptr, texMask },
        nums = re.findall(r"0x[0-9a-fA-F]+|\d+", line.split('"')[0])
        m_name = re.search(r'"([^"]*)"', line)
        after_name = parts[-1]
        tex_m = re.findall(r"0x[0-9a-fA-F]+|\d+", after_name)
        h = int(nums[0], 0)
        sp_off = int(nums[3], 0)
        sp_sz = int(nums[4], 0)
        lt_off = int(nums[5], 0)
        lt_sz = int(nums[6], 0)
        spec_mask = int(nums[9], 0)
        tex_mask = int(tex_m[0], 0) if tex_m else 0xFFFFFFFF
        fname = m_name.group(1) if m_name else f"stock/{h:016X}"
        blob = raw_spirv[sp_off:sp_off + sp_sz]
        late_blob = raw_spirv[lt_off:lt_off + lt_sz] if lt_sz > 0 else b""
        entries[h] = (spec_mask, fname, tex_mask, blob, late_blob)

    stock_count = len(entries)

    # 3. Leer los 4,508 archivos .xsh acumulados en POCO F6
    added = 0
    for xsh_path in sorted(glob.glob(os.path.join(XSH_DIR, "*.xsh"))):
        data = open(xsh_path, "rb").read()
        if len(data) < 40 or data[:4] != b"XSH1":
            continue
        magic, ver, key, h, pixel, spec_mask, tex_mask, smolv_sz = struct.unpack("<IIQQIIII", data[:40])
        blob = data[40:40 + smolv_sz]
        if len(blob) != smolv_sz or h in entries:
            continue
        is_late = (pixel != 0) and ((spec_mask & 0x702) == 0x702)
        late_blob = blob if is_late else b""
        entries[h] = (spec_mask, f"device/{h:016X}", tex_mask, blob, late_blob)
        added += 1

    # 4. Construir nuevo bloque unico ordenado ascendentemente por hash
    sorted_hashes = sorted(entries.keys())
    out_buf = bytearray()
    out_lines = []
    for h in sorted_hashes:
        spec_mask, fname, tex_mask, blob, late_blob = entries[h]
        sp_off = len(out_buf)
        sp_sz = len(blob)
        out_buf.extend(blob)
        if late_blob:
            if late_blob == blob:
                lt_off, lt_sz = sp_off, sp_sz
            else:
                lt_off = len(out_buf)
                lt_sz = len(late_blob)
                out_buf.extend(late_blob)
        else:
            lt_off, lt_sz = 0, 0
        out_lines.append(
            f'\t{{ 0x{h:016X}ULL, 0, 0, {sp_off}, {sp_sz}, {lt_off}, {lt_sz}, 0, 0, 0x{spec_mask:X}u, "{fname}", nullptr, 0x{tex_mask:X}u }},'
        )

    new_raw = bytes(out_buf)
    new_comp = zstd_compress(new_raw)

    # 5. Escribir shader_cache.cpp unificado
    with open(CACHE_CPP, "w", encoding="utf-8", newline="\n") as f:
        f.write('#include "shader_cache.h"\n\n')
        f.write("ShaderCacheEntry g_shaderCacheEntries[] = {\n")
        f.write("\n".join(out_lines) + "\n};\n\n")
        f.write("const uint8_t g_compressedSpirvCache[] = {")
        for i in range(0, len(new_comp), 32):
            chunk = ",".join(str(b) for b in new_comp[i:i + 32])
            f.write("\n\t" + chunk + ",")
        f.write("\n};\n\n")
        f.write(f"const size_t g_spirvCacheCompressedSize = {len(new_comp)};\n")
        f.write(f"const size_t g_spirvCacheDecompressedSize = {len(new_raw)};\n")
        f.write(f"const size_t g_shaderCacheEntryCount = {len(sorted_hashes)};\n")

    print(f"OK: Fusionados {stock_count} stock + {added} nuevos = {len(sorted_hashes)} shaders totales ({len(new_comp)/1024/1024:.2f} MB comprimidos)")

if __name__ == "__main__":
    main()
