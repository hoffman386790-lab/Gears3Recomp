#!/usr/bin/env bash
set -e
mkdir -p build/dxc/extracted
if [ -f build/dxc/libdxcompiler.so ] && [ -s build/dxc/libdxcompiler.so ]; then
  echo "OK: libdxcompiler.so ya listo"
  exit 0
fi
REPO="https://packages.termux"
DOM=".dev/apt/termux-main"
BASE="${REPO}${DOM}"
DEB_PATH=$(curl -fsSL "${BASE}/dists/stable/main/binary-aarch64/Packages.gz" | gunzip | awk '/^Package: directx-shader-compiler$/{f=1} f && /^Filename:/{print $2; exit}')
echo "Descargando: ${BASE}/${DEB_PATH}"
curl -fL "${BASE}/${DEB_PATH}" -o build/dxc/dxc.deb
ar p build/dxc/dxc.deb data.tar.xz | tar -xJ -C build/dxc/extracted
SO=$(find build/dxc/extracted -iname "libdxcompiler.so" | head -n 1)
cp "$SO" build/dxc/libdxcompiler.so
ls -lh build/dxc/libdxcompiler.so
