package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quick_shortcuts")
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val orderIndex: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
