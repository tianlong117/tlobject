/* AI Agent 管理页面：工作空间目录浏览 / 上传 / 新建文件夹 / 下载 / 删除（文件夹递归） */
const $ = s => document.querySelector(s);
const state = {
  dir: '',           // 当前目录（相对 workspace 根，'' = 根）
  parent: '',        // 上级目录（根时 ''）
  entries: [],       // 当前目录直接子项：{type:'dir'|'file', path, name, size?, mtime}
  truncated: false,
  filter: '',
  selected: new Set()   // 只存文件 path（批量删除仅文件；文件夹走行内删除按钮）
};

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
    await load('');
  } catch (e) {
    showLogin();
  }
}

async function load(dir) {
  state.selected.clear();
  try {
    const r = await apiJson('/api/workspace/list', { dir: dir || '' });
    if (!r.success) throw new Error(r.error || '加载失败');
    state.entries = Array.isArray(r.entries) ? r.entries : [];
    state.dir = r.dir || '';
    state.parent = r.parent || '';
    state.truncated = !!r.truncated;
    render();
  } catch (e) {
    notice('加载失败：' + e.message, true);
  }
}

function visibleEntries() {
  const filter = state.filter.trim().toLowerCase();
  return state.entries.filter(x => {
    if (!filter) return true;
    return String(x.name || '').toLowerCase().includes(filter)
        || String(x.path || '').toLowerCase().includes(filter);
  });
}

