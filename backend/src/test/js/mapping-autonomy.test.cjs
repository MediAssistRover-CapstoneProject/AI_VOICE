const {test}=require('node:test');
const assert=require('node:assert/strict');
const {AutonomyRun}=require('../../main/resources/static/mapping-autonomy.js');
const context={job:'job-a',session:'session-a',mapping:true};
const tick=()=>new Promise(resolve=>setImmediate(resolve));

test('page load is inert; START/RENEW are scoped and cancel stops delayed responses',async()=>{
  const calls=[];let release;
  const run=new AutonomyRun((body,scope)=>{calls.push({body,scope});return new Promise(r=>release=r);},()=>{},()=>{});
  assert.equal(calls.length,0);
  run.start(context,180,false,false);assert.equal(calls[0].body.action,'START');
  assert.equal(calls[0].scope.session,'session-a');
  run.cancel();release({status:'MQTT_PUBLISHED'});await tick();
  assert.equal(run.timer,null);assert.equal(run.active,false);
});
test('finish only saves on normal run end, once and for the same run',async()=>{
  let finishes=0;
  const run=new AutonomyRun(async()=>({status:'MQTT_PUBLISHED'}),()=>{},()=>finishes++);
  run.start(context,30,true,true);await tick();
  run.observe({run_id:'foreign',finished:true,reason:'TIME_LIMIT'},context);assert.equal(finishes,0);
  const report={run_id:run.run.runId,finished:true,state:'STOPPED',reason:'TIME_LIMIT'};
  run.observe(report,context);run.observe(report,context);
  assert.equal(finishes,1);assert.equal(run.active,false);
  run.start(context,30,false,true);await tick();
  run.observe({run_id:run.run.runId,finished:true,state:'STOPPED',reason:'LIDAR_STALE'},context);
  assert.equal(finishes,1);
});
test('lost HTTP stops lease, sends STOP, and never silently restarts',async()=>{
  const calls=[];
  const run=new AutonomyRun(async body=>{calls.push(body.action);if(body.action==='START')throw Error('offline');return {};},()=>{},()=>{});
  run.start(context,30,false,false);await tick();
  assert.deepEqual(calls,['START','STOP']);assert.equal(run.active,false);
  assert.equal(run.timer,null);
});
test('scope change sends STOP to old session and never moves the new session',async()=>{
  const calls=[];
  const run=new AutonomyRun(async(body,scope)=>{calls.push({body,scope});return {};},()=>{},()=>{});
  run.start(context,30,false,false);await tick();
  run.observe(null,{job:'job-b',session:'session-b',mapping:true});await tick();
  assert.equal(calls.at(-1).body.action,'STOP');assert.equal(calls.at(-1).scope.session,'session-a');
  assert.equal(run.active,false);
});

test('coverage autosave requires the explicit completed frontier report and runs once',async()=>{
  let finishes=0;
  const run=new AutonomyRun(async()=>({status:'MQTT_PUBLISHED'}),()=>{},()=>finishes++);
  run.start(context,180,false,true);await tick();
  run.observe({run_id:run.run.runId,finished:true,state:'STOPPED',reason:'COVERAGE_COMPLETE',coverage_complete:false},context);
  assert.equal(finishes,0);
  run.start(context,180,false,true);await tick();
  const report={run_id:run.run.runId,finished:true,state:'STOPPED',reason:'COVERAGE_COMPLETE',coverage_complete:true,exploration:{state:'COMPLETE'}};
  run.observe(report,context);run.observe(report,context);
  assert.equal(finishes,1);
});

test('blocked or timed out planner never triggers autosave',async()=>{
  let finishes=0;
  const run=new AutonomyRun(async()=>({status:'MQTT_PUBLISHED'}),()=>{},()=>finishes++);
  for(const reason of ['FRONTIERS_BLOCKED_BY_FOOTPRINT','FRONTIER_NO_PROGRESS','EXPLORATION_PLAN_TIMEOUT']){
    run.start(context,180,false,true);await tick();
    run.observe({run_id:run.run.runId,finished:true,state:'STOPPED',reason,coverage_complete:false},context);
  }
  assert.equal(finishes,0);
});

test('lost START is retried with the same run and RENEW waits for Pi acceptance',async()=>{
  const calls=[];
  const run=new AutonomyRun(async body=>{calls.push({...body});return {commandId:'c'+calls.length,status:'MQTT_PUBLISHED'};},()=>{},()=>{});
  try{
    run.start(context,30,false,false);await tick();clearTimeout(run.timer);
    await run.renew(run.generation);clearTimeout(run.timer);
    assert.deepEqual(calls.map(c=>c.action),['START','START']);
    assert.equal(calls[0].runId,calls[1].runId);
    run.observeReceipt({commandId:'c1',status:'PI_ACCEPTED'});
    await run.renew(run.generation);clearTimeout(run.timer);
    assert.equal(calls.at(-1).action,'RENEW');
  }finally{run.cancel();}
});
test('Pi rejection stops a pending start and exposes its reason',async()=>{
  const calls=[],messages=[];
  const run=new AutonomyRun(async body=>{calls.push(body.action);return {commandId:'c'+calls.length,status:'MQTT_PUBLISHED'};},m=>messages.push(m),()=>{});
  run.start(context,30,false,false);await tick();
  run.observeReceipt({commandId:'foreign',status:'PI_REJECTED'});assert.equal(run.active,true);
  run.observeReceipt({commandId:'c1',status:'PI_REJECTED',errorCode:'MAPPING_CONTROL_EXPIRED_OR_CLOCK_SKEW'});await tick();
  assert.equal(run.active,false);assert.deepEqual(calls,['START','STOP']);
  assert.match(messages.at(-1),/CLOCK_SKEW/);
});
test('missing start confirmation has a bounded wait and never falls back to RENEW',async()=>{
  const calls=[];
  const run=new AutonomyRun(async b=>{calls.push(b.action);return {commandId:'c'+calls.length,status:'MQTT_PUBLISHED'};},()=>{},()=>{});
  run.start(context,30,false,false);await tick();clearTimeout(run.timer);run.startedAt-=4000;
  await run.renew(run.generation);
  assert.equal(run.active,false);assert.deepEqual(calls,['START','STOP']);
});
