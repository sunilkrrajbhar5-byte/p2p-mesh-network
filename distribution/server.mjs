import http from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { readFile, mkdir } from 'node:fs/promises';
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
function httpsURL(text, base=false) {
  if(typeof text!=='string'||text.length>2048||/[\u0000-\u0020\u007f]/.test(text)) throw Error('Invalid HTTPS URL');
  const u=new URL(text);
  if(u.protocol!=='https:'||u.username||u.password||u.hash||(base&&u.search))throw Error('Invalid HTTPS URL');
  return u;
}
export function validateConfig(c) {
  if(!c||typeof c!=='object'||Array.isArray(c))throw Error('Invalid config');
  httpsURL(c.serverBaseUrl,true); httpsURL(c.apkDownloadUrl);
  if(!Number.isInteger(c.latestVersionCode)||c.latestVersionCode<1||c.latestVersionCode>2147483647||
    typeof c.latestVersionName!=='string'||!c.latestVersionName.trim()||c.latestVersionName.length>64||
    typeof c.releaseNotes!=='string'||c.releaseNotes.length>16384||typeof c.forceUpdate!=='boolean'||
    typeof c.telemetryEndpoint!=='string'||!/^\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/.test(c.telemetryEndpoint)||
    (c.apkSha256!=null&&!/^[a-f0-9]{64}$/i.test(c.apkSha256))) throw Error('Invalid config');
  return Object.freeze({...c});
}
async function readBounded(stream, limit) {
  const parts=[];let total=0;
  for await (const part of stream) { const bytes=Buffer.from(part);total+=bytes.length;if(total>limit)throw Error('Body too large');parts.push(bytes); }
  return Buffer.concat(parts,total);
}
const digest=text=>createHash('sha256').update(text).digest();

