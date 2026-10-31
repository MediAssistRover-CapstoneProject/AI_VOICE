// Headless browser contract test. All HTTP responses are synthetic; no robot is contacted.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),zlib=require('node:zlib');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../main/resources/static');
const ids=['11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',
 '33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444',
 '55555555-5555-4555-8555-555555555555','66666666-6666-4666-8666-666666666666'];
const [mapId,robotId,sessionId,endpointId,workflowId,requestId]=ids,hash='a'.repeat(64);
const meta={map_id:mapId,asset_hash:hash,frame_id:'map',width_px:100,height_px:100,
 resolution_m_per_pixel:.025,origin_x_m:0,origin_y_m:0,origin_yaw_rad:0,navigation_reload_safe:true};
function png(){
 const crc=b=>{let n=0xffffffff;for(const v of b){n^=v;for(let i=0;i<8;i++)n=(n>>>1)^((n&1)?0xedb88320:0);}return (n^0xffffffff)>>>0;};
 const chunk=(t,b)=>{const data=Buffer.concat([Buffer.from(t),b]),out=Buffer.alloc(b.length+12);out.writeUInt32BE(b.length);data.copy(out,4);out.writeUInt32BE(crc(data),out.length-4);return out;};
 const header=Buffer.alloc(13);header.writeUInt32BE(100);header.writeUInt32BE(100,4);header[8]=8;
 const rows=Buffer.alloc(10100,254);for(let y=0;y<100;y++)rows[y*101]=0;
 return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]);
}
const definition=JSON.parse(fs.readFileSync(root+'/navigation-move-workflow.json'));
function detail(){return {workflow:{...definition.workflow,workflowId,startStepId:'step',status:'PUBLISHED',acceptsNewJobs:true},
 steps:definition.graph.steps.map(s=>({...s,stepId:'step',stepTypeCode:s.stepType})),
 transitions:definition.graph.transitions.map(t=>({...t,fromStepId:'step',toStepId:null}))};}
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const page=await browser.newPage({viewport:{width:1100,height:1000}}),errors=[],calls=[];
  let session=null,endpoints=[],request=null,job=null;
  page.on('pageerror',e=>errors.push(e.message));
  function snapshot(){const now=new Date().toISOString();return {observedAt:now,mqttConnected:true,heartbeatFresh:true,dispatchReady:true,activeJobId:null,
    robot:{robotId,status:'IDLE',currentMapId:mapId,mapAssetHash:hash},navigationSession:session||{state:'NONE'},
    latestPose:{robotId,mapId,frameId:'map',xM:1,yM:1,yawRad:0,fresh:true,capturedAt:now}};}
  await page.route('http://127.0.0.1:18884/**',async route=>{
   const r=route.request(),p=new URL(r.url()).pathname,method=r.method();
   const json=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
   const paged=content=>json({content,totalPages:1,last:true});
   if(p==='/')return route.fulfill({contentType:'text/html',body:'<meta charset="UTF-8"><div id="host"></div><script src="/map-navigation.js"></script>'});
   if(p==='/map-navigation.js')return route.fulfill({contentType:'application/javascript',body:fs.readFileSync(root+p)});
   if(p==='/navigation-move-workflow.json')return json(definition);
   if(p==='/favicon.ico')return route.fulfill({status:204});
   assert.equal(r.headers().authorization,'Bearer test-admin');
   const body=r.postDataJSON();calls.push({p,method,body});
   if(p.endsWith('/image.png'))return route.fulfill({contentType:'image/png',headers:{'X-Map-Asset-Hash':hash},body:png()});
   if(p.endsWith('/image-metadata'))return json(meta);
   if(p==='/api/v1/maps/'+mapId)return json({mapId,assetHash:hash,mapName:'Synthetic map',status:'ACTIVE'});
   if(p.endsWith('/snapshot'))return json(snapshot());
   if(p.endsWith('/navigation-session')&&method==='POST'){session={sessionId,mapId,mapAssetHash:hash,state:'WAITING_FOR_PI',expiresAt:'2099-01-01T00:00:00Z'};return json(session);}
   if(p.endsWith('/navigation-session/initialization')){session.state='LOCALIZING';return json(session);}
   if(p.endsWith('/navigation-session/close')){session.state='CLOSED';return json(session);}
   if(p.endsWith('/validate-navigation-point'))return json({knownFree:true});
   if(p==='/api/v1/endpoints'){if(method==='GET')return paged(endpoints);endpoints=[{...body,endpointId}];return json(endpoints[0]);}
   if(p==='/api/v1/endpoints/'+endpointId)return json(endpoints[0]);
   if(p==='/api/v1/admin/workflows')return paged([detail().workflow]);
   if(p==='/api/v1/admin/workflows/'+workflowId)return json(detail());
   if(p==='/api/v1/navigation-requests'){request={serviceRequestId:requestId,status:'DRAFT'};return json({request});}
   if(p==='/api/v1/service-requests/'+requestId)return json(request);
   if(p.endsWith('/submit')){request.status='QUEUED';return json(request);}
   if(p==='/api/v1/jobs')return paged(job?[job]:[]);
   if(p.endsWith('/cancel')){request.status='CANCELLED';return json(request);}
   throw Error('Unexpected '+method+' '+p);
  });
  await page.goto('http://127.0.0.1:18884/');
  await page.evaluate(({robotId,mapId})=>{
   window.testNav=SavedMapNavigation.mount({element:document.querySelector('#host'),getRobotId:()=>robotId,getToken:()=> 'test-admin',isMappingActive:()=>false,getSavedMapId:()=>mapId});
   document.querySelector('#navMapId').value=mapId;
  },{robotId,mapId});
  await page.click('#navLoad');await page.waitForFunction(()=>!document.querySelector('#navLoad').disabled&&!document.querySelector('#navCanvas').hidden);
  assert.equal(calls.filter(c=>c.method!=='GET').length,0);
  assert(await page.isDisabled('#navMove'));
  await page.click('#navAssign');await page.waitForFunction(()=>document.querySelector('#navSessionStatus').textContent.includes('WAITING_FOR_PI'));
  assert((await page.textContent('#navPiCommand')).includes(sessionId));
  assert((await page.textContent('#navPose')).includes('Chưa có vị trí'));
  await page.check('#navSeedMode');
  const box=await page.locator('#navCanvas').boundingBox();
  await page.mouse.move(box.x+box.width*.4,box.y+box.height*.5);await page.mouse.down();
  await page.mouse.move(box.x+box.width*.4,box.y+box.height*.3);await page.mouse.up();
  assert(Math.abs(Number(await page.inputValue('#navSeedYaw'))-90)<2);
  await page.click('#navSendSeed');await page.waitForFunction(()=>document.querySelector('#navSessionStatus').textContent.includes('LOCALIZING'));
  const seed=calls.find(c=>c.p.endsWith('/initialization')).body;
  assert.equal(seed.sessionId,sessionId);assert.equal(seed.mode,'SEED');assert(Math.abs(seed.yawRad-Math.PI/2)<.05);
  assert(await page.isDisabled('#navMove'));assert((await page.textContent('#navPose')).includes('Chưa có vị trí'));
  session.state='READY';await page.click('#navRefresh');await page.waitForFunction(()=>document.querySelector('#navPose').textContent.includes('X='));
  await page.uncheck('#navSeedMode');await page.click('#navNewPoint');
  await page.fill('#navX','1.5');await page.fill('#navY','1.2');await page.fill('#navYaw','45');
  await page.fill('#navPointCode','POINT_TEST');await page.fill('#navPointName','Synthetic endpoint');
  await page.click('#navSavePoint');await page.waitForFunction(id=>document.querySelector('#navEndpoints').value===id,endpointId);
  assert(calls.some(c=>c.p.endsWith('/validate-navigation-point')));
  await page.click('#navPrepare');await page.waitForFunction(id=>document.querySelector('#navWorkflowId').value===id,workflowId);
  await page.click('#navMove');await page.waitForFunction(()=>document.querySelector('#navReceipt').textContent.includes('QUEUED'));
  const move=calls.find(c=>c.p==='/api/v1/navigation-requests').body;
  assert.equal(move.robotId,robotId);assert.equal(move.navigationSessionId,sessionId);
  assert.equal(move.mapId,mapId);assert.equal(move.destinationEndpointId,endpointId);assert.equal(move.workflowId,workflowId);
  assert(!calls.some(c=>c.method==='PUT'&&c.p==='/api/v1/robots/'+robotId));
  await page.click('#navCancel');await page.waitForFunction(()=>!document.querySelector('#navCloseSession').disabled);
  await page.click('#navCloseSession');await page.waitForFunction(()=>document.querySelector('#navSessionStatus').textContent.includes('CLOSED'));
  assert((await page.textContent('#navPose')).includes('Chưa có vị trí'));assert(await page.isDisabled('#navMove'));
  assert.deepEqual(errors,[]);
  console.log('PASS session UI: map load, seed drag, localization gate, endpoint, pinned workflow MOVE, cancel, close; no permanent robot assignment');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
