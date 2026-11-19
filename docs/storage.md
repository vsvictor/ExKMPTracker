# Encrypted queue storage

`EncryptedTrackQueue(context)` (Android) and `IosTrackQueue(storage: IosProtectedStorage)` (iOS) implement the existing `TrackQueue` port. Pass one `IosProtectedStorage` instance per directory to the queue and session store; instances sharing a directory are not mutually synchronized. Storage is opened and migrated lazily on the first operation. Do not use an older app version to write the same queue after migrating.

Each queue partitions by **both** driver and brand. `acknowledge` checks and removes only the exact current FIFO prefix in one transaction. SQLCipher uses full synchronous commits; iOS additionally enables `fullfsync`. Initialization checks `cipher_version`, reads the keyed schema, and runs `quick_check`: an unavailable codec, bad key, corrupt data or failed migration raises an error rather than returning an empty queue. Preserve database, journal/WAL, key material and legacy files together for investigation; do not delete or reset them on an error.

## Android

The production queue uses official `net.zetetic:sqlcipher-android:4.20.0`. A random database key is wrapped with a dedicated Android Keystore AES-GCM key; only its authenticated ciphertext is stored in the app's no-backup directory. Missing or unusable wrapping keys or wrapped key data cause an explicit failure when data exists. The existing per-row AES-GCM and Keystore HMAC identity partitioning remain in place. Configure the host application's backup rules so database, key and legacy queue data are excluded; restoring app data without the original Keystore cannot recover the queue.

On first open, an existing `exkmp-tracker-queue-v1.db` is copied into `exkmp-tracker-queue-sqlcipher-v2.db` transactionally, including ordering and encrypted payloads. A database migration marker makes retries idempotent. The old database and its SQLite sidecars are deliberately retained: they are a recovery copy, not an active queue, and may contain unencrypted row identifiers. Legacy payload and partition keys remain necessary; individual payload authentication is checked when read. If migration or authentication fails, keep all files and restore the original app data and Keystore keys. Verify successful migration and drain/upload the queue before deciding on a separately managed secure retirement of the old copy; never re-import it manually.

## iOS

`IosProtectedStorage` continues to manage protected session files and the stop marker. SQLCipher stores a separate database in its directory. A random key is held in the app Keychain with `AfterFirstUnlockThisDeviceOnly` accessibility, and never written in plaintext. The default Keychain account is derived from the standardized path relative to the app home, not its changing sandbox UUID. For an external directory which may move, use `IosTrackQueue(storage, keyIdentifier)` with a unique, stable, non-secret identifier, and always use the same identifier for that queue. Do not reuse one identifier for unrelated directories. An existing database with a missing Keychain item fails closed; device-only keys do not migrate to a new device or survive Keychain loss.

Legacy `queue-<driver-hex>-<brand-hex>.json` files are migrated **per identity on first access**, in the same transaction as their migration marker. Once committed, that identity's old snapshot is removed and the directory synchronized. A crash after commit is safe: the marker prevents re-import, and the next access retries removal. Corrupt snapshots are preserved and reported. Other identities' snapshots remain until those identities are accessed, so they still contain protected-file JSON and identity-bearing filenames until migrated. Keep the original application container and Keychain available during migration.

## Building and verification

Apple builds require Xcode command-line tools, its iOS and iOS Simulator SDKs, `curl`, `shasum`, and a C build toolchain. Gradle fetches the official SQLCipher v4.20.0 source archive from GitHub, verifies SHA-256 `38705f51b61ac0636a5fd064f9a77fafabc7227b2aa2513f1b14c8bb1102affc`, generates the amalgamation in `tracker/build`, and compiles arm64 device and simulator static libraries with SQLCipher's CommonCrypto backend. No system SQLite or OpenSSL is used for this queue. The app must link Security and CoreFoundation frameworks; the cinterop records these linker options. SQLCipher is distributed under its [BSD-style license](https://github.com/sqlcipher/sqlcipher/blob/v4.20.0/LICENSE.md); SQLite is public domain ([notice](https://github.com/sqlcipher/sqlcipher/blob/v4.20.0/SQLITE_LICENSE.md)).

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :tracker:compileAndroidMain :tracker:compileKotlinIosArm64 :tracker:compileTestKotlinIosSimulatorArm64 :tracker:assembleExKMPTrackerReleaseXCFramework
tracker/src/nativeTest/run-sqlcipher-tests.sh
```

The host C smoke test verifies encrypted headers, correct/wrong-key reopen, partition isolation, committed changes and rollback using the same SQLCipher source and CommonCrypto. iOS queue tests compile separately; run them on a suitable simulator/device to exercise Keychain and actual migration. Android Keystore and production migration also require Android device/instrumentation testing. Neither platform's device tests are replaced by the host smoke test.
