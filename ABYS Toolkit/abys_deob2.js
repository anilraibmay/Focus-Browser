const fs=require('fs');
const file=process.argv[2];
const s=fs.readFileSync(file,'utf8');
const mapFile=process.argv[3];
const map=JSON.parse(fs.readFileSync(mapFile,'utf8'));
const names=new Set(['a1_0x192b']);
let p=-1;
while((p=s.indexOf('=a1_0x192b',p+1))>=0){
  let j=p-1,nm='';
  while(j>=0 && /[A-Za-z0-9_$]/.test(s[j])){nm=s[j]+nm;j--;}
  if(nm) names.add(nm);
}
function replaceAll(str,name){
  let res='',i=0,needle=name+'(';
  while(true){
    const k=str.indexOf(needle,i);
    if(k<0){res+=str.slice(i);break;}
    const before=str.slice(Math.max(0,k-9),k);
    if(before==='function '){ res+=str.slice(i,k+needle.length); i=k+needle.length; continue; }
    res+=str.slice(i,k);
    let j=k+needle.length,arg='';
    while(j<str.length && str[j]!==')'){arg+=str[j];j++;}
    const idx=parseInt(arg,16);
    const v=map[idx];
    res+=(v===undefined)?('@@UNK'+arg):JSON.stringify(v);
    i=j+1;
  }
  return res;
}
let out=s;
[...names].forEach(n=>{out=replaceAll(out,n);});
fs.writeFileSync(file+'.deob',out);
console.log(file,'->',file+'.deob','len',out.length,'UNK',(out.split('@@UNK').length-1));
