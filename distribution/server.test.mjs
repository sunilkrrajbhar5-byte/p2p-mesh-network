import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {createDistributionServer,validateConfig} from './server.mjs';

const config=JSON.parse(await readFile(new URL('./config.json',import.meta.url),'utf8'));
test('configuration rejects insecure URLs and malformed policies',()=>{
  assert.equal(validateConfig(config).latestVersionCode,2);
  for(const change of [{serverBaseUrl:'http://example.com'},{apkDownloadUrl:'https://user:pass@example.com/a.apk'},
    {forceUpdate:'false'},{latestVersionCode:1.5},{telemetryEndpoint:'/../admin'},{apkSha256:'bad'}]) {
    assert.throws(()=>validateConfig({...config,...change}));
  }
});

test('protected dashboard, redirects, idempotent telemetry and persistence',async()=>{
  const folder=await mkdtemp(join(tmpdir(),'mesh-distribution-'));
  const options={initialConfig:config,desktopUrl:'https://example.com/desktop.zip',dbPath:join(folder,'metrics.sqlite'),
    adminPassword:'test-password-that-is-long-enough',installHmacKey:'test-hmac-key-at-least-thirty-two-characters'};
  let service;
  const auth={Authorization:'Basic '+Buffer.from('admin:'+options.adminPassword).toString('base64')};
  async function start(){service=await createDistributionServer(options);await new Promise(resolve=>service.server.listen(0,'127.0.0.1',resolve));return 'http://127.0.0.1:'+service.server.address().port;}
  try {
    let base=await start();
    assert.equal((await fetch(base+'/admin_stats.html')).status,401);
    assert.equal((await fetch(base+'/admin/api/stats',{headers:{Authorization:'Basic bad'}})).status,401);
    const page=await fetch(base+'/admin_stats.html',{headers:auth});
    assert.equal(page.status,200);assert.match(page.headers.get('content-security-policy'),/sha256-/);await page.text();
    let response=await fetch(base+'/download/apk',{redirect:'manual',headers:{'User-Agent':'Android test'}});
    assert.equal(response.status,302);assert.equal(response.headers.get('location'),config.apkDownloadUrl);
    await fetch(base+'/download/apk',{method:'HEAD',redirect:'manual'});
    await fetch(base+'/download/desktop',{redirect:'manual'});
    const event={installId:randomUUID(),os:'ANDROID',osVersion:'14',appVersion:'1.1.0',timestamp:new Date().toISOString()};
    const post=e=>fetch(base+'/api/v1/telemetry/install',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(e)});
    assert.equal((await post(event)).status,200);assert.equal((await post(event)).status,200);
    assert.equal((await post({...event,os:'DESKTOP'})).status,200);
    assert.equal((await post({...event,installId:'invalid'})).status,422);
    assert.equal((await post({...event,timestamp:'2999-01-01T00:00:00Z'})).status,422);
    assert.equal((await post({...event,unexpected:'field'})).status,422);
    let stats=await (await fetch(base+'/admin/api/stats',{headers:auth})).json();
    assert.deepEqual(stats.downloads,{android:1,desktop:1,androidUserAgent:1});
    assert.deepEqual(stats.observedInstalls,{ANDROID:1,DESKTOP:1});
    assert.deepEqual(stats.active30Days,{ANDROID:1,DESKTOP:1});
    await service.close();base=await start();
    stats=await (await fetch(base+'/admin/api/stats',{headers:auth})).json();
    assert.equal(stats.observedInstalls.ANDROID,1);assert.equal(stats.downloads.android,1);
    const bytes=await readFile(options.dbPath);assert.equal(bytes.includes(Buffer.from(event.installId)),false);
  } finally {await service?.close();await rm(folder,{recursive:true,force:true});}
});

test('remote configuration survives restart and failed refresh',async()=>{
  const folder=await mkdtemp(join(tmpdir(),'mesh-config-'));const original=globalThis.fetch;
  const options={initialConfig:config,desktopUrl:'https://example.com/desktop.zip',dbPath:join(folder,'db.sqlite'),
    configUrl:'https://raw.githubusercontent.com/example/config/main/config.json',adminPassword:'test-password-that-is-long-enough',installHmacKey:'test-hmac-key-at-least-thirty-two-characters'};
  let service;
  try {
    globalThis.fetch=async()=>new Response(JSON.stringify({...config,apkDownloadUrl:'https://example.com/new.apk'}));
    service=await createDistributionServer(options);await service.refreshConfig();await service.close();
    globalThis.fetch=async()=>{throw Error('offline')};service=await createDistributionServer(options);await service.refreshConfig();
    await new Promise(resolve=>service.server.listen(0,'127.0.0.1',resolve));
    globalThis.fetch=original;
    const response=await fetch('http://127.0.0.1:'+service.server.address().port+'/download/apk',{redirect:'manual'});
    assert.equal(response.headers.get('location'),'https://example.com/new.apk');
  }finally{globalThis.fetch=original;await service?.close();await rm(folder,{recursive:true,force:true});}
});
