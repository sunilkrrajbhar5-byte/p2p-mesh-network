const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {privateAddress,gatewayUrl,candidates,probe,discover}=require('../network');
test('discovery accepts only private IPv4 ranges',()=>{
 for(const host of ['192.168.49.1','10.1.2.3','172.16.1.1','172.31.255.254'])assert.equal(privateAddress(host),true);
 for(const host of ['127.0.0.1','8.8.8.8','172.32.1.1','192.168..1','192.168.1.999','::1'])assert.equal(privateAddress(host),false);
});
test('manual URL validation rejects credentials, public addresses and active paths',async()=>{
 assert.equal(await gatewayUrl('https://192.168.49.1:8443/'),'https://192.168.49.1:8443/');
 for(const value of ['file:///etc/passwd','https://user:pass@192.168.1.1/','http://8.8.8.8/','http://192.168.1.1/login','http://192.168.1.1/?secret=x'])await assert.rejects(gatewayUrl(value));
});
test('candidate generation is bounded and ignores public/internal interfaces',()=>{
 const interfaces={wifi:[{family:'IPv4',internal:false,address:'192.168.8.22'}],public:[{family:'IPv4',internal:false,address:'8.8.8.8'}]};
 const urls=candidates(interfaces);assert.ok(urls.includes('http://192.168.8.1:8080/'));assert.ok(urls.length<=2032);assert.ok(!urls.some(x=>x.includes('8.8.8.8')));
});
test('probe identifies protocol without logging in; unrelated/oversized/timed-out endpoints rejected',async()=>{
 const server=http.createServer((req,res)=>{if(req.url==='/slow')return;res.end(req.url==='/good'?'Offline Mesh /login /ws':req.url==='/large'?'x'.repeat(70000):'unrelated page');});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const origin='http://127.0.0.1:'+server.address().port;
 try{assert.equal(await probe(origin+'/good'),true);assert.equal(await probe(origin+'/'),false);assert.equal(await probe(origin+'/large'),false);assert.equal(await probe(origin+'/slow',50),false);}finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});

test('discovery probes non-.1 hosts, emits live results and prefers trusted HTTPS',async()=>{
 const emitted=[];let active=0,peak=0;
 const result=await discover({interfaces:{},mdns:false,onFound:n=>emitted.push(n),probeFn:async(url,timeout)=>{
  assert.equal(timeout,300);active++;peak=Math.max(peak,active);await new Promise(resolve=>setImmediate(resolve));active--;
  return new URL(url).hostname==='192.168.49.73';
 }});
 assert.equal(result.length,1);assert.equal(result[0].id,'192.168.49.73');assert.equal(result[0].secure,true);assert.equal(result[0].signal,null);assert.ok(emitted.length);assert.ok(peak<=128);
});
test('aborted discovery never starts probes',async()=>{
 const controller=new AbortController();controller.abort();let count=0;
 assert.deepEqual(await discover({interfaces:{},mdns:false,signal:controller.signal,probeFn:async()=>{count++;return true;}}),[]);assert.equal(count,0);
});
