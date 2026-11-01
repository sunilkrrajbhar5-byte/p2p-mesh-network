const $=id=>document.getElementById(id);
let scanning=false,connecting=false,retryTimer,guideTimer;const nodes=new Map();
async function open(url){if(connecting)return;connecting=true;try{await window.meshDesktop.openGateway(url);$('status').textContent='Gateway opened. Complete host access-key authentication in the session window.';}catch(error){$('status').textContent=error.message;}finally{connecting=false;}}
function found(node){
 nodes.set(node.id,node);$('guidance').hidden=true;$('results').replaceChildren();$('blips').replaceChildren();
 for(const [index,peer] of [...nodes.values()].entries()){
  const card=document.createElement('article');card.className='node';const info=document.createElement('div'),name=document.createElement('strong'),detail=document.createElement('p'),button=document.createElement('button');
  name.textContent='🟢 '+peer.label;detail.textContent=peer.id+' • '+(peer.secure?'HTTPS verified':'HTTP • unencrypted')+' • Signal unavailable';button.textContent='Connect →';button.addEventListener('click',()=>open(peer.url));info.append(name,detail);card.append(info,button);$('results').append(card);
  const dot=document.createElement('span');dot.className='blip';dot.style.left=(25+(index*37)%55)+'%';dot.style.top=(20+(index*23)%60)+'%';dot.title=peer.label+' · '+peer.id;$('blips').append(dot);
 }
 $('status').textContent=nodes.size+' gateway(s) found — choose Connect.';
}
async function discover(){
 if(scanning)return;scanning=true;clearTimeout(retryTimer);clearTimeout(guideTimer);nodes.clear();$('results').replaceChildren();$('blips').replaceChildren();$('guidance').hidden=true;$('discover').disabled=true;document.body.classList.remove('idle');$('status').textContent='🔍 Scanning local airspace for active SR-Mesh nodes...';
 guideTimer=setTimeout(()=>{if(!nodes.size)$('guidance').hidden=false;},5000);
 try{for(const node of await window.meshDesktop.discover())found(node);}catch(error){$('status').textContent='Discovery unavailable: '+error.message;}
 finally{scanning=false;$('discover').disabled=false;document.body.classList.add('idle');if(!nodes.size)$('status').textContent='No compatible gateway detected yet. Automatic discovery will retry.';retryTimer=setTimeout(()=>{if(!document.hidden&&!connecting)discover();},15000);}
}
const unsubscribe=window.meshDesktop.onFound(found);
$('discover').addEventListener('click',discover);$('connect').addEventListener('click',()=>open($('url').value));$('url').addEventListener('keydown',event=>{if(event.key==='Enter')open($('url').value);});$('website').addEventListener('click',()=>window.meshDesktop.website());
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!scanning)discover();});window.addEventListener('beforeunload',()=>{clearTimeout(retryTimer);clearTimeout(guideTimer);unsubscribe();});discover();
