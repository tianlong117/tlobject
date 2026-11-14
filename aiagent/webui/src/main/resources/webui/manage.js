/* AI Agent 管理页面：工作空间文件列表 / 下载 / 单选或批量删除（独立页面） */
const $ = s => document.querySelector(s);
const state = { files: [], truncated: false, filter: '', selected: new Set() };

/** 与 app.js 同款：POST JSON；401 回登录态。 */
async function apiJson(path, body) {
  const opt = { method: 'POST', headers: { 'Content-Type': 'application/json' } };
  if (body !== undefined) opt.body = JSON.stringify(body);
  const resp = await fetch(path, opt);
  let data = null;
  try { data = await resp.json(); } catch (e) { /* 非 JSON */ }
  if (resp.status === 401) {
    showLogin();
    throw new Error(data && data.error ? data.error : '未登录');
  }
  if (!resp.ok || data === null) {
    throw new Error(data && data.error ? data.error : ('HTTP ' + resp.status));
  }
  return data;
}

function showLogin() {
  $('#manageApp').classList.add('hidden');
  $('#mLogin').classList.remove('hidden');
}

async function boot() {
  try {
    const s = await fetch('/api/session').then(r => r.json());
    if (!s || !s.loggedIn) { showLogin(); return; }
    $('#manageApp').classList.remove('hidden');
    $('#mUser').textContent = s.userId || '';
    await load();
  } catch (e) {
    showLogin();
  }
}

async function load() {
  state.selected.clear();
  try {
    const r = await apiJson('/api/workspace/list', {});
    state.files = Array.isArray(r.files) ? r.files : [];
    state.truncated = !!r.truncated;
    render();
  } catch (e) {
    notice('加载失败：' + e.message, true);
  }
}

function visibleFiles() {
  const filter = state.filter.trim().toLowerCase();
  return state.files.filter(f =>
    !filter || String(f.path || '').toLowerCase().includes(filter));
}

