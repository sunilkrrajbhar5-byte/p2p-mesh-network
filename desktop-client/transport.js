'use strict';
const http=require('node:http'),https=require('node:https');
const {Transform}=require('node:stream');
const {pipeline}=require('node:stream/promises');
const fs=require('node:fs');
const {WebSocket}=require('ws');
const UUID='[0-9a-fA-F-]{36}';
function allowed(path,method){
 return (method==='POST'&&['/login','/logout','/upload'].includes(path)) || (method==='GET'&&path==='/files') ||
 (method==='PUT'&&new RegExp('^/upload/'+UUID+'/[0-9]{1,8}$').test(path)) ||
 (method==='POST'&&new RegExp('^/upload/'+UUID+'/commit$').test(path)) ||
 (method==='DELETE'&&new RegExp('^/(upload|file)/'+UUID+'$').test(path));
}
class GatewayTransport {
 constructor(origin,address){this.origin=new URL(origin).origin;this.address=address;this.cookie='';this.pending=new Map();this.sockets=new Map();}
 options(path,method){const u=new URL(path,this.origin);return {protocol:u.protocol,hostname:u.hostname,port:u.port,path:u.pathname,method,headers:{Origin:this.origin,Cookie:this.cookie},lookup:(_hostname,_options,callback)=>_options?.all?callback(null,[{address:this.address,family:4}]):callback(null,this.address,4)};}
 request(id,path,method='GET',value){
  if(!allowed(path,method)||typeof id!=='string'||this.pending.has(id)||this.pending.size>=8)throw Error('Invalid or busy gateway request');
  const body=value==null?Buffer.alloc(0):typeof value==='string'?Buffer.from(value):Buffer.from(value);
  if(body.length>(method==='PUT'?524288:4096))throw Error('Request exceeds protocol limit');
  const options=this.options(path,method);options.headers['Content-Type']=method==='PUT'?'application/octet-stream':'application/json';options.headers['Content-Length']=body.length;
  return new Promise((resolve,reject)=>{
   const req=(options.protocol==='https:'?https:http).request(options,res=>{
    const chunks=[];let size=0;
    res.on('data',chunk=>{size+=chunk.length;if(size>1048576){req.destroy(Error('Response exceeds limit'));return;}chunks.push(chunk);});
    res.on('error',reject);res.on('end',()=>{if(path==='/login'&&res.statusCode===200){const cookie=(res.headers['set-cookie']||[]).find(x=>/^meshSession=[A-Za-z0-9_-]+;/.test(x));if(!cookie){reject(Error('Gateway session cookie missing'));return;}this.cookie=cookie.split(';')[0];}if(path==='/logout')this.cookie='';resolve({status:res.statusCode,body:Buffer.concat(chunks).toString('utf8')});});
   });
   const timer=setTimeout(()=>req.destroy(Error('Gateway request timed out')),30000);this.pending.set(id,req);req.on('error',reject);req.on('close',()=>{clearTimeout(timer);this.pending.delete(id);});req.end(body);
  });
 }
 cancel(id){this.pending.get(id)?.destroy(Error('Request cancelled'));}
 socket(id,emit){
  if(!this.cookie)throw Error('Join the gateway first');if(this.sockets.size>=1)throw Error('Close the existing session first');
  const sock=new WebSocket(this.origin.replace(/^http/,'ws')+'/ws',{headers:{Origin:this.origin,Cookie:this.cookie},lookup:(_h,_o,cb)=>_o?.all?cb(null,[{address:this.address,family:4}]):cb(null,this.address,4),maxPayload:65536,handshakeTimeout:10000});this.sockets.set(id,sock);
  sock.on('open',()=>emit({id,type:'open'}));sock.on('message',(data,binary)=>{if(!binary)emit({id,type:'message',data:data.toString()});});sock.on('error',()=>emit({id,type:'error'}));sock.on('close',()=>{this.sockets.delete(id);emit({id,type:'close'});});
 }
 send(id,data){const socket=this.sockets.get(id);if(typeof data!=='string'||Buffer.byteLength(data)>16384)throw Error('Invalid message');const parsed=JSON.parse(data);if(!['chat','ping'].includes(parsed.type))throw Error('Unsupported message type');if(socket?.readyState!==WebSocket.OPEN||socket.bufferedAmount>65536)throw Error('Connection busy or closed');socket.send(data);}
 closeSocket(id){const s=this.sockets.get(id);this.sockets.delete(id);s?.terminate();}
 async download(id,destination){
  if(!new RegExp('^'+UUID+'$').test(id)||!this.cookie)throw Error('Invalid download');
  const options=this.options('/download/'+id,'GET');let timer;
  const res=await new Promise((resolve,reject)=>{const req=(options.protocol==='https:'?https:http).get(options,response=>{clearTimeout(timer);if(response.statusCode!==200){response.destroy();reject(Error('Download rejected'));return;}resolve(response);});timer=setTimeout(()=>req.destroy(Error('Download timed out')),15000);req.on('error',error=>{clearTimeout(timer);reject(error);});});
  let size=0;const limit=new Transform({transform(chunk,_enc,callback){size+=chunk.length;callback(size>2147483648?Error('Download exceeds 2 GiB'):null,chunk);}});
  res.setTimeout(30000,()=>res.destroy(Error('Download stalled')));
  try{await pipeline(res,limit,fs.createWriteStream(destination,{flags:'wx'}));return size;}catch(error){throw error;}
 }
 close(){for(const req of this.pending.values())req.destroy(Error('Session closed'));for(const id of this.sockets.keys())this.closeSocket(id);this.cookie='';}
}
module.exports={GatewayTransport,allowed};
