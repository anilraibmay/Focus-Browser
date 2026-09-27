#!/usr/bin/env node
/* abys_assemble.js - concatenate the .fd part set into a playable single MP4.
 * Part 1 is the .fd itself (64KB encrypted header); parts 2..N are plain continuations
 * of the same mdat. Nothing is re-muxed: a straight byte concatenation rebuilds the movie.
 * Usage:
 *   node abys_assemble.js --name <basename.fd> --out movie.mp4 [--plain] \
 *        --part 1=part1.fd --part 2=https://host/sora/232643771/<token> ...
 *   node abys_assemble.js --name <b.fd> --dir ./parts --out movie.mp4      (part<N>.bin)
 */
const fs=require('fs');const path=require('path');const crypto=require('crypto');const L=require('./abys_lib');
function arg(n,d){const i=process.argv.indexOf(n);return i>=0?process.argv[i+1]:d;}
function all(n){const r=[];for(let i=0;i<process.argv.length;i++)if(process.argv[i]===n)r.push(process.argv[i+1]);return r;}
(async()=>{
  const name=arg('--name');const out=arg('--out','movie_full.mp4');const plain=process.argv.includes('--plain');
  const specs={};
  for(const s of all('--part')){const i=s.indexOf('=');if(i<0){console.error('bad --part (need N=spec):',s);process.exit(2);}specs[Number(s.slice(0,i))]=s.slice(i+1);}
  const dir=arg('--dir');
  if(dir&&fs.existsSync(dir))for(const f of fs.readdirSync(dir)){const m=/^part(\d+)\.(bin|fd|part)$/.exec(f);if(m)specs[Number(m[1])]=path.join(dir,f);}
  const idx=Object.keys(specs).map(Number).sort((a,b)=>a-b);
  if(!idx.length){console.error('no parts given. use --part N=path|url or --dir <dir>');process.exit(2);}
  const bufs=[];
  for(const n of idx){
    const spec=specs[n];let data;
    if(/^https?:\/\//i.test(spec)){const r=await L.httpGet(spec);if(r.status>=400){console.error('part '+n+' HTTP '+r.status+' :: '+spec);process.exit(1);}data=r.body;console.log('part '+n+': HTTP '+r.status+' '+data.length+' bytes');}
    else{data=fs.readFileSync(spec);console.log('part '+n+': '+spec+' '+data.length+' bytes');}
    if(n===1&&!plain){const fn=name||spec.split('/').pop();data=L.decryptFd(data,fn);console.log('part 1: decrypted 64KB header with pass='+fn);}
    bufs.push(data);
  }
  const full=Buffer.concat(bufs);fs.writeFileSync(out,full);
  const md5=crypto.createHash('md5').update(full).digest('hex');
  console.log('\nassembled '+idx.length+' part(s) -> '+out+'  '+full.length+' bytes  md5='+md5);
  if(idx[0]!==1||idx[idx.length-1]!==idx.length)console.log('WARNING: non-contiguous part set (missing: '+[...Array(idx[idx.length-1])].map((_,i)=>i+1).filter(i=>!idx.includes(i)).join(',')+') - file is incomplete');
})().catch(e=>{console.error('ERR',e.stack);process.exit(1);});
