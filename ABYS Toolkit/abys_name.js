#!/usr/bin/env node
/*
 * abys_name.js - resolve o NOME FINAL correto (serie + temporada + episodio)
 * de um link do provedor ABYS (abyss.to / abysscdn.com).
 *
 * DESCOBERTA (validada ao vivo 26/09/2026):
 *   A pagina do player  https://abysscdn.com/?v=<slug>  devolve, RENDERIZADO
 *   NO SERVIDOR, o <title> = nome original do arquivo enviado, por exemplo:
 *     slug oQDjHTSeQ -> "A.Shop.for.Killers.S01E01.720p.MP4.LEG.BaixarSeriesMP4.pm.mp4"
 *     slug 2q7oyEYTh -> "Loja.Para.Assassinos.S01E01.Murthehelp.1080p.DSNP.WEB-DL.DDP5.1.H.264.DUAL-SiGLA_EMBEDPLAY.mp4"
 *   -> Contem Serie + SxxExx + resolucao + release. E o unico lugar, do lado
 *      do provedor, onde vem a numeracao de temporada/episodio.
 *      O JSON de media (sources/qualities) NAO tem season/episode.
 *
 *   A mesma pagina traz:  const datas = "<base64>"
 *   que decodifica para {"slug":..,"md5_id":..,"user_id":..,"media":<cifrado>}.
 *   O md5_id identifica o episodio no CDN e aparece em /mp4/<md5_id>/...
 *
 * IMPORTANTE: abysscdn.com esta atras do Cloudflare e responde 403 (challenge
 *   "Just a moment...") para clientes Node/undici. O `curl` do sistema passa.
 *   Por isso este script usa o binario curl (existe no Win10+, Linux e macOS).
 *
 * Uso:
 *   node abys_name.js <slug|url-do-player|url-do-embed> [--json]
 *   node abys_name.js oQDjHTSeQ
 *   node abys_name.js "https://embedplayabyss.top/player.html?v=oQDjHTSeQ"
 *   node abys_name.js oQDjHTSeQ 2q7oyEYTh --json
 */
'use strict';
const { execFileSync } = require('child_process');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36';

function extractSlug(input) {
  if (!input) return null;
  const m = String(input).match(/[?&]v=([A-Za-z0-9_-]+)/);
  if (m) return m[1];
  if (/^[A-Za-z0-9_-]{6,}$/.test(input) && !/^https?:/i.test(input)) return input;
  return null;
}

function fixTitle(t) {
  return t
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/&#x27;/gi, "'")
    .trim();
}

function parseSeasonEpisode(name) {
  const s = name.replace(/_/g, ' ');
  let m = s.match(/[Ss](\d{1,3})[\s._-]*[Ee](\d{1,4})/);
  if (m) return { season: +m[1], episode: +m[2], pattern: 'SxxExx' };
  m = s.match(/\b(\d{1,3})[xX](\d{1,4})\b/);
  if (m) return { season: +m[1], episode: +m[2], pattern: 'NxNN' };
  m = s.match(/(?:temporada|season|t)\s*(\d{1,3})[\s._-]*(?:epis[oó]dio|episode|ep|e)\s*(\d{1,4})/i);
  if (m) return { season: +m[1], episode: +m[2], pattern: 'temporada/episodio' };
  m = s.match(/(?:epis[oó]dio|episode|epizod|ep)\s*(\d{1,4})\b/i);
  if (m) return { season: 1, episode: +m[1], pattern: 'Ep NN (temporada suposta=1)' };
  return { season: null, episode: null, pattern: null };
}

function parseResolution(name) {
  const m = name.match(/\b(2160p|1440p|1080p|720p|576p|480p|360p|240p|4K|UHD|HD|SD)\b/i);
  return m ? m[1] : null;
}

function parseFields(title) {
  const base = title.replace(/\.(mp4|mkv|m4v|avi|webm|ts)$/i, '');
  const { season, episode, pattern } = parseSeasonEpisode(base);
  const cut = base.search(/[Ss]\d{1,3}[\s._-]*[Ee]\d{1,4}|\b\d{1,3}[xX]\d{1,4}\b|(?:temporada|season)\s*\d{1,3}|(?:epis[oó]dio|episode|\bep)\s*\d{1,4}/i);
  let show = base;
  if (cut > 0) show = base.slice(0, cut);
  show = show.replace(/[._]+/g, ' ').replace(/\s{2,}/g, ' ').replace(/[-_\s]+$/, '').trim();

  let rest = '';
  if (cut > 0) {
    rest = base.slice(cut)
      .replace(/^[Ss]\d{1,3}[\s._-]*[Ee]\d{1,4}/, '')
      .replace(/^\d{1,3}[xX]\d{1,4}/, '')
      .replace(/^[._\-\s]+/, '').replace(/[._]+/g, ' ').trim();
  }
  return { show, season, episode, pattern, resolution: parseResolution(base), rest };
}

