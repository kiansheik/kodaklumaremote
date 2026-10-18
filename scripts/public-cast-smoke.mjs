import {spawn} from 'node:child_process';
import {chromium} from 'playwright';
const port = '4174';
const server = spawn(process.execPath, ['scripts/demo-server.mjs'], {env:{...process.env,PORT:port},stdio:['ignore','pipe','pipe']});
let browser;
let page;
let stage='server';
const network=[];
const consoles={};
try {
  await new Promise((resolve,reject) => {
    const timeout=setTimeout(()=>reject(new Error('Fixture server did not start.')),10000);
    server.stdout.once('data',()=>{clearTimeout(timeout);resolve();});
    server.once('error',()=>{clearTimeout(timeout);reject(new Error('Fixture server failed.'));});
    server.once('exit',()=>{clearTimeout(timeout);reject(new Error('Fixture server exited.'));});
  });
  stage='browser';
  // Pinned Playwright 1.64.0 defaults disable MediaRouter. Exclude that exact
  // switch while retaining Playwright's managed, isolated temporary profile.
  const disabledFeatures=['AvoidUnnecessaryBeforeUnloadCheckSync','DestroyProfileOnBrowserClose',
    'DialMediaRouteProvider','GlobalMediaControls','HttpsUpgrades','LensOverlay','MediaRouter',
    'PaintHolding','ThirdPartyStoragePartitioning','BlockOriginHeaderModificationOnRedirect',
    'Translate','AutoDeElevate','OptimizationHints','NetworkTimeServiceQuerying','AimEnabled',
    'msForceBrowserSignIn','msEdgeUpdateLaunchServicesPreferredVersion'];
  browser=await chromium.launch({channel:'chrome',headless:false,ignoreDefaultArgs:[
    `--disable-features=${disabledFeatures.join(',')}`,'--disable-extensions',
    '--disable-component-extensions-with-background-pages']});
  page=await browser.newPage({ignoreHTTPSErrors:true});
  page.on('response',response=>{if(response.request().resourceType()==='script')network.push({origin:new URL(response.url()).origin,status:response.status()});});
  page.on('requestfailed',request=>network.push({origin:new URL(request.url()).origin,failed:true}));
  page.on('console',message=>{consoles[message.type()]=(consoles[message.type()]||0)+1;});
  let mediaRequests=0;
  page.on('request',request=>{if(request.resourceType()==='media')mediaRequests++;});
  stage='navigation';
  await page.goto(`https://localhost:${port}/cast-demo`,{waitUntil:'domcontentloaded',timeout:20000});
  stage='sdk';
  await page.waitForFunction(()=>!document.querySelector('#cast').disabled,{timeout:20000});
  console.log(JSON.stringify({timestamp:new Date().toISOString(),browser:browser.version(),
    sdkReady:true,secureContext:await page.evaluate(()=>window.isSecureContext),mediaRequests,
    deviceSelectionAttempted:false,receiverPlaybackTested:false},null,2));
} catch (error) {
  const diagnostics=await page?.evaluate(()=>({secureContext:window.isSecureContext,framework:!!window.cast?.framework,
    baseSDK:!!window.chrome?.cast,available:!!window.chrome?.cast?.isAvailable,button:document.querySelector('#cast')?.textContent})).catch(()=>null);
  console.log(JSON.stringify({sdkReady:false,receiverPlaybackTested:false,stage,diagnostics,network,consoles,
    error:'Public SDK smoke check did not become ready.',failureType:error.name,
    launchFailure:stage==='browser'?error.message.split('\n')[0]:undefined},null,2));
  process.exitCode=1;
} finally {
  server.kill('SIGTERM');
  await browser?.close();
}
