const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const script = fs.readFileSync('backend/src/main/resources/static/kiosk-voice.js', 'utf8');
function harness() {
  const elements = {}, timers = new Map(), speech = [], recognitions = [], requests = [], audio = [], urls = [], revokedUrls = [];
  let sequence = 0, micRequests = 0;
  const el = id => elements[id] ??= {textContent:'', hidden:false, disabled:false, className:'', replaceChildren(){}, append(){}};
  const synthesis = {cancel(){}, getVoices:()=>[{lang:'vi-VN', name:'Vietnamese'}], speak:u=>speech.push(u)};
  const ctx = {document:{getElementById:el, createElement:()=>({}), addEventListener(){}},
    window:{speechSynthesis:synthesis, SpeechRecognition:class {constructor(){recognitions.push(this);}start(){}abort(){}}, addEventListener(){}},
    navigator:{mediaDevices:{getUserMedia:async()=>{micRequests++;return {getTracks:()=>[{stop(){}}]};}}},
    speechSynthesis:synthesis, SpeechSynthesisUtterance:function(text){this.text=text;}, AbortController,
    Audio:class {
      constructor(src){this.src=src;this.playCount=0;this.pauseCount=0;audio.push(this);}
      play(){this.playCount++;return Promise.resolve();}
      pause(){this.pauseCount++;}
      removeAttribute(name){if(name==='src')this.src='';}
    },
    URL:{createObjectURL(blob){const url='blob:test-'+urls.length;urls.push({url,blob});return url;},revokeObjectURL:url=>revokedUrls.push(url)},
    setTimeout(fn,ms){timers.set(++sequence,{fn,ms});return sequence;}, clearTimeout:id=>timers.delete(id),
    crypto:{randomUUID:()=>String(++sequence)}, performance:{now:()=>0}, Date,
    fetch:async(url,options)=>{requests.push({url,options});return {ok:true,json:async()=> url.includes('/config')?{defaultRobotId:'robot'}:url.endsWith('/sessions')?{sessionId:'s',expiresAt:'2099-01-01'}:{groundingMode:'HOSPITAL',citations:[{title:'Nguồn'}],answer:'Nhà thuốc ở tầng một.'}};}};
  vm.createContext(ctx);vm.runInContext(script,ctx);
  const run = code => vm.runInContext(code,ctx);
  const fire = ms => {const entry=[...timers].find(([,t])=>t.ms===ms);assert.ok(entry,`timer ${ms} exists`);timers.delete(entry[0]);entry[1].fn();};
  const say = (text,final=true) => {const result=[{transcript:text}];result.isFinal=final;recognitions.at(-1).onresult({resultIndex:0,results:[result]});};
  return {ctx,run,el,fire,say,speech,recognitions,requests,timers,audio,urls,revokedUrls,get micRequests(){return micRequests;}};
}
const flush = async()=>{await new Promise(resolve=>setImmediate(resolve));};

