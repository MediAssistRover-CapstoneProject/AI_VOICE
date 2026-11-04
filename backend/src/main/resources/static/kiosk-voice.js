'use strict';

const $ = id => document.getElementById(id);
const API_BASE = window.MAR_API_BASE || '';
const apiFetch = (url, options) => fetch(API_BASE + url, options);
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
const LANGUAGE = 'vi-VN';
const GREETING = 'Tôi là MAR. Tôi có thể giúp bạn tra cứu thông tin bệnh viện. Bạn cần hỗ trợ gì?';
const OUT_OF_SCOPE = 'Câu hỏi này nằm ngoài phạm vi hỗ trợ của tôi. Tôi chỉ hỗ trợ thông tin có trong tài liệu bệnh viện.';
const IDLE_MS = 5000;
const SPEAKER_COOLDOWN_MS = 1200;
let state = 'OFF', enabled = false, epoch = 0, config = null;
let session = null, sessionExpiresAt = 0, recognizer = null, utterance = null;
let idleTimer = null, resumeTimer = null, watchdog = null, controller = null;
let lastSpoken = '', lastSpokenAt = 0;
let cloudAudio = null, cloudAudioUrl = null;

function releaseCloudAudio() {
  if (cloudAudio) { cloudAudio.onended = cloudAudio.onerror = null; cloudAudio.pause(); cloudAudio.removeAttribute('src'); cloudAudio = null; }
  if (cloudAudioUrl) { URL.revokeObjectURL(cloudAudioUrl); cloudAudioUrl = null; }
}

