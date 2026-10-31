import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const page=await readFile(new URL('../docs/index.html',import.meta.url),'utf8');
const script=page.match(/<script>([\s\S]*?)<\/script>/)[1];
function portal(){
  const elements=new Map();
  const element=id=>{if(!elements.has(id))elements.set(id,{hidden:false,value:'',textContent:''});return elements.get(id);};
  const location={hostname:'sunilkrrajbhar5-byte.github.io',assign:url=>{location.opened=url;}};
  const context={URL,location,document:{getElementById:element,querySelector:()=>({remove(){}})}};
  vm.runInNewContext(script,context);
  return {element,location};
}
test('Pages shows download portal without starting gateway APIs or sockets',()=>{
  const {element}=portal(); // No fetch/WebSocket/navigator supplied: any gateway access would throw.
  assert.equal(element('githubPortal').hidden,false);
  assert.equal(element('localClient').hidden,true);
  assert.match(page,/releases\/latest\/download\/app-debug\.apk/);
});
test('gateway launcher accepts HTTPS origins and rejects credentials/plaintext',()=>{
  const {element,location}=portal();
  for(const url of ['http://192.168.49.1:8080/','https://user:secret@gateway.test/','https://gateway.test/login']) {
    element('gatewayUrl').value=url;element('gatewayForm').onsubmit({preventDefault(){}});assert.equal(location.opened,undefined);
  }
  element('gatewayUrl').value='https://gateway.test:8443/';
  element('gatewayForm').onsubmit({preventDefault(){}});
  assert.equal(location.opened,'https://gateway.test:8443/');
});