function render() {
  renderBreadcrumb();
  const entries = visibleEntries();
  const files = entries.filter(x => x.type === 'file');
  const dirs = entries.filter(x => x.type === 'dir');
  const total = files.reduce((sum, x) => sum + (Number(x.size) || 0), 0);
  $('#mCount').textContent = files.length + ' 个文件'
    + (dirs.length ? ' · ' + dirs.length + ' 个文件夹' : '')
    + (state.filter.trim() ? '（已筛选）' : '');
  $('#mSummary').textContent = '总大小 ' + fmtSize(total)
    + (state.truncated ? ' · 条目过多，仅显示前 2000 个' : '');

  const list = $('#mList');
  list.innerHTML = '';
  if (!entries.length) {
    const empty = document.createElement('div');
    empty.className = 'm-empty';
    empty.textContent = state.filter.trim() ? '没有匹配的文件/文件夹' : '此文件夹为空';
    list.appendChild(empty);
    updateSelectionUI();
    return;
  }

  const table = document.createElement('table');
  table.className = 'm-table';
  table.innerHTML = '<thead><tr>'
    + '<th class="m-check-col"><input type="checkbox" id="mSelectAll" title="全选当前文件夹的文件"></th>'
    + '<th>名称</th><th>大小</th><th>修改时间</th><th>操作</th></tr></thead>';
  const tbody = document.createElement('tbody');
  entries.forEach(x => {
    const path = String(x.path || '');
    const isDir = x.type === 'dir';
    const tr = document.createElement('tr');
    if (isDir) tr.className = 'm-dir-row';

    const tdCheck = document.createElement('td');
    tdCheck.className = 'm-check-col';
    if (!isDir) {
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
    }

    const tdName = document.createElement('td');
    tdName.className = 'm-file';
    tdName.textContent = (isDir ? '📁 ' : '') + String(x.name || path);
    tdName.title = path;
    if (isDir) {
      tdName.classList.add('m-dir-name');
      tdName.onclick = () => load(path);
    }

    const tdSize = document.createElement('td');
    tdSize.className = 'm-size';
    tdSize.textContent = isDir ? '—' : fmtSize(Number(x.size) || 0);

    const tdTime = document.createElement('td');
    tdTime.className = 'm-time';
    tdTime.textContent = fmtTime(Number(x.mtime) || 0);

    const tdAct = document.createElement('td');
    tdAct.className = 'm-actions';
    if (isDir) {
      const open = document.createElement('button');
      open.type = 'button';
      open.className = 'm-act-btn';
      open.textContent = '打开';
      open.onclick = () => load(path);
      tdAct.appendChild(open);
      const delDir = document.createElement('button');
      delDir.type = 'button';
      delDir.className = 'm-danger';
      delDir.textContent = '删除';
      delDir.title = '删除文件夹 ' + path + '（连同其中全部内容）';
      delDir.onclick = () => deleteFolder(path);
      tdAct.appendChild(delDir);
    } else {
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
    }

    tr.appendChild(tdCheck);
    tr.appendChild(tdName);
    tr.appendChild(tdSize);
    tr.appendChild(tdTime);
    tr.appendChild(tdAct);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  list.appendChild(table);

  const allBox = table.querySelector('#mSelectAll');
  if (allBox) {
    allBox.disabled = files.length === 0;
    allBox.onchange = () => {
      if (allBox.checked) files.forEach(x => state.selected.add(String(x.path || '')));
      else files.forEach(x => state.selected.delete(String(x.path || '')));
      updateSelectionUI();
    };
  }
  updateSelectionUI();
}

/** 面包屑：工作空间 / uploads / sub；根可点，当前目录高亮；非根显示上一级。 */
function renderBreadcrumb() {
  const bc = $('#mBreadcrumb');
  bc.innerHTML = '';
  const root = document.createElement('button');
  root.type = 'button';
  root.className = 'm-crumb';
  root.textContent = '工作空间';
  root.onclick = () => load('');
  bc.appendChild(root);
  const parts = state.dir ? state.dir.split('/') : [];
  let acc = '';
  parts.forEach((p, i) => {
    const sep = document.createElement('span');
    sep.className = 'm-crumb-sep';
    sep.textContent = '/';
    bc.appendChild(sep);
    acc = acc ? acc + '/' + p : p;
    const path = acc;
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'm-crumb' + (i === parts.length - 1 ? ' m-crumb-current' : '');
    b.textContent = p;
    b.onclick = () => load(path);
    bc.appendChild(b);
  });
  if (state.dir) {
    const up = document.createElement('button');
    up.type = 'button';
    up.className = 'm-crumb m-up';
    up.textContent = '↑ 上一级';
    up.onclick = () => load(state.parent);
    bc.appendChild(up);
  }
}

/** 同步全选框（含 indeterminate）与“删除选中”按钮文案/禁用态。 */
function updateSelectionUI() {
  const files = visibleEntries().filter(x => x.type === 'file');
  const allBox = document.querySelector('#mSelectAll');
  if (allBox) {
    const selectedVisible = files.filter(x => state.selected.has(String(x.path || ''))).length;
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

/** 上传文件到当前目录（复用 /api/workspace/upload；可多选；dir 走 query 串）。 */
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
    const url = '/api/workspace/upload?dir=' + encodeURIComponent(state.dir || '');
    const resp = await fetch(url, { method: 'POST', body: fd, signal: ctrl.signal });
    let data = null;
    try { data = await resp.json(); } catch (e) { /* 非 JSON */ }
    if (resp.status === 401) { showLogin(); throw new Error('未登录'); }
    if (!resp.ok || !data || data.success !== true) {
      throw new Error((data && data.error) || ('HTTP ' + resp.status));
    }
    const saved = Array.isArray(data.filenames) ? data.filenames : [];
    notice('上传成功 ' + (saved.length || okFiles.length) + ' 个文件到当前文件夹', false);
    await load(state.dir);
  } catch (e) {
    notice('上传失败：' + (e.name === 'AbortError' ? '上传超时（120秒）' : e.message), true);
  } finally {
    clearTimeout(timer);
    btn.disabled = false;
    btn.textContent = oldText;
  }
}

async function createFolder() {
  const name = prompt('新建文件夹名称：');
  if (name == null || !name.trim()) return;
  try {
    const r = await apiJson('/api/workspace/mkdir', { dir: state.dir, name: name.trim() });
    if (!r.success) throw new Error(r.error || '创建失败');
    notice('已创建文件夹：' + name.trim(), false);
    await load(state.dir);
  } catch (e) {
    notice('创建失败：' + e.message, true);
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
    await load(state.dir);
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
    await load(state.dir);
  } catch (e) {
    notice('删除失败：' + e.message, true);
  }
}

/** 删除文件夹：后端 recursive=true 递归删除其中全部内容（workspace 根不可删）。 */
async function deleteFolder(path) {
  if (!confirm('确定删除文件夹？\n\n' + path
      + '\n\n文件夹内的全部内容会一并删除，删除后不可恢复。')) return;
  try {
    const r = await apiJson('/api/workspace/delete', { path, recursive: true });
    if (!r.success) throw new Error(r.error || '删除失败');
    notice('已删除文件夹：' + path, false);
    await load(state.dir);
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
  $('#mRefresh').onclick = () => load(state.dir);
  $('#mFilter').oninput = e => {
    state.filter = e.target.value;
    state.selected.clear();   // 筛选后旧勾选不可见，避免误删
    render();
  };
  $('#mDeleteSelected').onclick = deleteSelected;
  $('#mNewFolderBtn').onclick = createFolder;
  $('#mUploadBtn').onclick = () => $('#mUploadInput').click();
  $('#mUploadInput').onchange = e => {
    const files = Array.from(e.target.files || []);
    e.target.value = '';       // 允许连续上传同名文件
    uploadFiles(files);
  };
  boot();
});
