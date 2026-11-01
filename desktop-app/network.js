const os = require('node:os');
const dns = require('node:dns').promises;
const http = require('node:http');
const https = require('node:https');
function privateAddress(host) {
  if(!/^\d{1,3}(\.\d{1,3}){3}$/.test(host))return false;
  const parts=host.split('.').map(Number);
  if(parts.length!==4 || parts.some(x=>!Number.isInteger(x)||x<0||x>255))return false;
  return parts[0]===10 || (parts[0]===172 && parts[1]>=16 && parts[1]<=31) || (parts[0]===192 && parts[1]===168);
}
async function gatewayUrl(value) {
  const url=new URL(String(value));
  if(!['http:','https:'].includes(url.protocol) || url.username || url.password || url.pathname!=='/' || url.search || url.hash)throw Error('Enter a local HTTP(S) gateway origin only.');
  const addresses=await dns.lookup(url.hostname,{all:true,family:4});
  if(!addresses.length || addresses.some(a=>!privateAddress(a.address)))throw Error('Gateway must resolve to a private local IPv4 address.');
  return url.origin+'/';
}
function candidates(interfaces=os.networkInterfaces()) {
  const hosts=new Set(['192.168.49.1','192.168.43.1']);
  for(const entries of Object.values(interfaces))for(const item of entries||[]) {
    if(item.family==='IPv4' && !item.internal && privateAddress(item.address) && hosts.size<8)
      hosts.add(item.address.split('.').slice(0,3).join('.')+'.1');
  }
  return [...hosts].flatMap(host=>[`https://${host}:8443/`,`http://${host}:8080/`]);
}
function probe(url,timeout=1800) {
  return new Promise(resolve=>{
    let body='',done=false; const finish=value=>{if(done)return;done=true;clearTimeout(deadline);resolve(value);};
    const req=(url.startsWith('https:')?https:http).get(url,{headers:{'User-Agent':'SR-Mesh-Discovery/1.0.2'}},res=>{
      if(res.statusCode!==200){res.destroy();finish(false);return;}
      res.on('data',chunk=>{body+=chunk.toString();if(Buffer.byteLength(body)>65536){res.destroy();finish(false);}});
      res.on('end',()=>finish(body.includes('Offline Mesh') && body.includes('/login') && body.includes('/ws')));
      res.on('error',()=>finish(false));
    });
    const deadline=setTimeout(()=>{req.destroy();finish(false);},timeout);
    req.on('error',()=>finish(false));
  });
}
module.exports={privateAddress,gatewayUrl,candidates,probe};
