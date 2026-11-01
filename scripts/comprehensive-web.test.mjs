import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import vm from 'node:vm';
const root=new URL('../docs/',import.meta.url);
const index=await readFile(new URL('index.html',root),'utf8');
const scripts=[...index.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(x=>x[1]);
function menuFixture(){
 const events={},nodes=new Map();
 const element=id=>{if(!nodes.has(id))nodes.set(id,{hidden:id==='mobileNav',attrs:{},listeners:{},classList:{toggle(){}},contains:x=>x?.inside===true,addEventListener(n,f){this.listeners[n]=f},setAttribute(k,v){this.attrs[k]=v},focus(){this.focused=true}});return nodes.get(id)};
 const context={document:{getElementById:element,addEventListener(n,f){events[n]=f}},window:{matchMedia:()=>({addEventListener(){}})}};
 vm.runInNewContext(scripts[3],context);return {element,events};
}
test('menu toggles icon and accessibility state; outside clicks and Escape close',()=>{
 const {element,events}=menuFixture(),button=element('menuToggle'),nav=element('mobileNav');
 button.listeners.click();assert.equal(nav.hidden,false);assert.equal(button.textContent,'✕');assert.equal(button.attrs['aria-expanded'],'true');
 events.click({target:{inside:true}});assert.equal(nav.hidden,false);
 button.listeners.click();assert.equal(nav.hidden,true);assert.equal(button.textContent,'☰');
 button.listeners.click();events.click({target:{inside:false}});assert.equal(nav.hidden,true);
 button.listeners.click();events.keydown({key:'Escape',preventDefault(){}});assert.equal(nav.hidden,true);assert.equal(button.focused,true);
});
test('every menu destination closes on link selection, including nested content',()=>{
 for(const href of ['#home','about.html','#features','#download','#gateway']){
  const {element}=menuFixture();element('menuToggle').listeners.click();
  element('mobileNav').listeners.click({target:{closest:s=>s==='a'?{href}:null}});assert.equal(element('mobileNav').hidden,true);
 }
});
test('API rate limit leaves labeled fallback counts and clears timer',async()=>{
 const elements=new Map();let cleared=false;
 const element=id=>{if(!elements.has(id))elements.set(id,{textContent:id==='total-downloads'?'4.2K+':id==='btn-dl-count'?'4,248':'',style:{},setAttribute(){},addEventListener(){}});return elements.get(id)};
 const context={location:{hostname:'sunilkrrajbhar5-byte.github.io'},document:{getElementById:element,documentElement:{classList:{toggle(){}}},addEventListener(){}},window:{matchMedia:()=>({matches:false,addEventListener(){}})},fetch:async()=>({ok:false,status:403}),AbortController,Intl,setTimeout:()=>1,clearTimeout:()=>{cleared=true}};
 vm.runInNewContext(scripts[2],context);await new Promise(setImmediate);await new Promise(setImmediate);
 assert.equal(element('total-downloads').textContent,'4.2K+');assert.equal(element('btn-dl-count').textContent,'4,248');assert.match(element('stats-status').textContent,/rate limited.*not a verified count/);assert.ok(cleared);
});
test('HTTP portal and About return 200 and all internal links resolve',async()=>{
 const server=createServer(async(req,res)=>{try{const path=new URL(req.url,'http://localhost').pathname;const file=new URL('.'+path,root);assert.ok(file.href.startsWith(root.href));const data=await readFile(file);res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(data)}catch{res.writeHead(404);res.end()}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 try{
  const base=`http://127.0.0.1:${server.address().port}/`;
  for(const file of ['index.html','about.html']){
   const response=await fetch(base+file);assert.equal(response.status,200);const html=await response.text();assert.match(html,/<!doctype html>/i);assert.match(html,/charset="UTF-8"/i);
   const ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(x=>x[1]);assert.equal(ids.length,new Set(ids).size);
   for(const [,href] of html.matchAll(/<a\b[^>]*href="([^"]+)"/g)){
    const url=new URL(href,base+file);if(url.origin!==new URL(base).origin)continue;
    const linked=await fetch(url);assert.equal(linked.status,200,`${file}: ${href}`);
    if(url.hash)assert.ok((await linked.text()).includes(`id="${url.hash.slice(1)}"`),href);
   }
  }
 }finally{await new Promise(resolve=>server.close(resolve))}
});
