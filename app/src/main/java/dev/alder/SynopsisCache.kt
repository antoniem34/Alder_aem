package dev.alder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Persistent synopsis-only cache. Versioned by the original text, not by title alone. */
internal class SynopsisCache(context: Context) : SQLiteOpenHelper(context, "aem_synopses.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE synopses (cache_key TEXT PRIMARY KEY, text TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(key: String): String? = readableDatabase.rawQuery(
        "SELECT text FROM synopses WHERE cache_key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(key: String, value: String) {
        val row = android.content.ContentValues().apply {
            put("cache_key", key); put("text", value)
        }
        writableDatabase.insertWithOnConflict("synopses", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        fun key(id: String, source: String, original: String): String {
            val data = (id + "\u0000" + source + "\u0000" + original).toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