test('page load stays quiet and never requests microphone or network',()=>{
  const h=harness();assert.equal(h.micRequests,0);assert.equal(h.speech.length,0);assert.equal(h.requests.length,0);assert.equal(h.run('state'),'OFF');
});
test('voice click enables Vietnamese listening; hello prompts for help only once',async()=>{
  const h=harness();await h.el('mic').onclick();assert.equal(h.micRequests,1);assert.equal(h.recognitions[0].lang,'vi-VN');
  h.say('hello');await flush();assert.equal(h.speech.length,1);assert.match(h.speech[0].text,/Bạn cần hỗ trợ gì/);assert.equal(h.speech[0].lang,'vi-VN');
  const oldEnd=h.speech[0].onend;oldEnd();oldEnd();assert.equal([...h.timers.values()].filter(t=>t.ms===1200).length,1);
  h.fire(1200);assert.equal(h.run('state'),'LISTENING');h.fire(5000);assert.equal(h.run('enabled'),false);assert.equal(h.speech.length,1);
});
test('Vietnamese help phrase and common hello transcriptions work',async()=>{
  for(const phrase of ['tôi cần giúp đỡ','Tôi cần hỗ trợ!','Hello Mar','hê lô','xin chào']){
    const h=harness();await h.el('mic').onclick();h.say(phrase);await flush();assert.equal(h.speech.length,1,phrase);assert.equal(h.requests.filter(r=>!r.url.endsWith('/voice-settings')).length,0);
  }
  const h=harness();assert.equal(h.run("helpPhrase('helloworld')"),false);
});
test('stop while speaking ignores an old completion callback',async()=>{
  const h=harness();await h.el('mic').onclick();h.say('hello');await flush();const callback=h.speech[0].onend;
  h.el('stopVoice').onclick();callback();assert.equal(h.run('enabled'),false);assert.equal(h.timers.size,0);
});
test('mic click interrupts speech then listens without another introduction',async()=>{
  const h=harness();await h.el('mic').onclick();h.say('hello');await flush();const callback=h.speech[0].onend;
  await h.el('mic').onclick();callback();h.fire(1200);assert.equal(h.run('state'),'LISTENING');assert.equal(h.speech.length,1);
});
test('late microphone permission never restarts a stopped session',async()=>{
  const h=harness();let resolve;h.ctx.navigator.mediaDevices.getUserMedia=()=>new Promise(r=>resolve=r);
  const pending=h.el('mic').onclick();h.el('stopVoice').onclick();resolve({getTracks:()=>[{stop(){}}]});await pending;
  assert.equal(h.recognitions.length,0);assert.equal(h.run('enabled'),false);
});
test('hospital question is posted in Vietnamese and grounded answer is spoken',async()=>{
  const h=harness();await h.el('mic').onclick();h.say('Nhà thuốc ở đâu?');await flush();
  assert.equal(h.speech.at(-1).text,'Nhà thuốc ở tầng một.');
  assert.equal(JSON.parse(h.requests.find(r=>r.url.endsWith('/sessions')).options.body).language,'vi-VN');
});
test('general answer and fabricated hospital label without citations are refused',async()=>{
  for(const answer of [{groundingMode:'GENERAL',citations:[]},{groundingMode:'HOSPITAL',citations:[]}]){
    const h=harness();await h.el('mic').onclick();h.run("session='s';sessionExpiresAt=Date.now()+999999");
    h.ctx.fetch=async()=>({ok:true,json:async()=>({...answer,answer:'Không được đọc nội dung này'})});
    h.say('Cổ phiếu hôm nay?');await flush();assert.match(h.speech.at(-1).text,/ngoài phạm vi hỗ trợ/);
  }
});
test('late network answer is discarded after stop',async()=>{
  const h=harness();await h.el('mic').onclick();h.run("session='s';sessionExpiresAt=Date.now()+999999");let resolve;
  h.ctx.fetch=()=>new Promise(r=>resolve=r);h.say('Nhà thuốc ở đâu?');await flush();h.el('stopVoice').onclick();
  resolve({ok:true,json:async()=>({groundingMode:'HOSPITAL',citations:[{}],answer:'late'})});await flush();assert.equal(h.speech.length,0);
});
test('recognizer restart cannot extend idle deadline indefinitely',async()=>{
  const h=harness();await h.el('mic').onclick();const idle=h.run('idleTimer');h.recognitions.at(-1).onend();h.fire(250);
  assert.equal(h.run('idleTimer'),idle);h.fire(5000);assert.equal(h.run('enabled'),false);
});
test('speaker echo is ignored and never triggers a second response',async()=>{
  const h=harness();await h.el('mic').onclick();h.say('hello');await flush();const text=h.speech[0].text;h.speech[0].onend();h.fire(1200);
  h.say(text);assert.equal(h.speech.length,1);assert.equal(h.requests.filter(r=>!r.url.endsWith('/voice-settings')).length,0);
});
test('microphone and speech errors stop quietly without retry loops',async()=>{
  const h=harness();await h.el('mic').onclick();h.recognitions[0].onerror({error:'network'});
  assert.equal(h.timers.size,0);assert.equal(h.speech.length,0);assert.equal(h.run('enabled'),false);
});


test('admin voice and rate are applied on the next utterance',async()=>{
  const h=harness();await h.el('mic').onclick();
  const chosen={lang:'vi-VN',name:'Admin voice',voiceURI:'admin-voice'};
  h.ctx.speechSynthesis.getVoices=()=>[chosen];
  h.ctx.fetch=async()=>({ok:true,json:async()=>({browserVoiceUri:'admin-voice',rate:1.25})});
  h.say('hello');await flush();assert.equal(h.speech[0].voice,chosen);assert.equal(h.speech[0].rate,1.25);
});
test('stopping during settings refresh prevents delayed speech',async()=>{
  const h=harness();await h.el('mic').onclick();let resolve;
  h.ctx.fetch=()=>new Promise(r=>resolve=r);
  h.say('hello');h.el('stopVoice').onclick();
  resolve({ok:true,json:async()=>({rate:1.5})});await flush();assert.equal(h.speech.length,0);
});

function cloudPlayback(h) {
  const fallback=h.ctx.fetch;
  const config={kioskEngine:'CLOUD',rate:1.25,browserVoiceUri:''};
  h.ctx.fetch=async(url,options)=>{
    if(url.endsWith('/voice-settings')){h.requests.push({url,options});return {ok:true,json:async()=>({...config})};}
    if(url.endsWith('/speech')){h.requests.push({url,options});return {ok:true,blob:async()=>({type:'audio/wav'})};}
    return fallback(url,options);
  };
  return config;
}

