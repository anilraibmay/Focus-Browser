package com.example.controller

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.model.BrowserPreferences
import com.example.model.FocusMode
import com.example.util.AdBlockEngine
import java.net.URI
import java.net.URLEncoder

/**
 * Delegate responsible for URL normalization, web search formatting,
 * back/forward navigation, external link domain classification, focus mode routing,
 * and secure intent & deep link confinement (preventing malicious breakouts).
 */
class NavigationDelegate(
    private val onNotify: (String) -> Unit
) {
    // Known external browser packages that malicious ads or intent links try to escape to
    private val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.browser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.ucmobile.intl",
        "com.kiwibrowser.browser"
    )

    fun normalizeAndFormatUrl(urlInput: String): String {
        var formattedUrl = urlInput.trim()
        if (formattedUrl.isEmpty()) {
            return "about:blank"
        }
        if (formattedUrl.startsWith("about:")) {
            return formattedUrl
        }

        val hasSpaces = formattedUrl.contains(" ")
        val hasDot = formattedUrl.contains(".")
        val isUrlPattern = (formattedUrl.startsWith("http://") || formattedUrl.startsWith("https://")) || (hasDot && !hasSpaces)

        return if (!isUrlPattern) {
            "https://www.google.com/search?q=" + URLEncoder.encode(formattedUrl, "UTF-8")
        } else if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            "https://" + formattedUrl
        } else {
            formattedUrl
        }
    }

    fun isWithinSameDomain(url1: String, url2: String): Boolean {
        if (url1.isEmpty() || url2.isEmpty()) return false
        return try {
            val uri1 = URI(url1)
            val uri2 = URI(url2)
            val host1 = (uri1.host ?: "").trim().lowercase()
            val host2 = (uri2.host ?: "").trim().lowercase()

            if (host1.isEmpty() || host2.isEmpty()) return false
            if (host1 == host2) return true

            // Normalize canonical apex vs www subdomains (e.g. embedplay.one <-> www.embedplay.one)
            val d1 = host1.removePrefix("www.")
            val d2 = host2.removePrefix("www.")
            d1 == d2 || d1.endsWith(".$d2") || d2.endsWith(".$d1")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Attempts to extract a real web (HTTP/HTTPS) destination URL from an intent URI
     * ONLY when it is an explicit attempt to force-escape to an external browser
     * (e.g. googlechrome://, googlechromes://, or intent: targeting Chrome/Firefox packages).
     */
    private fun extractWebUrlFromIntentOrScheme(url: String): String? {
        val lower = url.lowercase()

        // 1. Handle googlechrome:// or googlechromes://
        if (lower.startsWith("googlechrome://") || lower.startsWith("googlechromes://")) {
            try {
                val uri = Uri.parse(url)
                val target = uri.getQueryParameter("url")
                if (!target.isNullOrBlank() && (target.startsWith("http://") || target.startsWith("https://"))) {
                    return target
                }
                val isHttps = lower.startsWith("googlechromes://")
                return (if (isHttps) "https://" else "http://") + url.substringAfter("://")
            } catch (_: Exception) {
                return null
            }
        }

        // 2. Handle intent:// scheme ONLY if explicitly targeting a known external browser
        if (lower.startsWith("intent:")) {
            try {
                val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val targetPkg = parsed.`package`?.lowercase()
                if (targetPkg != null && browserPackages.contains(targetPkg)) {
                    val fallback = parsed.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                        return fallback
                    }
                    val dataUri = parsed.dataString
                    if (!dataUri.isNullOrBlank() && (dataUri.startsWith("http://") || dataUri.startsWith("https://"))) {
                        return dataUri
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun handleLinkNavigation(
        context: Context,
        url: String,
        currentUrl: String,
        hasGesture: Boolean,
        isRedirect: Boolean,
        preferences: BrowserPreferences,
        onOpenInBackground: (String) -> Unit,
        onOpenInSplit: (String) -> Unit,
        onLoadInCurrentTab: (String) -> Unit = {}
    ): Boolean {
        // 1. AdBlocker Check: Intercept and block ad-network clicks & redirects
        if (preferences.enableAdBlock && AdBlockEngine.getInstance().isAdOrTracker(url)) {
            onNotify("Anúncio/Rastreador bloqueado pelo AdBlocker!")
            return true
        }

        // 2. Detect if link is trying to escape to an external browser (e.g. Chrome) via intent:// or googlechrome://
        val extractedWebUrl = extractWebUrlFromIntentOrScheme(url)
        if (extractedWebUrl != null) {
            // Check extracted target against AdBlocker
            if (preferences.enableAdBlock && AdBlockEngine.getInstance().isAdOrTracker(extractedWebUrl)) {
                onNotify("Anúncio externo bloqueado pelo AdBlocker!")
                return true
            }

            // Route the extracted URL through our internal Focus and Domain rules
            return routeWebNavigation(
                url = extractedWebUrl,
                currentUrl = currentUrl,
                hasGesture = hasGesture,
                isRedirect = isRedirect,
                preferences = preferences,
                onOpenInBackground = onOpenInBackground,
                onOpenInSplit = onOpenInSplit,
                onLoadInCurrentTab = onLoadInCurrentTab
            )
        }

        // 3. Handle non-HTTP/HTTPS schemes (e.g. intent://, market://, tel:, whatsapp:, tg:, maps:, etc.)
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("about:")) {
            val mode = preferences.focusMode

            // In TOTAL_BLOCK mode, block ALL external schemes and application launches
            if (mode == FocusMode.TOTAL_BLOCK) {
                onNotify("Modo Bloqueio Total: Abertura de app externo bloqueada!")
                return true
            }

            // Reject automatic background scheme executions (must have user tap gesture)
            if (!hasGesture) {
                return true
            }

            try {
                val intent = if (url.startsWith("intent:", ignoreCase = true)) {
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Strip browser-targeting package escape attempts
                val targetPkg = intent.`package`
                if (targetPkg != null && browserPackages.contains(targetPkg.lowercase())) {
                    val fallback = intent.getStringExtra("browser_fallback_url") ?: intent.dataString
                    if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                        return routeWebNavigation(
                            url = fallback,
                            currentUrl = currentUrl,
                            hasGesture = hasGesture,
                            isRedirect = isRedirect,
                            preferences = preferences,
                            onOpenInBackground = onOpenInBackground,
                            onOpenInSplit = onOpenInSplit,
                            onLoadInCurrentTab = onLoadInCurrentTab
                        )
                    }
                    onNotify("Tentativa de escape para navegador externo bloqueada!")
                    return true
                }

                // Check if activity resolves to another web browser
                val resolved = intent.resolveActivity(context.packageManager)
                if (resolved != null && browserPackages.contains(resolved.packageName.lowercase())) {
                    val fallback = intent.getStringExtra("browser_fallback_url") ?: intent.dataString
                    if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                        return routeWebNavigation(
                            url = fallback,
                            currentUrl = currentUrl,
                            hasGesture = hasGesture,
                            isRedirect = isRedirect,
                            preferences = preferences,
                            onOpenInBackground = onOpenInBackground,
                            onOpenInSplit = onOpenInSplit,
                            onLoadInCurrentTab = onLoadInCurrentTab
                        )
                    }
                    onNotify("Tentativa de escape para navegador externo bloqueada!")
                    return true
                }

                // Launch legitimate external native application (WhatsApp, Telegram, Phone, Maps, Banking, etc.)
                try {
                    context.startActivity(intent)
                    onNotify("A abrir aplicação...")
                    return true
                } catch (e: Exception) {
                    // App is not installed: Check for web fallback URL
                    val fallback = intent.getStringExtra("browser_fallback_url") ?: intent.dataString
                    if (!fallback.isNullOrBlank() && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                        return routeWebNavigation(
                            url = fallback,
                            currentUrl = currentUrl,
                            hasGesture = hasGesture,
                            isRedirect = isRedirect,
                            preferences = preferences,
                            onOpenInBackground = onOpenInBackground,
                            onOpenInSplit = onOpenInSplit,
                            onLoadInCurrentTab = onLoadInCurrentTab
                        )
                    }
                    return true
                }
            } catch (e: Exception) {
                return true
            }
        }

        // 4. Regular HTTP / HTTPS web navigation
        return routeWebNavigation(
            url = url,
            currentUrl = currentUrl,
            hasGesture = hasGesture,
            isRedirect = isRedirect,
            preferences = preferences,
            onOpenInBackground = onOpenInBackground,
            onOpenInSplit = onOpenInSplit,
            onLoadInCurrentTab = onLoadInCurrentTab
        )
    }

    private fun routeWebNavigation(
        url: String,
        currentUrl: String,
        hasGesture: Boolean,
        isRedirect: Boolean,
        preferences: BrowserPreferences,
        onOpenInBackground: (String) -> Unit,
        onOpenInSplit: (String) -> Unit,
        onLoadInCurrentTab: (String) -> Unit
    ): Boolean {
        if (isRedirect) {
            return false
        }

        val mode = preferences.focusMode
        val isSameDomain = isWithinSameDomain(currentUrl, url)

        if (!isSameDomain && mode != FocusMode.NORMAL) {
            if (mode == FocusMode.TOTAL_BLOCK) {
                onNotify("Modo Bloqueio Total: Link externo bloqueado!")
                return true
            }
            if (hasGesture) {
                if (mode == FocusMode.BACKGROUND) {
                    onOpenInBackground(url)
                    return true
                } else if (mode == FocusMode.SPLIT_SCREEN) {
                    onOpenInSplit(url)
                    onNotify("Painel de Foco aberto na lateral!")
                    return true
                }
            } else {
                // Background script or auto-redirect trying to leave the domain
                onNotify("Redirecionamento automático externo bloqueado!")
                return true
            }
        }

        return false
    }
}

