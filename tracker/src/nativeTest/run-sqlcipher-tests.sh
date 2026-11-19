#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source_dir="$PWD/build/sqlcipher-native/sqlcipher-4.20.0"
if [ ! -f "$source_dir/sqlite3.c" ]; then src/nativeInterop/sqlcipher/build.sh iphoneos; fi
mkdir -p build/sqlcipher-test
clang -O2 -DSQLITE_HAS_CODEC -DSQLITE_EXTRA_INIT=sqlcipher_extra_init -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown -DSQLCIPHER_CRYPTO_CC -DSQLITE_TEMP_STORE=2 -I"$source_dir" "$source_dir/sqlite3.c" src/nativeTest/sqlcipher_tests.c -framework Security -framework CoreFoundation -o build/sqlcipher-test/tests
rm -f build/sqlcipher-test/queue.db build/sqlcipher-test/queue.db-journal
build/sqlcipher-test/tests "$PWD/build/sqlcipher-test/queue.db"
rm -f build/sqlcipher-test/queue.db build/sqlcipher-test/queue.db-journal
