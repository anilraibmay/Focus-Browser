#!/usr/bin/env node
/*
 * abys_verify.js - verificador de integridade de .mp4 do toolkit ABYS
 *
 * Uso:
 *   node abys_verify.js <arquivo.mp4> [--size <bytes>] [--chunks <dir_ou_manifest.json>] [--json]
 *
 * O que faz:
 *   1. Confere o tamanho do arquivo contra --size (ou contra o total declarado no manifest).
 *   2. Faz o parse dos boxes ISO-BMFF de topo (ftyp/moov/mdat/moof...) para detectar
 *      arquivo incompleto ("so a primeira parte") ou corrompido.
 *   3. Se --chunks apontar para um manifest JSON com a lista de partes/ranges baixadas,
 *      calcula a COBERTURA de bytes e aponta buracos (gaps) entre 0..size-1.
 *   4. Sai com codigo != 0 quando algo esta errado (util p/ CI / scripts de retomada).
 *
 * Manifest esperado (formato do abys_pull / abys_grab):
 *   { "total": 232643771, "parts": [ {"start":0,"end":2097151,"ok":true}, ... ] }
 *   ou uma lista simples [{start,end,...}, ...]
 */
'use strict';
const fs = require('fs');
const path = require('path');

function arg(n, def) { const i = process.argv.indexOf(n); return i >= 0 ? process.argv[i + 1] : def; }
const has = (n) => process.argv.includes(n);

const file = process.argv[2];
if (!file || file.startsWith('--')) { console.error('uso: node abys_verify.js <arquivo.mp4> [--size N] [--chunks manifest.json] [--json]'); process.exit(2); }

const JSONOUT = has('--json');
let sizeArg = arg('--size', null) ? Number(arg('--size')) : null;
const chunksPath = arg('--chunks', null);

const resultado = { arquivo: path.resolve(file), existe: false, tamanho: null, esperado: sizeArg, boxTopo: [], ftyp: false, moov: false, mdat: false, moof: false, cobertura: null, faltas: [], ok: false, avisos: [] };

if (!fs.existsSync(file)) { resultado.avisos.push('arquivo nao existe'); relatar(); process.exit(1); }
resultado.existe = true;

const st = fs.statSync(file);
resultado.tamanho = st.size;
if (!sizeArg) sizeArg = null;

// ---- le cabecalho (e fim) para parse de boxes
const HEAD = Math.min(st.size, 8 * 1024 * 1024); // ate 8MB de cabecalho
const fd = fs.openSync(file, 'r');
const head = Buffer.alloc(HEAD);
fs.readSync(fd, head, 0, HEAD, 0);

function quatrocc(buf, o) { return buf.toString('latin1', o, o + 4); }

// parse de boxes de topo
let o = 0, guard = 0;
const MAXBOX = 8192;
while (o + 8 <= head.length && guard++ < MAXBOX) {
  let sz = head.readUInt32BE(o);
  const ty = quatrocc(head, o + 4);
  resultado.boxTopo.push({ type: ty, offset: o, size: sz });
  if (ty === 'ftyp') resultado.ftyp = true;
  if (ty === 'moov') resultado.moov = true;
  if (ty === 'moof') resultado.moof = true;
  if (ty === 'mdat') { resultado.mdat = true; break; }
  if (sz === 1) { // 64-bit size
    if (o + 16 > head.length) break;
    sz = Number(head.readBigUInt64BE(o + 8));
  }
  if (sz < 8) { resultado.avisos.push(`box '${ty}' com size invalido (${sz}) em offset ${o}`); break; }
  o += sz;
}

// ---- situacao do mdat (posicao/tamanho declarado)
const mdat = resultado.boxTopo.find(b => b.type === 'mdat');
if (mdat) {
  const fimMdat = mdat.offset + mdat.size;
  resultado.mdatDeclarado = mdat.size;
  if (mdat.size > 0 && mdat.offset + mdat.size > st.size) {
    resultado.avisos.push(`mdat declara ${mdat.size} bytes em offset ${mdat.offset} => fim ${fimMdat} > tamanho do arquivo ${st.size} (TRUNCADO)`);
  }
}