test('cloud greeting posts only null turn ID and resumes listening once audio ends',async()=>{
  const h=harness();cloudPlayback(h);await h.el('mic').onclick();h.say('hello');await flush();
  const request=h.requests.find(r=>r.url.endsWith('/speech'));
  assert.equal(request.url,'/api/v1/public/assistant/sessions/s/speech');
  assert.equal(request.options.method,'POST');
  assert.deepEqual(JSON.parse(request.options.body),{turnId:null});
  assert.equal(h.speech.length,0);assert.equal(h.audio.length,1);assert.equal(h.audio[0].playCount,1);
  assert.equal(h.run('state'),'SPEAKING');
  const ended=h.audio[0].onended;ended();ended();
  assert.equal(h.revokedUrls.length,1);assert.equal([...h.timers.values()].filter(t=>t.ms===1200).length,1);
  h.fire(1200);assert.equal(h.run('state'),'LISTENING');
});

test('cloud answer requests the stored turn instead of submitting arbitrary speech text',async()=>{
  const h=harness();cloudPlayback(h);const fallback=h.ctx.fetch;
  h.ctx.fetch=async(url,options)=>{
    if(url.endsWith('/turns')){h.requests.push({url,options});return {ok:true,json:async()=>({turnId:'answer-id',status:'READY',groundingMode:'HOSPITAL',citations:[{title:'Sổ tay'}],answer:'Nhà thuốc ở tầng một.'})};}
    return fallback(url,options);
  };
  await h.el('mic').onclick();h.say('Nhà thuốc ở đâu?');await flush();
  assert.deepEqual(JSON.parse(h.requests.find(r=>r.url.endsWith('/speech')).options.body),{turnId:'answer-id'});
  assert.equal(h.audio.length,1);assert.equal(h.speech.length,0);assert.equal(h.el('answer').textContent,'Nhà thuốc ở tầng một.');
});

test('stop aborts cloud download and a late response never plays audio or resumes mic',async()=>{
  const h=harness();cloudPlayback(h);const fallback=h.ctx.fetch;let resolve,signal;
  h.ctx.fetch=(url,options)=>{
    if(url.endsWith('/speech')){signal=options.signal;return new Promise(r=>resolve=r);}
    return fallback(url,options);
  };
  await h.el('mic').onclick();h.say('hello');await flush();
  assert.ok(resolve);h.el('stopVoice').onclick();assert.equal(signal.aborted,true);
  resolve({ok:true,blob:async()=>({type:'audio/wav'})});await flush();
  assert.equal(h.audio.length,0);assert.equal(h.urls.length,0);assert.equal(h.speech.length,0);
  assert.equal(h.run('state'),'OFF');assert.equal(h.timers.size,0);assert.equal(h.recognitions.length,1);
});

test('stop during cloud playback pauses audio, releases its URL and ignores old callbacks',async()=>{
  const h=harness();cloudPlayback(h);await h.el('mic').onclick();h.say('hello');await flush();
  const audio=h.audio[0],ended=audio.onended,failed=audio.onerror,url=audio.src;
  h.el('stopVoice').onclick();ended();failed();await flush();
  assert.equal(audio.pauseCount,1);assert.equal(audio.src,'');assert.deepEqual(h.revokedUrls,[url]);
  assert.equal(h.run('state'),'OFF');assert.equal(h.timers.size,0);assert.equal(h.recognitions.length,1);
});

test('mic interruption stops cloud audio and an old playback event cannot restart a second session',async()=>{
  const h=harness();cloudPlayback(h);await h.el('mic').onclick();h.say('hello');await flush();
  const audio=h.audio[0],ended=audio.onended;await h.el('mic').onclick();ended();
  assert.equal(audio.pauseCount,1);assert.equal([...h.timers.values()].filter(t=>t.ms===1200).length,1);
  h.fire(1200);assert.equal(h.run('state'),'LISTENING');assert.equal(h.recognitions.length,2);
});

test('changing admin engine is applied on the next utterance without reloading kiosk',async()=>{
  const h=harness(),config=cloudPlayback(h);config.kioskEngine='BROWSER';
  await h.el('mic').onclick();h.say('hello');await flush();
  assert.equal(h.speech.length,1);assert.equal(h.audio.length,0);assert.equal(h.speech[0].rate,1.25);
  h.speech[0].onend();h.fire(1200);config.kioskEngine='CLOUD';h.say('tôi cần giúp đỡ');await flush();
  assert.equal(h.audio.length,1);assert.equal(h.speech.length,1);
  h.audio[0].onended();h.fire(1200);config.kioskEngine='BROWSER';config.rate=.85;
  h.say('hello mar');await flush();assert.equal(h.speech.length,2);assert.equal(h.speech[1].rate,.85);assert.equal(h.audio.length,1);
  assert.equal(h.requests.filter(r=>r.url.endsWith('/speech')).length,1);
});
