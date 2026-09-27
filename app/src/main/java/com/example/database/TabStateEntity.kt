package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "frozen_tabs")
data class TabStateEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val openedInBackground: Boolean,
    val lastUpdated: String?,
    val isFrozen: Boolean,
    val webViewState: ByteArray? = null,
    val thumbnailState: ByteArray? = null,
    val isDiscarded: Boolean = false
)
