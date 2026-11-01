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
  // Bounded /24 fallback: default Android ranges plus at most two active LAN slices.
  const prefixes=new Set(['192.168.49','192.168.43']);
  for(const entries of Object.values(interfaces))for(const item of entries||[]) {
    if(item.family==='IPv4' && !item.internal && privateAddress(item.address) && prefixes.size<4)
      prefixes.add(item.address.split('.').slice(0,3).join('.'));
  }
  const hosts=[];
  for(let suffix=1;suffix<=254;suffix++)for(const prefix of prefixes)hosts.push(`${prefix}.${suffix}`);
  return hosts.flatMap(host=>[`https://${host}:8443/`,`http://${host}:8080/`]);
}
async function discover({onFound=()=>{},interfaces=os.networkInterfaces(),probeFn=probe,mdns=true,signal}={}) {
  const urls=candidates(interfaces),queued=new Set(urls),found=new Map();
  let bonjour,browser;
  // DNS-SD queries use multicast UDP through the maintained Bonjour implementation.
  // Older Android gateways do not advertise SR Mesh, so HTTP fingerprint fallback remains essential.
  if(mdns)try {
    const {Bonjour}=require('bonjour-service');bonjour=new Bonjour({},()=>{});
    browser=bonjour.find({type:'srmesh'},service=>{
      if(![8080,8443].includes(service.port))return;
      for(const host of service.addresses||[])if(privateAddress(host)) {
        const url=`${service.port===8443?'https':'http'}://${host}:${service.port}/`;
        if(!queued.has(url) && queued.size<2064){queued.add(url);urls.unshift(url);}
      }
    });
  } catch { /* Multicast may be blocked; unicast discovery still runs. */ }
  try {
    while(urls.length && !signal?.aborted) {
      const batch=urls.splice(0,128);
      await Promise.all(batch.map(async url=>{
        if(signal?.aborted)return;
        if(!await probeFn(url,300))return;
        const parsed=new URL(url),previous=found.get(parsed.hostname);
        if(previous?.secure || (previous && parsed.protocol==='http:'))return;
        const node={id:parsed.hostname,url,label:'SR Mesh gateway',secure:parsed.protocol==='https:',signal:null};
        found.set(node.id,node);if(!signal?.aborted)onFound(node);
      }));
    }
    return [...found.values()];
  } finally {browser?.stop();bonjour?.destroy();}
}
function probe(url,timeout=1800) {
  return new Promise(resolve=>{
    let body='',done=false; const finish=value=>{if(done)return;done=true;clearTimeout(deadline);resolve(value);};
    const req=(url.startsWith('https:')?https:http).get(url,{headers:{'User-Agent':'SR-Mesh-Discovery/1.0.3'}},res=>{
      if(res.statusCode!==200){res.destroy();finish(false);return;}
      res.on('data',chunk=>{body+=chunk.toString();if(Buffer.byteLength(body)>65536){res.destroy();finish(false);}});
      res.on('end',()=>finish(body.includes('Offline Mesh') && body.includes('/login') && body.includes('/ws')));
      res.on('error',()=>finish(false));
    });
    const deadline=setTimeout(()=>{req.destroy();finish(false);},timeout);
    req.on('error',()=>finish(false));
  });
}
module.exports={privateAddress,gatewayUrl,candidates,probe,discover};