function render() {
  const files = visibleFiles();
  const filter = state.filter.trim().toLowerCase();
  const total = files.reduce((sum, f) => sum + (Number(f.size) || 0), 0);
  $('#mCount').textContent = '共 ' + files.length + ' 个文件' + (filter ? '（已筛选）' : '');
  $('#mSummary').textContent = '总大小 ' + fmtSize(total)
    + (state.truncated ? ' · 文件过多，仅显示最近 2000 个' : '');

  const list = $('#mList');
  list.innerHTML = '';
  if (!files.length) {
    const empty = document.createElement('div');
    empty.className = 'm-empty';
    empty.textContent = filter ? '没有匹配的文件' : '工作空间里还没有文件';
    list.appendChild(empty);
    updateSelectionUI();
    return;
  }

  const table = document.createElement('table');
  table.className = 'm-table';
  table.innerHTML = '<thead><tr>'
    + '<th class="m-check-col"><input type="checkbox" id="mSelectAll" title="全选当前列表"></th>'
    + '<th>文件</th><th>大小</th><th>修改时间</th><th>操作</th></tr></thead>';
  const tbody = document.createElement('tbody');
  files.forEach(f => {
    const path = String(f.path || '');
    const tr = document.createElement('tr');

    const tdCheck = document.createElement('td');
    tdCheck.className = 'm-check-col';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.className = 'm-check';
    cb.dataset.path = path;
    cb.checked = state.selected.has(path);
    cb.title = '选择 ' + path;
    cb.onchange = () => {
      if (cb.checked) state.selected.add(path); else state.selected.delete(path);
      updateSelectionUI();
    };
    tdCheck.appendChild(cb);

    const tdFile = document.createElement('td');
    tdFile.className = 'm-file';
    tdFile.textContent = path;
    tdFile.title = path;

    const tdSize = document.createElement('td');
    tdSize.className = 'm-size';
    tdSize.textContent = fmtSize(Number(f.size) || 0);

    const tdTime = document.createElement('td');
    tdTime.className = 'm-time';
    tdTime.textContent = fmtTime(Number(f.mtime) || 0);

    const tdAct = document.createElement('td');
    tdAct.className = 'm-actions';
    const a = document.createElement('a');
    a.className = 'm-act';
    a.href = '/api/download?path=' + encodeURIComponent(path);
    a.setAttribute('download', '');
    a.textContent = '下载';
    a.title = '下载 ' + path;
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'm-danger';
    del.textContent = '删除';
    del.title = '删除 ' + path;
    del.onclick = () => deleteFile(path);
    tdAct.appendChild(a);
    tdAct.appendChild(del);

    tr.appendChild(tdCheck);
    tr.appendChild(tdFile);
    tr.appendChild(tdSize);
    tr.appendChild(tdTime);
    tr.appendChild(tdAct);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  list.appendChild(table);

  const allBox = table.querySelector('#mSelectAll');
  if (allBox) {
    allBox.onchange = () => {
      if (allBox.checked) files.forEach(f => state.selected.add(String(f.path || '')));
      else files.forEach(f => state.selected.delete(String(f.path || '')));
      updateSelectionUI();
    };
  }
  updateSelectionUI();
}

/** 同步全选框（含 indeterminate）与“删除选中”按钮文案/禁用态。 */
function updateSelectionUI() {
  const files = visibleFiles();
  const allBox = document.querySelector('#mSelectAll');
  if (allBox) {
    const selectedVisible = files.filter(f => state.selected.has(String(f.path || ''))).length;
    allBox.checked = files.length > 0 && selectedVisible === files.length;
    allBox.indeterminate = selectedVisible > 0 && selectedVisible < files.length;
  }
  document.querySelectorAll('input.m-check').forEach(cb => {
    cb.checked = state.selected.has(cb.dataset.path);
  });
  const btn = $('#mDeleteSelected');
  if (btn) {
    btn.disabled = state.selected.size === 0;
    btn.textContent = state.selected.size ? '删除选中（' + state.selected.size + '）' : '删除选中';
  }
}

const UPLOAD_MAX_MB = 50;   // 与后端 uploadSizeMaxMB 保持一致

/** 上传文件到 workspace/{userId}/uploads（复用 /api/upload，可多选、支持多文件字段 file_i）。 */
async function uploadFiles(files) {
  if (!files || !files.length) return;
  const over = files.filter(f => f.size > UPLOAD_MAX_MB * 1024 * 1024);
  const okFiles = files.filter(f => f.size <= UPLOAD_MAX_MB * 1024 * 1024);
  if (over.length) notice('以下文件超过 ' + UPLOAD_MAX_MB + 'MB，已跳过：'
      + over.map(f => f.name).join('、'), true);
  if (!okFiles.length) return;

  const btn = $('#mUploadBtn');
  const oldText = btn.textContent;
  btn.disabled = true;
  btn.textContent = '上传中…';

  const fd = new FormData();
  // 不同字段名 file_0/file_1...：uploadFile 的 filenames 按字段名收集，同 key 会覆盖
  okFiles.forEach((f, i) => fd.append('file_' + i, f));
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 120000);
  try {
    const resp = await fetch('/api/upload', { method: 'POST', body: fd, signal: ctrl.signal });
    let data = null;
    try { data = await resp.json(); } catch (e) { /* 非 JSON */ }
    if (resp.status === 401) { showLogin(); throw new Error('未登录'); }
    if (!resp.ok || !data || data.success !== true) {
      throw new Error((data && data.error) || ('HTTP ' + resp.status));
    }
    const saved = Array.isArray(data.filenames) ? data.filenames : [];
    notice('上传成功 ' + (saved.length || okFiles.length) + ' 个文件（保存到 uploads/）', false);
    await load();
  } catch (e) {
    notice('上传失败：' + (e.name === 'AbortError' ? '上传超时（120秒）' : e.message), true);
  } finally {
    clearTimeout(timer);
    btn.disabled = false;
    btn.textContent = oldText;
  }
}

async function deleteSelected() {
  const paths = [...state.selected];
  if (!paths.length) return;
  const preview = paths.slice(0, 8).join('\n') + (paths.length > 8 ? '\n…' : '');
  if (!confirm('确定删除选中的 ' + paths.length + ' 个文件？\n\n' + preview
      + '\n\n删除后不可恢复。')) return;
  try {
    const r = await apiJson('/api/workspace/delete', { paths });
    const deleted = Number(r.deleted) || 0;
    const total = Number(r.total) || paths.length;
    if (deleted < total) {
      notice('已删除 ' + deleted + '/' + total + ' 个，部分失败：' + (r.error || '文件不存在或无权删除'), true);
    } else {
      notice('已删除 ' + deleted + ' 个文件', false);
    }
    state.selected.clear();
    await load();
  } catch (e) {
    notice('删除失败：' + e.message, true);
  }
}

async function deleteFile(path) {
  if (!confirm('确定删除这个文件？\n\n' + path + '\n\n删除后不可恢复。')) return;
  try {
    const r = await apiJson('/api/workspace/delete', { path });
    if (!r.success) throw new Error(r.error || '删除失败');
    notice('已删除：' + path, false);
    await load();
  } catch (e) {
    notice('删除失败：' + e.message, true);
  }
}

function fmtSize(n) {
  if (!n || n < 0) return '0 B';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
  return (n / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function fmtTime(ms) {
  if (!ms) return '—';
  try { return new Date(ms).toLocaleString(); } catch (e) { return '—'; }
}

function notice(text, isErr) {
  const n = $('#mNotice');
  if (!n) return;
  n.textContent = text;
  n.classList.remove('hidden');
  n.classList.toggle('m-err', !!isErr);
  clearTimeout(n._hideTimer);
  n._hideTimer = setTimeout(() => n.classList.add('hidden'), 6000);
}

document.addEventListener('DOMContentLoaded', () => {
  $('#mRefresh').onclick = load;
  $('#mFilter').oninput = e => {
    state.filter = e.target.value;
    state.selected.clear();   // 筛选后旧勾选不可见，避免误删
    render();
  };
  $('#mDeleteSelected').onclick = deleteSelected;
  $('#mUploadBtn').onclick = () => $('#mUploadInput').click();
  $('#mUploadInput').onchange = e => {
    const files = Array.from(e.target.files || []);
    e.target.value = '';       // 允许连续上传同名文件
    uploadFiles(files);
  };
  boot();
});
