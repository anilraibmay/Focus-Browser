package com.example.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TabStateDao {
    @Query("SELECT * FROM frozen_tabs")
    fun getAllTabsFlow(): Flow<List<TabStateEntity>>

    @Query("SELECT * FROM frozen_tabs")
    suspend fun getAllTabs(): List<TabStateEntity>

    @Query("SELECT * FROM frozen_tabs WHERE id = :id")
    suspend fun getTabById(id: String): TabStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: TabStateEntity)

    @Delete
    suspend fun deleteTab(tab: TabStateEntity)

    @Query("DELETE FROM frozen_tabs WHERE id = :id")
    suspend fun deleteTabById(id: String)

    @Query("DELETE FROM frozen_tabs WHERE id NOT IN (:activeIds)")
    suspend fun deleteTabsNotIn(activeIds: List<String>)

    @Query("DELETE FROM frozen_tabs")
    suspend fun deleteAllTabs()
}
