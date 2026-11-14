/* AI Agent 管理页面：工作空间文件列表 / 下载 / 删除（独立页面） */
const $ = s => document.querySelector(s);
const state = { files: [], truncated: false, filter: '' };

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
  try {
    const r = await apiJson('/api/workspace/list', {});
    state.files = Array.isArray(r.files) ? r.files : [];
    state.truncated = !!r.truncated;
    render();
  } catch (e) {
    notice('加载失败：' + e.message, true);
  }
}

function render() {
  const filter = state.filter.trim().toLowerCase();
  const files = state.files.filter(f =>
    !filter || String(f.path || '').toLowerCase().includes(filter));
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
    return;
  }

  const table = document.createElement('table');
  table.className = 'm-table';
  table.innerHTML = '<thead><tr><th>文件</th><th>大小</th><th>修改时间</th><th>操作</th></tr></thead>';
  const tbody = document.createElement('tbody');
  files.forEach(f => {
    const path = String(f.path || '');
    const tr = document.createElement('tr');

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

    tr.appendChild(tdFile);
    tr.appendChild(tdSize);
    tr.appendChild(tdTime);
    tr.appendChild(tdAct);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  list.appendChild(table);
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
  $('#mFilter').oninput = e => { state.filter = e.target.value; render(); };
  boot();
});
