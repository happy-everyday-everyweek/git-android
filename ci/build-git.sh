#!/usr/bin/env bash
# 交叉编译 git 本体与 http 远端助手，产出 build/stage/{git,git-remote-http,git-upload-pack}
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/build/src"
PREFIX="$ROOT/build/prefix"
STAGE="$ROOT/build/stage"
API="${ANDROID_API:-26}"
: "${NDK:?需要设置 NDK 环境变量指向 Android NDK}"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TC/bin:$PATH"
REAL_CC="$TC/bin/aarch64-linux-android${API}-clang"
[ -x "$REAL_CC" ] || { echo "找不到交叉编译器 $REAL_CC"; exit 1; }
JOBS="$(nproc)"

mkdir -p "$SRC" "$STAGE"
cd "$SRC"
if [ ! -d "git-$GIT_VER" ]; then
  curl -fsSL --retry 3 -o "git-$GIT_VER.tar.gz" \
    "https://github.com/git/git/archive/refs/tags/v$GIT_VER.tar.gz"
  tar xf "git-$GIT_VER.tar.gz"
fi
cd "git-$GIT_VER"

# git 自带的 curl-config 探测会命中宿主的 curl-config，所以屏蔽 CURL_CONFIG；
# 依赖库的 -I/-L 与最终链接用的静态库统一由 CC 包装器注入，避免依赖 Makefile 变量拼接细节。
WRAP="$ROOT/build/toolchain"
mkdir -p "$WRAP"
cat >"$WRAP/cc" <<EOF
#!/bin/sh
for arg in "\$@"; do
  if [ "\$arg" = "-c" ]; then
    exec "$REAL_CC" -I"$PREFIX/include" "\$@" -Os -ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables
  fi
done
exec "$REAL_CC" -I"$PREFIX/include" -L"$PREFIX/lib" "\$@" -Wl,--gc-sections -Wl,-O1 \\
  -lexpat -lcurl -lssl -lcrypto -lz -lm -ldl
EOF
chmod +x "$WRAP/cc"

MAKEARGS=(
  "CC=$WRAP/cc" AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
  uname_S=Linux prefix=/usr
  NO_GETTEXT=YesPlease NO_TCLTK=YesPlease NO_PERL=YesPlease NO_PYTHON=YesPlease
  NO_ICONV=YesPlease NO_OPENSSL=YesPlease NO_REGEX=YesPlease NO_NSEC=YesPlease
  NO_INSTALL_HARDLINKS=YesPlease INSTALL_SYMLINKS=YesPlease
  HAVE_SYNC_FILE_RANGE= HAVE_GETRANDOM= NEEDS_LIBRT= NEEDS_LIBICONV= PTHREAD_LIBS=
  CURL_CONFIG= EXPATDIR="$PREFIX" CURLDIR="$PREFIX" ZLIB_PATH="$PREFIX"
  USE_EXPAT=YesPlease CSPRNG_METHOD=urandom DEFAULT_PAGER=cat
)

echo "==== 打印实际编译/链接命令（核对 -D 宏与链接库）"
make V=1 -n "${MAKEARGS[@]}" git 2>/dev/null | grep -m1 'build/toolchain/cc' | cut -c1-500 || true
make V=1 -n "${MAKEARGS[@]}" git-remote-http 2>/dev/null | grep -m1 -- '-o git-remote-http ' | cut -c1-700 || true

echo "==== 编译 git 主体 / http 助手 / upload-pack"
make -j"$JOBS" "${MAKEARGS[@]}" git git-remote-http git-upload-pack

echo "==== 收集并 strip 产物"
for f in git git-remote-http git-upload-pack; do
  [ -f "$f" ] || { echo "构建产物缺失: $f"; exit 1; }
  cp "$f" "$STAGE/$f"
  llvm-strip --strip-all "$STAGE/$f"
done
ls -l "$STAGE"

echo "==== 动态依赖检查（应当只有 bionic 的 libc/libdl/libm）"
for f in git git-remote-http; do
  echo "-- $f"
  llvm-readelf -d "$STAGE/$f" | grep NEEDED || true
done

echo "==== HTTP 传输能力自检（静态符号）"
llvm-nm --defined-only "$STAGE/git-remote-http" >"$ROOT/build/syms-helper.txt" || true
for s in curl_easy_init curl_easy_perform XML_Parse SSL_connect inflate; do
  if grep -qE "T $s\$|t $s\$" "$ROOT/build/syms-helper.txt"; then
    echo "  OK   $s"
  else
    echo "  MISS $s"
  fi
done
