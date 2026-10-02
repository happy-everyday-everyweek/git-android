#!/usr/bin/env bash
# 交叉编译 git 所需的原生依赖（Android aarch64，全部静态库）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/build/src"
PREFIX="$ROOT/build/prefix"
mkdir -p "$SRC" "$PREFIX"

: "${NDK:?需要设置 NDK 环境变量指向 Android NDK}"
API="${ANDROID_API:-26}"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TC/bin:$PATH"
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip NM=llvm-nm
CC="$TC/bin/aarch64-linux-android${API}-clang"
export CC
export CXX="${CC}++"
JOBS="$(nproc)"

[ -x "$CC" ] || { echo "找不到交叉编译器 $CC"; exit 1; }

get() { [ -s "$1" ] || curl -fsSL --retry 3 --retry-delay 2 -o "$1" "$2"; }

cd "$SRC"

echo "==== zlib $ZLIB_VER"
if [ ! -f "$PREFIX/lib/libz.a" ]; then
  get "zlib-$ZLIB_VER.tar.gz" "https://github.com/madler/zlib/releases/download/v$ZLIB_VER/zlib-$ZLIB_VER.tar.gz"
  rm -rf "zlib-$ZLIB_VER" && tar xf "zlib-$ZLIB_VER.tar.gz"
  ( cd "zlib-$ZLIB_VER"
    CFLAGS="-O2 -fPIC -D_LARGEFILE64_SOURCE=1" ./configure --prefix="$PREFIX" --static >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null )
fi

echo "==== expat $EXPAT_VER"
if [ ! -f "$PREFIX/lib/libexpat.a" ]; then
  get "expat-$EXPAT_VER.tar.gz" "https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_VER//./_}/expat-$EXPAT_VER.tar.gz"
  rm -rf "expat-$EXPAT_VER" && tar xf "expat-$EXPAT_VER.tar.gz"
  cmake -S "expat-$EXPAT_VER" -B b-expat \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DEXPAT_SHARED_LIBS=OFF -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF \
    -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_DOCS=OFF -DEXPAT_BUILD_PKGCONFIG=OFF >/dev/null
  cmake --build b-expat -j"$JOBS" >/dev/null
  cmake --install b-expat >/dev/null
fi

echo "==== OpenSSL $OPENSSL_VER"
if [ ! -f "$PREFIX/lib/libssl.a" ]; then
  get "openssl-$OPENSSL_VER.tar.gz" "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz"
  rm -rf "openssl-$OPENSSL_VER" && tar xf "openssl-$OPENSSL_VER.tar.gz"
  ( cd "openssl-$OPENSSL_VER"
    export ANDROID_NDK_ROOT="$NDK"
    export ANDROID_NDK="$NDK"
    ./Configure android-arm64 no-shared no-tests no-apps no-legacy no-engine no-dso \
      -D__ANDROID_API__="$API" --prefix="$PREFIX" --openssldir="$PREFIX/ssl" >conf.log 2>&1 \
      || { cat conf.log; exit 1; }
    make -j"$JOBS" build_sw >/dev/null
    make install_sw >/dev/null )
fi

echo "==== libcurl $CURL_VER"
if [ ! -f "$PREFIX/lib/libcurl.a" ]; then
  get "curl-$CURL_VER.tar.gz" "https://github.com/curl/curl/releases/download/curl-${CURL_VER//./_}/curl-$CURL_VER.tar.gz"
  rm -rf "curl-$CURL_VER" && tar xf "curl-$CURL_VER.tar.gz"
  cmake -S "curl-$CURL_VER" -B b-curl \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_CURL_EXE=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF \
    -DHTTP_ONLY=ON -DCURL_USE_OPENSSL=ON -DOPENSSL_USE_STATIC_LIBS=ON -DOPENSSL_ROOT_DIR="$PREFIX" \
    -DOPENSSL_INCLUDE_DIR="$PREFIX/include" -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a" \
    -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" \
    -DCURL_ZLIB=ON -DZLIB_INCLUDE_DIR="$PREFIX/include" -DZLIB_LIBRARY="$PREFIX/lib/libz.a" \
    -DUSE_LIBPSL=OFF -DUSE_NGHTTP2=OFF -DCMAKE_USE_LIBSSH2=OFF \
    -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF -DCURL_DISABLE_BROTLI=ON -DCURL_DISABLE_ZSTD=ON >/dev/null
  cmake --build b-curl -j"$JOBS" >/dev/null
  cmake --install b-curl >/dev/null
fi

echo "==== 依赖产物"
ls -l "$PREFIX/lib" | grep -E 'libz\.a|libexpat\.a|libssl\.a|libcrypto\.a|libcurl\.a'
