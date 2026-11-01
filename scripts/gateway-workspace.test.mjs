import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
const html=await readFile(new URL('../docs/gateway.html',import.meta.url),'utf8');
const script=html.split('<script>')[1].split('</script>')[0];
function fixture(fetcher){
 const nodes=new Map(),sockets=[];
 const node=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',disabled:id==='app',hidden:false,dataset:{},style:{},files:[],children:[],options:[],selectedOptions:[{value:'Peer#123456',dataset:{cluster:'cluster'}}],classList:{add(){},remove(){}},append(...items){this.children.push(...items);},replaceChildren(){this.children=[];},add(){},addEventListener(){},setAttribute(){},focus(){},click(){return this.onclick?.();}});return nodes.get(id);};
 class Socket{static OPEN=1;constructor(url){this.url=url;this.readyState=1;this.bufferedAmount=0;this.sent=[];sockets.push(this);}send(data){this.sent.push(JSON.parse(data));}close(){this.readyState=3;}}
 const context=vm.createContext({document:{hidden:true,getElementById:node,createElement:()=>node('new-'+Math.random()),createTextNode:text=>text,addEventListener(){}},window:{addEventListener(){}},location:{origin:'https://192.168.49.1:8443',hostname:'192.168.49.1',protocol:'https:',hash:'',pathname:'/',assign(){}},navigator:{},localStorage:{getItem(){return null;},setItem(){}},history:{replaceState(){}},WebSocket:Socket,crypto:webcrypto,TextEncoder,TextDecoder,DataView,Uint8Array,ArrayBuffer,URL,URLSearchParams,AbortController,DOMException,Response,Blob,fetch:fetcher,setTimeout,clearTimeout,setInterval(){},isSecureContext:false});
 vm.runInContext(script,context);return {context,node,sockets};
}
test('real protocol login, chat and 512 KiB file chunk upload use authenticated endpoints',async()=>{
 const requests=[];const f=fixture(async(path,options={})=>{requests.push({path,...options});return Response.json(path==='/upload'?{id:'upload-id'}:{ok:true});});
 vm.runInContext("hostNodes.set(location.origin+'/',location.origin+'/')",f.context);f.node('nickname').value='Sunil';f.node('secret').value='host-key';await f.node('connect').onclick();
 assert.equal(requests[0].path,'/login');assert.equal(JSON.parse(requests[0].body).secret,'host-key');assert.equal(f.node('secret').value,'');
 const socket=f.sockets[0];assert.equal(socket.url,'wss://192.168.49.1:8443/ws');socket.onmessage({data:JSON.stringify({type:'ready',cluster:'cluster'})});assert.equal(f.node('app').disabled,false);
 f.node('text').value='नमस्ते 🌐';f.node('send').onclick();assert.equal(socket.sent[0].text,'नमस्ते 🌐');assert.equal(socket.sent[0].target,'Peer#123456');
 const payload=new Blob([new Uint8Array(524288+31).fill(27)]);payload.name='payload.zip';f.context.payload=payload;vm.runInContext('selectedFile=payload',f.context);await f.node('upload').onclick();
 const chunks=requests.filter(r=>r.method==='PUT');assert.deepEqual(chunks.map(c=>c.body.length),[524288,31]);assert.ok(chunks.every(c=>c.body.every(b=>b===27)));assert.equal(requests.at(-1).path,'/upload/upload-id/commit');assert.equal(f.node('upload').disabled,true);
 socket.onmessage({data:JSON.stringify({type:'transfer',id:'upload-id',state:'delivered'})});assert.equal(f.node('upload').disabled,false);assert.match(f.node('transfer').textContent,/verified/);
 socket.onclose();assert.equal(f.node('app').disabled,true);
});
test('discovery rejects unrelated and opaque responses; accepts readable protocol signature',async()=>{
 let response=Response.json({unrelated:true});const f=fixture(async()=>response);
 assert.equal(await vm.runInContext("probeGateway('https://192.168.49.1:8443/')",f.context),false);
 response=new Response('<title>Offline Mesh</title> /login /ws');assert.equal(await vm.runInContext("probeGateway('https://192.168.49.1:8443/')",f.context),true);
 response={ok:true,type:'opaque'};assert.equal(await vm.runInContext("probeGateway('https://192.168.49.1:8443/')",f.context),false);
});

