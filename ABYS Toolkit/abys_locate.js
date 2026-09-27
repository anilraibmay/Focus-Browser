#!/usr/bin/env node
/* abys_locate.js - map time -> absolute byte offset -> .fd part number
 * The decrypted .fd carries the WHOLE movie's stbl index, so one 20MB part yields the full map.
 * The .fd is not a container: after decrypting its first 64KB it IS the first partSize bytes of
 * the final MP4, therefore part N = movie bytes [N*partSize, (N+1)*partSize).
 * Usage: node abys_locate.js <file.fd|url> --name <basename.fd> [--at 60] [--part 3] [--part-size 20971520] [--json out.json]
 */
const fs=require('fs');const L=require('./abys_lib');
function arg(n,d){const i=process.argv.indexOf(n);return i>=0?process.argv[i+1]:d;}
function num(n,d){const v=arg(n);return v===undefined?d:Number(v);}
const CONT=new Set(['moov','trak','mdia','minf','stbl','edts','udta','dinf','moof','traf']);
function collect(buf,start,end,path,acc){
  for(const b of L.readBoxes(buf,start,end)){
    const p=path?path+'.'+b.type:b.type;let k=p,n=1;while(acc.some(x=>x.p===k)){k=p+'#'+n;n++;}
    acc.push({p:k,b});
    if(!b.bad&&CONT.has(b.type))collect(buf,b.dataOffset,b.offset+b.size,k,acc);
    if(b.type==='mdat')break;
  }
  return acc;
}
function readStbl(buf,acc,prefix){
  const g=t=>{const x=acc.find(a=>a.p.startsWith(prefix+'.')&&a.p.endsWith('.stbl.'+t));return x?x.b:null;};
  const u32=(box,rel)=>buf.readUInt32BE(box.dataOffset+rel);
  const stsz=g('stsz'),stco=g('stco'),co64=g('co64'),stsc=g('stsc'),stts=g('stts');
  if(!stsz||(!stco&&!co64))return null;
  const uniform=u32(stsz,4),count=u32(stsz,8);
  const sizeOf=i=>uniform||buf.readUInt32BE(stsz.dataOffset+12+i*4);
  let chunks=[];
  if(stco){const n=u32(stco,4);for(let i=0;i<n;i++)chunks.push(buf.readUInt32BE(stco.dataOffset+8+i*4));}
  else{const n=u32(co64,4);for(let i=0;i<n;i++)chunks.push(Number(buf.readBigUInt64BE(co64.dataOffset+8+i*8)));}
  const ent=[];if(stsc){const n=u32(stsc,4);for(let i=0;i<n;i++)ent.push([buf.readUInt32BE(stsc.dataOffset+8+i*12),buf.readUInt32BE(stsc.dataOffset+12+i*12)]);}
  const spcFor=c=>{let r=ent.length?ent[0][1]:1;for(const e of ent){if(e[0]<=c)r=e[1];else break;}return r;};
  const offs=new Array(count);let si=0;
  for(let c=1;c<=chunks.length&&si<count;c++){let o=chunks[c-1];const spc=spcFor(c);for(let j=0;j<spc&&si<count;j++){offs[si]=o;o+=sizeOf(si);si++;}}
  const times=new Array(count);let t=0;
  if(stts){const n=u32(stts,4);const d=[];for(let i=0;i<n;i++){const cnt=buf.readUInt32BE(stts.dataOffset+8+i*8),dl=buf.readUInt32BE(stts.dataOffset+12+i*8);for(let k=0;k<cnt;k++)d.push(dl);}for(let i=0;i<count;i++){times[i]=t;t+=(d[i]||0);}}
  return {count,uniform,sizeOf,chunks,offs,times,lastTime:t,totalBytes:count?offs[count-1]+sizeOf(count-1):0};
}
(async()=>{
  const src=process.argv[2];
  if(!src){console.error('usage: node abys_locate.js <file.fd|url> --name <basename.fd> [--at 60] [--part 3]');process.exit(2);}
  const fn=arg('--name',src.split('/').pop());
  const P=num('--part-size',20971520);
  let raw;if(/^https?:\/\//i.test(src)){const r=await L.httpGet(src);raw=r.body;}else raw=fs.readFileSync(src);
  const buf=L.decryptFd(raw,fn);
  const acc=collect(buf,0,buf.length,'',[]);
  const moovE=acc.find(a=>a.p==='moov'),mdatE=acc.find(a=>a.p==='mdat');
  const movieTotal=moovE&&mdatE?mdatE.b.offset+mdatE.b.size:null;
  const trakEs=acc.filter(a=>/^moov\.trak(#\d+)?$/.test(a.p));
  const infos=trakEs.map(e=>{const h=acc.find(a=>a.p.startsWith(e.p+'.mdia.hdlr'));return {prefix:e.p,handler:h?buf.toString('latin1',h.b.dataOffset+8,h.b.dataOffset+12):null};});
  const vTrak=infos.find(t=>t.handler==='vide');
  if(!vTrak){console.error('no video track found (tracks: '+JSON.stringify(infos)+')');process.exit(1);}
  const st=readStbl(buf,acc,vTrak.prefix);
  const mdhdE=acc.find(a=>a.p.startsWith(vTrak.prefix+'.mdia.mdhd'));
  const timescale=mdhdE?(buf.readUInt8(mdhdE.b.dataOffset)===1?buf.readUInt32BE(mdhdE.b.dataOffset+20):buf.readUInt32BE(mdhdE.b.dataOffset+12)):24000;
  const nParts=Math.ceil((movieTotal||raw.length)/P);
  const R={pass:fn,fdBytes:raw.length,movieTotalBytes:movieTotal,mdatStartAbs:mdatE?mdatE.b.offset:null,mdatDataStartAbs:mdatE?mdatE.b.dataOffset:null,videoTimescale:timescale,videoSamples:st.count,videoLastEndByteAbs:st.totalBytes,partSize:P,partCount:nParts,parts:[]};
  for(let i=0;i<nParts;i++){const s=i*P,e=Math.min((i+1)*P,movieTotal||((i+1)*P));R.parts.push({part:i+1,start:s,end:e-1,bytes:e-s});}
  console.log(JSON.stringify(R,null,2));
  const ats=[];for(let i=0;i<process.argv.length;i++)if(process.argv[i]==='--at')ats.push(process.argv[i+1]);
  for(const q of ats){const tsec=Number(q);const target=Math.round(tsec*timescale);let s=0;while(s<st.count-1&&st.times[s+1]<=target)s++;
    const off=st.offs[s],part=Math.floor(off/P)+1;
    console.log('\n--at '+tsec+'s -> video sample #'+s+' @ t='+(st.times[s]/timescale).toFixed(3)+'s');
    console.log('  absolute byte offset : '+off+' (0x'+off.toString(16)+')');
    console.log('  lives in part #'+part+'/'+nParts+', local offset '+(off-(part-1)*P));
    console.log('  present in stored .fd: '+(off<raw.length));}
  const pt=arg('--part');if(pt!==undefined){const p=Number(pt),s0=(p-1)*P,en=s0+P-1;let a=-1,z=-1,n=0;for(let i=0;i<st.count;i++){if(st.offs[i]>=s0&&st.offs[i]<=en){if(a<0)a=i;z=i;n++;}}console.log('\npart #'+p+' bytes '+s0+'-'+en+' -> video samples '+(n?a+'..'+z:'none')+' ('+n+' samples)');}
  const out=arg('--json');if(out)fs.writeFileSync(out,JSON.stringify(R,null,2));
})().catch(e=>{console.error('ERR',e.stack);process.exit(1);});
