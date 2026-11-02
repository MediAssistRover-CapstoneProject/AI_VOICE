'use strict';

const $ = id => document.getElementById(id);
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
const STATES = Object.freeze({
  BOOTSTRAPPING:'BOOTSTRAPPING', WAKE_LISTENING:'WAKE_LISTENING', INTRO_PLAYING:'INTRO_PLAYING',
  QUESTION_LISTENING:'QUESTION_LISTENING', PROCESSING:'PROCESSING', ANSWER_PLAYING:'ANSWER_PLAYING',
  FOLLOW_UP_LISTENING:'FOLLOW_UP_LISTENING', ENDING:'ENDING', GOODBYE_PLAYING:'GOODBYE_PLAYING'
});

let state=STATES.BOOTSTRAPPING, config=null, session=null, sessionExpiresAt=0;
let recognizer=null, microphoneAllowed=false, idleHandle=null, pendingQuestion='', epoch=0;

function setState(next,message,hint){
  state=next;
  const mode={BOOTSTRAPPING:'thinking',WAKE_LISTENING:'listening',INTRO_PLAYING:'speaking',QUESTION_LISTENING:'listening',PROCESSING:'thinking',ANSWER_PLAYING:'speaking',FOLLOW_UP_LISTENING:'listening',ENDING:'thinking',GOODBYE_PLAYING:'speaking'}[next]||'happy';
  $('voiceFace').className='voice-avatar '+mode;
  $('faceStatus').textContent=message||next;
  $('faceHint').textContent=hint||'';
}

async function jsonFetch(url,options={}){
  const response=await fetch(url,options);let data={};try{data=await response.json();}catch(_e){}
  if(!response.ok)throw new Error(data.message||response.statusText);return data;
}
async function post(url,body){return jsonFetch(url,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()},body:JSON.stringify(body)});}

function stopListening(){const old=recognizer;recognizer=null;if(old){old.onend=null;try{old.abort();}catch(_e){}}}
function clearIdle(){clearTimeout(idleHandle);idleHandle=null;}
function wakeMatch(text){return /(?:^|[^\p{L}\p{N}])(?:hello|he\s*lô|hê\s*lô|hê\s*lâu)[\s,]+(?:mar|ma|mà)(?=$|[^\p{L}\p{N}])/iu.exec(text);}
function afterWake(text,match){return text.slice(match.index+match[0].length).replace(/^[\s,.;:!?]+/,'').trim();}

async function ensureSession(force=false){
  if(!force&&session&&Date.now()<sessionExpiresAt-30000)return session;
  const value=await post('/api/v1/public/assistant/sessions',{robotId:config.defaultRobotId,language:$('lang').value});
  session=value.sessionId;sessionExpiresAt=Date.parse(value.expiresAt)||Date.now()+25*60*1000;
  $('sessionState').textContent='Đã kết nối · chờ Hello MAR';return session;
}

async function requestMicrophone(){
  if(!Recognition)throw new Error('Trình duyệt này không hỗ trợ nhận dạng giọng nói. Hãy dùng Chrome hoặc Edge.');
  const stream=await navigator.mediaDevices.getUserMedia({audio:true});stream.getTracks().forEach(track=>track.stop());
  microphoneAllowed=true;$('permission').hidden=true;$('mic').disabled=false;enterWakeListening();
}

function listen(){
  if(!microphoneAllowed||recognizer||[STATES.INTRO_PLAYING,STATES.PROCESSING,STATES.ANSWER_PLAYING,STATES.ENDING,STATES.GOODBYE_PLAYING].includes(state))return;
  const currentEpoch=epoch;const r=new Recognition();recognizer=r;r.lang=$('lang').value;r.continuous=true;r.interimResults=true;
  r.onspeechstart=()=>{if(recognizer===r&&state!==STATES.WAKE_LISTENING)clearIdle();};
  r.onspeechend=()=>{if(recognizer===r&&[STATES.QUESTION_LISTENING,STATES.FOLLOW_UP_LISTENING].includes(state))armIdle();};
  r.onresult=event=>{if(recognizer!==r||currentEpoch!==epoch)return;for(let i=event.resultIndex;i<event.results.length;i++){
    const result=event.results[i],text=result[0].transcript.trim();$('work').textContent='Nghe được: '+text;if(!result.isFinal)continue;
    if(state===STATES.WAKE_LISTENING){const match=wakeMatch(text);if(match)startConversation(afterWake(text,match));}
    else if(state===STATES.QUESTION_LISTENING||state===STATES.FOLLOW_UP_LISTENING){const match=wakeMatch(text);const question=(match?afterWake(text,match):text).trim();if(question)ask(question);else armIdle();}
  }};
  r.onend=()=>{if(recognizer===r){recognizer=null;setTimeout(listen,150);}};
  r.onerror=event=>{if(recognizer!==r)return;recognizer=null;if(['not-allowed','service-not-allowed','audio-capture'].includes(event.error)){microphoneAllowed=false;$('permission').hidden=false;$('permission').textContent='Thử cấp quyền microphone';setState(STATES.BOOTSTRAPPING,'Không thể dùng microphone: '+event.error,'Kiểm tra quyền microphone của trình duyệt');}else setTimeout(listen,400);};
  try{r.start();}catch(_e){recognizer=null;setTimeout(listen,400);}
}

