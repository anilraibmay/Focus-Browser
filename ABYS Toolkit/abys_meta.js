#!/usr/bin/env node
/* abys_meta.js - forensic metadata / index extractor for ABYS .fd
   The .fd carries the COMPLETE moov index, so one 20MB part yields the whole film's metadata.
   Usage: node abys_meta.js <url-or-file.fd> [--json out.json] */
const fs=require('fs');const L=require('./abys_lib');
function arg(n,d){const i=process.argv.indexOf(n);return i>=0?process.argv[i+1]:d;}
function parseMvhd(b,off){const ver=b.readUInt8(off+8);const base=off+12;let ts,dur;if(ver===1){ts=b.readUInt32BE(base+16);dur=Number(b.readBigUInt64BE(base+20));}else{ts=b.readUInt32BE(base+8);dur=b.readUInt32BE(base+12);}return{timescale:ts,duration:dur,seconds:ts?dur/ts:0};}
function parseTrak(b,trak){const end=trak.offset+trak.size;const o={};L.walkBoxes(b,trak.dataOffset,end,0,box=>{if(box.bad)return;const d=box.dataOffset;switch(box.type){
 case 'mdhd':{const ver=b.readUInt8(d);if(ver===1)o.mdhd={timescale:b.readUInt32BE(d+20),duration:Number(b.readBigUInt64BE(d+24))};else o.mdhd={timescale:b.readUInt32BE(d+12),duration:b.readUInt32BE(d+16)};if(o.mdhd.timescale)o.mdhd.seconds=o.mdhd.duration/o.mdhd.timescale;break;}
 case 'hdlr':o.handler=b.toString('latin1',d+8,d+12);break;
 case 'stsd':{const n=b.readUInt32BE(d+4);if(n>0){const e=d+8;const fmt=b.toString('latin1',e+4,e+8);o.codec=fmt;if(fmt==='avc1'||fmt==='hvc1'||fmt==='av01'){o.width=b.readUInt16BE(e+32);o.height=b.readUInt16BE(e+34);}else if(fmt==='mp4a'){o.channels=b.readUInt16BE(e+24);o.sampleRate=b.readUInt32BE(e+32)/65536;}}break;}
 case 'stts':{const n=b.readUInt32BE(d+4);let tot=0;for(let i=0;i<n;i++)tot+=b.readUInt32BE(d+8+i*8);o.sampleCount=tot;break;}
 case 'stsz':{const ss=b.readUInt32BE(d+4),c=b.readUInt32BE(d+8);if(ss===0){let sum=0;for(let i=0;i<c;i++)sum+=b.readUInt32BE(d+12+i*4);o.stszBytes=sum;}else o.stszBytes=ss*c;o.samples=c;break;}
 case 'stco':case 'co64':o.chunks=b.readUInt32BE(d+4);break;
 case 'btrt':o.bitrate=b.readUInt32BE(d+12);break;
 }});return o;}
(async()=>{const src=process.argv[2];if(!src){console.error('usage: node abys_meta.js <url-or-file.fd> [--name <fd-basename>] [--json out.json]');process.exit(2);}const fn=arg('--name',src.split('/').pop());let raw;if(/^https?:\/\//i.test(src)){const r=await L.httpGet(src);if(r.status>=400){console.error('HTTP',r.status);process.exit(1);}raw=r.body;}else raw=fs.readFileSync(src);const buf=L.decryptFd(raw,fn);
 const top=L.readBoxes(buf,0,buf.length);const ftyp=top.find(b=>b.type==='ftyp'),moov=top.find(b=>b.type==='moov'),mdat=top.find(b=>b.type==='mdat');
 const R={file:fn,fileBytes:raw.length,brand:ftyp?buf.toString('latin1',ftyp.dataOffset,ftyp.dataOffset+4):null,compatible:ftyp?buf.toString('latin1',ftyp.dataOffset+8,ftyp.dataOffset+ftyp.size).replace(/\0/g,''):null,moovBytes:moov?moov.size:null,mdatDeclaredSize:mdat?mdat.size:null,mdatDataStart:mdat?mdat.dataOffset:null,mdatBytesPresent:mdat?Math.max(0,buf.length-mdat.dataOffset):null,tracks:[]};
 L.walkBoxes(buf,moov.dataOffset,moov.offset+moov.size,0,box=>{if(box.type==='mvhd')R.movie=parseMvhd(buf,box.offset);if(box.type==='trak'&&!box.bad)R.tracks.push(parseTrak(buf,box));});
 console.log(JSON.stringify(R,null,2));const out=arg('--json');if(out){fs.writeFileSync(out,JSON.stringify(R,null,2));console.log('\nsaved ->',out);}})().catch(e=>{console.error('ERR',e.stack);process.exit(1);});
