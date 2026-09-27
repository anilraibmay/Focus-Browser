(function() {
  let settings = {
    enabled: true,
    totalBlock: true,
    focusMode: false,
    bgTheme: 'normal'
  };

  // Buffer para guardar streams interceptados pelo main_world.js em tempo real
  const interceptedStreamsMap = new Map();

  // Escuta mensagens vindas do main_world.js
  window.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'FOCUS_ADBLOCK_POPUP_BLOCKED') {
      try {
        chrome.runtime.sendMessage({ action: 'incrementBlocked', count: 1 }).catch(() => {});
      } catch (e) {}
    }

    if (event.data && event.data.type === 'FOCUS_ADBLOCK_STREAM_DETECTED' && event.data.stream) {
      const s = event.data.stream;
      if (s.url && !interceptedStreamsMap.has(s.url)) {
        interceptedStreamsMap.set(s.url, s);
      }
    }
  });

  // Carrega configurações salvas do chrome.storage.local
  function loadSettings(callback) {
    chrome.storage.local.get(['enabled', 'totalBlock', 'focusMode', 'bgTheme'], (res) => {
      if (res.enabled !== undefined) settings.enabled = res.enabled;
      if (res.totalBlock !== undefined) settings.totalBlock = res.totalBlock;
      if (res.focusMode !== undefined) settings.focusMode = res.focusMode;
      if (res.bgTheme !== undefined) settings.bgTheme = res.bgTheme;
      if (callback) callback();
    });
  }

  // Aplica os atributos e classes no HTML raiz e notifica o main_world.js em tempo real
  function applyDOMState() {
    const root = document.documentElement;
    if (!root) return;

    root.setAttribute('data-focus-enabled', settings.enabled ? 'true' : 'false');
    root.setAttribute('data-focus-totalblock', settings.totalBlock ? 'true' : 'false');
    root.setAttribute('data-focus-reader', settings.focusMode ? 'true' : 'false');
    root.setAttribute('data-focus-theme', settings.bgTheme);

    window.postMessage({
      type: 'FOCUS_ADBLOCK_SETTINGS_UPDATED',
      settings: settings
    }, '*');
  }

  // Helper para formatar duração em MM:SS ou HH:MM:SS
  function formatDuration(seconds) {
    if (!seconds || isNaN(seconds) || !isFinite(seconds) || seconds <= 0) return null;
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    if (hrs > 0) {
      return `${hrs}:${mins < 10 ? '0' : ''}${mins}:${secs < 10 ? '0' : ''}${secs}`;
    }
    return `${mins < 10 ? '0' : ''}${mins}:${secs < 10 ? '0' : ''}${secs}`;
  }

  // Helper para estimar tamanho do arquivo de vídeo baseado na resolução e duração
  function estimateVideoSize(width, height, durationSeconds) {
    if (!durationSeconds || durationSeconds <= 0) return null;
    const h = height || 720;
    let bitrateMbps = 2.5;
    if (h >= 2160) bitrateMbps = 22.0;
    else if (h >= 1440) bitrateMbps = 10.0;
    else if (h >= 1080) bitrateMbps = 5.5;
    else if (h >= 720) bitrateMbps = 2.8;
    else if (h >= 480) bitrateMbps = 1.4;
    else bitrateMbps = 0.8;

    const sizeMB = (bitrateMbps * durationSeconds) / 8;
    if (sizeMB >= 1000) {
      return `~${(sizeMB / 1024).toFixed(1)} GB`;
    }
    return `~${sizeMB.toFixed(1)} MB`;
  }

  // Helper para rotular resolução de vídeo
  function getVideoQualityLabel(width, height) {
    if (!height) return 'HD';
    if (height >= 2160) return '4K (2160p)';
    if (height >= 1440) return '2K (1440p)';
    if (height >= 1080) return 'FHD (1080p)';
    if (height >= 720) return 'HD (720p)';
    if (height >= 480) return 'SD (480p)';
    return `${height}p`;
  }

  // DETECTOR DE MÍDIAS E VÍDEOS DE ALTA PERFORMANCE (YouTube, Facebook, Streaming, HLS/m3u8, DASH/mpd, Web)
  function detectMediaElements() {
    const mediaList = [];
    const addedUrls = new Set();
    const hostname = location.hostname.toLowerCase();
    const isYouTube = hostname.includes('youtube.com') || hostname.includes('youtu.be');
    const isFacebook = hostname.includes('facebook.com') || hostname.includes('fb.watch') || hostname.includes('fb.com');

    // 0. Inclui mídias e streams interceptados em tempo real de requisições de rede (m3u8, mpd, YouTube, Facebook, Blob)
    interceptedStreamsMap.forEach((streamItem) => {
      if (streamItem && streamItem.url && !addedUrls.has(streamItem.url)) {
        addedUrls.add(streamItem.url);
        mediaList.push(streamItem);
      }
    });

    // 1. DETECÇÃO ROBUSTA DE VÍDEOS DO YOUTUBE
    if (isYouTube) {
      const ytVideo = document.querySelector('video.html5-main-video') || document.querySelector('video');
      if (ytVideo) {
        const titleEl = document.querySelector('h1.ytd-watch-metadata, #container h1.title, ytd-video-primary-info-renderer h1, #title h1');
        let title = titleEl ? titleEl.innerText.trim() : document.title.replace('- YouTube', '').trim();
        if (!title) title = 'Vídeo do YouTube';

        const durationSec = ytVideo.duration || 0;
        const durationStr = formatDuration(durationSec) || '00:00';
        const qualityStr = getVideoQualityLabel(ytVideo.videoWidth, ytVideo.videoHeight);
        const estimatedSize = estimateVideoSize(ytVideo.videoWidth, ytVideo.videoHeight, durationSec) || '~25.0 MB';

        let videoUrl = ytVideo.currentSrc || ytVideo.src || window.location.href;

        // Se a fonte for Blob ou stream do player do YouTube, tenta extrair a página ou link direto
        if (!addedUrls.has(videoUrl)) {
          addedUrls.add(videoUrl);
          mediaList.push({
            url: videoUrl.startsWith('blob:') ? window.location.href : videoUrl,
            platform: 'YouTube',
            type: 'video',
            name: `${title}.mp4`,
            ext: 'mp4',
            duration: durationStr,
            quality: qualityStr,
            size: estimatedSize,
            isBlob: videoUrl.startsWith('blob:'),
            source: 'YouTube Player'
          });
        }
      }
    }

    // 2. DETECÇÃO ROBUSTA DE VÍDEOS DO FACEBOOK
    if (isFacebook) {
      document.querySelectorAll('video').forEach((fbVideo, index) => {
        let title = document.title.replace('| Facebook', '').replace('- Facebook', '').trim();
        if (!title || title === 'Facebook') {
          title = 'Vídeo do Facebook ' + (index + 1);
        }

        const durationSec = fbVideo.duration || 0;
        const durationStr = formatDuration(durationSec) || '00:00';
        const qualityStr = getVideoQualityLabel(fbVideo.videoWidth, fbVideo.videoHeight);
        const estimatedSize = estimateVideoSize(fbVideo.videoWidth, fbVideo.videoHeight, durationSec) || '~15.0 MB';

        let videoUrl = fbVideo.currentSrc || fbVideo.src || window.location.href;

        if (videoUrl && !addedUrls.has(videoUrl)) {
          addedUrls.add(videoUrl);
          mediaList.push({
            url: videoUrl,
            platform: 'Facebook',
            type: 'video',
            name: `${title}.mp4`,
            ext: 'mp4',
            duration: durationStr,
            quality: qualityStr,
            size: estimatedSize,
            isBlob: videoUrl.startsWith('blob:'),
            source: 'Facebook Video Tag'
          });
        }
      });
    }

    // 3. RASTREAMENTO DE METADADOS OPEN GRAPH E JSON-LD (Sites de Streaming, Filmes e Séries)
    try {
      const ogVideo = document.querySelector('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
      if (ogVideo && ogVideo.content && !addedUrls.has(ogVideo.content)) {
        const ogUrl = ogVideo.content;
        addedUrls.add(ogUrl);
        let ext = 'mp4';
        let type = 'video';
        if (ogUrl.includes('.m3u8')) { ext = 'm3u8'; type = 'hls'; }
        else if (ogUrl.includes('.mpd')) { ext = 'mpd'; type = 'dash'; }

        mediaList.push({
          url: ogUrl,
          platform: 'Streaming (Meta)',
          type: type,
          name: `${document.title.split('|')[0].trim()}.${ext}`,
          ext: ext,
          source: 'OpenGraph Meta'
        });
      }

      // Rastreia JSON-LD
      document.querySelectorAll('script[type="application/ld+json"]').forEach(scr => {
        try {
          const json = JSON.parse(scr.textContent);
          if (json) {
            const contentUrl = json.contentUrl || (json.video ? json.video.contentUrl : null) || (json.embedUrl);
            if (contentUrl && !addedUrls.has(contentUrl)) {
              addedUrls.add(contentUrl);
              let ext = 'mp4';
              let type = 'video';
              if (contentUrl.includes('.m3u8')) { ext = 'm3u8'; type = 'hls'; }
              mediaList.push({
                url: contentUrl,
                platform: 'Streaming (JSON-LD)',
                type: type,
                name: `${(json.name || document.title).split('|')[0].trim()}.${ext}`,
                ext: ext,
                source: 'JSON-LD Metadata'
              });
            }
          }
        } catch (e) {}
      });
    } catch (e) {}

    // 4. EMBEDS E IFRAMES DE PLAYER DE STREAMING (Vimeo, Dailymotion, Streamtape, VOE, Doodstream, Mixdrop, etc.)
    document.querySelectorAll('iframe').forEach((iframe, index) => {
      const src = iframe.src || iframe.getAttribute('data-src') || '';
      if (src && /vimeo|dailymotion|streamtape|voe|doodstream|mixdrop|fembed|ok\.ru|vk\.com|supervideo|filemoon|mp4upload/i.test(src)) {
        if (!addedUrls.has(src)) {
          addedUrls.add(src);
          mediaList.push({
            url: src,
            platform: 'Embed Player',
            type: 'video',
            name: `Player_Embed_${index + 1} (${new URL(src).hostname})`,
            ext: 'embed',
            source: 'Embedded Player Frame'
          });
        }
      }
    });

    // 5. TAGS <video> E <source> GERAIS NA WEB
    document.querySelectorAll('video').forEach((v, index) => {
      const src = v.currentSrc || v.src || v.getAttribute('data-src');
      if (src && !addedUrls.has(src) && !src.startsWith('blob:')) {
        addedUrls.add(src);

        const durationSec = v.duration || 0;
        const durationStr = formatDuration(durationSec);
        const qualityStr = getVideoQualityLabel(v.videoWidth, v.videoHeight);
        const estimatedSize = estimateVideoSize(v.videoWidth, v.videoHeight, durationSec);

        let cleanTitle = document.title ? document.title.split('|')[0].split('-')[0].trim() : '';
        let name = cleanTitle ? `${cleanTitle}_${index + 1}.mp4` : `Vídeo_${index + 1}.mp4`;

        let type = 'video';
        let ext = 'mp4';
        if (src.includes('.m3u8')) { type = 'hls'; ext = 'm3u8'; }
        else if (src.includes('.mpd')) { type = 'dash'; ext = 'mpd'; }

        mediaList.push({
          url: src,
          platform: 'Web Player',
          type: type,
          name: name,
          ext: ext,
          duration: durationStr,
          quality: qualityStr,
          size: estimatedSize,
          source: 'HTML5 Video Tag'
        });
      }

      v.querySelectorAll('source').forEach((s, sIndex) => {
        const sSrc = s.src || s.getAttribute('data-src');
        if (sSrc && !addedUrls.has(sSrc) && !sSrc.startsWith('blob:')) {
          addedUrls.add(sSrc);
          let ext = 'mp4';
          let type = 'video';
          if (sSrc.includes('.m3u8')) { type = 'hls'; ext = 'm3u8'; }
          else if (sSrc.includes('.mpd')) { type = 'dash'; ext = 'mpd'; }

          mediaList.push({
            url: sSrc,
            platform: 'Web Player',
            type: type,
            name: `Vídeo_Stream_${index + 1}_${sIndex + 1}.${ext}`,
            ext: ext,
            source: 'Video Source Tag'
          });
        }
      });
    });

    // 6. TAGS <audio>
    document.querySelectorAll('audio').forEach((a, index) => {
      const src = a.currentSrc || a.src;
      if (src && !addedUrls.has(src) && !src.startsWith('blob:')) {
        addedUrls.add(src);
        const durationStr = formatDuration(a.duration);

        mediaList.push({
          url: src,
          platform: 'Áudio',
          type: 'audio',
          name: `Áudio_${index + 1}.mp3`,
          ext: 'mp3',
          duration: durationStr,
          source: 'HTML5 Audio Tag'
        });
      }
    });

    // 7. LINKS DE MÍDIA E ARQUIVOS BAIXÁVEIS <a>
    document.querySelectorAll('a[href]').forEach(a => {
      const href = a.href;
      if (/\.(mp4|webm|mkv|avi|mov|flv|m3u8|mpd|mp3|wav|aac|m4a|ogg|flac|pdf|zip|rar|7z|apk)(\?.*)?$/i.test(href)) {
        if (!addedUrls.has(href)) {
          addedUrls.add(href);
          const match = href.match(/\/([^/?#]+)(\?.*)?$/);
          const fileName = match ? match[1] : 'Arquivo_' + (mediaList.length + 1);
          const extMatch = fileName.match(/\.([a-z0-9]+)$/i);
          const ext = extMatch ? extMatch[1].toLowerCase() : 'file';

          let type = 'file';
          if (['mp4', 'webm', 'mkv', 'avi', 'mov', 'flv'].includes(ext)) type = 'video';
          else if (ext === 'm3u8') type = 'hls';
          else if (ext === 'mpd') type = 'dash';
          else if (['mp3', 'wav', 'aac', 'm4a', 'ogg', 'flac'].includes(ext)) type = 'audio';

          mediaList.push({
            url: href,
            platform: 'Link',
            type: type,
            name: fileName,
            ext: ext,
            source: 'Anchor Tag Link'
          });
        }
      }
    });

    // 8. IMAGENS RELEVANTES (> 150px)
    document.querySelectorAll('img').forEach((img, index) => {
      const src = img.currentSrc || img.src;
      if (src && (img.naturalWidth > 150 || img.width > 150) && !addedUrls.has(src) && !src.startsWith('data:')) {
        addedUrls.add(src);
        const match = src.match(/\/([^/?#]+)(\?.*)?$/);
        const fileName = match ? match[1] : 'Imagem_' + (index + 1) + '.jpg';
        mediaList.push({
          url: src,
          platform: 'Imagem',
          type: 'image',
          name: fileName,
          ext: 'jpg',
          size: `${img.naturalWidth || img.width}x${img.naturalHeight || img.height}px`,
          source: 'Image Element'
        });
      }
    });

    return mediaList;
  }

  // Remoção ativa de Popups, Overlays de Player e Banners Dinâmicos sem afetar o Player de Vídeo
  function cleanDynamicAds() {
    if (!settings.enabled) return;

    if (settings.totalBlock) {
      const selectors = [
        '[id*="google_ads"]',
        '[id*="taboola"]',
        '[class*="taboola"]',
        '[class*="outbrain"]',
        '[id*="outbrain"]',
        '.adsbygoogle',
        'iframe[src*="doubleclick"]',
        'iframe[src*="googlead"]',
        'iframe[src*="popads"]',
        'iframe[src*="exoclick"]',
        'iframe[src*="adsterra"]',
        '#onetrust-banner-sdk',
        '.cookie-banner',
        '.gdpr-modal',
        'div[class*="popunder"]',
        'div[id*="popunder"]',
        'div[class*="popup-overlay"]',
        'div[id*="popup-overlay"]',
        'div[class*="click-trap"]',
        'div[id*="click-trap"]'
      ];

      selectors.forEach(sel => {
        document.querySelectorAll(sel).forEach(el => {
          const isMainContainer = ['VIDEO', 'MAIN', 'ARTICLE', 'BODY', 'HTML', 'NAV', 'HEADER'].includes(el.tagName);
          const containsVideoPlayer = el.querySelector('video, audio, embed, object') || (document.querySelector('video') && el.contains(document.querySelector('video')));

          if (!isMainContainer && !containsVideoPlayer) {
            el.remove();
          }
        });
      });
    }
  }

  // Observer para monitorar inclusões dinâmicas no DOM
  const observer = new MutationObserver(() => {
    if (settings.enabled) {
      cleanDynamicAds();
    }
  });

  function init() {
    loadSettings(() => {
      applyDOMState();
      cleanDynamicAds();

      if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
      } else {
        document.addEventListener('DOMContentLoaded', () => {
          applyDOMState();
          cleanDynamicAds();
          if (document.body) {
            observer.observe(document.body, { childList: true, subtree: true });
          }
        });
      }
    });
  }

  // Escuta atualizações enviadas em tempo real a partir da Popup da extensão
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.action === 'updateSettings') {
      if (message.settings) {
        settings = { ...settings, ...message.settings };
        applyDOMState();
        cleanDynamicAds();
        sendResponse({ success: true });
      }
    }

    // Detecção de mídias quando a popup do usuário é aberta e solicita a verificação
    if (message.action === 'detectMedia') {
      const mediaItems = detectMediaElements();
      sendResponse({ mediaItems: mediaItems });
    }
  });

  // Atualizações via storage listener
  chrome.storage.onChanged.addListener((changes, namespace) => {
    if (namespace === 'local') {
      loadSettings(() => {
        applyDOMState();
        cleanDynamicAds();
      });
    }
  });

  init();
})();
