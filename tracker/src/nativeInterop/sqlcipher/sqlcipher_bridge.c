#include "sqlcipher_bridge.h"
#include <CommonCrypto/CommonDigest.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <sys/stat.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>

static void clear_key(unsigned char *key, size_t size) {
    volatile unsigned char *p = key;
    while (size--) *p++ = 0;
}

int tracker_bind_text(sqlite3_stmt *stmt, int index, const char *text, int length) {
    return sqlite3_bind_text(stmt, index, text, length, SQLITE_TRANSIENT);
}

int tracker_cipher_open(const char *path, const char *key_id, sqlite3 **out) {
    *out = 0;
    unsigned char digest[CC_SHA256_DIGEST_LENGTH], key[32];
    CC_SHA256(key_id, (CC_LONG)strlen(key_id), digest);
    char account[65];
    for (int i = 0; i < 32; ++i) snprintf(account + 2*i, 3, "%02x", digest[i]);
    CFStringRef accountRef = CFStringCreateWithCString(NULL, account, kCFStringEncodingUTF8);
    if (!accountRef) return SQLITE_NOMEM;
    const void *keys[] = { kSecClass, kSecAttrService, kSecAttrAccount };
    const void *vals[] = { kSecClassGenericPassword, CFSTR("com.exkmptracker.sqlcipher.v1"), accountRef };
    CFMutableDictionaryRef query = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    if (!query) { CFRelease(accountRef); return SQLITE_NOMEM; }
    for (int i = 0; i < 3; ++i) CFDictionarySetValue(query, keys[i], vals[i]);
    CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue);
    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching(query, &result);
    if (status == errSecItemNotFound) {
        struct stat st;
        if (stat(path, &st) == 0 || errno != ENOENT) {
            CFRelease(query); CFRelease(accountRef); return SQLITE_AUTH;
        }
        if (SecRandomCopyBytes(kSecRandomDefault, sizeof(key), key) != errSecSuccess) { CFRelease(query); CFRelease(accountRef); return SQLITE_AUTH; }
        CFDataRef data = CFDataCreate(NULL, key, sizeof(key));
        if (!data) { clear_key(key, sizeof(key)); CFRelease(query); CFRelease(accountRef); return SQLITE_NOMEM; }
        CFDictionaryRemoveValue(query, kSecReturnData);
        CFDictionarySetValue(query, kSecValueData, data);
        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly);
        status = SecItemAdd(query, NULL);
        CFRelease(data);
        if (status == errSecDuplicateItem) {
            clear_key(key, sizeof(key)); CFRelease(query); CFRelease(accountRef);
            return tracker_cipher_open(path, key_id, out);
        }
    } else if (status == errSecSuccess) {
        if (CFGetTypeID(result) != CFDataGetTypeID() || CFDataGetLength((CFDataRef)result) != sizeof(key)) status = errSecDecode;
        else memcpy(key, CFDataGetBytePtr((CFDataRef)result), sizeof(key));
    }
    if (result) CFRelease(result);
    CFRelease(query); CFRelease(accountRef);
    if (status != errSecSuccess) { clear_key(key, sizeof(key)); return SQLITE_AUTH; }
    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, NULL);
    if (rc == SQLITE_OK) rc = sqlite3_key(db, key, sizeof(key));
    clear_key(key, sizeof(key));
    if (rc == SQLITE_OK) {
        sqlite3_stmt *stmt = NULL;
        rc = sqlite3_prepare_v2(db, "PRAGMA cipher_version", -1, &stmt, NULL);
        if (rc == SQLITE_OK && (sqlite3_step(stmt) != SQLITE_ROW || !sqlite3_column_text(stmt, 0))) rc = SQLITE_ERROR;
        sqlite3_finalize(stmt);
    }
    if (rc == SQLITE_OK) {
        sqlite3_stmt *stmt = NULL;
        rc = sqlite3_prepare_v2(db, "SELECT count(*) FROM sqlite_master", -1, &stmt, NULL);
        if (rc == SQLITE_OK && sqlite3_step(stmt) != SQLITE_ROW) rc = sqlite3_errcode(db);
        sqlite3_finalize(stmt);
    }
    if (rc != SQLITE_OK) { if (db) sqlite3_close(db); return rc; }
    *out = db;
    return SQLITE_OK;
}
