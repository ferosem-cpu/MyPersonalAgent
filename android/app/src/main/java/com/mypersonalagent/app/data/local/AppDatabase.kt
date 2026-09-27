package com.mypersonalagent.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TodoEntity::class,
        EntryEntity::class,
        NoteEntity::class,
        ContactEntity::class,
        FileEntity::class,
        AssistantEntity::class,
        ChatMessageEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun entryDao(): EntryDao
    abstract fun noteDao(): NoteDao
    abstract fun contactDao(): ContactDao
    abstract fun fileDao(): FileDao
    abstract fun assistantDao(): AssistantDao
    abstract fun chatMessageDao(): ChatMessageDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todos ADD COLUMN notifiedForDue TEXT")
    }
}

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
            """.trimIndent(),
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
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS files (
                id TEXT NOT NULL PRIMARY KEY,
                displayName TEXT NOT NULL,
                category TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                localPath TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'share',
                created TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS assistants (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                title TEXT NOT NULL,
                instructions TEXT NOT NULL,
                colorHex TEXT NOT NULL,
                shape TEXT NOT NULL DEFAULT 'round',
                emoji TEXT NOT NULL DEFAULT '',
                isGroup INTEGER NOT NULL DEFAULT 0,
                memberIdsJson TEXT NOT NULL DEFAULT '[]',
                pinned INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,
                created TEXT NOT NULL,
                updated TEXT NOT NULL,
                lastActive TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id TEXT NOT NULL PRIMARY KEY,
                threadId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                speakerId TEXT,
                speakerName TEXT,
                kind TEXT NOT NULL DEFAULT 'text',
                created TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_threadId ON chat_messages(threadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_created ON chat_messages(created)")
    }
}
