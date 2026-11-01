const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('meshDesktop',Object.freeze({discover:()=>ipcRenderer.invoke('mesh:discover'),openGateway:url=>ipcRenderer.invoke('mesh:open',url),website:()=>ipcRenderer.invoke('mesh:website')}));
