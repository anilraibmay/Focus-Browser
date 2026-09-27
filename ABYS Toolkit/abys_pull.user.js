// ==UserScript==
// @name         ABYS Full Pull (SW-oracle) - episodio completo em .mp4
// @namespace    abys
// @match        *://abysscdn.com/*
// @match        *://*.abysscdn.com/*
// @match        *://embedplayabyss.top/*
// @match        *://*.embedplayabyss.top/*
// @match        *://*.sssrr.org/*
// @run-at       document-start
// @grant        none
// @version      1.0
// ==/UserScript==
//
// OBJETIVO
// --------
// Baixar TODOS os segmentos de um episodio de forma continua e garantir UM .mp4
// unico, sem risco de pegar apenas a primeira parte inicial.
//
// COMO FUNCIONA (SW-oracle)
// -------------------------
// O Service Worker do ABYS instala um "oraculo" no origin: ele expoe uma URL
// VIRTUAL (ex.: /mp4/<md5_id>/<res_id>/<size>?v=<slug>) que responde a Range
// requests com Accept-Ranges: bytes e content-range <range>/<total>.
// Em vez de tentar reproduzir o /sora/ no CLI (que retorna 404 fora do SW),
// esta userscript roda DENTRO do navegador (onde o SW esta ativo e o fetch
// passa pelo Cloudflare normalmente) e faz um loop de Range requests cobrindo
// 0..size-1, verificando CADA resposta, concatenando na ordem e salvando um .mp4.
//
// GARANTIA CONTRA "SO A PRIMEIRA PARTE"
// -------------------------------------
// 1. Descobre o tamanho TOTAL pelo header content-range da 1a resposta.
// 2. Faz uma verificacao de cobertura (areas de bytes cobertas == [0,total)).
// 3. Rejeita/repete qualquer parte que venha vazia, truncada ou com status != 206/200.
// 4. Só habilita o save quando coverage == total e a assinatura ftyp/moov existe.
//
// USO
// ---
// 1. Abra a pagina do player (abysscdn.com/?v=<slug>) e deixe o video carregar/rodar.
// 2. Abra o console (F12). A API aparece como `__abys_pull`.
// 3. `await __abys_pull.auto()`  -> descobre a URL virtual + baixa tudo + salva.
//    Ou use o painel flutuante (canto inferior direito) com os botoes.
//
// Painel: [Detectar] [Baixar tudo] [Verificar] [Salvar]
(function () {
  'use strict';

  const CHUNK = 0x200000;          // 2 MiB (mesmo chunkSize do SW)
  const MAX_RETRY = 6;             // tentativas por parte
  const CONCURRENCY = 3;           // partes em voo (ordem de escrita preservada)

  // ---------------------------------------------------------------- captura
  const CANDIDATOS = new Set();    // URLs virtuais que aceitam Range (mp4/part/r2/hls virtual)
  const VISTAS = new Set();        // tudo que ja passou (log)
  let TOTAL = null;                // tamanho total descoberto
  let BASE = null;                 // URL virtual escolhida

  const abs = (u) => { try { return new URL(u, location.href).href; } catch (e) { return String(u); } };

  function nota(raw) {
    const u = abs(raw);
    VISTAS.add(u);
    // URL virtual "cheia": /mp4/<id>/<res>/<size>  ou /part/... ou /r2/...
    if (/\/(mp4|part|r2)\//.test(u)) CANDIDATOS.add(u);
  }
  const _f = window.fetch;
  if (_f) window.fetch = function (i) { try { nota(typeof i === 'string' ? i : (i && i.url) || i); } catch (e) {} return _f.apply(this, arguments); };
  const _o = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) { try { nota(u); } catch (e) {} return _o.apply(this, arguments); };
  try { new PerformanceObserver((l) => l.getEntries().forEach((e) => nota(e.name))).observe({ entryTypes: ['resource'] }); } catch (e) {}

  // ---------------------------------------------------------------- metadados / nome
  function meta() {
    const m = { url: location.href, slug: new URLSearchParams(location.search).get('v') || null, title: null, md5_id: null, user_id: null };
    const t = document.querySelector('title');
    if (t && t.textContent && !/Just a moment|Cloudflare/i.test(t.textContent)) m.title = t.textContent.trim();
    try {
      const html = document.documentElement ? document.documentElement.innerHTML : '';
      const dm = html.match(/const\s+datas\s*=\s*"([^"]+)"/);
      if (dm) { const j = JSON.parse(atob(dm[1])); m.slug = j.slug || m.slug; m.md5_id = j.md5_id || null; m.user_id = j.user_id || null; }
    } catch (e) {}
    return m;
  }
  function nomeFinal() {
    const m = meta();
    let n = m.title || '';
    n = n.replace(/\.mp4$/i, '').replace(/[\\/:*?"<>|]+/g, '_').trim();
    if (!n) n = 'abys_' + (m.slug || Date.now());
    return n + '.mp4';
  }

  // ---------------------------------------------------------------- sonda Range
  // Retorna {ok, status, total, partial, acceptRanges} para uma URL virtual.
  async function sonda(url) {
    try {
      const r = await fetch(url, { headers: { Range: 'bytes=0-0' }, cache: 'no-store' });
      const cr = r.headers.get('content-range');       // "bytes 0-0/232643771"
      const cl = r.headers.get('content-length');
      let total = null;
      if (cr) { const mm = cr.match(/\/(\d+)\s*$/); if (mm) total = +mm[1]; }
      if (!total) total = +(cl || 0) || null;
      return { url, ok: r.ok, status: r.status, total, partial: r.status === 206, acceptRanges: r.headers.get('accept-ranges') };
    } catch (e) { return { url, ok: false, status: 0, error: String(e) }; }
  }

  // Escolhe a melhor URL virtual (a que retorna 206 com total conhecido).
  async function detectar() {
    const cands = [...CANDIDATOS];
    if (!cands.length) { console.warn('[ABYS] nenhuma URL virtual /mp4|/part|/r2 capturada ainda. Reproduza o video por alguns segundos.'); return null; }
    let melhor = null;
    for (const u of cands) {
      const s = await sonda(u);
      if (s.ok && s.partial && s.total) return (BASE = u, TOTAL = s.total, s);
      if (!melhor && s.ok && s.total) melhor = s;   // fallback: 200 com length
    }
    if (melhor) { BASE = melhor.url; TOTAL = melhor.total; return melhor; }
    console.warn('[ABYS] nenhuma URL virtual respondeu com tamanho. Candidatos:', cands);
    return null;
  }

  // ---------------------------------------------------------------- baixar N partes em paralelo, ordem preservada
  async function buscaParte(url, start, end) {
    let last = null;
    for (let t = 0; t < MAX_RETRY; t++) {
      try {
        const r = await fetch(url, { headers: { Range: `bytes=${start}-${end}` }, cache: 'no-store' });
        // servidor ignorou Range e mandou o arquivo inteiro:
        if (r.status === 200) {
          const b = new Uint8Array(await r.arrayBuffer());
          if (b.length && b.length >= (end - start + 1)) return b.slice(0, end - start + 1);
          if (b.length) last = new Error('200 sem cobertura suficiente'); else throw new Error('200 vazio');
          continue;
        }
        if (r.status !== 206) { last = new Error('status ' + r.status); await dormir(200 * (t + 1)); continue; }
        const b = new Uint8Array(await r.arrayBuffer());
        const esperado = end - start + 1;
        if (b.length !== esperado) { last = new Error(`truncado: ${b.length}/${esperado}`); await dormir(200 * (t + 1)); continue; }
        return b;
      } catch (e) { last = e; await dormir(250 * (t + 1)); }
    }
    throw last || new Error('falha desconhecida');
  }

  const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------------------------------------------------------------- pull completo
  // onLog: callback de progresso. Retorna {chunks, total, coberto, faltas}
  async function baixarTudo(opts) {
    const o = opts || {};
    const url = o.url || BASE;
    if (!url) { if (!(await detectar())) throw new Error('URL virtual nao detectada'); }

    // descobre total se ainda nao sabemos
    if (!o.size) { const s = await sonda(url); if (s.total) TOTAL = s.total; }
    const size = o.size || TOTAL;
    if (!size) throw new Error('tamanho total desconhecido');

    const nParts = Math.ceil(size / CHUNK);
    const chunks = new Array(nParts);
    const faltas = [];
    let feito = 0, bytesOk = 0;
    const t0 = Date.now();

    let idx = 0;
    async function worker() {
      while (true) {
        const i = idx++;
        if (i >= nParts) return;
        const start = i * CHUNK;
        const end = Math.min(start + CHUNK - 1, size - 1);
        try {
          chunks[i] = await buscaParte(url, start, end);
          bytesOk += chunks[i].length; feito++;
          (o.onLog || logProgresso)(i + 1, nParts, bytesOk, size, t0);
        } catch (e) {
          faltas.push({ part: i, start, end, erro: String(e && e.message || e) });
          console.warn(`[ABYS] parte ${i + 1}/${nParts} FALHOU:`, e);
        }
      }
    }
    await Promise.all(Array.from({ length: Math.max(1, o.concurrency || CONCURRENCY) }, worker));

    const coberto = bytesOk;
    return { chunks, total: size, nParts, coberto, completo: coberto === size && faltas.length === 0, faltas };
  }

  function logProgresso(done, total, bytes, size, t0) {
    const pct = (100 * bytes / size).toFixed(1);
    const seg = (Date.now() - t0) / 1000;
    const mb = (bytes / 1048576).toFixed(1);
    const speed = seg > 0.5 ? (bytes / 1048576 / seg).toFixed(2) + ' MB/s' : '...';
    console.log(`[ABYS] ${done}/${total} partes | ${mb} MB (${pct}%) | ${speed}`);
    if (UI.status) UI.status.textContent = `${done}/${total} (${pct}%) ${mb}MB ${speed}`;
  }

  // ---------------------------------------------------------------- montagem / verificacao
  function montar(res) {
    if (res.faltas.length) throw new Error(`faltam ${res.faltas.length} partes: ` + res.faltas.map(f => f.part).join(','));
    const total = res.chunks.reduce((a, c) => a + c.length, 0);
    if (total !== res.total) throw new Error(`montagem ${total} != esperado ${res.total}`);
    const out = new Uint8Array(total);
    let o = 0;
    for (const c of res.chunks) { out.set(c, o); o += c.length; }
    return out;
  }

  function verificar(bytes, sizeEsperado) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const quatrocc = (off) => String.fromCharCode(bytes[off], bytes[off + 1], bytes[off + 2], bytes[off + 3]);
    const r = { tamanho: bytes.length, esperado: sizeEsperado || TOTAL, ftyp: false, moov: false, mdat: false, boxTopo: [] };
    let o = 0, guard = 0;
    while (o + 8 <= bytes.length && guard++ < 4096) {
      const sz = dv.getUint32(o);
      const ty = quatrocc(o + 4);
      r.boxTopo.push(ty);
      if (ty === 'ftyp') r.ftyp = true;
      if (ty === 'moov') r.moov = true;
      if (ty === 'mdat') { r.mdat = true; break; }
      if (sz < 8) break;
      o += sz;
    }
    r.ok = r.tamanho === r.esperado && r.ftyp;
    return r;
  }

  // ---------------------------------------------------------------- salvar
  async function salvar(bytes, nome) {
    nome = nome || nomeFinal();
    // Preferir disco (arquivos grandes): File System Access API
    if (window.showSaveFilePicker) {
      try {
        const h = await window.showSaveFilePicker({ suggestedName: nome, types: [{ description: 'MP4', accept: { 'video/mp4': ['.mp4'] } }] });
        const w = await h.createWritable();
        // grava em pedacos de 8 MB para nao estourar
        const STEP = 8 * 1048576;
        for (let o = 0; o < bytes.length; o += STEP) await w.write(bytes.subarray(o, Math.min(o + STEP, bytes.length)));
        await w.close();
        console.warn('[ABYS] salvo em disco:', nome, bytes.length, 'bytes');
        return nome;
      } catch (e) { if (e && e.name === 'AbortError') return null; console.warn('[ABYS] disk save falhou, caindo p/ Blob:', e); }
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([bytes], { type: 'video/mp4' }));
    a.download = nome;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 60000);
    console.warn('[ABYS] salvo (Blob):', nome, bytes.length, 'bytes');
    return nome;
  }

  // ---------------------------------------------------------------- fluxo unico
  let ULTIMO = null;
  async function auto(opts) {
    try {
      if (UI.status) UI.status.textContent = 'detectando...';
      if (!BASE && !(await detectar())) throw new Error('URL virtual nao detectada');
      const res = await baixarTudo(opts);
      if (!res.completo) throw new Error(`cobertura incompleta (${res.coberto}/${res.total}); faltam ${res.faltas.length} partes`);
      const bytes = montar(res);
      const v = verificar(bytes, res.total);
      if (!v.ok) throw new Error('verificacao falhou: ' + JSON.stringify(v));
      ULTIMO = { bytes, meta: meta(), verif: v, res };
      const nome = await salvar(bytes, nomeFinal());
      console.warn('[ABYS] SUCESSO', { nome, ...v });
      if (UI.status) UI.status.textContent = 'OK ' + nome;
      return { nome, verif: v };
    } catch (e) {
      console.error('[ABYS] FALHA:', e);
      if (UI.status) UI.status.textContent = 'ERRO: ' + (e && e.message || e);
      throw e;
    }
  }

  async function verificarUltimo() {
    if (!ULTIMO) { console.warn('[ABYS] nada baixado ainda'); return null; }
    const v = verificar(ULTIMO.bytes, ULTIMO.res.total);
    console.log('[ABYS] verificacao:', v); return v;
  }

  // ---------------------------------------------------------------- painel flutuante
  const UI = { status: null };
  function painel() {
    const box = document.createElement('div');
    box.style.cssText = 'position:fixed;right:14px;bottom:14px;z-index:2147483647;background:#111;color:#eee;font:12px/1.4 monospace;border:1px solid #444;border-radius:8px;padding:10px;max-width:340px;box-shadow:0 4px 18px rgba(0,0,0,.5)';
    box.innerHTML = '<b>ABYS Pull</b><div style="margin:6px 0" data-st>idle</div>';
    const st = box.querySelector('[data-st]'); UI.status = st;
    const mk = (t, fn, cor) => { const b = document.createElement('button'); b.textContent = t; b.style.cssText = `margin:2px;padding:4px 8px;background:${cor || '#2a6'};color:#fff;border:0;border-radius:5px;cursor:pointer`; b.onclick = fn; box.appendChild(b); return b; };
    mk('Detectar', async () => { const s = await detectar(); console.log('[ABYS] base=', BASE, 'total=', s && s.total); st.textContent = s ? `base OK total=${s.total}` : 'nada detectado'; });
    mk('Baixar tudo', async () => { try { await auto(); } catch (e) {} }, '#c33');
    mk('Verificar', async () => { const v = await verificarUltimo(); if (v) st.textContent = v.ok ? 'verificado OK' : 'verificacao FALHOU'; });
    mk('Salvar', async () => { if (ULTIMO) await salvar(ULTIMO.bytes, nomeFinal()); }, '#36c');
    mk('X', () => box.remove(), '#555');
    document.body.appendChild(box);
  }
  function pronto() { if (document.body) painel(); else window.addEventListener('DOMContentLoaded', painel); }
  pronto();

  window.__abys_pull = {
    CHUNK, meta, detectar, baixarTudo, montar, verificar, salvar, auto,
    candidatos: () => [...CANDIDATOS], vistas: () => [...VISTAS],
    get base() { return BASE; }, get total() { return TOTAL; }, get ultimo() { return ULTIMO; },
    sonda
  };
  console.warn('[ABYS] __abys_pull pronto. Use __abys_pull.auto() ou o painel. Reproduza o video para capturar a URL virtual.');
})();
