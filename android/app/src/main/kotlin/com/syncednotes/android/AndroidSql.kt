package com.syncednotes.android

import android.database.Cursor
import android.database.SQLException
import com.syncednotes.core.SqlDatabase
import com.syncednotes.core.SqlOpenException
import com.syncednotes.core.SqlOpener
import com.syncednotes.core.SqlRow
import com.syncednotes.core.sqlValue
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The phone's way into the store's database: SQLCipher for Android, in SQLCipher 4's format with
 * the vault's raw database key, as SQLite3 Multiple Ciphers keeps it on the Mac.
 */
object AndroidSqlOpener : SqlOpener {
    init {
        System.loadLibrary("sqlcipher")
    }

    override fun open(file: File, key: ByteArray): SqlDatabase {
        // `x'…'` is SQLCipher's raw key: the key is already the output of Argon2id and HKDF,
        // so it is used as it is, not run through SQLCipher's own key derivation again.
        val raw = "x'${key.joinToString("") { "%02x".format(it) }}'".toByteArray(Charsets.US_ASCII)
        val database = try {
            SQLiteDatabase.openOrCreateDatabase(file, raw, null, null, null)
        } catch (e: SQLException) {
            throw SqlOpenException(e)
        } finally {
            raw.fill(0)
        }
        try {
            // SQLCipher only finds out the key is wrong on the first read.
            database.rawQuery("SELECT count(*) FROM sqlite_master", *emptyArray<Any?>()).use { it.moveToFirst() }
        } catch (e: SQLException) {
            database.close()
            throw SqlOpenException(e)
        }
        return AndroidSqlDatabase(database)
    }
}

private class AndroidSqlDatabase(private val database: SQLiteDatabase) : SqlDatabase {
    override fun update(sql: String, arguments: Array<out Any?>): Int {
        val statement = database.compileStatement(sql)
        return try {
            bind(arguments) { i, v ->
                when (v) {
                    null -> statement.bindNull(i)
                    is ByteArray -> statement.bindBlob(i, v)
                    is String -> statement.bindString(i, v)
                    is Double -> statement.bindDouble(i, v)
                    is Long -> statement.bindLong(i, v)
                }
            }
            // CREATE, ALTER and the like change no rows, and executeUpdateDelete refuses some.
            if (sql.trimStart().take(6).uppercase() in setOf("INSERT", "UPDATE", "DELETE")) statement.executeUpdateDelete()
            else { statement.execute(); 0 }
        } finally {
            statement.close()
        }
    }

    override fun <T> query(sql: String, arguments: Array<out Any?>, row: (SqlRow) -> T): List<T> {
        val values = arrayOfNulls<Any?>(arguments.size)
        bind(arguments) { i, v -> values[i - 1] = v }
        return database.rawQuery(sql, *values).use { cursor ->
            val wrapped = CursorRow(cursor)
            buildList { while (cursor.moveToNext()) add(row(wrapped)) }
        }
    }

    override fun begin() = database.beginTransaction()

    override fun commit() {
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    override fun rollback() = database.endTransaction()

    override fun close() = database.close()

    private fun bind(arguments: Array<out Any?>, put: (Int, Any?) -> Unit) =
        arguments.forEachIndexed { i, value -> put(i + 1, sqlValue(value)) }
}

/** Columns count from 1, as in SQL and JDBC; a cursor's from 0. */
private class CursorRow(private val cursor: Cursor) : SqlRow {
    override fun bytes(column: Int): ByteArray? = if (cursor.isNull(column - 1)) null else cursor.getBlob(column - 1)
    override fun string(column: Int): String? = if (cursor.isNull(column - 1)) null else cursor.getString(column - 1)
    override fun double(column: Int): Double? = if (cursor.isNull(column - 1)) null else cursor.getDouble(column - 1)
    override fun long(column: Int): Long? = if (cursor.isNull(column - 1)) null else cursor.getLong(column - 1)
    override fun value(column: Int): Any? = when (cursor.getType(column - 1)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column - 1)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column - 1)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(column - 1)
        else -> cursor.getBlob(column - 1)
    }
}
