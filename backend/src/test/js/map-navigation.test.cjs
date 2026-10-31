// Run with: node --test src/test/js/map-navigation.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const nav = require('../../main/resources/static/map-navigation.js');
const id='11111111-1111-4111-8111-111111111111';
const robot='22222222-2222-4222-8222-222222222222';
const metadata={map_id:id,asset_hash:'a'.repeat(64),frame_id:'map',width_px:100,height_px:200,
  resolution_m_per_pixel:.025,origin_x_m:-.7,origin_y_m:-2.2,origin_yaw_rad:0};

test('pixel centres round-trip through a translated, rotated map',()=>{
  for(const yaw of [0,.6,-1.3,Math.PI/2]){
    const m={...metadata,origin_yaw_rad:yaw};nav.geometry(m);
    for(const [u,v] of [[.5,.5],[30.5,80.5],[99.5,199.5]]){
      const p=nav.pixelToMap(m,u,v), q=nav.mapToPixel(m,p.xM,p.yM);
      assert(Math.abs(q.u-u)<1e-9);assert(Math.abs(q.v-v)<1e-9);
      assert(nav.inside(m,p.xM,p.yM));
    }
    const p=nav.pixelToMap(m,101,100);assert(!nav.inside(m,p.xM,p.yM));
  }
});
test('cell zero uses half-cell offset, not the rounded catalog origin',()=>{
  const p=nav.pixelToMap(metadata,.5,.5);
  assert(Math.abs(p.xM-(metadata.origin_x_m+.0125))<1e-12);
  assert(Math.abs(p.yM-(metadata.origin_y_m+199.5*.025))<1e-12);
});
function snapshot(){
  const now='2026-10-23T05:00:00Z',p=nav.pixelToMap(metadata,40.5,80.5);
  return {observedAt:now,mqttConnected:true,heartbeatFresh:true,
    robot:{robotId:robot,currentMapId:id,mapAssetHash:metadata.asset_hash},
    latestPose:{robotId:robot,mapId:id,frameId:'map',xM:p.xM,yM:p.yM,yawRad:0,fresh:true,capturedAt:now}};
}
test('hide offline, wrong robot/map/hash/frame and stale poses',()=>{
  assert(nav.freshPose(snapshot(),metadata,0));
  assert(!nav.freshPose(snapshot(),metadata,3001));
  for(const change of [s=>s.mqttConnected=false,s=>s.heartbeatFresh=false,s=>s.latestPose.fresh=false,
    s=>s.latestPose.robotId=id,s=>s.latestPose.mapId=robot,s=>s.latestPose.frameId='odom',
    s=>s.robot.mapAssetHash='b'.repeat(64),s=>s.latestPose.yawRad=NaN,
    s=>s.latestPose.capturedAt='2026-10-23T04:59:56Z']){
    const s=snapshot();change(s);assert.equal(nav.freshPose(s,metadata,0),null);
  }
  const s=snapshot();s.latestPose.capturedAt='2026-10-23T04:59:58Z';
  assert(nav.freshPose(s,metadata,500));assert(!nav.freshPose(s,metadata,1500));
});
test('dedicated workflow contract rejects changed speed or extra physical steps',()=>{
  const definition=JSON.parse(fs.readFileSync(path.join(__dirname,'../../main/resources/static/navigation-move-workflow.json'),'utf8'));
  const expected=nav.templateContract(definition);
  assert.equal(expected.steps.length,1);assert.equal(expected.steps[0].stepType,'MOVE');
  assert.equal(expected.steps[0].paramsJson.max_speed_mps,.05);
  const altered=structuredClone(definition);altered.graph.steps[0].paramsJson.max_speed_mps=.3;
  assert.notDeepEqual(nav.templateContract(altered),expected);
  const extra=structuredClone(definition);extra.graph.steps.push({...extra.graph.steps[0],stepCode:'EXTRA'});
  assert.notDeepEqual(nav.templateContract(extra),expected);
});

test('temporary session must be READY on the exact map before displaying its pose',()=>{
  for(const state of ['WAITING_FOR_PI','LOCALIZING','CLOSED','EXPIRED']){
    const s=snapshot();s.navigationSession={state,mapId:id,mapAssetHash:metadata.asset_hash};
    assert.equal(nav.freshPose(s,metadata,0),null);
  }
  const s=snapshot();s.navigationSession={state:'READY',mapId:id,mapAssetHash:metadata.asset_hash};
  assert(nav.freshPose(s,metadata,0));s.navigationSession.mapId=robot;
  assert.equal(nav.freshPose(s,metadata,0),null);
});
