#!/usr/bin/env node
'use strict';
/*
 * abys_grab.js - coleta completa: lista de URLs -> baixa -> decifra -> concatena -> .mp4
 *
 * Esquema .fd (comprovado): somente os PRIMEIROS 0x10000 (65536) bytes estao cifrados em
 * AES-256-CTR; o restante ja e MP4 puro. Usa abys_lib.decryptFd para garantir consistencia.
 *
 * Entrada (urls.txt): uma URL por linha, em ordem de parte. Formatos aceitos:
 *   https://host/9/5/b/<md5>.<size>.<res>.fd
 *   https://host/9/5/b/<md5>.<size>.<res>.fd|bytes=0-20971519     (Range opcional)
 *   https://host/sora/<size>/<token>                              (resposta ja decifrada pelo SW)
 *   # comentario
 *   # title=<titulo original>      <- opcional (userscript grava; usado p/ nomear)
 *   # slug=<slug do player>        <- opcional (usado p/ nomear via abys_name)
 *
 * Uso:
 *   node abys_grab.js urls.txt
 *   node abys_grab.js urls.txt --ref https://abysscdn.com/ --out ep04.mp4
 *   node abys_grab.js urls.txt --raw          (nao decifra nada)
 *   node abys_grab.js urls.txt --name <basename.fd>   (forca o pass usado na decifragem)
 *
 * NOME AUTOMATICO (serie/temporada/episodio/resolucao):
 *   node abys_grab.js urls.txt --slug oQDjHTSeQ --dir ./downloads
 *     -> consulta abys_name.js, salva em <dir>/<Serie>/<Serie> - S01E01 - 720p.mp4
 *        e grava um sidecar .json com todos os metadados.
 *   Se o urls.txt tiver '# slug=...' ou '# title=...', o nome e' derivado sem --slug.
 *   Use --flat para ignorar a subpasta da serie.
 */
const fs = require('fs');
const path = require('path');
const L = require('./abys_lib');

function flag(n){ return process.argv.includes(n); }
function arg(n,d){ const i=process.argv.indexOf(n); return i>=0?process.argv[i+1]:d; }

const RAW  = flag('--raw');
const REF  = arg('--ref','https://abysscdn.com/');
const NAME = arg('--name', null);
const SLUG = arg('--slug', null);
const DIR  = arg('--dir', '.');
const FLAT = flag('--flat');
const META = flag('--meta');
let   OUT  = arg('--out', null);

const isMp4 = (b) => b.length > 12 && b.subarray(4,8).toString('latin1') === 'ftyp';

(async () => {
  const listFile = process.argv.slice(2).find(a => !a.startsWith('--') && fs.existsSync(a));
  if (!listFile) { console.error('uso: node abys_grab.js urls.txt [--raw] [--ref URL] [--out ep.mp4] [--name b.fd] [--slug S] [--dir D]'); process.exit(1); }

  const rawLines = fs.readFileSync(listFile,'utf8').split(/\r?\n/).map(s => s.trim());
  const lines = rawLines.filter(s => s && !s.startsWith('#'));

  // metadados opcionais vindos dos comentarios gravados pelo userscript
  const cmtSlug  = (rawLines.find(s => /^#\s*slug=/i.test(s)) || '').replace(/^#\s*slug=/i, '').trim();
  const cmtTitle = (rawLines.find(s => /^#\s*title=/i.test(s)) || '').replace(/^#\s*title=/i, '').trim();
  const effSlug  = SLUG || cmtSlug || null;

  // ---- resolucao do NOME FINAL (Serie SxxExx resolucao) ----
  let meta = { slug: effSlug || null, title: cmtTitle || null };
  if (!OUT) {
    let info = null;
    if (effSlug) {
      try { info = await require('./abys_name').resolveName(effSlug, { referer: REF }); }
      catch (e) { console.error('[!] abys_name falhou (' + e.message + '), usando fallback'); }
      if (info && info.md5_id) meta.md5_id = info.md5_id;
    }
    if (info) { meta = Object.assign(meta, info); }
    const baseName = (info && info.suggested_filename)
      || (cmtTitle ? cmtTitle.replace(/[<>:"/\\|?*\x00-\x1f]/g,'').trim() : null)
      || 'abys_episode.mp4';
    const folder = (!FLAT && info && info.suggested_folder) ? info.suggested_folder : '';
    OUT = path.join(DIR, folder, baseName);
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });

  // evita sobrescrever outro release/episodio com o mesmo nome (ex.: duas fontes do mesmo EP)
  if (fs.existsSync(OUT)) {
    const tag = (meta && meta.md5_id) ? meta.md5_id : Date.now();
    OUT = OUT.replace(/(\.[a-z0-9]+)$/i, '.' + tag + '$1');
    console.warn('[!] arquivo ja existia; salvando como: ' + OUT);
  }

  console.log('[i] ' + lines.length + ' partes | referer=' + REF + ' | decrypt=' + !RAW);
  console.log('[i] destino: ' + OUT);

  const parts = [];
  for (let i=0;i<lines.length;i++){
    const [u, rng] = lines[i].split('|').map(s => s.trim());
    const base = decodeURIComponent(u.split('/').pop().split('?')[0]);
    const isFd = /\.fd$/i.test(base);
    const pass = NAME || base;

    const resp = await L.httpGet(u, { Referer: REF }, rng || null);
    if (resp.status >= 400) throw new Error('HTTP ' + resp.status + ' ' + u);
    let data = resp.body; let tag = 'raw';

    if (!RAW) {
      if (isFd && !rng) {               // .fd completo -> decifra os primeiros 64KB
        data = L.decryptFd(resp.body, pass);
        tag = isMp4(data) ? 'decifrado OK (64KB)' : 'decifrado (sem ftyp)';
      } else if (isFd && rng) {         // range de .fd: so decifra se comeca no byte 0
        const m = /bytes=(\d+)-/.exec(rng);
        if (m && Number(m[1]) === 0) { data = L.decryptFd(resp.body, pass); tag = 'decifrado (range@0)'; }
        else tag = 'range (tail, plaintext)';
      } else {
        tag = isMp4(resp.body) ? 'ja-MP4' : 'bin';
      }
    }
    parts.push(data);
    console.log('  [' + (i+1) + '/' + lines.length + '] ' + resp.body.length + 'B  ' + tag + '  ' + base);
  }

  const full = Buffer.concat(parts);
  fs.writeFileSync(OUT, full);
  console.log('[ok] ' + OUT + '  bytes=' + full.length + '  ftyp=' + isMp4(full)
    + '  moov@' + full.indexOf('moov') + '  mdat@' + full.indexOf('mdat'));

  // ---- sidecar de metadados (nomenclatura correta, md5_id, S/E, fontes) ----
  const sidecar = OUT.replace(/\.(mp4|mkv|ts|bin)$/i, '') + '.json';
  try {
    const md = Object.assign({}, meta, {
      output: OUT, bytes: full.length, parts: lines.length,
      has_ftyp: isMp4(full), moov_offset: full.indexOf('moov'), mdat_offset: full.indexOf('mdat'),
      generated_at: new Date().toISOString()
    });
    fs.writeFileSync(sidecar, JSON.stringify(md, null, 2));
    console.log('[ok] meta: ' + sidecar);
  } catch (e) { console.error('[!] nao gravou sidecar: ' + e.message); }
})().catch(e => { console.error('[erro] ' + e.message); process.exit(2); });
