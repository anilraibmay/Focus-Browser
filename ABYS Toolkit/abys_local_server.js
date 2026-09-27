#!/usr/bin/env node
/* abys_local_server.js - Range-aware local HTTP server that streams a rebuilt ABYS movie.
 * Reconstructs byte ranges on the fly from the part set, so any player can seek.
 * Usage:
 *   node abys_local_server.js --name <basename.fd> [--plain] [--port 8787] \
 *        --part 1=part1.fd [--part 2=./parts/part2.bin | --part 2=https://...] [--dir ./parts]
 * Endpoints:  /  -> player page     /movie.mp4 -> video (Range/206/416)     /status -> JSON
 */
const fs=require('fs');const http=require('http');const path=require('path');const L=require('./abys_lib');
function arg(n,d){const i=process.argv.indexOf(n);return i>=0?process.argv[i+1]:d;}
function all(n){const r=[];for(let i=0;i<process.argv.length;i++)if(process.argv[i]===n)r.push(process.argv[i+1]);return r;}
const name=arg('--name'),PORT=Number(arg('--port',8787)),PLAIN=process.argv.includes('--plain');
const P=Number(arg('--part-size',20971520));
const specs={};
for(const s of all('--part')){const i=s.indexOf('=');if(i>0)specs[Number(s.slice(0,i))]=s.slice(i+1);}
const dir=arg('--dir');if(dir&&fs.existsSync(dir))for(const f of fs.readdirSync(dir)){const m=/^part(\d+)\.(bin|fd|part)$/.exec(f);if(m)specs[Number(m[1])]=path.join(dir,f);}
const cache={};const meta={};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function partData(n){
  if(cache[n])return cache[n];
  const spec=specs[n];
  if(!spec)throw new Error('no source for part '+n);
  let buf;
  for(let attempt=1;attempt<=3;attempt++){
    try{
      if(/^https?:\/\//i.test(spec)){const r=await L.httpGet(spec);if(r.status>=400)throw new Error('HTTP '+r.status);buf=r.body;}
      else buf=fs.readFileSync(spec);
      break;
    }catch(e){if(attempt===3)throw e;await sleep(400*attempt);}
  }
  if(n===1&&!PLAIN)buf=L.decryptFd(buf,name||path.basename(spec));
  cache[n]=buf;return buf;
}
function localRange(n){const s=(n-1)*P;return {start:s,end:s+cache[n].length-1};}
async function serveRange(req,res,start,end,status){status=status||206;
  const parts=[];for(let n=Math.floor(start/P)+1;n<=Math.floor(end/P)+1;n++)parts.push(n);
  const chunks=[];
  for(const n of parts){
    let d;try{d=await partData(n);}catch(e){res.writeHead(502,{'Content-Type':'text/plain'});return res.end('part '+n+' unavailable: '+e.message);}
    const rs=(n-1)*P;const a=Math.max(start,rs),b=Math.min(end,rs+d.length-1);
    if(b<a){res.writeHead(502,{'Content-Type':'text/plain'});return res.end('part '+n+' ends at '+ (rs+d.length-1)+', requested '+a);}
    chunks.push(d.subarray(a-rs,b-rs+1));}
  const body=Buffer.concat(chunks);
  const h={'Content-Type':'video/mp4','Accept-Ranges':'bytes','Content-Length':body.length,'Content-Range':'bytes '+start+'-'+end+'/'+TOTAL};
  if(start!==0||end!==TOTAL-1)h['Content-Range']='bytes '+start+'-'+end+'/'+TOTAL;
  res.writeHead(status,h);res.end(req.method==='HEAD'?undefined:body);
}
let TOTAL=0,LAST=0,MOVIE_TOTAL=0,MISSING=[];
(async()=>{
  // idxTotal = real movie size read from the index inside part 1; prefix = contiguous bytes we actually hold
  let idxTotal=0;
  try{const d1=await partData(1);const top=L.readBoxes(d1,0,d1.length);const mdat=top.find(x=>x.type==='mdat'),moov=top.find(x=>x.type==='moov');if(mdat&&moov)idxTotal=mdat.offset+mdat.size;}catch(e){}
  let prefix=0;
  for(let i=1;;i++){const sp=specs[i];if(!sp)break;let sz=P;if(sp.slice(0,4)!=='http'){try{sz=fs.statSync(sp).size;}catch(e){break;}}prefix=(i-1)*P+sz;}
  const ov=arg('--total');
  MOVIE_TOTAL=ov?Number(ov):(idxTotal||prefix||Object.keys(specs).length*P);
  LAST=prefix||MOVIE_TOTAL;
  const need=Math.ceil(MOVIE_TOTAL/P);MISSING=[];for(let i=1;i<=need;i++)if(!specs[i])MISSING.push(i);
  TOTAL=MOVIE_TOTAL;
  if(MISSING.length){TOTAL=Math.min(LAST,MOVIE_TOTAL);
    console.log('WARNING: parts '+MISSING.join(',')+' of 1..'+need+' are missing - serving only the contiguous prefix ('+TOTAL+' of '+MOVIE_TOTAL+' bytes).');
    console.log('         add them with --part N=<url|file> to stream the complete movie.');}
  const srv=http.createServer(async(req,res)=>{
    try{
      const u=req.url.split('?')[0];
      if(u==='/status'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({servedTotal:TOTAL,movieTotal:MOVIE_TOTAL,missingParts:MISSING,partSize:P,sources:specs,decryptFirstPart:!PLAIN,pass:name||null},null,2));}
      if(u==='/'||u==='/index.html'){res.writeHead(200,{'Content-Type':'text/html'});return res.end('<!doctype html><meta charset=utf-8><title>ABYS local stream</title><body style="background:#111;color:#eee;font-family:sans-serif"><h3>ABYS local stream - '+TOTAL+' bytes</h3><video id=v controls style="width:90%"></video><script>v.src="/movie.mp4"</script>');}
      if(u!=='/movie.mp4'){res.writeHead(404);return res.end('not found');}
      const r=req.headers.range;
      if(!r){return serveRange(req,res,0,Math.max(0,LAST-1),200);}
      const m=/bytes=(\d*)-(\d*)/.exec(r);
      if(!m){res.writeHead(416,{'Content-Range':'bytes */'+TOTAL});return res.end();}
      let start=m[1]===''?undefined:Number(m[1]);let end=m[2]===''?TOTAL-1:Number(m[2]);
      if(start===undefined){start=Math.max(0,TOTAL-end);end=TOTAL-1;}      // suffix range
      if(start>=TOTAL||start>end){res.writeHead(416,{'Content-Range':'bytes */'+TOTAL});return res.end();}
      end=Math.min(end,TOTAL-1);
      console.log(new Date().toISOString()+'  Range '+start+'-'+end+' ('+(end-start+1)+' bytes)');
      return serveRange(req,res,start,end);
    }catch(e){res.writeHead(500,{'Content-Type':'text/plain'});res.end('ERR '+e.message);}
  });
  srv.listen(PORT,'127.0.0.1',()=>{console.log('ABYS local stream on http://127.0.0.1:'+PORT+'/movie.mp4  (total '+TOTAL+' bytes, '+Object.keys(specs).length+' part source(s))');console.log('sources: '+JSON.stringify(specs));});
})();
