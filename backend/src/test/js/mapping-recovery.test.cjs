const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/mapping.html'),'utf8');
const start=html.indexOf("$('recover').onclick=");
const handler=html.slice(start,html.indexOf("$('watch').onclick=",start));
function page(api){
  const fields=new Map();const notices=[];
  const ctx={job:'job/a',busy:false,recovery:true,mapping:false,crypto:{randomUUID:()=> 'recovery-key'},
    $:key=>{if(!fields.has(key))fields.set(key,{});return fields.get(key);},
    jog:{active:false,stop:async()=>{}},api,buttons:()=>{},notice:text=>notices.push(text)};
  vm.runInNewContext(handler,ctx);return {ctx,fields,notices};
}
test('recovery button uses exact unsent STOP evidence and never replays a command',async()=>{
  const calls=[];const evidence={selectedUnsentStop:true,canReconcile:true,jobStepId:'stop-a',transportSessionId:'transport-a'};
  const p=page(async(...args)=>{calls.push(args);return args[1]?{status:'CANCELLED'}:evidence;});
  await p.fields.get('recover').onclick();
  assert.equal(calls.length,2);assert.equal(calls[0][0],'/api/v1/jobs/job%2Fa/mapping-recovery');
  assert.equal(calls[1][1].jobStepId,'stop-a');assert.equal(calls[1][1].transportSessionId,'transport-a');
  assert.equal(calls[1][1].confirmedStopped,true);assert.equal(calls[1][3].headers['Idempotency-Key'],'recovery-key');
  assert.equal(p.ctx.recovery,false);assert.equal(p.ctx.busy,false);
});
test('blocked unsent STOP cannot fall through into executed-operation reconcile',async()=>{
  const calls=[];const p=page(async(...args)=>{calls.push(args);return {selectedUnsentStop:true,canReconcile:false,blockers:['PI_NOT_IDLE']};});
  await p.fields.get('recover').onclick();assert.equal(calls.length,1);
  assert.equal(p.ctx.recovery,true);assert.match(p.notices[0],/PI_NOT_IDLE/);
});
test('published operation still uses existing operation-recovery receipt',async()=>{
  const calls=[];const p=page(async(...args)=>{calls.push(args);
    if(calls.length===1)return {selectedUnsentStop:false};
    if(calls.length===2)return {recoveryRequired:true,jobStepId:'save-a',operationKey:'op-a',transportSessionId:'transport-a',
      commands:[{jobStepId:'save-a',commandType:'EXECUTE_STEP',commandId:'command-a',physicalStopConfirmed:true}]};
    return {status:'CANCELLED'};
  });
  await p.fields.get('recover').onclick();assert.equal(calls.length,3);
  assert.equal(calls[2][0],'/api/v1/jobs/job%2Fa/operations/save-a/reconcile');
  assert.equal(calls[2][1].commandId,'command-a');assert.equal(calls[2][1].evidence,'PI_STOP_CONFIRMED');
});
