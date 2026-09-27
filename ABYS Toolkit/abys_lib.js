/*
 * abys_lib.js - reusable core for the ABYS (abyss.to) toolkit
 *
 * Confirmed scheme (from sw.bundle.js):
 *   keyMat  = ASCII bytes of md5hex(basename_of_fd)   -> 32 bytes (AES-256 key)
 *   counter = first 16 bytes of keyMat                -> AES-256-CTR IV
 *   Only the first 0x10000 (65536) bytes of a .fd are encrypted; the rest is plaintext.
 */
const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const http = require('http');

const PIECE = 0x10000; // 65536

const DEFAULT_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36',
  'Referer': 'https://abysscdn.com/',
  'Origin': 'https://abysscdn.com'
};

function md5hex(buf) {
  return crypto.createHash('md5').update(buf).digest('hex');
}

function keyMaterial(filename) {
  return Buffer.from(md5hex(filename), 'ascii'); // 32 bytes
}

// Decrypt a full .fd buffer (part 1). Only first 64KB is transformed.
function decryptFd(raw, filename) {
  const keyMat = keyMaterial(filename);
  const d = crypto.createDecipheriv('aes-256-ctr', keyMat, keyMat.subarray(0, 16));
  const head = Buffer.concat([d.update(raw.subarray(0, PIECE)), d.final()]);
  return Buffer.concat([head, raw.subarray(PIECE)]);
}

// Encrypt a string with the token scheme (used for /sora/ tokens).
function tokenEncrypt(plainStr, pass, double = true) {
  const keyMat = keyMaterial(pass);
  const c = crypto.createCipheriv('aes-256-ctr', keyMat, keyMat.subarray(0, 16));
  const enc = Buffer.concat([c.update(Buffer.from(plainStr, 'utf8')), c.final()]);
  let b1 = Buffer.from(enc.toString('latin1'), 'latin1').toString('base64').replace(/=/g, '');
  if (!double) return b1;
  return Buffer.from(b1, 'binary').toString('base64').replace(/=/g, '');
}

function httpGet(u, headers, range) {
  return new Promise((resolve, reject) => {
    const lib = u.startsWith('https') ? https : http;
    const h = Object.assign({}, DEFAULT_HEADERS, headers || {});
    if (range) h['Range'] = range;
    lib.get(u, { headers: h }, r => {
      const chunks = [];
      r.on('data', c => chunks.push(c));
      r.on('end', () => resolve({ status: r.statusCode, headers: r.headers, body: Buffer.concat(chunks) }));
    }).on('error', reject);
  });
}

// ---- Minimal ISO-BMFF box parser (for metadata / index extraction) ----
const CONTAINERS = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'udta', 'dinf', 'moof', 'traf', 'mvex', 'meta']);

function readBoxes(buf, start, end) {
  const out = [];
  let o = start;
  while (o + 8 <= end) {
    let size = buf.readUInt32BE(o);
    const type = buf.toString('latin1', o + 4, o + 8);
    let headerSize = 8;
    if (size === 1) { size = Number(buf.readBigUInt64BE(o + 8)); headerSize = 16; }
    else if (size === 0) { size = end - o; }
    if (size < headerSize || o + size > end) { out.push({ type, size, offset: o, headerSize, bad: true }); break; }
    out.push({ type, size, offset: o, headerSize, dataOffset: o + headerSize, bad: false });
    if (type === 'mdat') break;
    o += size;
  }
  return out;
}

function findBox(buf, start, end, type) {
  return readBoxes(buf, start, end).find(b => b.type === type) || null;
}

function walkBoxes(buf, start, end, depth, visit) {
  for (const b of readBoxes(buf, start, end)) {
    visit(b, depth);
    if (CONTAINERS.has(b.type) && !b.bad && depth < 8) {
      walkBoxes(buf, b.dataOffset, b.offset + b.size, depth + 1, visit);
    }
    if (b.type === 'mdat') break;
  }
}

module.exports = { PIECE, md5hex, keyMaterial, decryptFd, tokenEncrypt, httpGet, readBoxes, findBox, walkBoxes, DEFAULT_HEADERS };
