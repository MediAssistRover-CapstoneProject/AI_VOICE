'use strict';
const $ = id => document.getElementById(id);
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
let session, recognizer, enabled=false, active=false, busy=false, timer, epoch=0;
const reminder='Bạn cần tôi thì hãy nói hello Mar tôi sẽ hỗ trợ bạn.';
function face(mode,text){$('voiceFace').className='voice-avatar '+mode;$('faceStatus').textContent=text;$('faceHint').textContent=active?'Hãy nói câu hỏi về bệnh viện':'Nói “hello Mar” để bắt đầu';}
async function api(url,body){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw Error(data.message||r.statusText);return data;}
function stopRecognition(){const old=recognizer;recognizer=null;if(old){old.onend=null;old.abort();}}
function idleTimer(){clearTimeout(timer);timer=setTimeout(()=>{active=false;say(reminder);},5000);}
// Accept common Vietnamese transcription spellings of the spoken wake phrase.
function wakeMatch(text){return /(?:^|[^\p{L}\p{N}])(?:hello|he\s+lô|hê\s+lô|hê\s+lâu)[\s,]+(?:mar|ma|mà)(?=$|[^\p{L}\p{N}])/iu.exec(text);}
function listen(){if(!enabled||busy||recognizer)return;const r=new Recognition();recognizer=r;r.lang=$('lang').value;r.continuous=true;r.interimResults=true;
r.onspeechstart=()=>{if(recognizer===r)clearTimeout(timer);};
r.onspeechend=()=>{if(recognizer===r)idleTimer();};
r.onresult=e=>{if(recognizer!==r||busy)return;idleTimer();for(let i=e.resultIndex;i<e.results.length;i++){
 const result=e.results[i],text=result[0].transcript.trim();
 $('work').textContent='Nghe được: '+text;
 if(!result.isFinal)continue;
 const wake=wakeMatch(text);
 if(!active&&!wake){face('listening','Hãy nói “hello Mar” để gọi tôi');continue;}
 if(wake)active=true;
 const question=wake?text.slice(wake.index+wake[0].length).replace(/^[\s,.;:!?]+/,''):text;
 if(active&&question){ask(question);break;}
 if(wake){say('Mar đây. Tôi có thể hỗ trợ gì cho bạn?');break;}
}};
r.onend=()=>{if(recognizer===r){recognizer=null;listen();}};
r.onerror=e=>{if(['not-allowed','audio-capture','service-not-allowed','network'].includes(e.error)){enabled=false;clearTimeout(timer);stopRecognition();face('sad','Không thể nhận giọng nói: '+e.error);$('mic').textContent='🎙 Thử lại';}};
try{r.start();}catch(e){enabled=false;recognizer=null;face('sad',e.message);}}
function say(text){clearTimeout(timer);stopRecognition();busy=true;face('speaking','Mar đang nói');$('answer').textContent=text;const token=epoch;const done=()=>{if(token!==epoch)return;busy=false;face('listening',active?'Bạn muốn hỏi thêm gì?':'Đang chờ hello Mar');listen();if(active)idleTimer();};if(!window.speechSynthesis){done();return;}const u=new SpeechSynthesisUtterance(text);u.lang=$('lang').value;u.onend=done;u.onerror=done;speechSynthesis.cancel();speechSynthesis.speak(u);}
async function ask(text){clearTimeout(timer);stopRecognition();busy=true;const token=epoch;face('thinking','Đang tìm thông tin bệnh viện…');const start=performance.now();try{const t=await api(`/api/v1/public/assistant/sessions/${session}/turns`,{text});if(token!==epoch)return;if(t.status==='FAILED')throw Error('Chưa xử lý được câu hỏi. Vui lòng thử lại.');$('actions').replaceChildren();if(t.actionPreview&&t.confirmationNonce){const button=document.createElement('button');button.textContent='Xác nhận yêu cầu '+t.actionPreview.intent;button.onclick=async()=>{button.disabled=true;try{await api(`/api/v1/public/assistant/turns/${t.turnId}/confirm`,{nonce:t.confirmationNonce});button.textContent='Đã xác nhận';}catch(e){button.textContent=e.message;}};$('actions').append(button);}$('work').textContent=`Thời gian xử lý: ${((performance.now()-start)/1000).toFixed(1)} giây`;say(t.answer||'Tôi không trả lời thông tin này được.');}catch(e){if(token===epoch)say(e.message);}}
$('start').onclick=async()=>{if(enabled||busy)return;$('start').disabled=true;try{const s=await api('/api/v1/public/assistant/sessions',{robotId:$('robot').value,language:$('lang').value});session=s.sessionId;$('sessionState').textContent='Đã kết nối';$('mic').disabled=!Recognition;face('happy',Recognition?'Bấm nút voice và nói hello Mar':'Trình duyệt không hỗ trợ nhận dạng giọng nói');}catch(e){$('sessionState').textContent=e.message;}finally{$('start').disabled=false;}};
$('mic').onclick=()=>{enabled=!enabled;epoch++;clearTimeout(timer);stopRecognition();window.speechSynthesis?.cancel();busy=false;active=false;$('actions').replaceChildren();$('mic').textContent=enabled?'⏹ Tắt giọng nói':'🎙 Bật giọng nói';face('listening',enabled?'Đang chờ hello Mar':'Giọng nói đã tắt');if(enabled){listen();idleTimer();}};
// Browser recognition uses the operating system default microphone.
$('micDevice').closest('label').hidden=true;
window.addEventListener('pagehide',()=>{enabled=false;epoch++;clearTimeout(timer);stopRecognition();window.speechSynthesis?.cancel();});
face('happy','Xin chào, tôi là Mar');
