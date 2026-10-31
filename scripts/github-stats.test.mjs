import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readDownloads} from '../docs/stats.mjs';
const base='https://api.github.com/repos/sunilkrrajbhar5-byte/p2p-mesh-network';
test('counts only APK assets across paginated releases and assets',async()=>{
  const data=new Map([
    [base+'/releases?per_page=100',[[{id:1,tag_name:'v1.0.0'}],base+'/releases?per_page=100&page=2']],
    [base+'/releases?per_page=100&page=2',[[{id:2,tag_name:'v2.0.0'},{id:3,draft:true}],null]],
    [base+'/releases/1/assets?per_page=100',[[{name:'app-debug.apk',download_count:4},{name:'desktop.zip',download_count:50}],base+'/releases/1/assets?page=2']],
    [base+'/releases/1/assets?page=2',[[{name:'app-debug.apk.sha256',download_count:70}],null]],
    [base+'/releases/2/assets?per_page=100',[[{name:'SR-Mesh.apk',download_count:9}],null]]
  ]);
  const result=await readDownloads(async url=>{assert.ok(data.has(url));const[body,next]=data.get(url);return new Response(JSON.stringify(body),{headers:next?{link:`<${next}>; rel="next"`}:{}});});
  assert.equal(result.total,13);assert.equal(result.rows.length,2);
});
test('API failure is an error, not a false zero',async()=>{
  await assert.rejects(()=>readDownloads(async()=>new Response('',{status:403})),/rate limit/);
  await assert.rejects(()=>readDownloads(async()=>new Response('',{status:404})),/not available/);
});
test('pagination cannot send the fetcher to another origin',async()=>{
  await assert.rejects(()=>readDownloads(async()=>new Response('[]',{headers:{link:'<https://evil.example/>; rel="next"'}})),/pagination/);
});
