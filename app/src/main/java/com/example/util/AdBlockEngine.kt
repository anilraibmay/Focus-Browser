package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class AdBlockEngine private constructor() {

    private val blockedDomains = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val compiledRegexes = java.util.concurrent.CopyOnWriteArrayList<Regex>()
    @Volatile
    private var isLoaded = false

    companion object {
        @Volatile
        private var instance: AdBlockEngine? = null

        fun getInstance(): AdBlockEngine {
            return instance ?: synchronized(this) {
                instance ?: AdBlockEngine().also { instance = it }
            }
        }
    }

    suspend fun loadRules(context: Context) = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        // Emulação de regras EasyList para anúncios estruturados e telemetria
        val defaultRules = listOf(
            "||doubleclick.net^",
            "||adservice.google.com^",
            "||googleadservices.com^",
            "||analytics.google.com^",
            "||facebook.net/en_US/fbevents.js",
            "||connect.facebook.net^",
            "||googlesyndication.com^",
            "||taboola.com^",
            "||outbrain.com^",
            "||criteo.com^",
            "||scorecardresearch.com^",
            "||quantserve.com^",
            "||amazon-adsystem.com^",
            "||advertising.com^",
            "-analytics.",
            "/ads.js",
            "/adwords/",
            "/analytics.js",
            "?ad_type=",
            "&ad_zone=",
            "/telemetry.",
            "/tracker.",
            "metrics.js"
        )
        
        parseRules(defaultRules)
        isLoaded = true
        Log.d("AdBlockEngine", "Loaded ${blockedDomains.size} domains and ${compiledRegexes.size} regex filters.")
    }

    private fun parseRules(rules: List<String>) {
        rules.forEach { rule ->
            if (rule.isBlank() || rule.startsWith("!")) return@forEach
            
            if (rule.startsWith("||") && rule.endsWith("^")) {
                // Rule format: ||domain.com^ -> block this exact domain and subdomains
                val domain = rule.substring(2, rule.length - 1)
                blockedDomains.add(domain)
            } else {
                // Compile wildcard/substring strings into optimized case-insensitive Regexes
                val cleanRule = rule.replace("^", "")
                val regexPattern = cleanRule.split("*")
                    .joinToString(".*") { Regex.escape(it) }
                try {
                    compiledRegexes.add(Regex(regexPattern, RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    // Fallback to escaped literal match if splitting/escaping fails
                    compiledRegexes.add(Regex(Regex.escape(cleanRule), RegexOption.IGNORE_CASE))
                }
            }
        }
    }

    fun isAdOrTracker(url: String): Boolean {
        if (!isLoaded) return false

        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false

            // 1. O(1) domain checks
            for (domain in blockedDomains) {
                val lowerDomain = domain.lowercase()
                if (host == lowerDomain || host.endsWith(".$lowerDomain")) {
                    return true
                }
            }

            // 2. Optimized regular expression evaluation
            for (regex in compiledRegexes) {
                if (regex.containsMatchIn(url)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("AdBlockEngine", "Error checking URL: $url", e)
        }
        return false
    }
}
