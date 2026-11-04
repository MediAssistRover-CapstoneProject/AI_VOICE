/* Staff fall inbox. Data remains protected by existing STAFF/ADMIN API auth. */
"use strict";
function isNewFall(alert, seen) {
  return Boolean(alert && alert.alertId && alert.notificationType === "SUSPECTED_FALL"
    && alert.status === "OPEN" && alert.dataJson?.simulation !== true && !seen.has(alert.alertId));
}
if (typeof module !== "undefined") module.exports = { isNewFall };
if (typeof document !== "undefined") {
  const $ = id => document.getElementById(id);
  const seen = new Set();
  let timer = null, busy = false, generation = 0;
  async function api(path, options = {}) {
    const response = await fetch(path, { ...options, headers: {
      "Content-Type": "application/json", Authorization: "Bearer " + $("token").value,
      ...(options.headers || {}) } });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    return body;
  }
  function text(tag, value) { const el = document.createElement(tag); el.textContent = value; return el; }
  async function refresh() {
    if (busy || !$("token").value) return;
    busy = true;
    const current = generation;
    try {
      const page = await api("/api/v1/alerts?type=SUSPECTED_FALL&page=0&size=50");
      if (current !== generation) return;
      const alerts = page.content || [];
      $("alerts").replaceChildren();
      const newlyOpen = alerts.filter(a => isNewFall(a, seen));
      $("newAlert").textContent = newlyOpen.length
        ? `${newlyOpen.length} cảnh báo nghi té ngã mới. Cần xác minh và tiếp nhận.` : "";
      for (const alert of alerts) {
        seen.add(alert.alertId);
        const card = document.createElement("article"); card.className = "card stack";
        card.append(text("h3", "Nghi té ngã · " + alert.status), text("p", alert.message || "Cần kiểm tra hiện trường"));
        const data = alert.dataJson || {};
        card.append(text("p", `Robot: ${data.robot_code || "Chưa xác định"} · Khu vực: ${data.map_name || "Chưa xác định"}`));
        card.append(text("p", "Thời gian: " + (alert.createdAt ? new Date(alert.createdAt).toLocaleString("vi-VN") : "Chưa xác định")));
        card.append(text("small", `Alert: ${alert.alertId} · Sự cố: ${alert.incidentId || "Chưa liên kết"}`));
        if (alert.status === "OPEN" && alert.requireAck) {
          const button = text("button", "Xác nhận đã tiếp nhận");
          button.onclick = async () => {
            button.disabled = true;
            try { await api(`/api/v1/alerts/${encodeURIComponent(alert.alertId)}/acknowledge`, { method: "POST" }); await refresh(); }
            catch (error) { $("state").textContent = error.message; button.disabled = false; }
          };
          card.append(button);
        }
        $("alerts").append(card);
      }
      if (!alerts.length) $("alerts").append(text("p", "Chưa có cảnh báo phù hợp trong dữ liệu được phép xem."));
      $("state").textContent = "Đã cập nhật: " + new Date().toLocaleTimeString("vi-VN");
    } catch (error) { if (current === generation) $("state").textContent = error.message; }
    finally { busy = false; }
  }
  function connect() { if (timer) clearInterval(timer); generation++; seen.clear(); refresh(); timer = setInterval(refresh, 4000); }
  $("connect").onclick = connect;
  $("login").onclick = async () => {
    try {
      const response = await fetch("/api/v1/auth/login", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: $("username").value.trim(), password: $("password").value, deviceInfo: "Vision Staff Web" }) });
      const body = await response.json();
      if (!response.ok) throw new Error(body.message || "Đăng nhập thất bại");
      if (body.mustChangePassword) throw new Error("Tài khoản cần đổi mật khẩu trước. Mở trang Trợ lý Voice để đổi mật khẩu.");
      $("token").value = body.accessToken; $("password").value = ""; connect();
    } catch (error) { $("state").textContent = error.message; }
  };
  $("logout").onclick = () => { generation++; clearInterval(timer); timer = null; seen.clear();
    $("token").value = ""; $("password").value = ""; $("alerts").replaceChildren(); $("newAlert").textContent = ""; $("state").textContent = "Đã ngắt phiên."; };
}
