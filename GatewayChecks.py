"""HTTPS/WebSocket/raw TCP interoperability. Run with test certificate paths; see gateway guide."""
import asyncio
import base64
import hashlib
import http.client
import json
import os
import re
from pathlib import Path
import socket
import shutil
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import uuid
import zlib
import websockets

ORIGIN = 'https://127.0.0.1:18080'
GROUP = uuid.UUID('11111111-2222-4333-8444-555555555555')
TLS = ssl.create_default_context(cafile='.tools/gateway/test-cert.pem')
QUEUES = {}

async def pump(ws):
    try:
        async for value in ws:
            await QUEUES[ws].put(value)
    except websockets.exceptions.ConnectionClosed as error:
        await QUEUES[ws].put(error)

def request(method, path, data=None, cookie=None, origin=ORIGIN):
    connection = http.client.HTTPSConnection('127.0.0.1', 18080, context=TLS, timeout=40)
    headers = {'Origin': origin}
    if cookie: headers['Cookie'] = cookie
    if isinstance(data, dict): data = json.dumps(data).encode(); headers['Content-Type'] = 'application/json'
    try:
        connection.request(method, path, data, headers)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally: connection.close()

def packet(kind, sender, target, payload=b'', ttl=24):
    sender, target = sender.encode(), target.encode()
    body = uuid.uuid4().bytes + GROUP.bytes + bytes([ttl, 1]) + struct.pack('>q', int(time.time()*1000))
    body += bytes([len(sender)]) + sender + bytes([len(target)]) + target + payload
    data = bytes([2, kind]) + struct.pack('>H', len(body)) + body
    return data + struct.pack('>I', zlib.crc32(data)&0xffffffff)

def read_packet(sock):
    def exact(size):
        data = b''
        while len(data) < size:
            block = sock.recv(size-len(data))
            if not block: raise EOFError()
            data += block
        return data
    head = exact(4); data = head + exact(struct.unpack('>H',head[2:])[0]+4)
    assert zlib.crc32(data[:-4])&0xffffffff == struct.unpack('>I',data[-4:])[0]
    at = 46; sender_length = data[at]; at += sender_length+1
    target_length = data[at]; at += target_length+1
    return data[1], data[36], data[37], data[at:-4]

async def receive_type(ws, kind):
    while True:
        value = await asyncio.wait_for(QUEUES[ws].get() if ws in QUEUES else ws.recv(), 120)
        if isinstance(value, Exception): raise value
        if isinstance(value, str):
            data = json.loads(value)
            if data.get('type') == kind: return data

