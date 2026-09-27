package com.example.controller

import android.webkit.CookieManager
import com.example.database.HistoryEntity
import com.example.database.HistoryRepository
import com.example.model.BrowserTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gerenciador responsável pelo histórico de navegação:
 * registro, listagem reativa, exclusão individual, limpeza total e de cookies/cache.
 */
class HistoryManager(
    private val historyRepository: HistoryRepository,
    private val scope: CoroutineScope
) {
    private val _historyList = MutableStateFlow<List<HistoryEntity>>(emptyList())
    val historyList: StateFlow<List<HistoryEntity>> = _historyList.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            historyRepository.allHistoryFlow.collect { list ->
                _historyList.value = list
            }
        }
    }

    /**
     * Adiciona um novo registro ao histórico de navegação caso não seja em branco ou duplicata imediata.
     */
    fun addToHistory(title: String, url: String) {
        if (url.isEmpty() || url == "about:blank") return
        scope.launch(Dispatchers.IO) {
            val currentList = _historyList.value
            val isDuplicate = currentList.firstOrNull()?.let { last ->
                last.url == url && last.title == title
            } ?: false

            if (!isDuplicate) {
                historyRepository.insertHistory(
                    HistoryEntity(
                        title = if (title.isEmpty()) "Nova guia" else title,
                        url = url
                    )
                )
            }
        }
    }

    /**
     * Remove um item específico do histórico pelo ID.
     */
    fun deleteHistoryEntry(id: Long) {
        scope.launch(Dispatchers.IO) {
            historyRepository.deleteHistoryById(id)
        }
    }

    /**
     * Apaga todos os registros do histórico no banco de dados.
     */
    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            historyRepository.deleteAllHistory()
        }
    }

    /**
     * Limpa histórico do banco, cache de todos os WebViews ativos e cookies de navegação.
     */
    fun clearHistoryAndCache(tabs: List<BrowserTab>, splitTab: BrowserTab?) {
        tabs.forEach { tab ->
            tab.webView?.let {
                it.clearCache(true)
                it.clearHistory()
            }
        }
        splitTab?.webView?.let {
            it.clearCache(true)
            it.clearHistory()
        }
        CookieManager.getInstance().removeAllCookies(null)
        clearHistory()
    }
}
