import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const page=await readFile(new URL('../docs/index.html',import.meta.url),'utf8');
const scripts=[...page.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(x=>x[1]);
function setup(nav={},Detector){
 const elements=new Map(),events={};
 const element=id=>{
  if(!elements.has(id))elements.set(id,{value:'',textContent:'',hidden:false,open:false,disabled:false,
   events:{},addEventListener(n,f){this.events[n]=f;},focus(){this.focused=true;},
   showModal(){this.open=true;},close(){this.open=false;this.events.close?.();},play:async()=>{},
   requestSubmit(){this.onsubmit({preventDefault(){}});}});
  return elements.get(id);
 };
 const location={hostname:'sunilkrrajbhar5-byte.github.io',assign(url){this.opened=url;}};
 const window={BarcodeDetector:Detector,addEventListener(n,f){events[n]=f;}};
 const document={getElementById:element,querySelector:()=>({remove(){}}),addEventListener(n,f){events[n]=f;}};
 const context={URL,location,document,window,navigator:nav,BarcodeDetector:Detector,setTimeout,clearTimeout};
 scripts.slice(0,2).forEach(s=>vm.runInNewContext(s,context));
 return {element,location,events};
}
test('Paste connects only through original origin validation',async()=>{
 for(const [value,allowed] of [['https://192.168.43.1:8443/',true],['http://192.168.43.1/',false],['https://user:pass@host.test/',false],['javascript:alert(1)',false],['https://host.test/path',false]]){
  const x=setup({clipboard:{readText:async()=>value}});await x.element('pasteGateway').onclick();
  assert.equal(Boolean(x.location.opened),allowed);
  assert.equal(x.element('pasteGateway').disabled,false);
 }
});
test('Clipboard denial gives manual fallback and restores button',async()=>{
 const x=setup({clipboard:{readText:async()=>{throw Error('Denied');}}});await x.element('pasteGateway').onclick();
 assert.equal(x.element('gatewayUrl').focused,true);assert.equal(x.element('pasteGateway').disabled,false);
});
test('Unsupported scanner explains system camera fallback',async()=>{
 const x=setup();await x.element('scanGateway').onclick();
 assert.match(x.element('scannerStatus').textContent,/Google Lens/);
});
test('QR detection stops camera before connecting',async()=>{
 let stops=0;
 class Detector{static async getSupportedFormats(){return ['qr_code'];} async detect(){return [{format:'qr_code',rawValue:'https://gateway.test:8443/'}];}}
 const x=setup({mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop(){stops++;}}]})}},Detector);
 await x.element('scanGateway').onclick();await new Promise(resolve=>setImmediate(resolve));
 assert.equal(stops,1);assert.equal(x.location.opened,'https://gateway.test:8443/');assert.equal(x.element('gatewayScanner').open,false);
});
test('Closing while permission is pending stops late-arriving camera stream',async()=>{
 let resolveCamera,stops=0;
 class Detector{static async getSupportedFormats(){return ['qr_code'];}}
 const x=setup({mediaDevices:{getUserMedia:()=>new Promise(r=>resolveCamera=r)}},Detector);
 const opening=x.element('scanGateway').onclick();await new Promise(resolve=>setImmediate(resolve));
 x.element('gatewayScanner').close();resolveCamera({getTracks:()=>[{stop(){stops++;}}]});await opening;
 assert.equal(stops,1);assert.equal(x.location.opened,undefined);
});
