const fs=require('fs'), vm=require('vm');
const s=fs.readFileSync('abyss_lite.js','utf8');
const startFn=s.indexOf('function a1_0xeb19');
let last=-1,p=-1; while((p=s.indexOf('a1_0xeb19',p+1))>=0) last=p;
const end=s.indexOf(';', last);
const code=s.slice(startFn, end+1);
const sb={}; vm.createContext(sb);
vm.runInContext(code + '\nthis.__fn=a1_0xeb19; this.__acc=a1_0x192b;', sb);
const arr=sb.__fn(), acc=sb.__acc;
console.log('arr len', arr.length);
const map={};
for(let i=0x185;i<0x185+arr.length;i++){ try{map[i]=String(acc(i));}catch(e){} }
fs.writeFileSync('abys_indexmap.json', JSON.stringify(map));
const kws=['m3u8','mpd','hls','master','mcdn','appspot','abyss','crypto','importkey','aes','decrypt','http','/api','source','slug','md5','media','user_id','fetch','.mp4','.ts','primary','subtle'];
Object.keys(map).forEach(k=>{const v=map[k]; const low=v.toLowerCase(); if(kws.some(w=>low.includes(w))) console.log('  idx='+k+' (0x'+Number(k).toString(16)+') '+JSON.stringify(v));});
