package com.example.util

import android.content.MutableContextWrapper
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

object WebViewRegistry {
    private val webViews = ConcurrentHashMap<String, WebView>()

    fun get(tabId: String): WebView? {
        return webViews[tabId]
    }

    fun put(tabId: String, webView: WebView) {
        webViews[tabId] = webView
    }

    fun remove(tabId: String): WebView? {
        return webViews.remove(tabId)
    }

    fun contains(tabId: String): Boolean {
        return webViews.containsKey(tabId)
    }

    fun getAll(): Map<String, WebView> {
        return webViews.toMap()
    }

    fun clear() {
        webViews.keys.forEach { tabId ->
            remove(tabId)?.apply {
                try {
                    stopLoading()
                    removeAllViews()
                    destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        webViews.clear()
    }
}
