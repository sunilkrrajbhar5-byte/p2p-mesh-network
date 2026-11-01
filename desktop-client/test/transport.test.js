const {test}=require('node:test'),assert=require('node:assert/strict');
const http=require('node:http'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
const {WebSocketServer}=require('ws');
const {GatewayTransport,allowed}=require('../transport');
test('native HTTP, cookies, WebSocket, chunks and streaming downloads work without browser CORS',async()=>{
 let origin;const payload=Buffer.alloc(524288,37),id='12345678-1234-1234-1234-123456789abc';let chunkReceived=false;
 const server=http.createServer(async(req,res)=>{
  assert.equal(req.headers.origin,origin);if(req.url==='/login'){res.setHeader('Set-Cookie','meshSession=test_cookie; HttpOnly; SameSite=Strict; Path=/');res.end('{}');return;}
  assert.equal(req.headers.cookie,'meshSession=test_cookie');
  if(req.url.startsWith('/download/')){res.end(payload);return;}
  if(req.method==='PUT'){const data=[];for await(const chunk of req)data.push(chunk);assert.deepEqual(Buffer.concat(data),payload);chunkReceived=true;}
  res.end('{}');
 });
 const wss=new WebSocketServer({server});wss.on('connection',(socket,req)=>{assert.equal(req.headers.origin,origin);assert.equal(req.headers.cookie,'meshSession=test_cookie');socket.on('message',data=>socket.send(data.toString()));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://localhost:'+server.address().port;const client=new GatewayTransport(origin,'127.0.0.1');const dir=await fs.mkdtemp(path.join(os.tmpdir(),'srmesh-test-'));
 try{
  assert.equal((await client.request('login','/login','POST',JSON.stringify({identity:'Test#123456',secret:'test'}))).status,200);
  await client.request('upload','/upload/'+id+'/0','PUT',payload);assert.equal(chunkReceived,true);
  const message=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Socket timeout')),3000);client.socket('socket',event=>{if(event.type==='open')client.send('socket',JSON.stringify({type:'chat',text:'नमस्ते 🌐'}));if(event.type==='message'){clearTimeout(timer);resolve(JSON.parse(event.data));}});});assert.equal(message.text,'नमस्ते 🌐');
  const destination=path.join(dir,'received.bin');assert.equal(await client.download(id,destination),payload.length);assert.deepEqual(await fs.readFile(destination),payload);
 }finally{client.close();wss.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await fs.rm(path.join(dir,'received.bin'),{force:true});await fs.rmdir(dir);}
});
test('transport rejects arbitrary paths and over-limit buffers',()=>{
 for(const path of ['https://example.com/','/../../file','/upload/../../secret','/login?secret=x'])assert.equal(allowed(path,'POST'),false);
 const client=new GatewayTransport('http://192.168.49.1:8080','192.168.49.1');assert.throws(()=>client.request('a','/login','POST',Buffer.alloc(4097)),/limit/);assert.throws(()=>client.socket('s',()=>{}),/Join/);
});

