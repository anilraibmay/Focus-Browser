const fs=require('fs');
const s=fs.readFileSync('abyss_lite.js','utf8');
function ctx(tok,before,after,max){let idx=s.indexOf(tok),c=0;while(idx>=0&&c<max){console.log('--- ctx '+tok+' ---');console.log(s.slice(Math.max(0,idx-before),idx+after));idx=s.indexOf(tok,idx+1);c++;}}
// context around array literal start
const st=s.indexOf("['ainer'");
console.log('=== 500 chars BEFORE array ===');
console.log(s.slice(Math.max(0,st-800),st));
console.log('=== 300 chars AFTER array end ===');
let i=s.indexOf('[',st),d=0,end=-1;for(let k=i;k<s.length;k++){const c=s[k];if(c==='[')d++;else if(c===']'){d--;if(d===0){end=k;break;}}}
console.log(s.slice(end+1,end+400));
