#include "sqlite3.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void run(sqlite3 *db, const char *sql) {
    char *message = NULL;
    int rc = sqlite3_exec(db, sql, NULL, NULL, &message);
    if (rc != SQLITE_OK) fprintf(stderr, "%s\n", message ? message : "sqlite error");
    sqlite3_free(message);
    assert(rc == SQLITE_OK);
}
static int number(sqlite3 *db, const char *sql) {
    sqlite3_stmt *stmt = NULL;
    assert(sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK);
    assert(sqlite3_step(stmt) == SQLITE_ROW);
    int result = sqlite3_column_int(stmt, 0);
    sqlite3_finalize(stmt);
    return result;
}
int main(int argc, char **argv) {
    assert(argc == 2);
    const char *path = argv[1];
    sqlite3 *db = NULL;
    assert(sqlite3_open(path, &db) == SQLITE_OK);
    assert(sqlite3_key(db, "correct-key", 11) == SQLITE_OK);
    sqlite3_stmt *cipher = NULL;
    assert(sqlite3_prepare_v2(db, "PRAGMA cipher_version", -1, &cipher, NULL) == SQLITE_OK);
    assert(sqlite3_step(cipher) == SQLITE_ROW && sqlite3_column_text(cipher, 0));
    sqlite3_finalize(cipher);
    run(db, "PRAGMA synchronous=FULL; PRAGMA fullfsync=ON; CREATE TABLE q(seq INTEGER PRIMARY KEY, driver TEXT, brand TEXT, id TEXT); INSERT INTO q VALUES(1,'d','a','first'),(2,'d','a','second'),(3,'d','b','other');");
    run(db, "BEGIN IMMEDIATE; DELETE FROM q WHERE seq=1; ROLLBACK;");
    assert(number(db, "SELECT count(*) FROM q WHERE driver='d' AND brand='a'") == 2);
    run(db, "BEGIN IMMEDIATE; DELETE FROM q WHERE seq=1; COMMIT;");
    assert(number(db, "SELECT count(*) FROM q WHERE driver='d' AND brand='b'") == 1);
    assert(sqlite3_close(db) == SQLITE_OK);
    FILE *f = fopen(path, "rb"); assert(f);
    char header[16]; assert(fread(header, 1, sizeof(header), f) == sizeof(header)); fclose(f);
    assert(memcmp(header, "SQLite format 3", 15) != 0);
    assert(sqlite3_open(path, &db) == SQLITE_OK);
    assert(sqlite3_key(db, "wrong-key", 9) == SQLITE_OK);
    assert(sqlite3_exec(db, "SELECT * FROM q", NULL, NULL, NULL) != SQLITE_OK);
    sqlite3_close(db);
    assert(sqlite3_open(path, &db) == SQLITE_OK);
    assert(sqlite3_key(db, "correct-key", 11) == SQLITE_OK);
    assert(number(db, "SELECT count(*) FROM q") == 2);
    sqlite3_close(db);
    puts("SQLCipher encrypted reopen, wrong-key rejection, partition isolation and rollback: OK");
    return 0;
}
