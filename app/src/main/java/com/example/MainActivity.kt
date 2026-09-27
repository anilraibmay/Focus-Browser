package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.BrowserMainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.BrowserViewModel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.fragment.app.FragmentActivity
import com.example.viewmodel.CredentialViewModel

class MainActivity : FragmentActivity() {
  private var browserViewModelRef: BrowserViewModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    sanitizeWebViewCache()
    com.example.util.PlayerCipherDeobfuscator.init(applicationContext)
    enableEdgeToEdge()
    setContent {
      val browserViewModel: BrowserViewModel = viewModel()
      val credentialViewModel: CredentialViewModel = viewModel()
      val preferences by browserViewModel.preferences.collectAsState()
      browserViewModelRef = browserViewModel

      MyApplicationTheme(themeMode = preferences.themeMode) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          BrowserMainScreen(viewModel = browserViewModel, credentialViewModel = credentialViewModel)
        }
      }
    }
  }

  override fun onPause() {
    super.onPause()
    browserViewModelRef?.saveCurrentSession()
  }

  override fun onStop() {
    super.onStop()
    browserViewModelRef?.saveCurrentSession()
  }

  private fun sanitizeWebViewCache() {
    try {
      // Chromium manages its own internal cache structures in WebView/Default/HTTP Cache.
      // Pre-creating these directories with mkdirs() breaks Chromium's SimpleIndexFile
      // state machine. If an unclean shutdown left orphaned Code Cache files without an index,
      // purge the corrupt Code Cache directory so Chromium rebuilds a pristine index.
      val codeCache = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
      if (codeCache.exists()) {
        val indexDir = java.io.File(codeCache, "js/index-dir")
        if (!indexDir.exists() || indexDir.listFiles().isNullOrEmpty()) {
          codeCache.deleteRecursively()
        }
      }
    } catch (_: Exception) {
      // Ignored if file access is restricted
    }
  }
}
