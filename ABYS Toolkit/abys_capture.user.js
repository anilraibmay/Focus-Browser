// ==UserScript==
// @name         ABYS Capture + Metadata
// @namespace    abys
// @match        *://abysscdn.com/*
// @match        *://*.abyss.to/*
// @match        *://*.trycloudflare.com/*
// @match        *://embedplayabyss.top/*
// @run-at       document-start
// @grant        none
// @version      2.0
// ==/UserScript==
// Captura as URLs reais que o Service Worker usa (.fd com Range, /sora/...) e, NOVIDADE,
// os METADADOS de nomeacao (titulo com Serie/SxxExx/resolucao + slug + md5_id) que a
// pagina do player abysscdn.com/?v=<slug> renderiza no servidor.
// Gera um urls.txt com cabecalho  # title=... # slug=... # md5_id=...  para o abys_grab.js
// nomear o arquivo final corretamente.
(function () {
  const FD = [], SORA = [], MP4 = [];
  const REG = {}; // slug -> metadata (acumula varios episodios)
  const keep = (u) => { try { return new URL(u, location.href).href; } catch (e) { return String(u); } };
  const note = (raw) => {
    const u = keep(raw);
    if (/\.fd(\?|$)/.test(u)) { if (!FD.includes(u)) FD.push(u); }
    else if (/\/sora\//.test(u)) { if (!SORA.includes(u)) SORA.push(u); }
    else if (/\/mp4\//.test(u)) { if (!MP4.includes(u)) MP4.push(u); }
  };
  const _f = window.fetch;
  if (_f) window.fetch = function (input) { try { note(typeof input === 'string' ? input : input.url); } catch (e) {} return _f.apply(this, arguments); };
  const _o = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) { try { note(u); } catch (e) {} return _o.apply(this, arguments); };
  try { new PerformanceObserver((l) => l.getEntries().forEach((e) => note(e.name))).observe({ entryTypes: ['resource'] }); } catch (e) {}

  // -------- metadados de nomeacao --------
  function meta() {
    const m = {
      url: location.href,
      slug: new URLSearchParams(location.search).get('v') || null,
      title: null, md5_id: null, user_id: null
    };
    // 1) <title> renderizado no servidor = nome original do arquivo (Serie.SxxExx.res.mp4)
    const t = document.querySelector('title');
    if (t && t.textContent && !/Just a moment|Cloudflare/i.test(t.textContent)) m.title = t.textContent.trim();
    // 2) var datas = "b64" -> {slug, md5_id, user_id}
    try {
      const html = document.documentElement ? document.documentElement.innerHTML : '';
      const dm = html.match(/const\s+datas\s*=\s*"([^"]+)"/);
      if (dm) {
        const j = JSON.parse(atob(dm[1]));
        m.slug = j.slug || m.slug; m.md5_id = j.md5_id || null; m.user_id = j.user_id || null;
      }
    } catch (e) {}
    return m;
  }
  function rememberMeta() {
    const m = meta();
    if (m.slug) { REG[m.slug] = Object.assign(REG[m.slug] || {}, m); return m; }
    return m;
  }

  // -------- Cache Storage --------
  async function dumpCache() {
    const out = [];
    for (const n of await caches.keys()) {
      const c = await caches.open(n);
      for (const req of await c.keys()) {
        const r = await c.match(req);
        let size = null;
        if (r) { try { size = +(r.headers.get('content-length') || 0) || (await r.clone().blob()).size; } catch (e) {} }
        out.push({ cache: n, url: req.url, range: req.headers.get('range') || null, size: size, status: r ? r.status : null });
      }
    }
    return out;
  }
  async function downloadCached() {
    for (const n of await caches.keys()) {
      const c = await caches.open(n);
      for (const req of await c.keys()) {
        if (!/\.fd|\/sora\/|\/mp4\//.test(req.url)) continue;
        const res = await c.match(req);
        if (!res) continue;
        const blob = await res.blob();
        if (blob.size < 100000) continue;
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = decodeURIComponent(req.url.split('/').pop().split('?')[0]) || 'chunk.bin';
        document.body.appendChild(a); a.click(); a.remove();
        await new Promise((r) => setTimeout(r, 400));
      }
    }
  }

  // -------- exportacao com cabecalho de metadados --------
  function header(m) {
    return [
      m.title ? '# title=' + m.title : null,
      m.slug ? '# slug=' + m.slug : null,
      m.md5_id ? '# md5_id=' + m.md5_id : null
    ].filter(Boolean).join('\n');
  }
  window.__abys = {
    fds: FD, sora: SORA, mp4: MP4, reg: REG,
    meta, remember: rememberMeta,
    list: () => { const all = [...MP4, ...FD, ...SORA]; console.log(all.join('\n')); return all; },
    cache: dumpCache, grab: downloadCached,
    manifest: () => { rememberMeta(); console.log(JSON.stringify(REG, null, 2)); return REG; },
    save: (name, slug) => {
      const m = (slug && REG[slug]) || rememberMeta();
      const body = [...MP4, ...FD, ...SORA];
      const text = header(m) + '\n' + body.join('\n') + '\n';
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
      a.download = name || ((m.slug || 'abys') + '_urls.txt');
      a.click();
      console.warn('[ABYS] salvou ' + a.download + '  (' + body.length + ' URLs). Abra no CLI: node abys_grab.js ' + a.download);
    }
  };
  // registra metadados ao carregar a pagina do player
  window.addEventListener('DOMContentLoaded', rememberMeta);
  console.warn('[ABYS] hooks ativos. __abys.meta() | __abys.save() | __abys.manifest() | __abys.grab()');
})();
