'use strict';
const el = id => document.getElementById(id);
let data = {items: [], revision: '', active: 0, total: 0};
let selectedId = null;

function notice(message, error = false) {
  el('notice').hidden = false;
  el('notice').textContent = message;
  el('notice').className = 'notice' + (error ? ' error' : '');
}
async function api(path, options = {}) {
  const response = await fetch('/api/faqs' + path, options);
  const value = await response.json();
  if (!response.ok) {
    const message = Array.isArray(value.detail)
      ? value.detail.map(item => item.msg).join('; ') : value.detail;
    throw new Error(message || 'Không thực hiện được yêu cầu.');
  }
  return value;
}
function published(item) { return item.approved && item.visibility === 'PUBLIC'; }
function status(item) { return published(item) ? 'Đang dùng' : item.visibility === 'INTERNAL' ? 'Nội bộ' : 'Bản nháp'; }
function updateSaveLabel() {
  el('save-faq').textContent = el('approved').checked && el('visibility').value === 'PUBLIC' ? 'Lưu và bật FAQ' : 'Lưu bản nháp';
}
function renderList() {
  el('data-location').textContent = data.storage_file ? 'Nơi lưu dữ liệu: ' + data.storage_file : 'Chưa xác định được nơi lưu dữ liệu.';
  el('active-count').textContent = String(data.active);
  el('total-count').textContent = data.total + ' câu đã lưu';
  const filter = el('filter').value.trim().toLocaleLowerCase('vi');
  const list = el('faq-list');
  list.replaceChildren();
  const items = data.items.filter(item => [...item.questions, item.answer].join(' ').toLocaleLowerCase('vi').includes(filter));
  if (!items.length) {
    const empty = document.createElement('p'); empty.className = 'empty';
    empty.textContent = data.total ? 'Không có FAQ khớp từ tìm kiếm.' : 'Chưa có FAQ. Bấm Thêm FAQ để tạo câu đầu tiên.';
    list.append(empty);
  }
  for (const item of items) {
    const button = document.createElement('button');button.type = 'button';
    button.className = 'faq-entry' + (item.id === selectedId ? ' selected' : '');
    button.setAttribute('aria-pressed', String(item.id === selectedId));
    const title = document.createElement('strong');title.textContent = item.questions[0];
    const badge = document.createElement('span');badge.className = 'badge ' + (published(item) ? 'active' : 'draft');badge.textContent = status(item);
    const source = document.createElement('small');source.textContent = item.source;
    button.append(title, badge, source);
    button.addEventListener('click', () => edit(item));list.append(button);
  }
}
function edit(item = null) {
  selectedId = item?.id || null;
  el('editor-title').textContent = item ? 'Kiểm tra và sửa FAQ' : 'Thêm FAQ';
  el('question').value = item?.questions[0] || '';
  el('aliases').value = item?.questions.slice(1).join('\n') || '';
  el('answer').value = item?.answer || '';
  el('source').value = item?.source || '';
  el('source-excerpt').value = item?.source_excerpt || '';
  el('visibility').value = item?.visibility || 'PUBLIC';
  el('approved').checked = Boolean(item?.approved);
  el('answer-length').textContent = String(el('answer').value.length);
  el('entry-status').textContent = item ? status(item) : 'Bản nháp';
  el('entry-status').className = 'badge ' + (item && published(item) ? 'active' : 'draft');
  el('delete-faq').hidden = !item;
  el('approval-hint').textContent = 'Bản nháp không được dùng để trả lời robot. FAQ cần được duyệt và đặt công khai để bật.';
  if (item) el('test-question').value = item.questions[0];
  updateSaveLabel();renderList();
}
async function load() {
  data = await api('');renderList();
}
el('new-faq').addEventListener('click', () => {edit();el('question').focus();});
el('filter').addEventListener('input', renderList);
el('approved').addEventListener('change', updateSaveLabel);
el('visibility').addEventListener('change', updateSaveLabel);
for (const id of ['question', 'aliases', 'answer', 'source', 'source-excerpt']) {
  el(id).addEventListener('input', () => {
    el('answer-length').textContent = String(el('answer').value.length);
    if (el('approved').checked) {
      el('approved').checked = false;
      el('approval-hint').textContent = 'Nội dung đã thay đổi. Kiểm tra lại nguồn rồi đánh dấu duyệt trước khi bật.';
      updateSaveLabel();
    }
  });
}
el('faq-form').addEventListener('submit', async event => {
  event.preventDefault();el('save-faq').disabled = true;
  const body = {
    questions: [el('question').value, ...el('aliases').value.split('\n').map(line => line.trim()).filter(Boolean)],
    answer: el('answer').value, source: el('source').value, source_excerpt: el('source-excerpt').value,
    approved: el('approved').checked, visibility: el('visibility').value, revision: data.revision
  };
  try {
    const before = new Set(data.items.map(item => item.id));
    const id = selectedId;
    data = await api(id ? '/' + encodeURIComponent(id) : '', {method: id ? 'PUT' : 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    const saved = data.items.find(item => id ? item.id === id : !before.has(item.id));
    edit(saved);
    notice(saved && published(saved) ? 'Đã bật FAQ. Có thể thử câu hỏi ở bên dưới ngay; không cần Re-index.' : 'Đã lưu bản nháp. FAQ này chưa được dùng để trả lời robot.');
  } catch (error) {notice(error.message, true);}
  finally {el('save-faq').disabled = false;}
});
el('delete-faq').addEventListener('click', async () => {
  if (!selectedId || !confirm('Xóa FAQ đang chọn khỏi kho câu trả lời?')) return;
  try {
    data = await api('/' + encodeURIComponent(selectedId) + '?revision=' + encodeURIComponent(data.revision), {method: 'DELETE'});
    edit();notice('Đã xóa FAQ.');
  } catch (error) {notice(error.message, true);}
});
el('test-form').addEventListener('submit', async event => {
  event.preventDefault();const result = el('test-result');result.hidden = false;result.textContent = 'Đang kiểm tra…';
  try {
    const value = await api('/test', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({question: el('test-question').value})});
    result.replaceChildren();
    const answer = document.createElement('div');
    answer.textContent = value.matched ? value.answer : value.routing === 'intent_handler'
      ? 'Câu này thuộc luồng chào, cấp cứu hoặc điều khiển. FAQ không thay thế luồng đó.'
      : 'Chưa khớp FAQ đang bật. Hãy kiểm tra trạng thái duyệt và thêm đúng cách hỏi vào danh sách.';
    const details = document.createElement('small');
    details.textContent = (value.matched ? 'Khớp FAQ đã duyệt. Nguồn: ' + value.sources.map(source => source.source).join(', ') + '. ' : '')
      + 'Tra FAQ: ' + value.processing_ms + ' ms. Không gọi AI; chưa gồm thu âm và phát loa.';
    result.append(answer, details);
  } catch (error) {result.textContent = error.message;}
});
load().then(() => {if (data.items.length) edit(data.items[0]);})
  .catch(error => notice('Chưa tải được FAQ: ' + error.message, true));