/** No mutable metrics in memory: SQLite WAL holds counters and idempotent installation observations. */
export async function createDistributionServer(options) {
  let config=validateConfig(options.initialConfig);
  const desktop=httpsURL(options.desktopUrl).href;
  if(typeof options.adminPassword!=='string'||options.adminPassword.length<24||typeof options.installHmacKey!=='string'||options.installHmacKey.length<32)throw Error('Strong admin password and HMAC key required');
  const expectedAuth=digest((options.adminUser||'admin')+':'+options.adminPassword);
  if(options.configUrl) {const raw=httpsURL(options.configUrl);if(raw.hostname!=='raw.githubusercontent.com'||raw.search)throw Error('Use public GitHub Raw configuration');}
  const dbPath=resolve(options.dbPath);await mkdir(dirname(dbPath),{recursive:true});
  const db=new DatabaseSync(dbPath);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY,value INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS installs(os TEXT NOT NULL,id_hash TEXT NOT NULL,os_version TEXT NOT NULL,app_version TEXT NOT NULL,
      first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,event_time INTEGER NOT NULL,PRIMARY KEY(os,id_hash));
    CREATE INDEX IF NOT EXISTS installs_seen ON installs(last_seen);
    CREATE TABLE IF NOT EXISTS settings(name TEXT PRIMARY KEY,value TEXT NOT NULL);`);
  const saved = db.prepare("SELECT value FROM settings WHERE name='config'").get();
  if (saved) config=validateConfig(JSON.parse(saved.value));
  let closed=false;
  const increment=db.prepare('INSERT INTO counters(name,value) VALUES(?,1) ON CONFLICT(name) DO UPDATE SET value=value+1');
  const observe=db.prepare(`INSERT INTO installs(os,id_hash,os_version,app_version,first_seen,last_seen,event_time) VALUES(?,?,?,?,?,?,?)
    ON CONFLICT(os,id_hash) DO UPDATE SET os_version=excluded.os_version,app_version=excluded.app_version,last_seen=excluded.last_seen,event_time=excluded.event_time
    WHERE excluded.event_time>installs.event_time`);
  const dashboard=await readFile(resolve(HERE,'admin_stats.html'),'utf8');
  const scriptHash=createHash('sha256').update(dashboard.match(/<script>([\s\S]*?)<\/script>/)[1]).digest('base64');
  const rates=new Map();let lastCleanup=Date.now(),lastRefresh=0,refreshError=null;
  function allowed(req) {
    let key=req.socket.remoteAddress||'unknown';
    if(options.trustLoopbackProxy&&['127.0.0.1','::1','::ffff:127.0.0.1'].includes(key)) {
      const forwarded=req.headers['x-forwarded-for'];if(typeof forwarded==='string')key=forwarded.split(',')[0].trim().slice(0,64);
    }
    const now=Date.now();
    if(now-lastCleanup>60000){for(const[k,v]of rates)if(now-v.start>60000)rates.delete(k);lastCleanup=now;}
    let record=rates.get(key);if(!record||now-record.start>60000){if(rates.size>=10000)return false;record={start:now,count:0};rates.set(key,record);}
    return ++record.count<=120;
  }
  function authenticated(req) {
    const header=req.headers.authorization||'';
    if(header.length>2048||!header.startsWith('Basic '))return false;
    return timingSafeEqual(digest(Buffer.from(header.slice(6),'base64')),expectedAuth);
  }
  function respond(res,status,data,type='application/json',extra={}) {
    const body=type==='application/json'?JSON.stringify(data):data;
    res.writeHead(status,{'Content-Type':type,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
      'Referrer-Policy':'no-referrer','Content-Security-Policy':`default-src 'none'; script-src 'sha256-${scriptHash}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'`,...extra});
    res.end(body);
  }
  const server=http.createServer({maxHeaderSize:8192,requestTimeout:15000,headersTimeout:10000},async(req,res)=>{
    try {
      if(!allowed(req)){respond(res,429,{error:'Rate limited'},'application/json',{'Retry-After':'60'});req.resume();return;}
      const path=new URL(req.url,'http://distribution.invalid').pathname;
      if(path.startsWith('/admin/')||path==='/admin_stats.html') {
        if(!authenticated(req)){respond(res,401,{error:'Authentication required'},'application/json',{'WWW-Authenticate':'Basic realm="Mesh statistics", charset="UTF-8"'});return;}
        if(req.method==='GET'&&path==='/admin_stats.html'){respond(res,200,dashboard,'text/html; charset=utf-8');return;}
        if(req.method==='GET'&&path==='/admin/api/stats') {
          const counts=Object.fromEntries(db.prepare('SELECT name,value FROM counters').all().map(r=>[r.name,r.value]));
          const total=db.prepare('SELECT os,COUNT(*) AS count FROM installs GROUP BY os').all();
          const active=db.prepare('SELECT os,COUNT(*) AS count FROM installs WHERE last_seen>=? GROUP BY os').all(Date.now()-30*86400000);
          respond(res,200,{downloads:{android:counts.android_downloads||0,desktop:counts.desktop_downloads||0,androidUserAgent:counts.android_ua_downloads||0},
            observedInstalls:Object.fromEntries(total.map(r=>[r.os,r.count])),active30Days:Object.fromEntries(active.map(r=>[r.os,r.count])),
            configFetchedAt:lastRefresh||null,configError:refreshError,updatedAt:new Date().toISOString()});return;
        }
        respond(res,404,{error:'Not found'});return;
      }
      if((req.method==='GET'||req.method==='HEAD')&&(path==='/download/apk'||path==='/download/desktop')) {
        const android=path.endsWith('/apk');
        if(req.method==='GET') {db.exec('BEGIN IMMEDIATE');try{increment.run(android?'android_downloads':'desktop_downloads');if(android&&/Android/i.test(req.headers['user-agent']||''))increment.run('android_ua_downloads');db.exec('COMMIT');}catch(e){db.exec('ROLLBACK');throw e;}}
        res.writeHead(302,{'Location':android?config.apkDownloadUrl:desktop,'Cache-Control':'no-store','Referrer-Policy':'no-referrer'});res.end();return;
      }
      if(req.method==='POST'&&path===config.telemetryEndpoint+'/install') {
        if(!(req.headers['content-type']||'').toLowerCase().startsWith('application/json')){respond(res,415,{error:'JSON required'});req.resume();return;}
        const declared=Number(req.headers['content-length']||0);if(declared>4096){respond(res,413,{error:'Body too large'});req.resume();return;}
        let event;try{event=JSON.parse((await readBounded(req,4096)).toString('utf8'));}catch{respond(res,400,{error:'Invalid JSON'});return;}
        const keys=['installId','os','osVersion','appVersion','timestamp'];
        if(!event||typeof event!=='object'||Object.keys(event).length!==keys.length||!keys.every(k=>typeof event[k]==='string')||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(event.installId)||
          !['ANDROID','DESKTOP','WEB'].includes(event.os)||event.osVersion.length>64||!event.appVersion.length||event.appVersion.length>64||
          /[\u0000-\u001f\u007f]/.test(event.osVersion+event.appVersion)||event.timestamp.length>40){respond(res,422,{error:'Invalid event'});return;}
        const time=Date.parse(event.timestamp),now=Date.now();
        if(!Number.isFinite(time)||time>now+300000||time<now-90*86400000){respond(res,422,{error:'Invalid timestamp'});return;}
        const id=createHmac('sha256',options.installHmacKey).update(event.os+'|'+event.installId.toLowerCase()).digest('hex');
        observe.run(event.os,id,event.osVersion,event.appVersion,now,Math.min(time,now),time);
        respond(res,200,{ok:true});return;
      }
      if(req.method==='GET'&&path==='/') {
        respond(res,200,'<!doctype html><html lang="en"><meta name="viewport" content="width=device-width"><title>Mesh downloads</title><h1>Mesh downloads</h1><p><a href="/download/apk">Download Android APK</a></p><p><a href="/download/desktop">Download desktop bundle</a></p><p>Direct downloads require compatible, correctly signed releases.</p></html>','text/html; charset=utf-8');return;
      }
      respond(res,404,{error:'Not found'});req.resume();
    } catch { if(!res.headersSent)respond(res,500,{error:'Server error'});else res.destroy(); }
  });
  server.keepAliveTimeout=5000;server.maxConnections=128;
  async function refreshConfig() {
    if(!options.configUrl)return;
    try {
      const response=await fetch(options.configUrl,{redirect:'error',signal:AbortSignal.timeout(10000),headers:{Accept:'application/json'}});
      if(!response.ok)throw Error('Config unavailable');
      const candidate=validateConfig(JSON.parse((await readBounded(response.body,65536)).toString('utf8')));
      if(closed)return;
      db.prepare("INSERT INTO settings(name,value) VALUES('config',?) ON CONFLICT(name) DO UPDATE SET value=excluded.value").run(JSON.stringify(candidate));
      config=candidate;
      lastRefresh=Date.now();refreshError=null;
    }catch{refreshError='Remote config unavailable; using last known-good config';}
  }
  const timer=setInterval(()=>void refreshConfig(),300000);timer.unref();
  const retention=setInterval(()=>db.prepare('DELETE FROM installs WHERE last_seen<?').run(Date.now()-365*86400000),86400000);retention.unref();
  return {server,refreshConfig,close:async()=>{if(closed)return;closed=true;clearInterval(timer);clearInterval(retention);await new Promise(resolve=>server.close(resolve));db.close();}};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  const env=process.env;
  const service=await createDistributionServer({initialConfig:JSON.parse(await readFile(resolve(env.CONFIG_FILE||HERE+'/config.json'),'utf8')),
    configUrl:env.CONFIG_URL,desktopUrl:env.DESKTOP_DOWNLOAD_URL,dbPath:env.DB_PATH||'./data/metrics.sqlite',
    adminUser:env.ADMIN_USER||'admin',adminPassword:env.ADMIN_PASSWORD,installHmacKey:env.INSTALL_ID_HMAC_KEY,trustLoopbackProxy:env.TRUST_LOOPBACK_PROXY==='true'});
  await service.refreshConfig();
  service.server.listen(Number(env.PORT||3000),env.BIND_ADDRESS||'127.0.0.1',()=>console.log('Distribution service listening'));
  for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>void service.close().then(()=>process.exit(0)));
}
