'use strict';
const {app,BrowserWindow,ipcMain,dialog}=require('electron');
const path=require('node:path'),dns=require('node:dns').promises,fs=require('node:fs/promises');
const {pathToFileURL}=require('node:url');
const {randomUUID}=require('node:crypto');
const {gatewayUrl,probe,discover,privateAddress}=require('./discovery');
const {GatewayTransport}=require('./transport');
let win,transport,scanPromise;
const iconPath=path.join(__dirname,'assets','icon.ico');
// Preserve the existing profile directory while updating the visible product name.
const profilePath=path.join(app.getPath('appData'),'SR Mesh Workspace');
require('node:fs').mkdirSync(profilePath,{recursive:true});
app.setPath('userData',profilePath);
if(process.platform==='win32')app.setAppUserModelId('com.srsystems.srmesh.workspace');
const dashboard=app.isPackaged?path.join(process.resourcesPath,'gateway.html'):path.join(__dirname,'../docs/gateway.html');
function trusted(event){if(!win||event.sender!==win.webContents||event.senderFrame!==win.webContents.mainFrame||event.senderFrame.url!==pathToFileURL(dashboard).href)throw Error('Untrusted request');}
function session(event){trusted(event);if(!transport)throw Error('Select a gateway');return transport;}
ipcMain.handle('mesh-native:discover',async event=>{trusted(event);if(!scanPromise)scanPromise=discover().finally(()=>{scanPromise=null;});return scanPromise;});
ipcMain.handle('mesh-native:select',async(event,value)=>{trusted(event);const origin=await gatewayUrl(value);if(!await probe(origin))throw Error('Compatible gateway not found. Check Wi-Fi and certificate trust.');const records=await dns.lookup(new URL(origin).hostname,{all:true,family:4});if(!records.length||records.some(x=>!privateAddress(x.address)))throw Error('Only private LAN gateways are allowed');transport?.close();transport=new GatewayTransport(origin,records[0].address);return origin;});
ipcMain.handle('mesh-native:request',(event,{id,path,method,body})=>session(event).request(id,path,method,body));
ipcMain.on('mesh-native:cancel',(event,id)=>{session(event).cancel(id);});
ipcMain.handle('mesh-native:socket',(event,id)=>{session(event).socket(id,data=>{if(!win.isDestroyed())win.webContents.send('mesh-native:socket-event',data);});});
ipcMain.handle('mesh-native:send',(event,{id,data})=>session(event).send(id,data));
ipcMain.on('mesh-native:close',(event,id)=>{session(event).closeSocket(id);});
ipcMain.handle('mesh-native:download',async(event,{id,name})=>{
 const client=session(event);const safeName=path.basename(String(name)).replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').slice(0,180)||'mesh-download';
 const result=await dialog.showSaveDialog(win,{defaultPath:safeName,title:'Save received SR Mesh file'});if(result.canceled)return {cancelled:true};
 const temporary=result.filePath+'.srmesh-'+randomUUID()+'.part';try{const bytes=await client.download(id,temporary);await fs.rename(temporary,result.filePath);return {bytes};}finally{await fs.rm(temporary,{force:true}).catch(()=>{});}
});
app.whenReady().then(()=>{win=new BrowserWindow({width:1280,height:800,minWidth:780,minHeight:560,autoHideMenuBar:true,backgroundColor:'#07090E',title:'SR MESH Desktop',icon:iconPath,webPreferences:{preload:path.join(__dirname,'preload.js'),nodeIntegration:false,contextIsolation:true,sandbox:true,webSecurity:true}});win.on('page-title-updated',event=>event.preventDefault());if(process.platform==='win32')win.setIcon(iconPath);win.webContents.setWindowOpenHandler(()=>({action:'deny'}));win.webContents.on('will-navigate',event=>event.preventDefault());win.webContents.session.setPermissionRequestHandler((_wc,_permission,callback)=>callback(false));if(process.env.SR_MESH_SMOKE_TEST==='1'){win.webContents.once('did-finish-load',async()=>{try{const okay=await win.webContents.executeJavaScript("Boolean(window.meshNative && document.getElementById('chat-tab') && document.getElementById('files-tab'))");app.exit(okay?0:1);}catch{app.exit(1);}});win.webContents.once('render-process-gone',()=>app.exit(1));}win.loadFile(dashboard);win.on('closed',()=>transport?.close());});
app.on('window-all-closed',()=>app.quit());
