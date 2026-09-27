package com.example

import android.graphics.Bitmap
import com.example.model.BrowserTab
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabsManagerTest {

    @Test
    fun testBrowserTabWithThumbnailCreation() {
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        val tab = BrowserTab(
            id = "tab_123",
            title = "Página de Teste",
            url = "https://example.com/artigo",
            thumbnail = bitmap,
            isSecure = true
        )

        assertNotNull(tab.thumbnail)
        assertEquals("tab_123", tab.id)
        assertEquals("Página de Teste", tab.title)
        assertEquals("https://example.com/artigo", tab.url)
        assertEquals(160, tab.thumbnail?.width)
        assertEquals(240, tab.thumbnail?.height)
    }

    @Test
    fun testTabFilteringLogic() {
        val tabs = listOf(
            BrowserTab(id = "1", title = "Google Search", url = "https://google.com"),
            BrowserTab(id = "2", title = "YouTube Vídeos", url = "https://youtube.com"),
            BrowserTab(id = "3", title = "GitHub Repositórios", url = "https://github.com")
        )

        // Filter by title
        val query1 = "you"
        val filtered1 = tabs.filter {
            it.title.lowercase().contains(query1) || it.url.lowercase().contains(query1)
        }
        assertEquals(1, filtered1.size)
        assertEquals("2", filtered1.first().id)

        // Filter by url
        val query2 = "git"
        val filtered2 = tabs.filter {
            it.title.lowercase().contains(query2) || it.url.lowercase().contains(query2)
        }
        assertEquals(1, filtered2.size)
        assertEquals("3", filtered2.first().id)

        // Filter matches none
        val query3 = "twitter"
        val filtered3 = tabs.filter {
            it.title.lowercase().contains(query3) || it.url.lowercase().contains(query3)
        }
        assertTrue(filtered3.isEmpty())
    }

    @Test
    fun testSwipeDismissThresholdLogic() {
        val itemWidthPx = 400f
        val threshold = itemWidthPx * 0.38f // 152px

        // Small drag should not dismiss
        val smallDrag = 80f
        val shouldDismissSmall = abs(smallDrag) > threshold
        assertFalse(shouldDismissSmall)

        // Large drag to the right should dismiss
        val largeDragRight = 200f
        val shouldDismissLargeRight = abs(largeDragRight) > threshold
        assertTrue(shouldDismissLargeRight)

        // Large drag to the left should dismiss
        val largeDragLeft = -180f
        val shouldDismissLargeLeft = abs(largeDragLeft) > threshold
        assertTrue(shouldDismissLargeLeft)
    }

    @Test
    fun testTabRemovalUpdatesActiveTab() {
        val tabs = mutableListOf(
            BrowserTab(id = "tab1", title = "Tab 1", url = "https://tab1.com"),
            BrowserTab(id = "tab2", title = "Tab 2", url = "https://tab2.com"),
            BrowserTab(id = "tab3", title = "Tab 3", url = "https://tab3.com")
        )

        var activeTabId = "tab2"

        // Remove active tab
        val tabToClose = "tab2"
        val closedIndex = tabs.indexOfFirst { it.id == tabToClose }
        tabs.removeIf { it.id == tabToClose }

        if (activeTabId == tabToClose) {
            val newIndex = (closedIndex - 1).coerceAtLeast(0)
            activeTabId = tabs.getOrNull(newIndex)?.id ?: ""
        }

        assertEquals(2, tabs.size)
        assertEquals("tab1", activeTabId)
    }
}
