const fs=require('fs');const P=20971520;const d=fs.mkdirSync('parts',{recursive:true});
const b=fs.readFileSync('fixture_src.mp4');let n=0;
for(let o=0;o<b.length;o+=P){n++;fs.writeFileSync('parts/part'+n+'.bin',b.subarray(o,Math.min(o+P,b.length)));console.log('part'+n+'.bin',Math.min(P,b.length-o));}
console.log('total',b.length,'parts',n);
