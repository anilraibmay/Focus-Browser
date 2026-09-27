#!/usr/bin/env node
/*
 * abys_decrypt.js - ABYS (abyss.to) .fd "first data" decryptor
 *
 * Usage:  node abys_decrypt.js <url-or-local-path> [out.mp4]
 *
 * Scheme (reverse-engineered from sw.bundle.js):
 *   pass    = basename of the .fd URL (e.g. e63a6e9602c10ab2b0527e9d37392.232643771.4.fd)
 *   keyMat  = ASCII bytes of md5hex(pass)            -> 32 bytes, AES-256 key
 *   counter = first 16 bytes of keyMat               -> AES-256-CTR initial counter
 *   Only the FIRST 0x10000 (65536) bytes are encrypted; the remainder is plaintext MP4.
 *
 * Output: a playable MP4 containing the first part of the stream (the .fd = part 1 of N).
 */
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');
const http = require('http');

const PIECE = 0x10000; // 65536 bytes encrypted

function md5hex(buf) { return crypto.createHash('md5').update(buf).digest('hex'); }

function decryptFd(raw, filename) {
  const keyMat = Buffer.from(md5hex(filename), 'ascii');      // 32 bytes
  const counter = keyMat.subarray(0, 16);                     // first 16 bytes
  const d = crypto.createDecipheriv('aes-256-ctr', keyMat, counter);
  const head = Buffer.concat([d.update(raw.subarray(0, PIECE)), d.final()]);
  return Buffer.concat([head, raw.subarray(PIECE)]);
}

function fetchUrl(u) {
  return new Promise((resolve, reject) => {
    const lib = u.startsWith('https') ? https : http;
    lib.get(u, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36',
        'Referer': 'https://abysscdn.com/',
        'Origin': 'https://abysscdn.com'
      }
    }, r => {
      const chunks = [];
      r.on('data', c => chunks.push(c));
      r.on('end', () => resolve({ status: r.statusCode, headers: r.headers, body: Buffer.concat(chunks) }));
    }).on('error', reject);
  });
}

(async () => {
  const src = process.argv[2];
  if (!src) { console.error('usage: node abys_decrypt.js <url-or-path> [out.mp4]'); process.exit(2); }
  const isUrl = /^https?:\/\//i.test(src);
  const filename = src.split('/').pop();
  const out = process.argv[3] || (filename.replace(/\.fd$/i, '') + '.mp4');
  console.log('source    :', src);
  console.log('filename  :', filename);
  console.log('md5hex(fn):', md5hex(filename));

  let raw;
  if (isUrl) {
    const r = await fetchUrl(src);
    if (r.status >= 400) { console.error('HTTP', r.status); process.exit(1); }
    raw = r.body;
    console.log('HTTP', r.status, 'len', raw.length);
  } else {
    raw = fs.readFileSync(src);
    console.log('local len', raw.length);
  }

  const outBuf = decryptFd(raw, filename);
  fs.writeFileSync(out, outBuf);
  const sig = outBuf.subarray(4, 8).toString('latin1');
  console.log('saved     :', out, outBuf.length, 'bytes | first box:', sig, sig === 'ftyp' ? '(valid MP4)' : '(!! unexpected)');
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
