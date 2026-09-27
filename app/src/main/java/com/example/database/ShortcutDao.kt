package com.example.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM quick_shortcuts ORDER BY orderIndex ASC, id ASC")
    fun getAllShortcutsFlow(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM quick_shortcuts ORDER BY orderIndex ASC, id ASC")
    suspend fun getAllShortcuts(): List<ShortcutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShortcut(shortcut: ShortcutEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shortcuts: List<ShortcutEntity>)

    @Update
    suspend fun updateShortcut(shortcut: ShortcutEntity)

    @Delete
    suspend fun deleteShortcut(shortcut: ShortcutEntity)

    @Query("DELETE FROM quick_shortcuts WHERE id = :id")
    suspend fun deleteShortcutById(id: Long)

    @Query("DELETE FROM quick_shortcuts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM quick_shortcuts")
    suspend fun getCount(): Int
}
