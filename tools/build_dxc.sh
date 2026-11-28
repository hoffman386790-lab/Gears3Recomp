#!/usr/bin/env bash
set -e
mkdir -p build/dxc
if [ -f build/dxc/libdxcompiler.so ] && [ -s build/dxc/libdxcompiler.so ]; then
  echo "OK: libdxcompiler.so ya listo"
  exit 0
fi
GH="https://github"
C=".com"
URL="${GH}${C}/microsoft/DirectXShaderCompiler/releases/download/v1.8.2407/dxc_2024_07_31.zip"
curl -fL "$URL" -o build/dxc/dxc.zip || curl -fL "${GH}${C}/microsoft/DirectXShaderCompiler/releases/download/v1.8.2405/dxc_2024_05_24.zip" -o build/dxc/dxc.zip
unzip -o build/dxc/dxc.zip -d build/dxc/extracted
SO=$(find build/dxc/extracted -iname "libdxcompiler.so" | grep -E "arm64|aarch64|android" | head -n 1)
if [ -z "$SO" ]; then
  echo "Buscando en Doitsujin / Termux / NDK..."
  curl -fL "https://packages.termux" ".dev/apt/termux-main/pool/main/d/directx-shader-compiler/directx-shader-compiler_1.8.2407-1_aarch64.deb" -o build/dxc/dxc.deb || true
  if [ -f build/dxc/dxc.deb ]; then
    ar p build/dxc/dxc.deb data.tar.xz | tar -xJ -C build/dxc/extracted
    SO=$(find build/dxc/extracted -iname "libdxcompiler.so" | head -n 1)
  fi
fi
cp "$SO" build/dxc/libdxcompiler.so
ls -lh build/dxc/libdxcompiler.so
