const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('meshDesktop',Object.freeze({
 discover:()=>ipcRenderer.invoke('mesh:discover'),
 onFound:callback=>{const listener=(_event,node)=>callback(node);ipcRenderer.on('mesh:found',listener);return()=>ipcRenderer.removeListener('mesh:found',listener);},
 openGateway:url=>ipcRenderer.invoke('mesh:open',url),website:()=>ipcRenderer.invoke('mesh:website')
}));
