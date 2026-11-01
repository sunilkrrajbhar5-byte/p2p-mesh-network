'use strict';
const base=require('./package.json').build;
const cloud=['AZURE_SIGN_ENDPOINT','AZURE_SIGN_ACCOUNT','AZURE_SIGN_PROFILE','AZURE_SIGN_PUBLISHER'];
const azure=cloud.some(name=>process.env[name]);
const config={...base,forceCodeSigning:true,win:{...base.win,signAndEditExecutable:true,signExecutable:true}};
if(azure){
 if(cloud.some(name=>!process.env[name]))throw Error('Set all AZURE_SIGN_ENDPOINT/ACCOUNT/PROFILE/PUBLISHER values for Artifact Signing.');
 config.win.azureSignOptions={endpoint:process.env.AZURE_SIGN_ENDPOINT,codeSigningAccountName:process.env.AZURE_SIGN_ACCOUNT,certificateProfileName:process.env.AZURE_SIGN_PROFILE,publisherName:process.env.AZURE_SIGN_PUBLISHER};
 delete config.win.signtoolOptions;
}else{
 if(!(process.env.WIN_CSC_LINK||process.env.CSC_LINK)||!(process.env.WIN_CSC_KEY_PASSWORD||process.env.CSC_KEY_PASSWORD))throw Error('Signing credentials missing. Set CSC_LINK and CSC_KEY_PASSWORD, or use npm run build:dev for a local development certificate. No unsigned release will be produced.');
 config.win.signtoolOptions={signingHashAlgorithms:['sha256']};
}
module.exports=config;
