package com.mypersonalagent.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TodoEntity::class, EntryEntity::class, NoteEntity::class, ContactEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun entryDao(): EntryDao
    abstract fun noteDao(): NoteDao
    abstract fun contactDao(): ContactDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todos ADD COLUMN notifiedForDue TEXT")
    }
}

/** Adds local-only storage for notes and contacts (previously server-only, no local table existed). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                id TEXT NOT NULL PRIMARY KEY,
                text TEXT NOT NULL,
                tagsJson TEXT NOT NULL DEFAULT '[]',
                created TEXT,
                updated TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                firstName TEXT,
                lastName TEXT,
                phoneNumber TEXT,
                email TEXT,
                telegramUserId TEXT,
                whatsappNumber TEXT,
                emailAccountsNote TEXT,
                created TEXT,
                updated TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}
