package com.syncednotes.core

import com.google.protobuf.ByteString
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The little of SQL the store needs, so that one [NoteStore] runs on both devices: the Mac
 * reaches SQLCipher's format through JDBC, and Android through SQLCipher for Android, which
 * has no JDBC driver, while the JDBC one has no Android build.
 */
interface SqlDatabase : AutoCloseable {
    /** Runs a statement that returns no rows; returns how many rows it changed. */
    fun update(sql: String, arguments: Array<out Any?> = emptyArray()): Int

    fun <T> query(sql: String, arguments: Array<out Any?> = emptyArray(), row: (SqlRow) -> T): List<T>

    fun begin()
    fun commit()
    fun rollback()
}

/** One row of a result; columns count from 1, as in SQL. */
interface SqlRow {
    fun bytes(column: Int): ByteArray?
    fun string(column: Int): String?
    fun double(column: Int): Double?
    fun long(column: Int): Long?

    /** The value as it is stored: null, a ByteArray, a String, a Double or a Long. */
    fun value(column: Int): Any?

    fun int(column: Int): Int = long(column)!!.toInt()
    fun boolean(column: Int): Boolean = long(column)!! != 0L
}

/** Opens the database file locked as a whole with [key], SQLCipher 4's format, creating it if new. */
fun interface SqlOpener {
    /** Throws [SqlOpenException] when the key does not open it. */
    fun open(file: File, key: ByteArray): SqlDatabase

    companion object {
        /**
         * The device's way in. Android sets its own before opening any notes; anywhere else
         * this is JDBC, which is what the Mac and the tests run.
         */
        @Volatile var installed: SqlOpener? = null

        val current: SqlOpener get() = installed ?: JdbcOpener
    }
}

class SqlOpenException(cause: Throwable) : Exception(cause.message, cause)

/** The Mac's: SQLite3 Multiple Ciphers in its SQLCipher 4 mode, through JDBC. */
object JdbcOpener : SqlOpener {
    override fun open(file: File, key: ByteArray): SqlDatabase {
        val connection = try {
            // The key is already the output of Argon2id and HKDF, so it is used as it is, not
            // run through SQLCipher's own key derivation again.
            org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build()
                .createConnection("jdbc:sqlite:${file.absolutePath}")
        } catch (e: SQLException) {
            throw SqlOpenException(e)
        }
        val database = JdbcDatabase(connection)
        try {
            // SQLCipher only finds out the key is wrong on the first read.
            database.query("SELECT count(*) FROM sqlite_master") { }
        } catch (e: SQLException) {
            connection.close()
            throw SqlOpenException(e)
        }
        return database
    }
}

private class JdbcDatabase(private val connection: Connection) : SqlDatabase {
    override fun update(sql: String, arguments: Array<out Any?>): Int =
        if (arguments.isEmpty() && !sql.contains('?')) connection.createStatement().use { it.executeUpdate(sql) }
        else prepare(sql, arguments).use { it.executeUpdate() }

    override fun <T> query(sql: String, arguments: Array<out Any?>, row: (SqlRow) -> T): List<T> =
        prepare(sql, arguments).use { statement ->
            statement.executeQuery().use { r ->
                val wrapped = JdbcRow(r)
                buildList { while (r.next()) add(row(wrapped)) }
            }
        }

    override fun begin() { connection.autoCommit = false }
    override fun commit() { connection.commit(); connection.autoCommit = true }
    override fun rollback() { connection.rollback(); connection.autoCommit = true }
    override fun close() = connection.close()

    private fun prepare(sql: String, arguments: Array<out Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        arguments.forEachIndexed { i, value ->
            when (val v = sqlValue(value)) {
                null -> statement.setNull(i + 1, java.sql.Types.NULL)
                is ByteArray -> statement.setBytes(i + 1, v)
                is String -> statement.setString(i + 1, v)
                is Double -> statement.setDouble(i + 1, v)
                is Long -> statement.setLong(i + 1, v)
            }
        }
        return statement
    }
}

private class JdbcRow(private val r: ResultSet) : SqlRow {
    override fun bytes(column: Int): ByteArray? = r.getBytes(column)
    override fun string(column: Int): String? = r.getString(column)
    override fun double(column: Int): Double? = r.getDouble(column).takeUnless { r.wasNull() }
    override fun long(column: Int): Long? = r.getLong(column).takeUnless { r.wasNull() }
    override fun value(column: Int): Any? = when (val v = r.getObject(column)) {
        null, is ByteArray, is String, is Double, is Long -> v
        is Int -> v.toLong()
        is Float -> v.toDouble()
        else -> error("No stored type for ${v::class}")
    }
}

/** An argument as SQLite stores it: null, a ByteArray, a String, a Double or a Long. */
fun sqlValue(value: Any?): Any? = when (value) {
    null, is ByteArray, is String, is Double, is Long -> value
    is ByteString -> value.toByteArray()
    is Int -> value.toLong()
    is Boolean -> if (value) 1L else 0L
    else -> error("No SQL type for ${value::class}")
}