async def check():
    status,headers,body=request('GET','/')
    assert status==200
    for script in re.findall(r'<script>([\s\S]*?)</script>',body.decode('utf-8')):
        digest=base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
        assert "'sha256-"+digest+"'" in headers['Content-Security-Policy']
    assert ORIGIN.replace('https','wss') in headers['Content-Security-Policy']
    for path,size in [('/icon.png',512),('/icon192.png',192)]:
        status,_,icon=request('GET',path)
        assert status==200 and struct.unpack('>II',icon[16:24])==(size,size)
    assert request('GET','/files')[0] == 401
    assert request('POST','/login',{'identity':'Alice#123456','secret':'bad'})[0] == 400
    assert request('POST','/login',{},origin='https://evil.example')[0] == 400
    assert request('GET','/sw.js')[0] == 200
    cookies=[]
    for identity in ['Alice#123456','Bob#654321']:
        status, headers, _ = request('POST','/login',{'identity':identity,'secret':'test-only-join-secret-01234567890123456789'})
        assert status == 200
        cookies.append(headers['Set-Cookie'].split(';')[0])
        assert request('POST','/login',{'identity':identity,'secret':''},cookies[-1])[0]==200
    async with websockets.connect(ORIGIN.replace('https','wss')+'/ws',ssl=TLS,origin=ORIGIN,additional_headers={'Cookie':cookies[0]}) as alice, \
               websockets.connect(ORIGIN.replace('https','wss')+'/ws',ssl=TLS,origin=ORIGIN,additional_headers={'Cookie':cookies[1]}) as bob:
        await receive_type(alice,'ready'); await receive_type(bob,'ready')
        QUEUES[alice] = asyncio.Queue(maxsize=4096)
        QUEUES[bob] = asyncio.Queue(maxsize=4096)
        pumps = [asyncio.create_task(pump(alice)), asyncio.create_task(pump(bob))]
        raw=socket.create_connection(('127.0.0.1',18888),timeout=10)
        try:
            raw.sendall(packet(0,'Native#111111','Host#123456',ttl=0))
            assert read_packet(raw)[0] == 0
            await alice.send(json.dumps({'type':'chat','target':'Native#111111','cluster':str(GROUP),'text':'browser to native'}))
            while True:
                kind,ttl,hops,payload=await asyncio.to_thread(read_packet,raw)
                if kind==1: break
            assert (ttl,hops,payload)==(23,2,b'browser to native')
            raw.sendall(packet(1,'Native#111111','Alice#123456',b'native to browser'))
            received=await receive_type(alice,'chat')
            assert received['text']=='native to browser' and received['sender']=='Native#111111'
        finally: raw.close()
        total=1073741824+12345 if '--large' in sys.argv else 1048576+12345
        status,_,data=request('POST','/upload',{'name':'test.bin','size':total,'target':'Bob#654321','cluster':str(GROUP)},cookies[0])
        assert status==200; upload=json.loads(data)['id']
        assert request('PUT','/upload/'+upload+'/1',b'invalid',cookies[0])[0]==400
        digest=hashlib.sha256()
        for index,offset in enumerate(range(0,total,524288)):
            chunk=bytes([index%251])*min(524288,total-offset); digest.update(chunk)
            assert (await asyncio.to_thread(request,'PUT','/upload/'+upload+'/'+str(index),chunk,cookies[0]))[0]==200
            if index%20==0: await alice.send(json.dumps({'type':'ping'})); await bob.send(json.dumps({'type':'ping'}))
        assert request('POST','/upload/'+upload+'/commit',b'',cookies[0])[0]==200
        while True:
            state=await receive_type(alice,'transfer')
            assert state['state']!='failed'
            if state['state']=='delivered': break
        while True:
            files=await receive_type(bob,'files')
            if files['files']: break
        file=files['files'][0]
        assert file['size']==total
        assert request('GET','/file/'+file['id'],cookie=cookies[0])[0]==400
        actual=hashlib.sha256()
        for index,offset in enumerate(range(0,total,524288)):
            status,_,chunk=await asyncio.to_thread(request,'GET','/chunk/'+file['id']+'/'+str(index),None,cookies[1])
            assert status==200 and len(chunk)==min(524288,total-offset); actual.update(chunk)
            if index%20==0: await alice.send(json.dumps({'type':'ping'})); await bob.send(json.dumps({'type':'ping'}))
        assert actual.digest()==digest.digest()
        assert request('DELETE','/file/'+file['id'],b'',cookies[1])[0]==200
        assert json.loads(request('GET','/files',cookie=cookies[1])[2])['files']==[]
        print(f'Gateway checks passed: trusted TLS, authorization, WebSocket/TCP TTL/CRC, {total} bytes HTTP upload/mesh/download, SHA-256 and verified ACK')
        for task in pumps: task.cancel()

if __name__=='__main__':
    java=os.environ.get('MESH_TEST_JAVA') or shutil.which('java')
    if not java: raise SystemExit('Set MESH_TEST_JAVA to your JDK java executable')
    cp=os.pathsep.join(['build/mesh-universal-final.jar','.tools/kotlin/kotlinc/lib/kotlin-stdlib.jar','.tools/coroutines.jar','.tools/gateway/nanohttpd.jar','.tools/gateway/classes','.tools/gateway/json.jar'])
    with tempfile.TemporaryDirectory(prefix='mesh-gateway-check-') as cache:
        process=subprocess.Popen([java,'-Xmx256m','-cp',cp,'com.example.mesh.GatewayTestHostKt','.tools/gateway/test.p12',cache],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            assert process.stdout.readline().strip()=='READY', 'Fixture did not start'
            asyncio.run(check())
        finally:
            process.stdin.write('\n'); process.stdin.flush()
            try: _,error=process.communicate(timeout=30)
            except subprocess.TimeoutExpired: process.kill(); _,error=process.communicate(); print('Fixture shutdown timed out',file=sys.stderr)
            if process.returncode: print(error,file=sys.stderr)