// ---- cobertura de partes (opcional)
if (chunksPath && fs.existsSync(chunksPath)) {
  try {
    const raw = JSON.parse(fs.readFileSync(chunksPath, 'utf8'));
    const parts = Array.isArray(raw) ? raw : (raw.parts || []);
    if (!sizeArg && raw && raw.total) { sizeArg = Number(raw.total); resultado.esperado = sizeArg; }
    // normaliza e ordena
    const ivals = parts
      .filter(p => p && (p.ok === undefined || p.ok !== false) && p.start != null && p.end != null)
      .map(p => [Number(p.start), Number(p.end)])
      .sort((a, b) => a[0] - b[0]);
    const faltas = [];
    if (sizeArg) {
      let cur = 0;
      for (const [s, e] of ivals) {
        if (s > cur) faltas.push([cur, s - 1]);
        cur = Math.max(cur, e + 1);
      }
      if (cur < sizeArg) faltas.push([cur, sizeArg - 1]);
    } else {
      // sem tamanho: so relata buracos internos entre partes
      let cur = null;
      for (const [s, e] of ivals) { if (cur != null && s > cur) faltas.push([cur, s - 1]); cur = Math.max(cur == null ? 0 : cur, e + 1); }
    }
    const cobertos = ivals.reduce((a, [s, e]) => a + (e - s + 1), 0);
    resultado.cobertura = { partes: ivals.length, bytesCobertos: cobertos, faltas };
    if (faltas.length) resultado.avisos.push(`${faltas.length} buraco(s) de cobertura`);
    // compara cobertura com o arquivo em disco
    if (sizeArg && cobertos < sizeArg) resultado.avisos.push(`cobertura ${cobertos} < esperado ${sizeArg}`);
  } catch (e) { resultado.avisos.push('manifest invalido: ' + e.message); }
} else if (chunksPath) {
  resultado.avisos.push('manifest de chunks nao encontrado: ' + chunksPath);
}

// ---- veredito
if (sizeArg && st.size !== sizeArg) resultado.avisos.push(`tamanho ${st.size} != esperado ${sizeArg}`);
if (!resultado.ftyp) resultado.avisos.push('falta box ftyp (nao e MP4 valido ou cabecalho incompleto)');
const coberturaOk = !resultado.cobertura || resultado.cobertura.faltas.length === 0;
const tamanhoOk = !sizeArg || st.size === sizeArg;
resultado.ok = resultado.existe && tamanhoOk && resultado.ftyp && coberturaOk && !resultado.avisos.some(a => /TRUNCADO|buraco|!= esperado/.test(a));

function relatar() {
  if (JSONOUT) { console.log(JSON.stringify(resultado, null, 2)); return; }
  const L = (k, v) => console.log(('  ' + k).padEnd(20), v);
  console.log('=== ABYS verify ===');
  L('arquivo', resultado.arquivo);
  L('existe', resultado.existe);
  L('tamanho', resultado.tamanho);
  L('esperado', resultado.esperado == null ? '(nao informado)' : resultado.esperado);
  L('boxes topo', resultado.boxTopo.map(b => b.type).join(' > ') || '(nenhum)');
  L('ftyp/moov/mdat', `${resultado.ftyp}/${resultado.moov}/${resultado.mdat}`);
  if (resultado.cobertura) L('cobertura', `${resultado.cobertura.bytesCobertos} bytes em ${resultado.cobertura.partes} partes; faltas=${resultado.cobertura.faltas.length}`);
  if (resultado.avisos.length) { console.log('  avisos:'); resultado.avisos.forEach(a => console.log('   - ' + a)); }
  console.log('  RESULTADO:', resultado.ok ? 'OK' : 'FALHOU');
}
relatar();
process.exit(resultado.ok ? 0 : 1);
