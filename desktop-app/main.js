const {app,BrowserWindow,ipcMain,dialog,shell}=require('electron');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {gatewayUrl,probe,discover}=require('./network');
let launcher,discovering=null;const gateways=new Set();
const launcherPath=path.join(__dirname,'launcher.html');
const launcherUrl=pathToFileURL(launcherPath).href;
function trusted(event){if(!launcher || event.sender!==launcher.webContents || event.senderFrame!==launcher.webContents.mainFrame || event.senderFrame.url!==launcherUrl)throw Error('Untrusted caller');}
function createLauncher(){
 launcher=new BrowserWindow({width:1200,height:800,minWidth:780,minHeight:560,backgroundColor:'#07090e',title:'SR Mesh Desktop',titleBarStyle:'hidden',titleBarOverlay:{color:'#07090e',symbolColor:'#00f2fe',height:36},icon:path.join(__dirname,'icon.png'),webPreferences:{preload:path.join(__dirname,'preload.js'),contextIsolation:true,nodeIntegration:false,sandbox:true}});
 launcher.setMenuBarVisibility(false);
 launcher.webContents.setWindowOpenHandler(()=>({action:'deny'}));
 launcher.webContents.on('will-navigate',(event,url)=>{if(url!==launcherUrl)event.preventDefault();});
 launcher.webContents.session.setPermissionRequestHandler((_wc,_permission,callback)=>callback(false));
 launcher.loadFile(launcherPath);
}
ipcMain.handle('mesh:discover',async event=>{
 trusted(event);if(discovering)return discovering;
 const controller=new AbortController();
 const cancel=()=>controller.abort();event.sender.once('destroyed',cancel);
 discovering=discover({signal:controller.signal,onFound:node=>{if(!event.sender.isDestroyed())event.sender.send('mesh:found',node);}});
 try{return await discovering;}finally{event.sender.removeListener('destroyed',cancel);discovering=null;}
});
ipcMain.handle('mesh:open',async(event,value)=>{
 trusted(event);const url=await gatewayUrl(value);
 if(!await probe(url))throw Error('No compatible gateway responded. Check Wi-Fi, address, gateway server and certificate trust.');
 if(gateways.size>=4)throw Error('Close a gateway window before opening another.');
 const win=new BrowserWindow({width:1200,height:800,backgroundColor:'#07090e',title:'SR Mesh Local Gateway',autoHideMenuBar:true,webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true,partition:'persist:mesh-gateways'}});
 const origin=new URL(url).origin;gateways.add(win);win.on('closed',()=>gateways.delete(win));
 win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
 win.webContents.on('will-navigate',(event,target)=>{if(new URL(target).origin!==origin)event.preventDefault();});
 win.webContents.on('will-redirect',(event,target)=>{if(new URL(target).origin!==origin)event.preventDefault();});
 // Remote gateway content has no preload or Node access. Never override TLS trust.
 win.webContents.session.setPermissionCheckHandler((wc,permission,requestingOrigin)=>permission==='media' && gateways.has(BrowserWindow.fromWebContents(wc)) && requestingOrigin===new URL(wc.getURL()).origin && requestingOrigin.startsWith('https:'));
 win.webContents.session.setPermissionRequestHandler(async(wc,permission,callback,details)=>{
  if(permission!=='media' || !details.isMainFrame || !(details.requestingUrl||'').startsWith('https:') || details.mediaTypes?.some(type=>type!=='audio')){callback(false);return;}
  const owner=BrowserWindow.fromWebContents(wc);if(!gateways.has(owner) || new URL(details.requestingUrl).origin!==new URL(wc.getURL()).origin){callback(false);return;}
  const result=await dialog.showMessageBox(owner,{type:'question',buttons:['Deny','Allow microphone'],defaultId:0,cancelId:0,message:'Allow this local gateway to use your microphone?',detail:new URL(wc.getURL()).origin});callback(result.response===1);
 });
 try{await win.loadURL(url);}catch(error){win.close();throw Error('Gateway could not load. Use a certificate trusted by Windows for HTTPS.');}
 return true;
});
ipcMain.handle('mesh:website',event=>{trusted(event);return shell.openExternal('https://sunilkrrajbhar5-byte.github.io/p2p-mesh-network/desktop.html?force=desktop');});
if(!app.requestSingleInstanceLock())app.quit();else{
 app.on('second-instance',()=>{if(launcher){if(launcher.isMinimized())launcher.restore();launcher.focus();}});
 app.whenReady().then(createLauncher);
 app.on('window-all-closed',()=>app.quit());
}
