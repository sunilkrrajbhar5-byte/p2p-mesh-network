const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('meshNative',Object.freeze({
 discover:()=>ipcRenderer.invoke('mesh-native:discover'),select:url=>ipcRenderer.invoke('mesh-native:select',url),
 request:request=>ipcRenderer.invoke('mesh-native:request',request),cancel:id=>ipcRenderer.send('mesh-native:cancel',id),
 socket:id=>ipcRenderer.invoke('mesh-native:socket',id),send:(id,data)=>ipcRenderer.invoke('mesh-native:send',{id,data}),close:id=>ipcRenderer.send('mesh-native:close',id),
 onSocket:callback=>{const listener=(_event,value)=>callback(value);ipcRenderer.on('mesh-native:socket-event',listener);return()=>ipcRenderer.removeListener('mesh-native:socket-event',listener);},
 download:(id,name)=>ipcRenderer.invoke('mesh-native:download',{id,name})
}));
