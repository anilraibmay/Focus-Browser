(function() {
  'use strict';

  // Guarda referência nativa do hasFocus para evitar burlas de scripts da página
  const nativeHasFocus = Document.prototype.hasFocus || document.hasFocus;

  // Configuração global da proteção no contexto da página (Reativo em tempo real)
  const state = {
    enabled: true,
    totalBlock: true,
    blockedPopupsCount: 0
  };

  // Conjunto para evitar duplicatas de streams enviadas
  const detectedStreamUrls = new Set();

  function notifyStreamDetected(streamData) {
    if (!streamData || !streamData.url) return;
    if (detectedStreamUrls.has(streamData.url)) return;

    detectedStreamUrls.add(streamData.url);
    window.postMessage({
      type: 'FOCUS_ADBLOCK_STREAM_DETECTED',
      stream: streamData
    }, '*');
  }

  // --- 1. SNIFFER DE REDE REAL-TIME (Fetch & XMLHttpRequest) ---
  const STREAM_KEYWORDS = /m3u8|manifest|master\.m3u8|index\.m3u8|playlist\.m3u8|\.mpd|googlevideo\.com\/videoplayback|fbcdn\.net\/v\/|video\.xx\.fbcdn\.net|\/hls\/|\/dash\//i;

  function classifyStreamUrl(url) {
    const urlLower = (url || '').toLowerCase();
    let type = 'video';
    let ext = 'mp4';
    let isStream = false;
    let isManifest = false;

    if (urlLower.includes('.m3u8') || urlLower.includes('m3u8')) {
      type = 'hls';
      ext = 'm3u8';
      isStream = true;
      isManifest = true;
    } else if (urlLower.includes('.mpd') || urlLower.includes('mpd')) {
      type = 'dash';
      ext = 'mpd';
      isStream = true;
      isManifest = true;
    } else if (urlLower.includes('manifest') || urlLower.includes('.json') || urlLower.includes('playlist')) {
      type = 'manifest';
      ext = 'json';
      isStream = true;
      isManifest = true;
    } else if (urlLower.includes('.webm')) {
      ext = 'webm';
    } else if (urlLower.includes('.mkv')) {
      ext = 'mkv';
    }

    return { type, ext, isStream, isManifest };
  }

  // Intercepta window.fetch
  const originalFetch = window.fetch;
  window.fetch = function(input, init) {
    try {
      const url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
      if (url && STREAM_KEYWORDS.test(url) && !url.includes('google-analytics') && !url.includes('doubleclick')) {
        const cls = classifyStreamUrl(url);

        notifyStreamDetected({
          url: url,
          platform: location.hostname.includes('youtube') ? 'YouTube' : location.hostname.includes('facebook') ? 'Facebook' : 'Stream',
          type: cls.type,
          name: (document.title || 'Stream_Video').split('|')[0].trim() + '.' + cls.ext,
          ext: cls.ext,
          isStream: cls.isStream,
          isManifest: cls.isManifest,
          source: 'Network (Fetch)'
        });
      }
    } catch (e) {}
    return originalFetch.apply(this, arguments);
  };

  // Intercepta XMLHttpRequest
  const originalXhrOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    try {
      if (url && typeof url === 'string' && STREAM_KEYWORDS.test(url) && !url.includes('google-analytics') && !url.includes('doubleclick')) {
        const cls = classifyStreamUrl(url);

        notifyStreamDetected({
          url: url,
          platform: location.hostname.includes('youtube') ? 'YouTube' : location.hostname.includes('facebook') ? 'Facebook' : 'Stream',
          type: cls.type,
          name: (document.title || 'Stream_Video').split('|')[0].trim() + '.' + cls.ext,
          ext: cls.ext,
          isStream: cls.isStream,
          isManifest: cls.isManifest,
          source: 'Network (XHR)'
        });
      }
    } catch (e) {}
    return originalXhrOpen.apply(this, arguments);
  };

  // --- 2. INTERCEÇÃO DE HTMLMediaElement src & URL.createObjectURL ---
  const originalCreateObjectURL = URL.createObjectURL;
  URL.createObjectURL = function(object) {
    const blobUrl = originalCreateObjectURL.apply(this, arguments);
    try {
      if (object && (object instanceof MediaSource || (object.type && object.type.includes('video')))) {
        notifyStreamDetected({
          url: window.location.href, // Guarda URL da página de streaming
          blobUrl: blobUrl,
          platform: location.hostname.includes('youtube') ? 'YouTube' : location.hostname.includes('facebook') ? 'Facebook' : 'MediaSource',
          type: 'stream',
          name: (document.title || 'Vídeo_Streaming').split('|')[0].trim() + '.mp4',
          ext: 'mp4',
          isBlob: true,
          isStream: true,
          source: 'MediaSource / Blob'
        });
      }
    } catch (e) {}
    return blobUrl;
  };

  // --- 3. EXTRAÇÃO DIRETA DE YOUTUBE E FACEBOOK VIA WINDOW OBJECTS ---
  function inspectPagePlayerObjects() {
    // Extração YouTube via ytInitialPlayerResponse
    if (window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.streamingData) {
      try {
        const sd = window.ytInitialPlayerResponse.streamingData;
        const details = window.ytInitialPlayerResponse.videoDetails || {};
        const title = details.title || document.title.replace('- YouTube', '').trim();
        const durationSec = parseInt(details.lengthSeconds || '0', 10);

        // Formatos progressivos combinados (Vídeo + Áudio direto em MP4)
        const formats = (sd.formats || []).concat(sd.adaptiveFormats || []);
        formats.forEach(f => {
          if (f.url) {
            const quality = f.qualityLabel || f.quality || '720p';
            const mime = f.mimeType || '';
            let ext = mime.includes('webm') ? 'webm' : 'mp4';
            let type = mime.includes('audio') ? 'audio' : 'video';

            let sizeMB = null;
            if (f.contentLength) {
              sizeMB = `~${(parseInt(f.contentLength, 10) / (1024 * 1024)).toFixed(1)} MB`;
            }

            notifyStreamDetected({
              url: f.url,
              platform: 'YouTube',
              type: type,
              name: `${title} [${quality}].${ext}`,
              ext: ext,
              quality: quality,
              durationSeconds: durationSec,
              size: sizeMB,
              isStream: false,
              source: 'YouTube Player Data'
            });
          }
        });

        if (sd.hlsManifestUrl) {
          notifyStreamDetected({
            url: sd.hlsManifestUrl,
            platform: 'YouTube (HLS)',
            type: 'hls',
            name: `${title} [HLS Stream].m3u8`,
            ext: 'm3u8',
            durationSeconds: durationSec,
            isStream: true,
            isManifest: true,
            source: 'YouTube HLS'
          });
        }
      } catch (e) {}
    }

    // Extração Facebook via window state
    if (location.hostname.includes('facebook.com')) {
      try {
        const scripts = document.querySelectorAll('script:not([src])');
        scripts.forEach(scr => {
          const text = scr.textContent || '';
          if (text.includes('playable_url') || text.includes('browser_native_hd_url')) {
            const hdMatch = text.match(/"browser_native_hd_url":"([^"]+)"/) || text.match(/"playable_url_quality_hd":"([^"]+)"/);
            const sdMatch = text.match(/"browser_native_sd_url":"([^"]+)"/) || text.match(/"playable_url":"([^"]+)"/);

            const title = document.title.replace('- Facebook', '').trim() || 'Vídeo do Facebook';

            if (hdMatch && hdMatch[1]) {
              const cleanUrl = hdMatch[1].replace(/\\/g, '');
              notifyStreamDetected({
                url: cleanUrl,
                platform: 'Facebook',
                type: 'video',
                name: `${title} [HD].mp4`,
                ext: 'mp4',
                quality: 'HD (1080p/720p)',
                isStream: false,
                source: 'Facebook Script Parser'
              });
            }
            if (sdMatch && sdMatch[1]) {
              const cleanUrl = sdMatch[1].replace(/\\/g, '');
              notifyStreamDetected({
                url: cleanUrl,
                platform: 'Facebook',
                type: 'video',
                name: `${title} [SD].mp4`,
                ext: 'mp4',
                quality: 'SD (480p)',
                isStream: false,
                source: 'Facebook Script Parser'
              });
            }
          }
        });
      } catch (e) {}
    }
  }

  // Executa inspeção periodicamente e na inicialização
  setTimeout(inspectPagePlayerObjects, 1000);
  setTimeout(inspectPagePlayerObjects, 3000);

  // --- MENSAGENS E SINCRONIZAÇÃO DA EXTENSÃO ---
  function updateStateFromDOM() {
    const root = document.documentElement;
    if (root) {
      const enabledAttr = root.getAttribute('data-focus-enabled');
      const totalBlockAttr = root.getAttribute('data-focus-totalblock');

      if (enabledAttr !== null) state.enabled = (enabledAttr === 'true');
      if (totalBlockAttr !== null) state.totalBlock = (totalBlockAttr === 'true');
    }
  }

  window.addEventListener('message', function(event) {
    if (event.data && event.data.type === 'FOCUS_ADBLOCK_SETTINGS_UPDATED') {
      if (event.data.settings) {
        state.enabled = !!event.data.settings.enabled;
        state.totalBlock = !!event.data.settings.totalBlock;
      }
    }
  });

  const attrObserver = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.type === 'attributes' && mutation.attributeName && mutation.attributeName.startsWith('data-focus-')) {
        updateStateFromDOM();
      }
    }
  });

  function initObserver() {
    if (document.documentElement) {
      attrObserver.observe(document.documentElement, { attributes: true });
      updateStateFromDOM();
    }
  }

  if (document.documentElement) {
    initObserver();
  } else {
    document.addEventListener('DOMContentLoaded', initObserver);
  }

  // --- SOBRESCRITA DO WINDOW.OPEN (BLOQUEIO POPUP / POPUNDER) ---
  const originalOpen = window.open;
  window.open = function(url, target, features) {
    updateStateFromDOM();
    if (!state.enabled || !state.totalBlock) {
      return originalOpen.apply(this, arguments);
    }

    const urlString = (url || '').toString().toLowerCase();
    const isKnownAdUrl = /popad|popcash|exoclick|propeller|adsterra|clickadu|hilltopads|monetag|bet365|doubleclick|syndication|redirect|outbrain|taboola|ad-delivery|a-ads|juicyads/i.test(urlString);
    const isBlankOrSuspicious = !url || urlString === 'about:blank' || urlString === 'javascript:void(0)' || isKnownAdUrl;
    const isPopunderAttempt = typeof nativeHasFocus === 'function' && !nativeHasFocus.call(document);

    if (isBlankOrSuspicious || isPopunderAttempt || isKnownAdUrl) {
      console.warn('[Focus AdBlocker] Popup/Popunder bloqueado:', url || 'janela em branco');
      state.blockedPopupsCount++;
      window.postMessage({ type: 'FOCUS_ADBLOCK_POPUP_BLOCKED' }, '*');
      return null;
    }

    try {
      if (url && typeof url === 'string' && url.startsWith('http')) {
        const targetUrl = new URL(url, location.href);
        const currentUrl = new URL(location.href);
        if (targetUrl.hostname !== currentUrl.hostname && (target === '_blank' || target === '_new')) {
          console.warn('[Focus AdBlocker] Redirecionamento/Aba externa bloqueada:', url);
          state.blockedPopupsCount++;
          window.postMessage({ type: 'FOCUS_ADBLOCK_POPUP_BLOCKED' }, '*');
          return null;
        }
      }
    } catch (e) {}

    return originalOpen.apply(this, arguments);
  };

  // --- INTERCEÇÃO DE ANCHOR CLICK & FORM SUBMIT ---
  const originalAnchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function() {
    updateStateFromDOM();
    if (state.enabled && state.totalBlock) {
      const href = (this.getAttribute('href') || '').toLowerCase();
      const target = this.getAttribute('target') || '';

      if (target === '_blank' || /popad|exoclick|adsterra|clickadu|hilltopads|monetag|redirect|syndication/i.test(href)) {
        if (!this.isConnected || this.style.display === 'none' || this.style.opacity === '0' || this.offsetWidth === 0) {
          console.warn('[Focus AdBlocker] Clique automático em link invisível bloqueado:', href);
          state.blockedPopupsCount++;
          window.postMessage({ type: 'FOCUS_ADBLOCK_POPUP_BLOCKED' }, '*');
          return;
        }
      }
    }
    return originalAnchorClick.apply(this, arguments);
  };

  const originalFormSubmit = HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit = function() {
    updateStateFromDOM();
    if (state.enabled && state.totalBlock) {
      const action = (this.getAttribute('action') || '').toLowerCase();
      const target = this.getAttribute('target') || '';
      const isAdUrl = /popad|exoclick|adsterra|clickadu|hilltopads|monetag|redirect|syndication/i.test(action);

      if (target === '_blank' || isAdUrl) {
        if (isAdUrl || !this.isConnected || this.style.display === 'none' || this.style.opacity === '0') {
          console.warn('[Focus AdBlocker] Formulário de popup/anúncio bloqueado:', action);
          state.blockedPopupsCount++;
          window.postMessage({ type: 'FOCUS_ADBLOCK_POPUP_BLOCKED' }, '*');
          return;
        }
      }
    }
    return originalFormSubmit.apply(this, arguments);
  };

  // --- OVERLAY TRAP CLEANUP ---
  function removeClickTraps() {
    updateStateFromDOM();
    if (!state.enabled || !state.totalBlock) return;

    const elements = document.querySelectorAll('div, a, span, iframe');
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;

    elements.forEach(el => {
      if (el.tagName === 'BODY' || el.tagName === 'HTML' || el.id === 'focus-adblock-styles') return;

      const style = window.getComputedStyle(el);
      const position = style.position;

      if ((position === 'fixed' || position === 'absolute') && (parseInt(style.zIndex, 10) >= 50 || style.zIndex === 'auto')) {
        const rect = el.getBoundingClientRect();
        const opacity = parseFloat(style.opacity);
        const bgColor = style.backgroundColor;

        const coversScreen = rect.width >= viewportWidth * 0.5 && rect.height >= viewportHeight * 0.5;
        const isTransparent = opacity < 0.15 || bgColor === 'transparent' || bgColor === 'rgba(0, 0, 0, 0)';
        const hasNoVideo = !el.querySelector('video, audio, iframe[src*="player"]');

        if (coversScreen && isTransparent && el.children.length === 0 && !el.innerText.trim() && hasNoVideo) {
          console.warn('[Focus AdBlocker] Overlay transparente removido:', el);
          el.remove();
        }
      }
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    removeClickTraps();
  });

  window.addEventListener('click', function(e) {
    updateStateFromDOM();
    if (!state.enabled || !state.totalBlock) return;

    let target = e.target;
    const trapCandidate = target ? (target.closest ? target.closest('div, a, span') : target) : null;
    const checkEl = trapCandidate || target;

    if (checkEl && checkEl !== document.body && checkEl !== document.documentElement) {
      const style = window.getComputedStyle(checkEl);
      const isPositioned = (style.position === 'fixed' || style.position === 'absolute');
      const zIndexVal = parseInt(style.zIndex, 10);

      if (isPositioned && (zIndexVal >= 50 || isNaN(zIndexVal))) {
        const opacity = parseFloat(style.opacity);
        const bgColor = style.backgroundColor;
        const isTransparent = opacity < 0.15 || bgColor === 'transparent' || bgColor === 'rgba(0, 0, 0, 0)';
        const hasNoVideo = !checkEl.querySelector('video, audio, iframe[src*="player"]');

        if (isTransparent && hasNoVideo && checkEl.children.length === 0 && !checkEl.innerText.trim()) {
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          console.warn('[Focus AdBlocker] Clique em overlay armadilha neutralizado!');
          checkEl.remove();
          state.blockedPopupsCount++;
          window.postMessage({ type: 'FOCUS_ADBLOCK_POPUP_BLOCKED' }, '*');
        }
      }
    }
  }, true);

})();
