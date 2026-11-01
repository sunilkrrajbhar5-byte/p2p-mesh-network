/* Desktop-only caching. Mobile index/root requests and gateway traffic are never intercepted. */
const CACHE='sr-mesh-desktop-pwa-v5';
const assets=['gateway.html','desktop.html','manifest.json','desktop-icon-192.png','desktop-icon-512.png'];
const urls=assets.map(file=>new URL(file,self.registration.scope).href);
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(urls)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(Promise.all([caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('sr-mesh-desktop-')&&key!==CACHE).map(key=>caches.delete(key)))),self.clients.claim()])));
self.addEventListener('fetch',event=>{
 const url=new URL(event.request.url);
 if(event.request.method!=='GET' || url.origin!==self.location.origin || !urls.includes(url.origin+url.pathname))return;
 event.respondWith(fetch(event.request).then(response=>{
  if(response.ok){const copy=response.clone();event.waitUntil(caches.open(CACHE).then(cache=>cache.put(url.origin+url.pathname,copy)));}return response;
 }).catch(()=>caches.match(url.origin+url.pathname).then(response=>response||Response.error())));
});
