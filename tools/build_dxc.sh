#!/usr/bin/env bash
set -e
mkdir -p build/dxc
xz -d -c vendor_dxc/libdxcompiler.so.xz > build/dxc/libdxcompiler.so
ls -lh build/dxc/libdxcompiler.so
