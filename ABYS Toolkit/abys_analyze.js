const fs=require('fs');
function countKw(s,k){let c=0,i=0;const lk=k.toLowerCase(),ls=s.toLowerCase();while((i=ls.indexOf(lk,i))>=0){c++;i+=lk.length;}return c;}
function analyze(f){
  const s=fs.readFileSync(f,'utf8');
  console.log('==== '+f+' ('+s.length+' bytes) ====');
  const re=/\['[^']*'(?:,'[^']*')*\]/g; let m,best=null;
  while((m=re.exec(s))){ if(!best||m[0].length>best.length) best=m[0]; }
  if(best){ console.log('biggest array len chars=',best.length); console.log('head:',best.slice(0,400)); }
  ['m3u8','.mpd','dash','/hls','playlist','master.txt','.ts','AES','importKey','decrypt','atob','SoTrym','fetch(','mcdn.net','abyss','.mp4'].forEach(k=>{const c=countKw(s,k); if(c) console.log('  kw',k,'=',c);});
  let idx=s.indexOf('SoTrym'),cnt=0;
  while(idx>=0&&cnt<3){console.log('  --- SoTrym ctx ---');console.log(s.slice(Math.max(0,idx-250),idx+250));idx=s.indexOf('SoTrym',idx+1);cnt++;}
}
['abyss_lite.js','abyss_core.js'].forEach(analyze);
