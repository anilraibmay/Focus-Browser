package com.example.database

import kotlinx.coroutines.flow.Flow

class TabStateRepository(private val tabStateDao: TabStateDao) {
    val allTabsFlow: Flow<List<TabStateEntity>> = tabStateDao.getAllTabsFlow()

    suspend fun getAllTabs(): List<TabStateEntity> = tabStateDao.getAllTabs()

    suspend fun getTabById(id: String): TabStateEntity? = tabStateDao.getTabById(id)

    suspend fun insertTab(tab: TabStateEntity) = tabStateDao.insertTab(tab)

    suspend fun deleteTabById(id: String) = tabStateDao.deleteTabById(id)

    suspend fun deleteTabsNotIn(activeIds: List<String>) = tabStateDao.deleteTabsNotIn(activeIds)

    suspend fun deleteAllTabs() = tabStateDao.deleteAllTabs()
}
