#!/usr/bin/env node
// ABYS /sora token generator - reproduz a funcao _0x185e0e do service worker.
// token = b64( b64( AES_CTR_encrypt(pathnameAndQuery, key=md5hex(sizeId)) ).replace(/=/g,'') ).replace(/=/g,'')
// key material = ASCII do md5hex(sizeId) (32 bytes) ; IV = primeiros 16 bytes do md5hex
const crypto = require('crypto');

function md5hex(s){ return crypto.createHash('md5').update(s).digest('hex'); }

function ctr(blob, sizeId){
  const km = Buffer.from(md5hex(String(sizeId)), 'latin1'); // 32 bytes ASCII
  const iv = km.subarray(0,16);
  const c  = crypto.createCipheriv('aes-256-ctr', km, iv);  // CTR: enc==dec
  return Buffer.concat([c.update(blob), c.final()]);
}

function tokenFor(pathnameAndQuery, sizeId){
  const enc = ctr(Buffer.from(pathnameAndQuery,'latin1'), sizeId);
  const b64 = s => Buffer.from(s,'latin1').toString('base64').replace(/=/g,'');
  return b64(b64(enc.toString('latin1')));
}

// decifra um token -> pathname (para auditoria)
function pathFromToken(token, sizeId){
  const inner = Buffer.from(token,'base64').toString('latin1');
  const blob  = Buffer.from(inner,'base64');
  return ctr(blob, sizeId).toString('latin1');
}

module.exports = { tokenFor, pathFromToken, md5hex };

if(require.main === module){
  const [,,cmd,a,b] = process.argv;
  if(cmd==='gen') console.log(tokenFor(a,b));
  else if(cmd==='dec') console.log(pathFromToken(a,b));
  else console.log('uso: node abys_token.js gen "<pathname?query>" <sizeId> | dec <token> <sizeId>');
}
