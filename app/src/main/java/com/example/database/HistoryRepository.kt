package com.example.database

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {
    val allHistoryFlow: Flow<List<HistoryEntity>> = historyDao.getAllHistoryFlow()

    suspend fun insertHistory(item: HistoryEntity) {
        historyDao.insertHistory(item)
    }

    suspend fun deleteHistoryById(id: Long) {
        historyDao.deleteHistoryById(id)
    }

    suspend fun deleteAllHistory() {
        historyDao.deleteAllHistory()
    }
}
