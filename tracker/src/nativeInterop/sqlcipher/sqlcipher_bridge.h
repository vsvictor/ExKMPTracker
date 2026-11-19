#ifndef EXKMP_SQLCIPHER_BRIDGE_H
#define EXKMP_SQLCIPHER_BRIDGE_H
#include "sqlite3.h"
/* On failure, *db is NULL. Existing keychain items and database files are never deleted. */
int tracker_bind_text(sqlite3_stmt *stmt, int index, const char *text, int length);
int tracker_cipher_open(const char *path, const char *key_id, sqlite3 **db);
#endif
