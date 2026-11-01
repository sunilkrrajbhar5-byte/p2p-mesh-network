import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const page=await readFile(new URL('../docs/index.html',import.meta.url),'utf8');
const script=[...page.matchAll(/<script>([\s\S]*?)<\/script>/g)][2][1];
const context={};vm.runInNewContext(script.split('// Portal interactions')[0],context);
const reply=items=>({ok:true,json:async()=>items});
test('landing totals all release assets, including checksum downloads',async()=>{
 assert.equal(await context.fetchReleaseDownloads(async()=>reply([{id:1,assets:[{download_count:7},{download_count:2}]},{id:2,assets:[{download_count:4}]}])),13);
});
test('landing follows release and asset pagination without partial totals',async()=>{
 const calls=[];
 const total=await context.fetchReleaseDownloads(async url=>{
  calls.push(url);
  if(url.includes('/releases/1/assets'))return reply(url.endsWith('page=1')?Array.from({length:100},()=>({download_count:1})):[{download_count:5}]);
  if(url.endsWith('page=1'))return reply(Array.from({length:100},(_,i)=>({id:i+1,assets:i===0?Array.from({length:100},()=>({download_count:1})):[]})));
  return reply([{id:101,assets:[{download_count:3}]}]);
 });
 assert.equal(total,108);assert.equal(calls.length,4);
});
test('landing API errors and malformed counts reject instead of becoming zero',async()=>{
 await assert.rejects(context.fetchReleaseDownloads(async()=>({ok:false})),/unavailable/);
 await assert.rejects(context.fetchReleaseDownloads(async()=>reply([{id:1,assets:[{download_count:-1}]}])),/Invalid count/);
});
test('landing zero count remains a legitimate live value',async()=>{
 assert.equal(await context.fetchReleaseDownloads(async()=>reply([])),0);
});
