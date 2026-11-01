import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const docs=new URL('../docs/',import.meta.url);
const manifest=JSON.parse(await readFile(new URL('manifest.json',docs),'utf8'));
const worker=await readFile(new URL('sw.js',docs),'utf8');
test('install manifest starts on forced desktop and has offline icons',async()=>{
 assert.equal(manifest.display,'standalone');assert.equal(manifest.theme_color,'#00F2FE');assert.match(manifest.start_url,/force=desktop/);
 for(const icon of manifest.icons)assert.ok((await readFile(new URL(icon.src,docs))).length>0);
});
test('desktop service worker never intercepts mobile, gateway or binary requests',()=>{
 const handlers={};let intercepted=false;
 const self={registration:{scope:'https://example.com/p2p-mesh-network/'},location:{origin:'https://example.com'},addEventListener:(event,fn)=>handlers[event]=fn};
 vm.runInNewContext(worker,{self,URL});
 for(const url of ['https://example.com/p2p-mesh-network/','https://example.com/p2p-mesh-network/index.html','https://192.168.49.1:8443/ws','https://example.com/p2p-mesh-network/downloads/client.exe'])handlers.fetch({request:{method:'GET',url},respondWith(){intercepted=true;}});
 assert.equal(intercepted,false);
});
test('offline desktop navigation uses the cached shell independent of query parameters',async()=>{
 const handlers={};let result,key;
 const self={registration:{scope:'https://example.com/p2p-mesh-network/'},location:{origin:'https://example.com'},addEventListener:(event,fn)=>handlers[event]=fn};
 vm.runInNewContext(worker,{self,URL,Response,fetch:()=>Promise.reject(Error('offline')),caches:{match:async value=>{key=value;return 'cached desktop';}}});
 handlers.fetch({request:{method:'GET',url:'https://example.com/p2p-mesh-network/desktop.html?force=desktop&pwa=1'},respondWith(value){result=value;}});
 assert.equal(await result,'cached desktop');assert.equal(key,'https://example.com/p2p-mesh-network/desktop.html');
});
const portal=await readFile(new URL('desktop.html',docs),'utf8');
const installCode=portal.split('// PWA install controller:')[1].split('// End PWA install controller.')[0].split('\n').slice(1).join('\n');
function installFixture(standalone=false){
 const events={},button={disabled:false,addEventListener:(_name,fn)=>button.click=fn},status={textContent:''};
 vm.runInNewContext(installCode,{window:{matchMedia:()=>({matches:standalone}),addEventListener:(name,fn)=>events[name]=fn},document:{getElementById:id=>id==='install-app'?button:status},navigator:{serviceWorker:{register:()=>Promise.resolve()}}});
 return {events,button,status};
}
test('install button consumes native prompt only after user click and handles installed state',async()=>{
 const {events,button,status}=installFixture();let prompted=0,prevented=false;
 events.beforeinstallprompt({preventDefault(){prevented=true;},async prompt(){prompted++;},userChoice:Promise.resolve({outcome:'accepted'})});
 assert.equal(prevented,true);assert.equal(prompted,0);await button.click();assert.equal(prompted,1);assert.match(status.textContent,/Installation requested/);
 events.appinstalled();assert.equal(button.disabled,true);assert.match(status.textContent,/App installed/);
});
test('unsupported, dismissed and rejected install prompts have recoverable guidance',async()=>{
 const {events,button,status}=installFixture();await button.click();assert.match(status.textContent,/Chrome\/Edge/);
 events.beforeinstallprompt({preventDefault(){},async prompt(){},userChoice:Promise.resolve({outcome:'dismissed'})});await button.click();assert.match(status.textContent,/cancelled/);assert.equal(button.disabled,false);
 events.beforeinstallprompt({preventDefault(){},async prompt(){throw Error('Unavailable');}});await button.click();assert.match(status.textContent,/browser menu/);assert.equal(button.disabled,false);
 assert.equal(installFixture(true).button.disabled,true);
});