const pad2 = (n) => (n == null ? null : String(n).padStart(2, '0'));

// Busca o HTML via curl (imune ao challenge do Cloudflare para Node).
function curlGet(url, referer) {
  const args = [
    '-s', '-L', '--compressed', '--max-time', '25',
    '-A', UA,
    '-H', 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    '-H', 'Referer: ' + (referer || 'https://embedplayabyss.top/')
  ];
  if (!url.startsWith('https://abysscdn.com')) args.push('-H', 'Origin: https://abysscdn.com');
  args.push(url);
  return execFileSync('curl', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
}

async function resolveName(input, opts = {}) {
  const slug = extractSlug(input);
  if (!slug) throw new Error('slug nao encontrado em: ' + input);

  const url = 'https://abysscdn.com/?v=' + encodeURIComponent(slug);
  const html = curlGet(url, opts.referer);
  if (/Just a moment|cf-mitigated|Attention Required/i.test(html.slice(0, 4000)) && !/<title>/i.test(html)) {
    throw new Error('Cloudflare challenge nao resolvido para ' + url);
  }

  const tm = html.match(/<title>([^<]*)<\/title>/i);
  const title = tm ? fixTitle(tm[1]) : null;

  let datas = null;
  const dm = html.match(/const\s+datas\s*=\s*"([^"]+)"/);
  if (dm) {
    try {
      const j = JSON.parse(Buffer.from(dm[1], 'base64').toString('utf8'));
      datas = { slug: j.slug, md5_id: j.md5_id, user_id: j.user_id };
    } catch (e) { datas = { raw_error: String(e.message) }; }
  }

  const pm = html.match(/params\s*=\s*\{id:\s*(\d+),\s*v:\s*"([^"]+)",\s*referer:\s*"([^"]+)"\}/);
  const playerParams = pm ? { id: +pm[1], v: pm[2], referer: pm[3] } : null;

  const fields = title ? parseFields(title) : {};
  const se = (fields.season != null && fields.episode != null)
    ? 'S' + pad2(fields.season) + 'E' + pad2(fields.episode) : null;

  const safe = (s) => (s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, '').replace(/\s{2,}/g, ' ').trim();
  const em = title && title.match(/\.(mp4|mkv|m4v|avi|webm|ts)$/i);
  const ext = em ? em[1].toLowerCase() : 'mp4';
  const suggested = safe([
    safe(fields.show || 'ABYS_' + (datas && datas.md5_id ? datas.md5_id : slug)),
    se,
    fields.resolution
  ].filter(Boolean).join(' - ')) + '.' + ext;

  return {
    input, slug, player_url: url, original_title: title,
    md5_id: datas ? datas.md5_id : null,
    user_id: datas ? datas.user_id : null,
    player_params: playerParams,
    parsed: { ...fields, se },
    suggested_filename: suggested,
    suggested_folder: safe(fields.show || 'ABYS')
  };
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const jsonOut = args.includes('--json');
  const inputs = args.filter((a) => a !== '--json');
  if (!inputs.length) {
    console.error('uso: node abys_name.js <slug|player-url|embed-url> [--json]');
    process.exit(2);
  }
  (async () => {
    for (const inp of inputs) {
      try {
        const r = await resolveName(inp);
        if (jsonOut) { console.log(JSON.stringify(r, null, 2)); continue; }
        console.log('----------------------------------------------------------');
        console.log('slug               :', r.slug);
        console.log('titulo original    :', r.original_title);
        console.log('md5_id             :', r.md5_id, '(user_id', r.user_id + ')');
        console.log('serie              :', r.parsed.show);
        console.log('temporada/episodio :', r.parsed.se, '(padrao:', r.parsed.pattern + ')');
        console.log('resolucao (no nome):', r.parsed.resolution);
        console.log('NOME FINAL         :', r.suggested_filename);
        console.log('PASTA              :', r.suggested_folder);
      } catch (e) {
        console.error('[erro]', inp, '->', e.message);
      }
    }
  })();
}

module.exports = { resolveName, parseFields, parseSeasonEpisode, extractSlug, curlGet };
