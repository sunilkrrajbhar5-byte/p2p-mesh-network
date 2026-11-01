const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {NtExecutable,NtExecutableResource,Data,Resource}=require('resedit');
const ico=Data.IconFile.from(fs.readFileSync(path.join(__dirname,'../assets/icon.ico')));
const digest=data=>crypto.createHash('sha256').update(Buffer.from(data.isRaw()?data.bin:data.generate())).digest('hex');
const expected=ico.icons.map(item=>digest(item.data)).sort();
assert.deepEqual(ico.icons.map(item=>item.width||item.data.width||256).sort((a,b)=>a-b),[16,32,48,64,128,256]);
for(const file of process.argv.slice(2)){
 const entries=NtExecutableResource.from(NtExecutable.from(fs.readFileSync(file),{ignoreCert:true})).entries;
 const groups=Resource.IconGroupEntry.fromEntries(entries);
 assert.ok(groups.some(group=>JSON.stringify(group.getIconItemsFromEntries(entries).map(digest).sort())===JSON.stringify(expected)),`${file}: custom multi-resolution icon missing`);
 console.log('Verified embedded SR Mesh icon:',file);
}
console.log('ICO contains all six requested resolutions.');