function show(next, message, hint = '') {
  state = next;
  const mode = { OFF:'happy', STARTING:'thinking', LISTENING:'listening', PROCESSING:'thinking', SPEAKING:'speaking', COOLDOWN:'happy' }[next];
  $('voiceFace').className = 'voice-avatar ' + mode;
  $('faceStatus').textContent = message;
  $('faceHint').textContent = hint;
  $('mic').textContent = enabled ? '🎙 Ngắt lời / Nghe câu hỏi' : '🎙 Bật voice';
  $('stopVoice').disabled = !enabled;
}
function normalize(text) {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/đ/g, 'd').replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim();
}
function helpPhrase(text) {
  return /^(?:(?:hello|he\s*lo|he\s*lau)(?:\s+(?:mar|ma))?|toi can giup do|toi can ho tro|mar oi|xin chao)(?:\s|$)/.test(normalize(text));
}
function detachRecognition() {
  const old = recognizer;
  recognizer = null;
  if (old) {
    old.onresult = old.onend = old.onerror = old.onspeechstart = old.onspeechend = null;
    try { old.abort(); } catch (_) {}
  }
}
function cancelCurrent() {
  epoch++;
  clearTimeout(idleTimer); clearTimeout(resumeTimer); clearTimeout(watchdog);
  idleTimer = resumeTimer = watchdog = null;
  detachRecognition();
  controller?.abort(); controller = null;
  if (utterance) { utterance.onend = utterance.onerror = null; utterance = null; }
  window.speechSynthesis?.cancel();
  releaseCloudAudio();
}
function stopVoice(message = 'Đã tắt giọng nói') {
  enabled = false;
  cancelCurrent();
  $('actions').replaceChildren();
  show('OFF', message, 'Bấm Bật voice để trò chuyện bằng tiếng Việt.');
}
async function jsonFetch(url, options = {}) {
  const response = await apiFetch(url, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.message || 'Không thể kết nối máy chủ.');
    error.status = response.status;
    throw error;
  }
  return data;
}
function post(url, body, signal) {
  return jsonFetch(url, { method:'POST', signal, headers:{ 'Content-Type':'application/json', 'Idempotency-Key':crypto.randomUUID() }, body:JSON.stringify(body) });
}
async function loadConfig(signal) {
  if (!config) config = await jsonFetch('/api/v1/public/assistant/config?language=vi-VN', {signal});
  return config;
}
async function ensureSession(signal, token) {
  if (session && Date.now() < sessionExpiresAt - 30000) return session;
  await loadConfig(signal);
  const result = await post('/api/v1/public/assistant/sessions', {robotId:config.defaultRobotId, language:LANGUAGE}, signal);
  if (token !== epoch || !enabled) return null;
  session = result.sessionId;
  sessionExpiresAt = Date.parse(result.expiresAt) || Date.now() + 25 * 60000;
  $('sessionState').textContent = 'Đã kết nối · Tiếng Việt';
  return session;
}
function armIdle(delay = IDLE_MS) {
  clearTimeout(idleTimer);
  const token = epoch;
  idleTimer = setTimeout(() => {
    if (token === epoch && enabled && state === 'LISTENING') stopVoice('Đã tạm dừng vì không nghe thấy câu hỏi');
  }, delay);
}
function resumeListening(token) {
  show('COOLDOWN', 'MAR đã nói xong', 'Chuẩn bị nghe câu hỏi tiếp theo…');
  resumeTimer = setTimeout(() => {
    if (enabled && token === epoch) beginListening();
  }, SPEAKER_COOLDOWN_MS);
}
function beginListening() {
  if (!enabled || recognizer) return;
  const token = epoch;
  show('LISTENING', 'MAR đang lắng nghe', 'Nói “hello”, “tôi cần giúp đỡ” hoặc hỏi về bệnh viện.');
  const r = new Recognition();
  recognizer = r;
  r.lang = LANGUAGE; r.continuous = false; r.interimResults = true;
  r.onspeechstart = () => { if (recognizer === r) armIdle(30000); };
  r.onresult = event => {
    if (recognizer !== r || token !== epoch || !enabled || state !== 'LISTENING') return;
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i], text = result[0].transcript.trim();
      $('work').textContent = 'Nghe được: ' + text;
      armIdle(result.isFinal ? IDLE_MS : 10000);
      if (!result.isFinal || !text) continue;
      // Ignore a late echo of the sentence just played through the laptop speaker.
      if (Date.now() - lastSpokenAt < 6000 && normalize(text) === normalize(lastSpoken)) continue;
      detachRecognition(); clearTimeout(idleTimer);
      if (helpPhrase(text)) speak(GREETING);
      else if (['dung lai', 'tat voice', 'tat giong noi', 'tam biet'].includes(normalize(text))) stopVoice();
      else ask(text);
      return;
    }
  };
  r.onend = () => {
    if (recognizer !== r || token !== epoch || !enabled) return;
    recognizer = null;
    // Do not reset the silence deadline when the recognition service restarts.
    resumeTimer = setTimeout(() => { if (token === epoch && enabled && state === 'LISTENING') beginListening(); }, 250);
  };
  r.onerror = event => {
    if (recognizer !== r || token !== epoch) return;
    if (event.error === 'no-speech') return;
    const messages = {'not-allowed':'Bạn chưa cho phép dùng microphone.', 'audio-capture':'Không tìm thấy microphone.', network:'Dịch vụ nhận dạng giọng nói mất kết nối.', 'service-not-allowed':'Trình duyệt chưa cho phép nhận dạng giọng nói.'};
    stopVoice(messages[event.error] || 'Không nhận được giọng nói. Bấm Bật voice để thử lại.');
  };
  try { r.start(); if (!idleTimer) armIdle(); }
  catch (_) { stopVoice('Không mở được nhận dạng giọng nói. Bấm Bật voice để thử lại.'); }
}
async function speak(text, turnId = null) {
  if (!enabled) return;
  cancelCurrent();
  const token = epoch;
  $('answer').textContent = text;
  show('SPEAKING', 'MAR đang trả lời', 'Bấm nút mic để ngắt lời, hoặc Dừng giọng nói để tắt.');
  lastSpoken = text;
  // A small settings read makes admin changes apply to the very next utterance.
  controller = new AbortController();
  const voiceController = controller;
  const settingsTimeout = setTimeout(() => voiceController.abort(), 1500);
  let voice = {};
  try { voice = await jsonFetch('/api/v1/public/assistant/voice-settings', {signal:voiceController.signal, cache:'no-store'}); }
  catch (_) { /* Browser defaults remain available if settings cannot be read. */ }
  finally { clearTimeout(settingsTimeout); }
  if (token !== epoch || !enabled) return;
  if (voice.kioskEngine === 'CLOUD') { await speakCloud(turnId, token, voiceController.signal); return; }
  if (!window.speechSynthesis) { stopVoice('Trình duyệt không hỗ trợ đọc giọng nói. Xem câu trả lời trên màn hình.'); return; }
  const u = new SpeechSynthesisUtterance(text);
  utterance = u; u.lang = LANGUAGE; u.rate = Number.isFinite(voice.rate) ? Math.max(0.75, Math.min(1.5, voice.rate)) : 1;
  const voices = speechSynthesis.getVoices();
  const vietnamese = voices.find(v => v.voiceURI === voice.browserVoiceUri) || voices.find(v => /^vi([_-]|$)/i.test(v.lang));
  if (vietnamese) u.voice = vietnamese;
  let finished = false;
  u.onend = () => {
    if (finished || token !== epoch || !enabled) return;
    finished = true; utterance = null; clearTimeout(watchdog); lastSpokenAt = Date.now();
    resumeListening(token);
  };
  u.onerror = () => { if (token === epoch && enabled) stopVoice('Không phát được giọng nói. Câu trả lời đã hiện trên màn hình.'); };
  watchdog = setTimeout(() => { if (token === epoch) stopVoice('Đã dừng phát giọng nói. Bấm Bật voice để tiếp tục.'); }, 60000);
  speechSynthesis.speak(u);
}
async function speakCloud(turnId, token, signal) {
  show('PROCESSING', 'Đang chuẩn bị giọng AI…', 'Bấm nút mic để ngắt hoặc Dừng giọng nói để tắt.');
  watchdog = setTimeout(() => { if (token === epoch) stopVoice('Tạo giọng AI mất quá lâu. Bấm Bật voice để thử lại.'); }, 90000);
  try {
    const id = await ensureSession(signal, token);
    if (!id || token !== epoch || !enabled) return;
    // Only server-owned answers or the fixed greeting may be synthesized.
    const response = await apiFetch(`/api/v1/public/assistant/sessions/${id}/speech`, {
      method:'POST', signal, headers:{'Content-Type':'application/json'}, body:JSON.stringify({turnId})
    });
    if (!response.ok) throw new Error('Không tạo được giọng AI.');
    const blob = await response.blob();
    if (token !== epoch || !enabled) return;
    clearTimeout(watchdog);
    cloudAudioUrl = URL.createObjectURL(blob);
    const audio = new Audio(cloudAudioUrl); cloudAudio = audio;
    audio.onended = () => {
      if (cloudAudio !== audio || token !== epoch || !enabled) return;
      clearTimeout(watchdog); releaseCloudAudio(); lastSpokenAt = Date.now(); resumeListening(token);
    };
    audio.onerror = () => { if (cloudAudio === audio && token === epoch) stopVoice('Không phát được giọng AI. Câu trả lời đã hiện trên màn hình.'); };
    show('SPEAKING', 'MAR đang trả lời bằng giọng AI', 'Bấm nút mic để ngắt lời, hoặc Dừng giọng nói để tắt.');
    watchdog = setTimeout(() => { if (token === epoch) stopVoice('Đã dừng phát giọng nói. Bấm Bật voice để tiếp tục.'); }, 90000);
    await audio.play();
  } catch (error) {
    if (token === epoch && enabled && error.name !== 'AbortError') stopVoice('Chưa phát được giọng AI. Bấm Bật voice để thử lại hoặc chọn giọng trên máy trong quản trị.');
  }
}
async function ask(text) {
  if (!enabled) return;
  cancelCurrent();
  const token = epoch;
  controller = new AbortController(); const signal = controller.signal;
  show('PROCESSING', 'Đang tra cứu thông tin bệnh viện…');
  $('actions').replaceChildren(); $('sourceBadge').hidden = true;
  watchdog = setTimeout(() => { if (token === epoch) stopVoice('Tra cứu đang mất nhiều thời gian. Bấm Bật voice để thử lại.'); }, 30000);
  const started = performance.now();
  try {
    let turn;
    for (let attempt = 0; attempt < 2; attempt++) {
      const id = await ensureSession(signal, token);
      if (!id || token !== epoch || !enabled) return;
      try { turn = await post(`/api/v1/public/assistant/sessions/${id}/turns`, {text}, signal); break; }
      catch (error) { if (attempt === 0 && /expired|session not found/i.test(error.message)) { session = null; continue; } throw error; }
    }
    if (token !== epoch || !enabled) return;
    if (turn.status === 'FAILED') throw new Error('Không xử lý được câu hỏi.');
    const grounded = turn.groundingMode === 'HOSPITAL' && turn.citations?.length > 0;
    $('sourceBadge').hidden = false;
    $('sourceBadge').textContent = grounded ? 'Nguồn tài liệu bệnh viện' : 'Ngoài phạm vi hỗ trợ';
    $('sourceBadge').className = 'badge' + (grounded ? '' : ' warning');
    $('work').textContent = `Thời gian tra cứu: ${((performance.now() - started) / 1000).toFixed(1)} giây`;
    if (grounded && turn.actionPreview && turn.confirmationNonce && !window.MAR_TEST_MODE) {
      const button = document.createElement('button'); button.textContent = 'Xác nhận yêu cầu';
      button.onclick = async () => {
        button.disabled = true;
        try { await post(`/api/v1/public/assistant/turns/${turn.turnId}/confirm`, {nonce:turn.confirmationNonce}); button.textContent = 'Yêu cầu đã được tiếp nhận'; }
        catch (_) { button.textContent = 'Không xác nhận được yêu cầu'; }
      };
      $('actions').append(button);
    }
    speak(grounded ? (turn.answer || OUT_OF_SCOPE) : OUT_OF_SCOPE, turn.turnId);
  } catch (error) {
    if (token !== epoch || !enabled || error.name === 'AbortError') return;
    stopVoice('Chưa tra cứu được thông tin. Bấm Bật voice để thử lại.');
  }
}
async function enableVoice() {
  // This function is only called by a click; page load never opens mic or speaks.
  const interrupted = enabled;
  cancelCurrent(); enabled = true;
  const token = epoch;
  show('STARTING', 'Đang bật microphone…');
  if (!Recognition) { stopVoice('Hãy mở trang bằng Chrome hoặc Edge có hỗ trợ nhận dạng giọng nói.'); return; }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true, noiseSuppression:true}});
    stream.getTracks().forEach(track => track.stop());
    if (token !== epoch || !enabled) return;
    if (interrupted) resumeListening(token); else beginListening();
  } catch (_) { if (token === epoch) stopVoice('Không mở được microphone. Hãy cho phép microphone trong trình duyệt.'); }
}
$('mic').disabled = false;
$('mic').onclick = enableVoice;
$('stopVoice').onclick = () => stopVoice();
window.addEventListener('pagehide', () => stopVoice());
document.addEventListener('visibilitychange', () => { if (document.hidden) stopVoice(); });
show('OFF', 'Xin chào, tôi là MAR', 'Bấm Bật voice để bắt đầu. MAR chỉ hỗ trợ thông tin bệnh viện.');
$('sessionState').textContent = 'Sẵn sàng · Tiếng Việt';
