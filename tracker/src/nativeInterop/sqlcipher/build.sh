#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
root="$PWD"
build="$root/build/sqlcipher-native"
source_dir="$build/sqlcipher-4.20.0"
archive="$build/sqlcipher-4.20.0.tar.gz"
mkdir -p "$build"
if [ ! -f "$archive" ]; then
  curl -fL --retry 3 https://codeload.github.com/sqlcipher/sqlcipher/tar.gz/refs/tags/v4.20.0 -o "$archive"
fi
echo '38705f51b61ac0636a5fd064f9a77fafabc7227b2aa2513f1b14c8bb1102affc  '"$archive" | shasum -a 256 -c -
if [ ! -f "$source_dir/sqlite3.c" ]; then
  rm -rf "$source_dir"
  tar -xzf "$archive" -C "$build"
  (cd "$source_dir" && ./configure --disable-tcl --disable-shared && make -s sqlite3.c)
fi
sdk="$1"
out="$build/$sdk"
mkdir -p "$out"
sdkpath="$(xcrun --sdk "$sdk" --show-sdk-path)"
clang="$(xcrun --sdk "$sdk" -f clang)"
if [ "$sdk" = iphoneos ]; then
  target=arm64-apple-ios14.0
elif [ "$sdk" = iphonesimulator ]; then
  target=arm64-apple-ios14.0-simulator
else
  echo "Unsupported SDK: $sdk" >&2
  exit 1
fi
flags=(-target "$target" -isysroot "$sdkpath" -O2 -DSQLITE_HAS_CODEC -DSQLITE_EXTRA_INIT=sqlcipher_extra_init -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown -DSQLCIPHER_CRYPTO_CC -DSQLITE_TEMP_STORE=2 -DSQLITE_THREADSAFE=1 -I"$source_dir" -I"$root/src/nativeInterop/sqlcipher")
"$clang" "${flags[@]}" -c "$source_dir/sqlite3.c" -o "$out/sqlite3.o"
"$clang" "${flags[@]}" -c "$root/src/nativeInterop/sqlcipher/sqlcipher_bridge.c" -o "$out/bridge.o"
xcrun libtool -static -o "$out/libtrackercipher.a" "$out/sqlite3.o" "$out/bridge.o"
cp "$source_dir/sqlite3.h" "$out/sqlite3.h"