function speak(text,kind,done){
  clearIdle();stopListening();epoch++;const label=kind==='INTRO'?'MAR đang giới thiệu':kind==='GOODBYE'?'MAR đang kết thúc':'MAR đang trả lời';
  setState(kind==='INTRO'?STATES.INTRO_PLAYING:kind==='GOODBYE'?STATES.GOODBYE_PLAYING:STATES.ANSWER_PLAYING,label,'Microphone tạm dừng để không thu tiếng của MAR');
  const complete=()=>{if(done)done();};if(!window.speechSynthesis){complete();return;}
  const utterance=new SpeechSynthesisUtterance(text);utterance.lang=$('lang').value;utterance.onend=complete;utterance.onerror=complete;speechSynthesis.cancel();speechSynthesis.speak(utterance);
}

function startConversation(trailing=''){
  if(state!==STATES.WAKE_LISTENING)return;pendingQuestion=trailing;stopListening();
  speak(config.introText,'INTRO',()=>{if(pendingQuestion){const q=pendingQuestion;pendingQuestion='';ask(q);}else enterQuestionListening();});
}
function enterQuestionListening(){setState(STATES.QUESTION_LISTENING,'Tôi đang nghe câu hỏi','Bạn không cần nói lại Hello MAR');listen();armIdle();}
function enterFollowUp(){setState(STATES.FOLLOW_UP_LISTENING,'Bạn muốn hỏi thêm gì?','Im lặng '+config.idleTimeoutSeconds+' giây để kết thúc phiên');listen();armIdle();}
function enterWakeListening(){clearIdle();pendingQuestion='';setState(STATES.WAKE_LISTENING,'Đang chờ “Hello MAR”','Hoặc nhấn Bắt đầu thủ công');listen();}
function armIdle(){clearIdle();idleHandle=setTimeout(endConversation,Math.max(3,config.idleTimeoutSeconds)*1000);}
function endConversation(){if(![STATES.QUESTION_LISTENING,STATES.FOLLOW_UP_LISTENING].includes(state))return;setState(STATES.ENDING,'Đang kết thúc phiên','');speak(config.goodbyeText,'GOODBYE',enterWakeListening);}

async function ask(text,retry=true){
  if(!text||state===STATES.PROCESSING)return;clearIdle();stopListening();setState(STATES.PROCESSING,'Đang tìm câu trả lời…','Ưu tiên nguồn chính thức của bệnh viện');$('actions').replaceChildren();
  const started=performance.now();try{
    await ensureSession();const turn=await post(`/api/v1/public/assistant/sessions/${session}/turns`,{text});if(turn.status==='FAILED')throw new Error(turn.errorMessage||'Chưa xử lý được câu hỏi.');
    const general=turn.groundingMode==='GENERAL';$('sourceBadge').hidden=false;$('sourceBadge').textContent=general?'Kiến thức chung · không phải thông tin chính thức của bệnh viện':'Nguồn bệnh viện';
    $('sourceBadge').className='badge '+(general?'warning':'');$('answer').textContent=turn.answer||'';$('work').textContent=`Thời gian xử lý: ${((performance.now()-started)/1000).toFixed(1)} giây`;
    if(turn.actionPreview&&turn.confirmationNonce){const button=document.createElement('button');button.textContent='Xác nhận '+turn.actionPreview.intent;button.onclick=async()=>{button.disabled=true;try{await post(`/api/v1/public/assistant/turns/${turn.turnId}/confirm`,{nonce:turn.confirmationNonce});button.textContent='Đã xác nhận';}catch(error){button.textContent=error.message;}};$('actions').append(button);}
    speak(turn.speechText||turn.answer||'Tôi chưa có câu trả lời.','ANSWER',enterFollowUp);
  }catch(error){if(retry&&/expired|not found/i.test(error.message)){session=null;await ensureSession(true);return ask(text,false);}speak(error.message,'ANSWER',enterFollowUp);}
}

async function bootstrap(){
  setState(STATES.BOOTSTRAPPING,'MAR đang khởi tạo','Đang tải cấu hình hội thoại');
  try{config=await jsonFetch('/api/v1/public/assistant/config?language='+encodeURIComponent($('lang').value));$('robot').value=config.defaultRobotId;await ensureSession(true);$('mic').disabled=!Recognition;
    try{await requestMicrophone();}catch(_e){$('permission').hidden=false;setState(STATES.BOOTSTRAPPING,'Hãy cho phép microphone','Sau khi cấp quyền, chỉ cần nói “Hello MAR”');}
  }catch(error){$('sessionState').textContent=error.message;setState(STATES.BOOTSTRAPPING,'Không thể khởi tạo MAR',error.message);}
}

$('permission').onclick=async()=>{try{await requestMicrophone();}catch(error){$('sessionState').textContent=error.message;}};
$('mic').onclick=async()=>{if(!microphoneAllowed){await requestMicrophone();return;}if(state===STATES.WAKE_LISTENING)startConversation('');};
$('lang').onchange=async()=>{stopListening();epoch++;config=await jsonFetch('/api/v1/public/assistant/config?language='+encodeURIComponent($('lang').value));session=null;await ensureSession(true);enterWakeListening();};
$('micDevice').closest('label').hidden=true;$('robot').closest('label').hidden=true;
window.addEventListener('pagehide',()=>{microphoneAllowed=false;clearIdle();stopListening();speechSynthesis?.cancel();});
bootstrap();
