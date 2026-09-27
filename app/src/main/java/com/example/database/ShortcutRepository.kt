package com.example.database

import kotlinx.coroutines.flow.Flow

class ShortcutRepository(private val shortcutDao: ShortcutDao) {
    val allShortcutsFlow: Flow<List<ShortcutEntity>> = shortcutDao.getAllShortcutsFlow()

    suspend fun getCount(): Int = shortcutDao.getCount()

    suspend fun getAll(): List<ShortcutEntity> = shortcutDao.getAllShortcuts()

    suspend fun insertShortcut(title: String, url: String, orderIndex: Int = -1): Long {
        val currentCount = shortcutDao.getCount()
        val index = if (orderIndex >= 0) orderIndex else currentCount
        return shortcutDao.insertShortcut(
            ShortcutEntity(
                title = title.trim(),
                url = url.trim(),
                orderIndex = index
            )
        )
    }

    suspend fun updateShortcut(id: Long, title: String, url: String) {
        val all = shortcutDao.getAllShortcuts()
        val existing = all.find { it.id == id }
        if (existing != null) {
            shortcutDao.updateShortcut(
                existing.copy(
                    title = title.trim(),
                    url = url.trim()
                )
            )
        }
    }

    suspend fun deleteShortcut(id: Long) {
        shortcutDao.deleteShortcutById(id)
    }

    suspend fun seedDefaultsIfEmpty() {
        if (shortcutDao.getCount() == 0) {
            resetToDefaults()
        }
    }

    suspend fun resetToDefaults() {
        shortcutDao.deleteAll()
        val defaults = listOf(
            ShortcutEntity(title = "Google", url = "https://www.google.com", orderIndex = 0),
            ShortcutEntity(title = "YouTube", url = "https://www.youtube.com", orderIndex = 1),
            ShortcutEntity(title = "GitHub", url = "https://github.com", orderIndex = 2),
            ShortcutEntity(title = "LinkedIn", url = "https://www.linkedin.com", orderIndex = 3),
            ShortcutEntity(title = "Reddit", url = "https://www.reddit.com", orderIndex = 4),
            ShortcutEntity(title = "X (Twitter)", url = "https://x.com", orderIndex = 5),
            ShortcutEntity(title = "Tiktok", url = "https://www.tiktok.com", orderIndex = 6),
            ShortcutEntity(title = "Notícias", url = "https://news.google.com", orderIndex = 7)
        )
        shortcutDao.insertAll(defaults)
    }

    suspend fun updateOrder(shortcuts: List<ShortcutEntity>) {
        val updated = shortcuts.mapIndexed { index, item -> item.copy(orderIndex = index) }
        shortcutDao.deleteAll()
        shortcutDao.insertAll(updated)
    }
}
