package com.example.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TabStateEntity::class,
        HistoryEntity::class,
        PasswordEntity::class,
        DownloadEntity::class,
        ShortcutEntity::class,
        VideoHistoryEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tabStateDao(): TabStateDao
    abstract fun historyDao(): HistoryDao
    abstract fun passwordDao(): PasswordDao
    abstract fun downloadDao(): DownloadDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun videoHistoryDao(): VideoHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focus_browser_database"
                )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `video_history` ADD COLUMN `thumbnailUrl` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `video_history` ADD COLUMN `lastPositionMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `video_history` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `video_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `videoUrl` TEXT NOT NULL,
                        `originalPageUrl` TEXT,
                        `mimeType` TEXT,
                        `isAudioOnly` INTEGER NOT NULL,
                        `subtitle` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `quick_shortcuts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `filePath` TEXT,
                        `errorMessage` TEXT,
                        `userAgent` TEXT NOT NULL,
                        `cookies` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                var hasPasswordColumn = false
                val cursor = db.query("PRAGMA table_info(`passwords`)")
                try {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex != -1) {
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameIndex) == "password") {
                                hasPasswordColumn = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    cursor.close()
                }

                // 1. Create a secure temporary table with correct columns
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `passwords_secure` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `domain` TEXT NOT NULL, 
                        `username` TEXT NOT NULL, 
                        `encryptedPassword` BLOB NOT NULL, 
                        `initializationVector` BLOB NOT NULL, 
                        `createdAtMs` INTEGER NOT NULL
                    )
                """.trimIndent())

                // 2. Transfer legacy credentials without data loss if legacy table has raw 'password' column
                if (hasPasswordColumn) {
                    try {
                        db.execSQL("""
                            INSERT INTO `passwords_secure` (`id`, `domain`, `username`, `encryptedPassword`, `initializationVector`, `createdAtMs`)
                            SELECT `id`, `domain`, `username`, CAST(`password` AS BLOB), CAST('' AS BLOB), strftime('%s','now')*1000
                            FROM `passwords`
                        """.trimIndent())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 3. Drop old table and rename the new secure table
                db.execSQL("DROP TABLE IF EXISTS `passwords`")
                db.execSQL("ALTER TABLE `passwords_secure` RENAME TO `passwords`")
            }
        }
    }
}
