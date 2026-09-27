const fs=require('fs');
function extractArray(s){const st=s.indexOf("['ainer'");let i=s.indexOf('[',st),d=0,end=-1;for(let k=i;k<s.length;k++){const c=s[k];if(c==='[')d++;else if(c===']'){d--;if(d===0){end=k;break;}}}return s.slice(i,end+1);}
const s=fs.readFileSync('abyss_lite.js','utf8');
const arr=eval(extractArray(s));
function show(a,b){let out=[];for(let k=a;k<=b;k++)out.push(k+':"'+arr[k]+'"');console.log(out.join('  '));}
console.log('around 388-400:'); show(388,400);
console.log('around 666-676:'); show(666,676);
console.log('around 1128-1136:'); show(1128,1136);
console.log('around 936-946:'); show(936,946);
console.log('around 1090-1105:'); show(1090,1105);
console.log('around 76-90:'); show(76,90);
console.log('lengths histogram:'); const h={};arr.forEach(v=>{const l=String(v).length;h[l]=(h[l]||0)+1;});console.log(JSON.stringify(h));
