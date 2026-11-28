#!/usr/bin/env bash
set -e
mkdir -p build/dxc/src/external/DirectX-Headers build/dxc/src/external/SPIRV-Headers build/dxc/src/external/SPIRV-Tools
G="https://codeload.github"
C=".com"
curl -sL "${G}${C}/microsoft/DirectXShaderCompiler/tar.gz/refs/tags/v1.8.2407" | tar -xz -C build/dxc/src --strip-components=1
curl -sL "${G}${C}/microsoft/DirectX-Headers/tar.gz/980971e835876dc0cde415e8f9bc646e64667bf7" | tar -xz -C build/dxc/src/external/DirectX-Headers --strip-components=1
curl -sL "${G}${C}/KhronosGroup/SPIRV-Headers/tar.gz/2acb319af38d43be3ea76bfabf3998e5281d8d12" | tar -xz -C build/dxc/src/external/SPIRV-Headers --strip-components=1
curl -sL "${G}${C}/KhronosGroup/SPIRV-Tools/tar.gz/0cfe9e7219148716dfd30b37f4d21753f098707a" | tar -xz -C build/dxc/src/external/SPIRV-Tools --strip-components=1
sed -i 's/if(CMAKE_CROSSCOMPILING OR/if((CMAKE_CROSSCOMPILING AND NOT LLVM_TABLEGEN) OR/' build/dxc/src/CMakeLists.txt
CFG="-GNinja -C build/dxc/src/cmake/caches/PredefinedParams.cmake -DCMAKE_BUILD_TYPE=Release -DLLVM_TARGETS_TO_BUILD=None -DLLVM_INCLUDE_DOCS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_TOOLS=OFF -DLLVM_ENABLE_BINDINGS=OFF -DLLVM_ENABLE_WARNINGS=OFF -DLLVM_OPTIMIZED_TABLEGEN=OFF -DENABLE_SPIRV_CODEGEN=ON -DHLSL_INCLUDE_TESTS=OFF -DLLVM_DISTRIBUTION_COMPONENTS="""
cmake -S build/dxc/src -B build/dxc/host $CFG -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DENABLE_SPIRV_CODEGEN=OFF
ninja -C build/dxc/host llvm-tblgen clang-tblgen
cmake -S build/dxc/src -B build/dxc/android $CFG -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DLLVM_INFERRED_HOST_TRIPLE=aarch64-unknown-linux-android -DLLVM_TABLEGEN="$PWD/build/dxc/host/bin/llvm-tblgen" -DCLANG_TABLEGEN="$PWD/build/dxc/host/bin/clang-tblgen"
ninja -C build/dxc/android dxcompiler
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o build/dxc/libdxcompiler.so build/dxc/android/lib/libdxcompiler.so
