const fs=require('fs');
function extractArray(s){
  const starts=[s.indexOf("['ainer'"),s.indexOf("['gger'")].filter(x=>x>=0);
  if(!starts.length) return null;
  const st=starts[0]; let i=s.indexOf('[',st); let d=0,end=-1;
  for(let k=i;k<s.length;k++){const c=s[k]; if(c==='[')d++; else if(c===']'){d--; if(d===0){end=k;break;}}}
  return s.slice(i,end+1);
}
function analyze(f){
  const s=fs.readFileSync(f,'utf8');
  const lit=extractArray(s);
  if(!lit){ console.log(f+': array not found'); return; }
  let arr; try{ arr=eval(lit); }catch(e){ console.log(f+' eval err '+e.message); return; }
  console.log('==== '+f+' :: array len='+arr.length+' ====');
  const kws=['m3u8','mpd','dash','hls','master','mcdn','appspot','abyss','crypto','key','iv','aes','token','http','playlist','.ts','segment','slug','md5','media','primary','derive','subtle','user_id','fetch','abysscdn'];
  arr.forEach((v,i)=>{ const u=String(v); const low=u.toLowerCase(); if(kws.some(k=>low.includes(k))) console.log('  ['+i+'] '+u); });
}
['abyss_lite.js','abyss_core.js'].forEach(analyze);
