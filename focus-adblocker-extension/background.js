chrome.runtime.onInstalled.addListener(() => {
  console.log("[Focus Extension] Instalado com sucesso!");
  chrome.storage.local.set({
    enabled: true,
    totalBlock: true,
    focusMode: false,
    bgTheme: "normal",
    blockedCount: 0
  });
  syncDeclarativeRules(true);
});

chrome.runtime.onStartup.addListener(() => {
  chrome.storage.local.get(["enabled"], (data) => {
    syncDeclarativeRules(data.enabled !== false);
  });
});

// Sincroniza o conjunto de regras DeclarativeNetRequest com o estado do botão 'enabled'
function syncDeclarativeRules(enabled) {
  if (chrome.declarativeNetRequest && chrome.declarativeNetRequest.updateEnabledRulesets) {
    if (enabled) {
      chrome.declarativeNetRequest.updateEnabledRulesets({
        enableRulesetIds: ["ruleset_1"]
      }).catch(() => {});
    } else {
      chrome.declarativeNetRequest.updateEnabledRulesets({
        disableRulesetIds: ["ruleset_1"]
      }).catch(() => {});
    }
  }
}

// Escuta mudanças de configuração no storage
chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace === "local" && changes.enabled !== undefined) {
    syncDeclarativeRules(changes.enabled.newValue);
  }
});

// Listener de requisições bloqueadas via DeclarativeNetRequest
if (chrome.declarativeNetRequest && chrome.declarativeNetRequest.onRuleMatchedDebug) {
  chrome.declarativeNetRequest.onRuleMatchedDebug.addListener((info) => {
    chrome.storage.local.get(["blockedCount"], (result) => {
      const current = result.blockedCount || 0;
      chrome.storage.local.set({ blockedCount: current + 1 });
    });
  });
}

// Bloqueio de novas abas indesejadas (Popups/Popunders) via WebNavigation API
if (chrome.webNavigation && chrome.webNavigation.onCreatedNavigationTarget) {
  chrome.webNavigation.onCreatedNavigationTarget.addListener((details) => {
    chrome.storage.local.get(["enabled", "totalBlock", "blockedCount"], (data) => {
      if (!data.enabled || !data.totalBlock) return;

      const url = (details.url || "").toLowerCase();
      const isKnownAd = /popad|popcash|exoclick|propeller|adsterra|clickadu|hilltopads|monetag|bet365|doubleclick|syndication|outbrain|taboola|ad-delivery|a-ads|juicyads|redirect|clck\.ru/i.test(url);

      if (details.sourceTabId !== undefined) {
        if (isKnownAd || url === "about:blank" || url === "") {
          console.warn("[Focus Extension] Nova aba/popup indesejada fechada automaticamente:", details.url);
          chrome.tabs.remove(details.tabId).catch(() => {});
          
          const current = data.blockedCount || 0;
          chrome.storage.local.set({ blockedCount: current + 1 });
        }
      }
    });
  });
}

// Monitora navegação antes de carregar domínios para fechar abas filhas que tentam redirecionar para anúncios
if (chrome.webNavigation && chrome.webNavigation.onBeforeNavigate) {
  chrome.webNavigation.onBeforeNavigate.addListener((details) => {
    if (details.frameId === 0) {
      chrome.storage.local.get(["enabled", "totalBlock", "blockedCount"], (data) => {
        if (!data.enabled || !data.totalBlock) return;

        const url = (details.url || "").toLowerCase();
        const isKnownAd = /popad|popcash|exoclick|propeller|adsterra|clickadu|hilltopads|monetag|bet365|syndication|redirect|juicyads/i.test(url);

        if (isKnownAd) {
          chrome.tabs.get(details.tabId, (tab) => {
            if (chrome.runtime.lastError || !tab) return;
            if (tab.openerTabId) {
              console.warn("[Focus Extension] Navegação para anúncio em aba secundária bloqueada:", url);
              chrome.tabs.remove(details.tabId).catch(() => {});
              const current = data.blockedCount || 0;
              chrome.storage.local.set({ blockedCount: current + 1 });
            }
          });
        }
      });
    }
  });
}

// Monitora criação de abas para fechar janelas popunders em segundo plano
if (chrome.tabs && chrome.tabs.onCreated) {
  chrome.tabs.onCreated.addListener((tab) => {
    chrome.storage.local.get(["enabled", "totalBlock", "blockedCount"], (data) => {
      if (!data.enabled || !data.totalBlock) return;

      if (tab.openerTabId && !tab.active) {
        const url = (tab.url || tab.pendingUrl || "").toLowerCase();
        const isKnownAd = /popad|popcash|exoclick|propeller|adsterra|clickadu|hilltopads|monetag|bet365|syndication|redirect/i.test(url);

        if (isKnownAd) {
          console.warn("[Focus Extension] Popunder em segundo plano fechado:", url);
          chrome.tabs.remove(tab.id).catch(() => {});
          const current = data.blockedCount || 0;
          chrome.storage.local.set({ blockedCount: current + 1 });
        }
      }
    });
  });
}

// Comunicação com Content Scripts e Popup (Downloader incluído)
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === "getSettings") {
    chrome.storage.local.get(["enabled", "totalBlock", "focusMode", "bgTheme", "blockedCount"], (data) => {
      sendResponse(data);
    });
    return true;
  }

  if (request.action === "incrementBlocked") {
    chrome.storage.local.get(["blockedCount"], (result) => {
      const current = result.blockedCount || 0;
      const added = request.count || 1;
      chrome.storage.local.set({ blockedCount: current + added });
      sendResponse({ success: true, newCount: current + added });
    });
    return true;
  }

  // Executa o Download de arquivos e mídias através da Chrome Downloads API
  if (request.action === "downloadMedia" && request.url) {
    chrome.downloads.download({
      url: request.url,
      filename: request.filename || undefined,
      saveAs: false
    }, (downloadId) => {
      if (chrome.runtime.lastError) {
        console.error("[Focus Downloader] Erro ao baixar:", chrome.runtime.lastError.message);
        sendResponse({ success: false, error: chrome.runtime.lastError.message });
      } else {
        console.log("[Focus Downloader] Download iniciado com ID:", downloadId);
        sendResponse({ success: true, downloadId: downloadId });
      }
    });
    return true;
  }
});
