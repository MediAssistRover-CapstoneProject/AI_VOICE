(function (root, factory) {
  const navigation = factory();
  if (typeof module === 'object' && module.exports) module.exports = navigation;
  else root.SavedMapNavigation = navigation;
})(globalThis, function () {
  'use strict';
  const DEFAULT_MAP = '73f7af66-ae21-499f-8209-57801411e9be';
  const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
  const angle = value => Math.atan2(Math.sin(value), Math.cos(value));
  const stable = value => JSON.stringify(value, function (key, item) {
    return item && typeof item === 'object' && !Array.isArray(item)
      ? Object.keys(item).sort().reduce((out, name) => (out[name] = item[name], out), {}) : item;
  });
  function geometry(meta) {
    if (!meta || !UUID.test(meta.map_id || '') || !/^[a-f0-9]{64}$/i.test(meta.asset_hash || '') ||
        ![meta.width_px, meta.height_px].every(v => Number.isInteger(v) && v > 0) ||
        ![meta.resolution_m_per_pixel, meta.origin_x_m, meta.origin_y_m, meta.origin_yaw_rad].every(Number.isFinite) ||
        meta.resolution_m_per_pixel <= 0 || meta.width_px * meta.height_px > 4000000 || !meta.frame_id) {
      throw Error('Metadata map thiếu hoặc không hợp lệ.');
    }
    return meta;
  }
  // u/v are continuous PNG coordinates measured from the top-left image edge.
  // A click snaps to a cell centre (column + .5, row + .5), including rotated origins.
  function pixelToMap(meta, u, v) {
    const r = meta.resolution_m_per_pixel, c = Math.cos(meta.origin_yaw_rad), s = Math.sin(meta.origin_yaw_rad);
    const x = u * r, y = (meta.height_px - v) * r;
    return {xM: meta.origin_x_m + c * x - s * y, yM: meta.origin_y_m + s * x + c * y};
  }
  function mapToPixel(meta, xM, yM) {
    const c = Math.cos(meta.origin_yaw_rad), s = Math.sin(meta.origin_yaw_rad);
    const x = xM - meta.origin_x_m, y = yM - meta.origin_y_m, r = meta.resolution_m_per_pixel;
    return {u: (c * x + s * y) / r, v: meta.height_px - (-s * x + c * y) / r};
  }
  function inside(meta, x, y) {
    if (![x, y].every(Number.isFinite)) return false;
    const p = mapToPixel(meta, x, y);
    return p.u >= 0 && p.v >= 0 && p.u < meta.width_px && p.v < meta.height_px;
  }
  function freshPose(snapshot, meta, receivedAgeMs) {
    const pose = snapshot?.latestPose, robot = snapshot?.robot;
    const age = Date.parse(snapshot?.observedAt) - Date.parse(pose?.capturedAt);
    const session=snapshot?.navigationSession;
    if(session && session.state!=='NONE' && (session.state!=='READY'||session.mapId!==meta.map_id||session.mapAssetHash!==meta.asset_hash))return null;
    if (!snapshot?.mqttConnected || !snapshot.heartbeatFresh || pose?.fresh !== true ||
        pose.robotId !== robot?.robotId || robot.currentMapId !== meta.map_id ||
        robot.mapAssetHash?.toLowerCase() !== meta.asset_hash.toLowerCase() ||
        pose.mapId !== meta.map_id || pose.frameId !== meta.frame_id ||
        !Number.isFinite(age) || age < -1000 || age > 3000 || receivedAgeMs < 0 || receivedAgeMs > 3000 ||
        Math.max(0, age) + receivedAgeMs > 3000 ||
        ![pose.xM, pose.yM, pose.yawRad].every(Number.isFinite) || !inside(meta, pose.xM, pose.yM)) return null;
    return pose;
  }
  function workflowContract(detail) {
    const w = detail?.workflow;
    if (!w || !Array.isArray(detail.steps) || !Array.isArray(detail.transitions)) return null;
    const steps = detail.steps, code = id => steps.find(s => s.stepId === id)?.stepCode;
    return {
      serviceType: w.serviceType, schemaVersion: w.schemaVersion, graphVersion: w.graphVersion,
      maxTotalVisits: w.maxTotalVisits, inputSchemaJson: w.inputSchemaJson,
      defaultInputsJson: w.defaultInputsJson, requiredCapabilitiesJson: w.requiredCapabilitiesJson,
      startStepCode: code(w.startStepId),
      steps: steps.map(s => ({stepType: s.stepTypeCode, stepCode: s.stepCode, paramsJson: s.paramsJson,
        inputMappingJson: s.inputMappingJson, timeoutSec: s.timeoutSec, maxAttempts: s.maxAttempts,
        retryDelaySec: s.retryDelaySec, retryCodesJson: s.retryCodesJson, maxVisits: s.maxVisits})),
      transitions: detail.transitions.map(t => ({fromStepCode: code(t.fromStepId),
        toStepCode: t.toStepId == null ? null : code(t.toStepId), outcome: t.outcome,
        conditionJson: t.conditionJson ?? null, priority: t.priority,
        defaultTransition: t.defaultTransition, terminalStatus: t.terminalStatus ?? null}))
        .sort((a, b) => a.outcome.localeCompare(b.outcome))
    };
  }
  function templateContract(template) {
    const steps = template.graph.steps.map(s => ({...s, stepId: s.stepCode, stepTypeCode: s.stepType}));
    return workflowContract({workflow: {...template.workflow, startStepId: template.graph.startStepCode}, steps,
      transitions: template.graph.transitions.map(t => ({...t, fromStepId: t.fromStepCode, toStepId: t.toStepCode ?? null}))});
  }

  function mount(options) {
    const host = options.element;
    host.innerHTML = `
      <h2>Endpoint và dẫn đường trên map đã lưu</h2>
      <p>Chọn vị trí và hướng trên map. BE thực thi workflow có step <code>MOVE</code> qua MQTT; Pi dùng Nav2 để đi tới đích.</p>
      <div class="nav-inline"><label>Map ID<input id="navMapId" value="${DEFAULT_MAP}"></label>
      <button id="navLoad">Tải map và endpoint</button><button id="navUseSaved">Dùng map vừa lưu ở trên</button></div>
      <small id="navMapInfo">Dùng token ADMIN ở đầu trang. Xem và đặt điểm không tự chạy robot.</small>
      <button id="navActivate" disabled>Kích hoạt map trên BE</button>
      <button id="navNormalize" disabled>Tạo bản map sửa ô xám cho Nav2</button>
      <button id="navAssign" disabled>Mở phiên dùng map tạm cho robot</button>
      <button id="navCloseSession" disabled>Đóng phiên map tạm</button>
      <small>Map và endpoint nằm trong thư viện; robot dùng map này trong phiên 2 giờ, ràng buộc với một lần chạy Pi. Đổi nơi test: đóng phiên rồi chọn map khác.</small>
      <pre id="navPiCommand">Mở phiên để lấy lệnh khởi động Pi.</pre>
      <p id="navSessionStatus" role="status">Chưa có phiên định vị.</p>
      <h3>Khởi tạo vị trí robot</h3>
      <label><input type="checkbox" id="navSeedMode"> Bấm/kéo trên map để đặt vị trí và hướng KHỞI TẠO, thay vì endpoint</label>
      <div class="fields"><label>X dự kiến (m)<input id="navSeedX" type="number" step="any"></label><label>Y dự kiến (m)<input id="navSeedY" type="number" step="any"></label>
      <label>Hướng dự kiến (độ)<input id="navSeedYaw" type="number" min="-180" max="180" step="1" value="0"></label></div>
      <button id="navSendSeed" disabled>Gửi vị trí khởi tạo cho AMCL</button><button id="navGlobal" disabled>Yêu cầu tự tìm vị trí toàn cục</button>
      <small>Mũi tên khởi tạo là ước lượng do người dùng đặt. Chỉ mũi tên xanh khi Pi xác minh READY mới là vị trí robot. Phòng đối xứng hoặc ít đặc trưng có thể cần đặt vị trí khởi tạo.</small>
      <div class="nav-layout"><div>
        <div class="nav-map-wrap"><canvas id="navCanvas" width="640" height="360" hidden aria-label="Bản đồ chọn vị trí và hướng endpoint"></canvas></div>
        <div class="nav-legend"><span style="--marker:#0879ee">Robot còn dữ liệu mới</span><span style="--marker:#157347">Endpoint đã lưu</span><span style="--marker:#dc7400">Điểm đang đặt</span></div>
        <div id="navPose" class="nav-status" role="status">Chưa có vị trí robot trên map này.</div>
        <small id="navRobotStatus">Chưa theo dõi robot.</small>
      </div><div>
        <label>Endpoint đã lưu<select id="navEndpoints"><option value="">Chọn endpoint</option></select></label>
        <button id="navNewPoint">Đặt endpoint mới</button>
        <small>Đặt điểm mới: bấm trên vùng sàn đã quét, kéo rồi thả để chọn hướng. Chọn endpoint trong danh sách để sửa hoặc chạy.</small>
        <label>Mã endpoint<input id="navPointCode" maxlength="64" placeholder="DEMO_POINT_A"></label>
        <label>Tên endpoint<input id="navPointName" maxlength="160" placeholder="Điểm trước cửa phòng"></label>
        <div class="fields"><label>X (m)<input id="navX" type="number" step="any"></label><label>Y (m)<input id="navY" type="number" step="any"></label></div>
        <label>Hướng thân robot trên map (độ)<input id="navYaw" type="number" step="1" min="-180" max="180" value="0"></label>
        <small>0° theo +X map; 90° theo +Y. Mũi tên thể hiện hướng thân robot; cần hiệu chuẩn góc lắp camera.</small>
        <button id="navSavePoint" disabled>Lưu endpoint</button>
        <small id="navPointId">Chưa chọn điểm. Endpoint mới dùng loại WAYPOINT, active và accessible.</small>
      </div></div>
      <h3>Chạy workflow MOVE</h3>
      <label>Workflow NAVIGATION một step MOVE<input id="navWorkflowId" readonly></label>
      <button id="navPrepare">Chuẩn bị workflow MOVE</button>
      <small>Tốc độ tối đa của workflow mẫu là 0,05 m/s. Không tạo StepType mới; dùng MOVE đã có.</small>
      <button id="navRefresh">Kiểm tra vị trí và trạng thái</button>
      <button id="navMove" disabled>Đi tới endpoint bằng robot của phiên</button>
      <button id="navCancel" disabled>Hủy yêu cầu dẫn đường</button>
      <small>MOVE được gắn đúng robot, map và phiên định vị. Vị trí hoặc hướng endpoint thay đổi cần lưu trước; vị trí robot luôn lấy từ Pi.</small>
      <p id="navAction" class="nav-status" role="status">Tải map để bắt đầu.</p>
      <details><summary>Request, job và kết quả step MOVE</summary><pre id="navReceipt">Chưa tạo yêu cầu.</pre></details>
      <details><summary>JSON endpoint để dùng Swagger</summary><pre id="navSwagger">Chọn vị trí trên map để lấy JSON.</pre></details>`;
    const $ = id => host.querySelector('#' + id), canvas = $('navCanvas'), ctx = canvas.getContext('2d');
    const state = {meta: null, map: null, image: null, imageUrl: null, endpoints: [], draft: null, editing: null,
      snapshot: null, receivedAt: 0, robotId: '', busy: false, generation: 0, timer: null, closed: false,
      workflow: null, template: null, pending: null, job: null, request: null, dragging: null,seed:null};
    const say = (message, error = false) => { $('navAction').textContent = message; $('navAction').classList.toggle('nav-error', error); };
    const active = () => state.pending && !state.pending.done;
    function persist() {
      try { if (state.pending) sessionStorage.setItem('mar-navigation-request', JSON.stringify(state.pending));
        else sessionStorage.removeItem('mar-navigation-request'); } catch (_) { /* In-memory retry remains available. */ }
    }
    try {
      const saved = JSON.parse(sessionStorage.getItem('mar-navigation-request') || 'null');
      if (saved && UUID.test(saved.body?.mapId || '') && saved.key && !saved.done) {
        state.pending = saved; $('navMapId').value = saved.body.mapId;
        say('Có yêu cầu đang theo dõi. Tải map để kiểm tra; nút tiếp tục dùng lại Idempotency-Key cũ.');
      }
    } catch (_) { /* Ignore corrupt UI state. */ }
    function robotId() {
      const selectedId=options.getRobotId().trim(),id=state.robotId||selectedId;
      if(selectedId!==id&&!active())throw Error('Robot ID đã đổi; tải lại map để theo dõi đúng robot.');
      if (!UUID.test(id)) throw Error('Robot ID không hợp lệ.'); return id;
    }
    function mapId() { if (!state.meta || $('navMapId').value.trim() !== state.meta.map_id) throw Error('Tải lại Map ID đã chọn trước.'); return state.meta.map_id; }
    async function api(path, {method = 'GET', body, raw = false, headers = {}} = {}) {
      const token = options.getToken().trim().replace(/^Bearer\s+/i, '');
      if (!token) throw Error('Cần access token ADMIN ở đầu trang.');
      const response = await fetch(path, {method, headers: {Authorization: 'Bearer ' + token,
        ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...headers},
        body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store', signal: AbortSignal.timeout(10000)});
      if (!response.ok) { const error = Error(response.status + ' ' + (await response.text()).slice(0, 500)); error.status = response.status; throw error; }
      return raw ? response : response.status === 204 ? null : response.json();
    }
    async function pages(path) {
      const result = [];
      for (let page = 0; page < 100; page++) {
        const data = await api(path + (path.includes('?') ? '&' : '?') + 'page=' + page + '&size=100');
        if (!Array.isArray(data.content)) throw Error('BE trả danh sách không hợp lệ.');
        result.push(...data.content);
        if (data.last === true || page + 1 >= data.totalPages) return result;
      }
      throw Error('Danh sách quá lớn; chưa tải đủ để thao tác chính xác.');
    }
    function selected() { return state.endpoints.find(e => e.endpointId === $('navEndpoints').value); }
    function pose() { return state.meta ? freshPose(state.snapshot, state.meta, performance.now() - state.receivedAt) : null; }
    function controls() {
      const loaded = !!state.meta && $('navMapId').value.trim() === state.meta.map_id;
      const locked = state.busy || !!active();
      $('navLoad').disabled = state.busy || !!(active() && state.meta);
      $('navUseSaved').disabled = locked;
      $('navMapId').disabled = locked;
      $('navActivate').disabled = !loaded || locked || state.map?.status === 'ACTIVE';
      $('navAssign').disabled = !loaded || locked || state.map?.status !== 'ACTIVE';
      const session=state.snapshot?.navigationSession,open=session&&['WAITING_FOR_PI','LOCALIZING','READY'].includes(session.state)&&session.mapId===state.meta?.map_id;
      $('navAssign').disabled ||= !!open || state.meta?.navigation_reload_safe===false;
      $('navNormalize').disabled=!loaded||locked||state.meta?.navigation_reload_safe!==false;
      $('navCloseSession').disabled=locked||!open;
      $('navSendSeed').disabled=locked||!open||!state.seed;
      $('navGlobal').disabled=locked||!open;
      for(const id of ['navSeedMode','navSeedX','navSeedY','navSeedYaw'])$(id).disabled=!loaded||locked;
      for (const id of ['navNewPoint', 'navEndpoints', 'navPointCode', 'navPointName', 'navX', 'navY', 'navYaw']) $(id).disabled = !loaded || locked;
      $('navSavePoint').disabled = !loaded || locked || !state.draft;
      $('navPrepare').disabled = locked;
      $('navMove').disabled = state.busy || !loaded || (active() ? !state.pending.retryable : !selected() || !state.workflow || session?.state!=='READY'||!pose());
      $('navMove').textContent = active() && state.pending.retryable ? 'Tiếp tục yêu cầu vừa gửi' : 'Đi tới endpoint bằng robot của phiên';
      $('navCancel').disabled = state.busy || !active() || !state.pending.requestId;
    }
    async function mutate(operation) {
      if (state.busy) return;
      state.busy = true; controls();
      try { await operation(); } catch (error) { say(error.message, true); }
      finally { state.busy = false; controls(); }
    }
    function endpointBody() {
      mapId();
      if (['navX', 'navY', 'navYaw'].some(id => $(id).value.trim() === '')) throw Error('Cần X, Y và hướng endpoint.');
      const xM = Number($('navX').value), yM = Number($('navY').value), degrees = Number($('navYaw').value);
      if (!Number.isFinite(degrees) || degrees < -180 || degrees > 180 || !inside(state.meta, xM, yM)) throw Error('Điểm phải nằm trong map; hướng từ -180° đến 180°.');
      const old = state.editing;
      return {mapId: state.meta.map_id, zoneId: old?.zoneId ?? null, roomId: old?.roomId ?? null,
        endpointCode: $('navPointCode').value.trim(), endpointName: $('navPointName').value.trim(),
        endpointType: old?.endpointType ?? 'WAYPOINT', xM, yM, yawRad: angle(degrees * Math.PI / 180),
        active: old?.active ?? true, accessible: old?.accessible ?? true, publicVisible: old?.publicVisible ?? false,
        description: old?.description ?? 'Đặt trên map đã lưu qua trang điều khiển'};
    }
    function updateDraft() {
      if (!state.meta) return;
      try { const body = endpointBody(); state.draft = body; $('navSwagger').textContent = JSON.stringify(body, null, 2); }
      catch (error) { state.draft = null; $('navSwagger').textContent = error.message; }
      controls(); draw();
    }
    function arrow(x, y, yaw, color, label, radius) {
      const p = mapToPixel(state.meta, x, y), direction = -(yaw - state.meta.origin_yaw_rad);
      ctx.save(); ctx.translate(p.u, p.v); ctx.rotate(direction);
      ctx.fillStyle = color; ctx.strokeStyle = '#fff'; ctx.lineWidth = .6;
      ctx.beginPath(); ctx.arc(0, 0, radius, 0, 2 * Math.PI); ctx.fill(); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(radius * 2, 0); ctx.lineTo(radius * .2, -radius * .7); ctx.lineTo(radius * .2, radius * .7); ctx.closePath(); ctx.fill(); ctx.stroke();
      ctx.restore();
      if (label) { ctx.fillStyle = color; ctx.font = Math.max(3, radius) + 'px system-ui'; ctx.fillText(label, p.u + radius + 1, p.v - radius - 1); }
    }
    function draw() {
      if (!state.image || !state.meta) return;
      ctx.imageSmoothingEnabled = false; ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(state.image, 0, 0, canvas.width, canvas.height);
      const radius = Math.max(1.5, .07 / state.meta.resolution_m_per_pixel);
      for (const e of state.endpoints) if (inside(state.meta, e.xM, e.yM)) arrow(e.xM, e.yM, e.yawRad, e.endpointId === selected()?.endpointId ? '#7427bd' : '#157347', e.endpointCode, radius);
      if (state.draft) arrow(state.draft.xM, state.draft.yM, state.draft.yawRad, '#dc7400', '', radius);
      if(state.seed)arrow(state.seed.xM,state.seed.yM,state.seed.yawRad,'#bd7515','Ước lượng khởi tạo',radius*1.4);
      const current = pose();
      if (current) {
        arrow(current.xM, current.yM, current.yawRad, '#0879ee', '', radius * 1.6);
        $('navPose').textContent = `Robot ${current.robotId}\nX=${current.xM.toFixed(3)} m · Y=${current.yM.toFixed(3)} m · hướng ${(current.yawRad * 180 / Math.PI).toFixed(1)}°`;
      } else $('navPose').textContent = 'Chưa có vị trí mới, đúng map và hash đã chọn. Cần Pi nạp map, định vị AMCL và gửi pose mới; không hiển thị pose cũ.';
    }
    function showEndpoints(id = '') {
      $('navEndpoints').replaceChildren(new Option('Chọn endpoint', ''));
      for (const e of state.endpoints) $('navEndpoints').add(new Option(e.endpointCode + ' — ' + e.endpointName + (e.active && e.accessible ? '' : ' (chưa khả dụng)'), e.endpointId));
      $('navEndpoints').value = id;
    }
    function clearPoint() {
      state.draft = state.editing = null; $('navEndpoints').value = '';
      for (const id of ['navX', 'navY', 'navPointCode', 'navPointName']) $(id).value = '';
      $('navYaw').value = '0'; $('navPointId').textContent = 'Đang đặt endpoint mới. Bấm rồi kéo trên map để chọn hướng.';
      $('navSwagger').textContent = 'Chọn vị trí trên map để lấy JSON.'; controls(); draw();
    }
    async function endpoints(id = '') {
      state.endpoints = (await pages('/api/v1/endpoints')).filter(e => e.mapId === state.meta.map_id);
      showEndpoints(id); draw();
    }
    async function loadMap() {
      if (active() && state.meta) throw Error('Theo dõi/hủy yêu cầu hiện tại trước khi đổi map.');
      const id = $('navMapId').value.trim(); if (!UUID.test(id)) throw Error('Map ID không hợp lệ.');
      const generation = ++state.generation; clearTimeout(state.timer);
      state.seed=null;state.dragging=null;
      for(const field of ['navSeedX','navSeedY'])$(field).value='';
      state.meta = state.map = state.image = state.snapshot = null; canvas.hidden = true; clearPoint();
      if (state.imageUrl) URL.revokeObjectURL(state.imageUrl); state.imageUrl = null;
      const [map, meta, response] = await Promise.all([api('/api/v1/maps/' + id), api('/api/v1/maps/' + id + '/image-metadata'),
        api('/api/v1/maps/' + id + '/image.png', {raw: true, headers: {Accept: 'image/png'}})]);
      geometry(meta);
      if (meta.map_id !== id || map.mapId !== id || map.assetHash?.toLowerCase() !== meta.asset_hash.toLowerCase() ||
          response.headers.get('X-Map-Asset-Hash')?.toLowerCase() !== meta.asset_hash.toLowerCase() ||
          response.headers.get('Content-Type')?.split(';')[0] !== 'image/png') throw Error('Ảnh và metadata không cùng map/hash.');
      const url = URL.createObjectURL(await response.blob()), image = new Image();
      try { image.src = url; await image.decode(); } catch (error) { URL.revokeObjectURL(url); throw error; }
      if (generation !== state.generation) { URL.revokeObjectURL(url); return; }
      if (image.naturalWidth !== meta.width_px || image.naturalHeight !== meta.height_px) { URL.revokeObjectURL(url); throw Error('Kích thước PNG khác metadata.'); }
      state.meta = meta; state.map = map; state.image = image; state.imageUrl = url;
      state.robotId = options.getRobotId().trim();
      canvas.width = meta.width_px; canvas.height = meta.height_px;
      canvas.style.width = 'min(100%, ' + Math.max(180, 650 * meta.width_px / meta.height_px) + 'px)'; canvas.hidden = false;
      $('navMapInfo').textContent = `${map.mapName} · ${map.status} · ${meta.width_px} × ${meta.height_px} ô · ${(meta.resolution_m_per_pixel * 100).toFixed(1)} cm/ô · frame ${meta.frame_id}`;
      await endpoints();
      if (state.pending?.body.mapId === id) {
        $('navEndpoints').value = state.pending.body.destinationEndpointId;
        if (selected()) $('navEndpoints').onchange();
      }
      say('Đã tải map thật. Chọn endpoint đã lưu hoặc bấm/kéo để đặt điểm mới.');
      controls(); draw(); poll(generation);
    }
    async function snapshot() {
      const value = await api('/api/v1/robots/' + robotId() + '/snapshot');
      if (value.robot?.robotId !== robotId()) throw Error('Snapshot không đúng robot.');
      state.snapshot = value; state.receivedAt = performance.now();
      const session=value.navigationSession;
      $('navSessionStatus').textContent=session?.sessionId?`Phiên ${session.sessionId} · ${session.state} · ${session.localization?.reason||'chờ Pi nạp map và xác minh vị trí'} · hết hạn ${session.expiresAt}`:'Chưa mở phiên map tạm.';
      $('navPiCommand').textContent=session?.sessionId&&session.mapId===state.meta?.map_id&&['WAITING_FOR_PI','LOCALIZING','READY'].includes(session.state)?'Trong container, sau khi dừng robot.zsh run:\ncd /home/ubuntu/mediassist-mqtt-2026-10-08\nzsh tools/navigation_session.zsh --session-id '+session.sessionId:'Mở phiên để lấy lệnh khởi động Pi.';
      $('navRobotStatus').textContent = `Robot theo dõi: ${value.robot.robotId} · ${value.robot.status} · MQTT ${value.mqttConnected ? 'đã nối' : 'chưa nối'} · dispatch ${value.dispatchReady ? 'sẵn sàng' : 'chưa sẵn sàng'}${value.sessionFailureReason ? ' · ' + value.sessionFailureReason : ''}`;
      controls();draw(); return value;
    }
    async function trackRequest() {
      const p = state.pending; if (!p?.requestId) return;
      const [request, jobs] = await Promise.all([api('/api/v1/service-requests/' + p.requestId), pages('/api/v1/jobs?requestId=' + p.requestId)]);
      if (p !== state.pending) return;
      state.request = request;
      if (jobs.some(j => j.serviceRequestId !== p.requestId)) throw Error('BE trả job khác request đang theo dõi.');
      const job = jobs[0]; let detail = null;
      if (job) {
        detail = await api('/api/v1/jobs/' + job.jobId); state.job = detail.job;
        if (detail.job.serviceRequestId !== p.requestId) throw Error('Chi tiết job khác request đang theo dõi.');
        if (detail.job.robotId) state.robotId = detail.job.robotId;
        if(p.body.robotId&&detail.job.robotId&&detail.job.robotId!==p.body.robotId)throw Error('Job khác robot của phiên đã chọn; kiểm tra BE trước khi tiếp tục.');
        if (TERMINAL.has(detail.job.status)) { p.done = true; p.retryable = false; persist(); }
        else if (detail.job.status === 'RECOVERY_REQUIRED') say('Job cần khôi phục. Không tạo thêm MOVE; xem errorCode và bằng chứng dừng trong chi tiết.', true);
      } else if (['CANCELLED', 'FAILED'].includes(request.status)) { p.done = true; p.retryable = false; persist(); }
      $('navReceipt').textContent = JSON.stringify({request, job: detail?.job ?? null, steps: detail?.steps ?? []}, null, 2);
      if (p.done) say('Yêu cầu ' + p.requestId + ' · Job ' + (detail?.job.status ?? request.status) + (detail?.job.errorCode ? ' · ' + detail.job.errorCode : ''));
      controls();
    }
    async function poll(generation) {
      if (state.closed || generation !== state.generation || !state.meta) return;
      try { await trackRequest(); await snapshot(); }
      catch (error) { state.snapshot = null; $('navRobotStatus').textContent = 'Chưa cập nhật được: ' + error.message; draw();
        if (error.status === 401 || error.status === 403) return; }
      if (!state.closed && generation === state.generation) state.timer = setTimeout(() => poll(generation), 1000);
    }
    async function template() { if (!state.template) state.template = await api('/navigation-move-workflow.json'); return state.template; }
    async function requireWorkflow(id) {
      if (!UUID.test(id || '')) throw Error('Chuẩn bị workflow MOVE trước.');
      const detail = await api('/api/v1/admin/workflows/' + id), expected = templateContract(await template());
      if (stable(workflowContract(detail)) !== stable(expected) || detail.workflow.status !== 'PUBLISHED' || !detail.workflow.acceptsNewJobs) throw Error('Workflow phải đã publish, nhận job mới và đúng hợp đồng một step MOVE của trang này.');
      state.workflow = detail; $('navWorkflowId').value = id; return detail;
    }
    async function prepareWorkflow() {
      const definition = await template(), code = definition.workflow.workflowCode;
      const candidates = (await pages('/api/v1/admin/workflows')).filter(w => w.workflowCode === code).sort((a, b) => b.versionNo - a.versionNo);
      const expected = stable(templateContract(definition)); let detail = null;
      for (const candidate of candidates) {
        const item = await api('/api/v1/admin/workflows/' + candidate.workflowId);
        if (stable(workflowContract(item)) === expected && (['DRAFT', 'VALIDATED'].includes(item.workflow.status) || item.workflow.status === 'PUBLISHED' && item.workflow.acceptsNewJobs)) { detail = item; break; }
      }
      if (!detail) {
        const body = JSON.parse(JSON.stringify(definition)); body.workflow.versionNo = Math.max(0, ...candidates.map(w => w.versionNo)) + 1;
        detail = await api('/api/v1/admin/workflows/graph', {method: 'POST', body});
      }
      const id = detail.workflow.workflowId;
      if (['DRAFT', 'VALIDATED'].includes(detail.workflow.status)) {
        await api('/api/v1/admin/workflows/' + id + '/validate', {method: 'POST', body: {}});
        await api('/api/v1/admin/workflows/' + id + '/publish', {method: 'POST', body: {}});
      }
      await requireWorkflow(id); say('Workflow MOVE đã sẵn sàng: ' + id + '. Chưa tạo job hay di chuyển robot.');
    }
    async function move() {
      if (options.isMappingActive()) throw Error('Kết thúc phiên quét trước khi chạy navigation.');
      let pending = state.pending;
      if (!pending || pending.done) {
        const id = mapId(), endpoint = selected();
        if (!endpoint?.active || !endpoint.accessible) throw Error('Chọn endpoint đã lưu, active và accessible.');
        if (state.editing) {
          const draft = endpointBody();
          if (Math.hypot(draft.xM - endpoint.xM, draft.yM - endpoint.yM) > 1e-8 || Math.abs(angle(draft.yawRad - endpoint.yawRad)) > 1e-8) {
            throw Error('Lưu thay đổi vị trí/hướng endpoint trước khi chạy MOVE.');
          }
        }
        // Refetch catalog and pose so stale browser state cannot authorize a MOVE.
        const [map, point] = await Promise.all([api('/api/v1/maps/' + id), api('/api/v1/endpoints/' + endpoint.endpointId)]);
        if (map.status !== 'ACTIVE' || map.assetHash?.toLowerCase() !== state.meta.asset_hash.toLowerCase()) throw Error('Map phải ACTIVE và đúng phiên bản đang hiển thị.');
        if (point.mapId !== id || !point.active || !point.accessible || !inside(state.meta, point.xM, point.yM)) throw Error('Endpoint đã đổi map/trạng thái hoặc nằm ngoài map.');
        if (stable([point.xM, point.yM, point.yawRad]) !== stable([endpoint.xM, endpoint.yM, endpoint.yawRad])) throw Error('Endpoint đã thay đổi. Tải lại map/endpoint trước khi chạy.');
        const detail = await requireWorkflow($('navWorkflowId').value), current = await snapshot();
        if (current.robot.status !== 'IDLE' || !current.dispatchReady || !pose()) throw Error('Robot chưa IDLE/sẵn sàng hoặc chưa có pose định vị mới đúng map và hash.');
        const session=current.navigationSession;
        if(session?.state!=='READY'||session.mapId!==id)throw Error('Phiên map chưa xác minh vị trí.');
        pending = {key: crypto.randomUUID(), body: {mapId: id, workflowId: detail.workflow.workflowId,robotId:robotId(),navigationSessionId:session.sessionId,
          destinationEndpointId: point.endpointId, targetMode: 'ENDPOINT', navigationMode: 'STANDARD',
          passengerCount: 1, priority: 50, note: 'Đi tới endpoint từ bản đồ đã lưu', data: {}}, retryable: true, done: false};
        state.pending = pending; persist();
      }
      try {
        if (!pending.requestId) {
          const created = await api('/api/v1/navigation-requests', {method: 'POST', body: pending.body, headers: {'Idempotency-Key': pending.key}});
          if (!UUID.test(created.request?.serviceRequestId || '')) throw Error('BE chưa trả serviceRequestId. Giữ nguyên yêu cầu để thử lại.');
          pending.requestId = created.request.serviceRequestId; persist();
        }
        const request = await api('/api/v1/service-requests/' + pending.requestId);
        if (request.status === 'DRAFT') await api('/api/v1/service-requests/' + pending.requestId + '/submit', {method: 'POST', body: {}});
        pending.retryable = false; persist();
        say('Đã gửi yêu cầu ' + pending.requestId + '. BE phân công robot và dispatch step MOVE; đang chờ phản hồi Pi.');
        await trackRequest(); await snapshot();
      } catch (error) { pending.retryable = true; persist(); throw error; }
    }

    $('navLoad').onclick = () => mutate(loadMap);
    $('navUseSaved').onclick = () => mutate(async () => { const id = options.getSavedMapId(); if (!UUID.test(id)) throw Error('Xem map đã lưu của job ở trên trước.'); $('navMapId').value = id; await loadMap(); });
    $('navNewPoint').onclick = clearPoint;
    $('navEndpoints').onchange = () => {
      const point = selected(); if (!point) return clearPoint();
      state.editing = point;
      for (const [id, value] of Object.entries({navX: point.xM, navY: point.yM, navYaw: point.yawRad * 180 / Math.PI, navPointCode: point.endpointCode, navPointName: point.endpointName})) $(id).value = value;
      $('navPointId').textContent = 'Endpoint ID: ' + point.endpointId; updateDraft();
    };
    for (const id of ['navX', 'navY', 'navYaw', 'navPointCode', 'navPointName']) $(id).addEventListener('input', updateDraft);
    $('navSavePoint').onclick = () => mutate(async () => {
      if (active()) throw Error('Không sửa endpoint trong khi yêu cầu đang chạy.');
      const body = endpointBody(); if (!body.endpointCode || !body.endpointName) throw Error('Cần mã và tên endpoint.');
      await api('/api/v1/maps/'+mapId()+'/validate-navigation-point',{method:'POST',body:{xM:body.xM,yM:body.yM}});
      const existing = state.editing;
      const saved = await api('/api/v1/endpoints' + (existing ? '/' + existing.endpointId : ''), {method: existing ? 'PUT' : 'POST', body});
      await endpoints(saved.endpointId); $('navEndpoints').onchange();
      say('Đã lưu endpoint ' + saved.endpointId + '. Chưa gửi MOVE; Nav2 sẽ kiểm tra khả năng đi tới khi chạy.');
    });
    $('navActivate').onclick = () => mutate(async () => {
      if (options.isMappingActive()) throw Error('Kết thúc phiên quét trước khi đổi map active.');
      await api('/api/v1/maps/' + mapId() + '/activate', {method: 'POST', body: {}});
      await loadMap(); say('Map đã ACTIVE trên BE. Cần gán map và nạp vào Pi trước khi chạy.');
    });
    $('navAssign').onclick = () => mutate(async () => {
      if (options.isMappingActive()) throw Error('Kết thúc phiên quét trước khi gán map.');
      state.robotId = options.getRobotId().trim(); const id = robotId();
      const current = await api('/api/v1/robots/' + id + '/snapshot');
      if (current.activeJobId) throw Error('Robot còn job đang chạy/khôi phục. Chưa đổi map.');
      await api('/api/v1/robots/'+id+'/navigation-session',{method:'POST',body:{mapId:mapId(),durationSec:7200}});
      await snapshot();say('Đã mở phiên map tạm. Chạy lệnh Pi bên trên, rồi chọn vị trí khởi tạo hoặc tìm toàn cục.');
    });
    $('navNormalize').onclick=()=>mutate(async()=>{
      const copy=await api('/api/v1/maps/'+mapId()+'/navigation-copy',{method:'POST',body:{}});
      $('navMapId').value=copy.mapId;await loadMap();say('Đã tạo bản map có ngưỡng unknown đúng và sao chép endpoint. Kích hoạt map mới trước khi mở phiên.');
    });
    $('navCloseSession').onclick=()=>mutate(async()=>{
      const session=state.snapshot?.navigationSession;if(!session?.sessionId)throw Error('Không có phiên đang mở.');
      await api('/api/v1/robots/'+robotId()+'/navigation-session/close',{method:'POST',body:{sessionId:session.sessionId}});
      await snapshot();say('Đã đóng phiên. Dừng terminal navigation; robot.zsh run bình thường vẫn dùng cấu hình quét map ban đầu.');
    });
    function updateSeed(){
      const values=['navSeedX','navSeedY','navSeedYaw'].map(id=>$(id).value.trim());
      const [x,y,yaw]=values.map(Number);state.seed=values.every(Boolean)&&inside(state.meta||{},x,y)&&Number.isFinite(yaw)&&Math.abs(yaw)<=180?{xM:x,yM:y,yawRad:yaw*Math.PI/180}:null;
      controls();draw();
    }
    for(const id of ['navSeedX','navSeedY','navSeedYaw'])$(id).addEventListener('input',updateSeed);
    async function initialize(mode){
      const session=state.snapshot?.navigationSession;if(!session?.sessionId||session.mapId!==mapId())throw Error('Mở phiên của đúng map trước.');
      await api('/api/v1/robots/'+robotId()+'/navigation-session/initialization',{method:'POST',body:{sessionId:session.sessionId,mode,...(mode==='SEED'?state.seed:{})}});
      state.snapshot=null;draw();await snapshot();say('Đã yêu cầu khởi tạo; chờ AMCL xác minh. Chưa gửi MOVE.');
    }
    $('navSendSeed').onclick=()=>mutate(()=>initialize('SEED'));
    $('navGlobal').onclick=()=>mutate(()=>initialize('GLOBAL'));
    $('navPrepare').onclick = () => mutate(prepareWorkflow);
    $('navMove').onclick = () => mutate(move);
    $('navCancel').onclick = () => mutate(async () => {
      if (!state.pending?.requestId || state.pending.done) throw Error('Chưa có yêu cầu đang chạy để hủy.');
      await api('/api/v1/service-requests/' + state.pending.requestId + '/cancel', {method: 'POST', body: {reason: 'Người vận hành hủy dẫn đường từ trang map'}});
      state.pending.retryable = false; persist();
      say('BE đã nhận yêu cầu hủy. Chờ job/Pi xác nhận dừng; không coi HTTP thành công là robot đã dừng.');
      await trackRequest();
    });
    $('navRefresh').onclick = () => mutate(async () => {
      mapId(); await trackRequest(); await snapshot();
      clearTimeout(state.timer); state.timer = setTimeout(() => poll(state.generation), 1000);
      say(pose() ? 'Đã nhận vị trí mới đúng map.' : 'Chưa có pose hợp lệ trên map đã chọn; xem trạng thái robot.');
    });
    const pointer = event => { const box = canvas.getBoundingClientRect(); return {u: (event.clientX - box.left) * canvas.width / box.width, v: (event.clientY - box.top) * canvas.height / box.height}; };
    canvas.addEventListener('pointerdown', event => {
      if (!state.meta || state.busy || active() || event.button !== 0) return;
      const p = pointer(event); if (p.u < 0 || p.v < 0 || p.u >= canvas.width || p.v >= canvas.height) return;
      if($('navSeedMode').checked){
        const point=pixelToMap(state.meta,Math.floor(p.u)+.5,Math.floor(p.v)+.5);
        $('navSeedX').value=point.xM;$('navSeedY').value=point.yM;
        state.dragging={point,x:event.clientX,y:event.clientY,seed:true};canvas.setPointerCapture(event.pointerId);updateSeed();return;
      }
      clearPoint(); const point = pixelToMap(state.meta, Math.floor(p.u) + .5, Math.floor(p.v) + .5);
      $('navX').value = point.xM; $('navY').value = point.yM;
      state.dragging = {point, x: event.clientX, y: event.clientY}; canvas.setPointerCapture(event.pointerId); updateDraft();
    });
    canvas.addEventListener('pointermove', event => {
      if (!state.dragging) return;
      if (Math.hypot(event.clientX - state.dragging.x, event.clientY - state.dragging.y) < 6) return;
      const p = pointer(event), end = pixelToMap(state.meta, p.u, p.v), start = state.dragging.point;
      if(state.dragging.seed){$('navSeedYaw').value=(Math.atan2(end.yM-start.yM,end.xM-start.xM)*180/Math.PI).toFixed(2);updateSeed();return;}
      $('navYaw').value = (Math.atan2(end.yM - start.yM, end.xM - start.xM) * 180 / Math.PI).toFixed(2); updateDraft();
    });
    const release = () => { state.dragging = null; };
    canvas.addEventListener('pointerup', release); canvas.addEventListener('pointercancel', release);
    $('navMapId').addEventListener('input', () => {
      state.generation++; clearTimeout(state.timer); state.meta = state.image = state.snapshot = null; canvas.hidden = true;
      state.endpoints = []; showEndpoints(); clearPoint(); $('navMapInfo').textContent = 'Map ID đã đổi; tải map trước khi thao tác.';
      state.seed=null;for(const id of ['navSeedX','navSeedY'])$(id).value='';
      $('navPose').textContent = 'Chưa tải map mới.'; controls();
    });
    const expiryTimer = setInterval(draw, 250);
    window.addEventListener('pagehide', () => { state.closed = true; state.generation++; clearTimeout(state.timer); clearInterval(expiryTimer); if (state.imageUrl) URL.revokeObjectURL(state.imageUrl); });
    controls();
    return {loadMap, state};
  }
  return {geometry, pixelToMap, mapToPixel, inside, freshPose, workflowContract, templateContract, mount};
});
