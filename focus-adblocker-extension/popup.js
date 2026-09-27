document.addEventListener('DOMContentLoaded', () => {
  const countElement = document.getElementById('count');
  const statusBadge = document.getElementById('statusBadge');

  const toggleEnabled = document.getElementById('toggleEnabled');
  const toggleTotalBlock = document.getElementById('toggleTotalBlock');
  const toggleFocusMode = document.getElementById('toggleFocusMode');

  const themeButtons = document.querySelectorAll('.theme-btn');
  const btnReloadTab = document.getElementById('btnReloadTab');
  const btnScanMedia = document.getElementById('btnScanMedia');
  const mediaListContainer = document.getElementById('mediaList');
  const infoBanner = document.getElementById('infoBanner');

  let currentSettings = {
    enabled: true,
    totalBlock: true,
    focusMode: false,
    bgTheme: 'normal',
    blockedCount: 0
  };

  // Carrega estado do storage local
  chrome.storage.local.get(['enabled', 'totalBlock', 'focusMode', 'bgTheme', 'blockedCount'], (result) => {
    if (result.enabled !== undefined) currentSettings.enabled = result.enabled;
    if (result.totalBlock !== undefined) currentSettings.totalBlock = result.totalBlock;
    if (result.focusMode !== undefined) currentSettings.focusMode = result.focusMode;
    if (result.bgTheme !== undefined) currentSettings.bgTheme = result.bgTheme;
    if (result.blockedCount !== undefined) currentSettings.blockedCount = result.blockedCount;

    if (currentSettings.totalBlock && currentSettings.focusMode) {
      currentSettings.focusMode = false;
    }

    updateUI();
    scanPageMedia();
  });

  // Atualiza a visualização do número de bloqueios do storage em tempo real
  chrome.storage.onChanged.addListener((changes, namespace) => {
    if (namespace === 'local' && changes.blockedCount) {
      currentSettings.blockedCount = changes.blockedCount.newValue || 0;
      countElement.textContent = currentSettings.blockedCount;
    }
  });

  function updateUI() {
    countElement.textContent = currentSettings.blockedCount || 0;

    toggleEnabled.checked = currentSettings.enabled;
    toggleTotalBlock.checked = currentSettings.totalBlock;
    toggleFocusMode.checked = currentSettings.focusMode;

    toggleTotalBlock.disabled = !currentSettings.enabled;
    toggleFocusMode.disabled = !currentSettings.enabled;

    if (currentSettings.enabled) {
      statusBadge.textContent = 'Proteção Ativa';
      statusBadge.classList.remove('disabled');
    } else {
      statusBadge.textContent = 'Pausado';
      statusBadge.classList.add('disabled');
    }

    themeButtons.forEach(btn => {
      const theme = btn.getAttribute('data-theme');
      if (theme === currentSettings.bgTheme) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });
  }

  function saveAndNotify() {
    chrome.storage.local.set({
      enabled: currentSettings.enabled,
      totalBlock: currentSettings.totalBlock,
      focusMode: currentSettings.focusMode,
      bgTheme: currentSettings.bgTheme
    }, () => {
      chrome.tabs.query({}, (tabs) => {
        if (tabs && tabs.length) {
          tabs.forEach(tab => {
            if (tab.id) {
              chrome.tabs.sendMessage(tab.id, {
                action: 'updateSettings',
                settings: {
                  enabled: currentSettings.enabled,
                  totalBlock: currentSettings.totalBlock,
                  focusMode: currentSettings.focusMode,
                  bgTheme: currentSettings.bgTheme
                }
              }).catch(() => {});
            }
          });
        }
      });
    });

    updateUI();
  }

  // --- ESCANEAMENTO E RENDERIZAÇÃO DE MÍDIAS & STREAMS DE VÍDEO ---
  function scanPageMedia() {
    mediaListContainer.innerHTML = '<div class="empty-media">Escaneando mídias e streams de vídeo na página...</div>';
    if (infoBanner) infoBanner.style.display = 'none';

    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (!tabs || !tabs[0] || !tabs[0].id) {
        mediaListContainer.innerHTML = '<div class="empty-media">Não foi possível acessar a aba atual.</div>';
        return;
      }

      chrome.tabs.sendMessage(tabs[0].id, { action: 'detectMedia' }, (response) => {
        if (chrome.runtime.lastError || !response || !response.mediaItems || response.mediaItems.length === 0) {
          mediaListContainer.innerHTML = '<div class="empty-media">Nenhuma mídia ou stream detectado. Tente dar play no vídeo!</div>';
          return;
        }

        renderMediaList(response.mediaItems);
      });
    });
  }

  function renderMediaList(items) {
    mediaListContainer.innerHTML = '';
    let hasStreamItem = false;

    items.forEach(item => {
      const div = document.createElement('div');
      div.className = 'media-item';

      const extLower = (item.ext || '').toLowerCase();
      const urlLower = (item.url || '').toLowerCase();
      const typeLower = (item.type || '').toLowerCase();

      // Identifica se a mídia é um Stream ou Manifesto (DASH / HLS / JSON / Blob)
      const isStream = item.isStream || item.isManifest || item.isBlob || 
                       ['m3u8', 'mpd', 'json', 'embed'].includes(extLower) || 
                       ['hls', 'dash', 'manifest', 'stream'].includes(typeLower) ||
                       urlLower.includes('manifest') || urlLower.includes('playlist') || urlLower.includes('.json');

      if (isStream) hasStreamItem = true;

      let icon = '📁';
      if (item.type === 'video' && !isStream) icon = '📹';
      else if (isStream) icon = '📡';
      else if (item.type === 'audio') icon = '🎵';
      else if (item.type === 'image') icon = '🖼️';

      let platformBadgeClass = 'platform-default';
      const platLower = (item.platform || '').toLowerCase();
      if (platLower.includes('youtube')) platformBadgeClass = 'platform-youtube';
      else if (platLower.includes('facebook')) platformBadgeClass = 'platform-facebook';

      const platformLabel = item.platform || 'Mídia';

      let metaHtml = '';
      if (item.quality) {
        metaHtml += `<span class="meta-tag">📺 ${item.quality}</span>`;
      }
      if (item.duration) {
        metaHtml += `<span class="meta-tag">⏱️ ${item.duration}</span>`;
      }
      if (item.size) {
        metaHtml += `<span class="meta-tag">📦 ${item.size}</span>`;
      }

      if (isStream) {
        metaHtml += `<span class="meta-tag" style="background: rgba(255, 179, 0, 0.15); color: #ffb300; border: 1px solid rgba(255, 179, 0, 0.3);">MANIFESTO</span>`;
      } else if (item.ext) {
        metaHtml += `<span class="meta-tag">${item.ext.toUpperCase()}</span>`;
      }

      const actionBtnText = isStream ? 'Copiar Stream' : 'Baixar';
      const actionBtnClass = isStream ? 'btn-download btn-copy-stream' : 'btn-download';

      div.innerHTML = `
        <div class="media-info">
          <span class="media-icon">${icon}</span>
          <div class="media-details">
            <div class="media-name-row">
              <span class="platform-badge ${platformBadgeClass}">${platformLabel}</span>
              <span class="media-name" title="${item.name}">${item.name}</span>
            </div>
            <div class="media-meta-row">
              ${metaHtml}
            </div>
          </div>
        </div>
        <button class="${actionBtnClass}">${actionBtnText}</button>
      `;

      const actionBtn = div.querySelector('.btn-download');

      actionBtn.addEventListener('click', () => {
        if (isStream || item.url.startsWith('blob:') || extLower === 'json') {
          // Copia link direto do HLS/DASH/JSON/Blob para a área de transferência do usuário
          navigator.clipboard.writeText(item.url).then(() => {
            actionBtn.textContent = '✓ Copiado!';
            actionBtn.style.background = '#03dac6';
            actionBtn.style.color = '#121212';
            if (infoBanner) infoBanner.style.display = 'block';

            setTimeout(() => {
              actionBtn.textContent = actionBtnText;
              actionBtn.style.background = '';
              actionBtn.style.color = '';
            }, 3000);
          }).catch(() => {
            actionBtn.textContent = 'Erro ao copiar';
          });
        } else {
          // Download direto via Chrome Downloads API
          actionBtn.textContent = 'Baixando...';
          actionBtn.disabled = true;

          chrome.runtime.sendMessage({
            action: 'downloadMedia',
            url: item.url,
            filename: item.name
          }, (res) => {
            if (res && res.success) {
              actionBtn.textContent = '✓ Baixado';
              actionBtn.style.background = '#03dac6';
              actionBtn.style.color = '#121212';
            } else {
              // Se falhar ou for um endpoint restrito, alterna para Copiar URL
              actionBtn.textContent = 'Copiar Link';
              actionBtn.disabled = false;
              navigator.clipboard.writeText(item.url).catch(() => {});
              if (infoBanner) infoBanner.style.display = 'block';
            }
          });
        }
      });

      mediaListContainer.appendChild(div);
    });

    if (hasStreamItem && infoBanner) {
      infoBanner.style.display = 'block';
    }
  }

  // Event Listeners
  if (btnScanMedia) {
    btnScanMedia.addEventListener('click', scanPageMedia);
  }

  toggleEnabled.addEventListener('change', (e) => {
    currentSettings.enabled = e.target.checked;
    saveAndNotify();
  });

  toggleTotalBlock.addEventListener('change', (e) => {
    currentSettings.totalBlock = e.target.checked;
    if (e.target.checked) {
      currentSettings.focusMode = false;
    }
    saveAndNotify();
  });

  toggleFocusMode.addEventListener('change', (e) => {
    currentSettings.focusMode = e.target.checked;
    if (e.target.checked) {
      currentSettings.totalBlock = false;
    }
    saveAndNotify();
  });

  themeButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const theme = btn.getAttribute('data-theme');
      currentSettings.bgTheme = theme;
      saveAndNotify();
    });
  });

  if (btnReloadTab) {
    btnReloadTab.addEventListener('click', () => {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs && tabs[0] && tabs[0].id) {
          chrome.tabs.reload(tabs[0].id);
        }
      });
    });
  }
});
