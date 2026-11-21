/* AI Agent 管理页面：工作空间目录浏览 / 上传 / 新建文件夹 / 下载 / 删除（文件夹递归）/ 排序 */
const $ = s => document.querySelector(s);
const state = {
  dir: '',           // 当前目录（相对 workspace 根，'' = 根）
  parent: '',        // 上级目录（根时 ''）
  entries: [],       // 当前目录直接子项：{type:'dir'|'file', path, name, size?, mtime}
  truncated: false,
  filter: '',
  selected: new Set(),  // 只存文件 path（批量删除仅文件；文件夹走行内删除按钮）
  sortBy: 'name',       // name | type | size | mtime
  sortDir: 1,           // 1 升序 / -1 降序；文件夹恒在最前
  view: 'workspace',     // workspace | users | registration
  users: {
    list: [], total: 0, page: 1, pageSize: 20,
    keyword: '', status: '', editing: null
  },
  registration: {
    allowRegister: true, registerNeedApproval: false, loginCaptcha: true,
    available: true, persisted: false,
    updatedAt: 0, updatedBy: '',
    pending: [], total: 0, page: 1, pageSize: 20
  },
  isAdmin: false,
  csrfToken: null    // 会话级 CSRF token（/api/session 返回；状态变更请求随 X-CSRF-Token 发送）
};

/** 与 app.js 同款：POST JSON；401 回登录态。 */
async function apiJson(path, body) {
  const opt = { method: 'POST', headers: { 'Content-Type': 'application/json' } };
  if (state.csrfToken) opt.headers['X-CSRF-Token'] = state.csrfToken;
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
    state.csrfToken = s.csrfToken || null;
    $('#manageApp').classList.remove('hidden');
    const u = s.user || {};
    $('#mUser').textContent = (u.nickname || s.userId || '');
    state.isAdmin = !!s.isAdmin;
    if (!state.isAdmin) {
      $('#mNavUsers').classList.add('hidden');
      const regNav = $('#mNavRegistration');
      if (regNav) regNav.classList.add('hidden');
    }
    bindUserEvents();
    await load('');
  } catch (e) {
    showLogin();
  }
}

function switchView(view) {
  state.view = view;
  document.querySelectorAll('.m-nav').forEach(b =>
    b.classList.toggle('active', b.dataset.view === view));
  $('#workspaceView').classList.toggle('hidden', view !== 'workspace');
  $('#usersView').classList.toggle('hidden', view !== 'users');
  const regView = $('#registrationView');
  if (regView) regView.classList.toggle('hidden', view !== 'registration');
  if (view === 'users') loadUsers();
  if (view === 'registration') { loadRegisterSettings(); loadPendingUsers(); }
}

function bindUserEvents() {
  document.querySelectorAll('.m-nav').forEach(b => {
    b.onclick = () => switchView(b.dataset.view);
  });
  $('#mUserRefresh').onclick = () => loadUsers();
  $('#mUserFilter').oninput = () => {
    state.users.keyword = $('#mUserFilter').value.trim();
    state.users.page = 1;
    loadUsers();
  };
  $('#mUserStatus').onchange = () => {
    state.users.status = $('#mUserStatus').value;
    state.users.page = 1;
    loadUsers();
  };
  $('#mUserNew').onclick = () => openUserModal(null);
  $('#mUserModalClose').onclick = () => $('#mUserModal').classList.add('hidden');
  $('#mUserSave').onclick = saveUser;
  $('#mUserPrev').onclick = () => {
    if (state.users.page > 1) { state.users.page--; loadUsers(); }
  };
  $('#mUserNext').onclick = () => {
    const max = Math.max(1, Math.ceil(state.users.total / state.users.pageSize));
    if (state.users.page < max) { state.users.page++; loadUsers(); }
  };
  // 注册管理（元素判空：旧缓存 HTML 缺新面板时不影响其余功能）
  const regSave = $('#mRegSave');
  if (regSave) regSave.onclick = saveRegisterSettings;
  const pendingRefresh = $('#mPendingRefresh');
  if (pendingRefresh) pendingRefresh.onclick = () => loadPendingUsers();
  const pendingPrev = $('#mPendingPrev');
  if (pendingPrev) pendingPrev.onclick = () => {
    if (state.registration.page > 1) { state.registration.page--; loadPendingUsers(); }
  };
  const pendingNext = $('#mPendingNext');
  if (pendingNext) pendingNext.onclick = () => {
    const g = state.registration;
    const max = Math.max(1, Math.ceil(g.total / g.pageSize));
    if (g.page < max) { g.page++; loadPendingUsers(); }
  };
  // 弹框：点遮罩关闭 / ESC 关闭
  $('#mUserModal').addEventListener('click', e => {
    if (e.target === $('#mUserModal')) $('#mUserModal').classList.add('hidden');
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !$('#mUserModal').classList.contains('hidden'))
      $('#mUserModal').classList.add('hidden');
  });
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

/** 文件类型分组（按类型排序时先按分组序，再按扩展名）。 */
const FILE_TYPE_GROUPS = [
  ['图片', ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tif', 'tiff']],
  ['文档', ['doc', 'docx', 'pdf', 'odt', 'rtf']],
  ['表格', ['xls', 'xlsx', 'csv', 'ods']],
  ['演示', ['ppt', 'pptx', 'odp']],
  ['压缩包', ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz']],
  ['网页', ['html', 'htm', 'css']],
  ['数据', ['json', 'xml', 'yaml', 'yml', 'sql']],
  ['代码', ['py', 'java', 'js', 'ts', 'c', 'cpp', 'h', 'go', 'rs', 'sh', 'bat', 'ps1']],
  ['音视频', ['mp3', 'wav', 'flac', 'mp4', 'avi', 'mov', 'mkv', 'webm']],
  ['文本', ['txt', 'md', 'log', 'ini', 'conf', 'properties']]
];

/** 文件名 → {rank, ext, label}；未知扩展名归“其他”，无扩展名单独一组排在最后。 */
function fileType(name) {
  const s = String(name || '');
  const dot = s.lastIndexOf('.');
  const ext = dot > 0 && dot < s.length - 1 ? s.slice(dot + 1).toLowerCase() : '';
  for (let i = 0; i < FILE_TYPE_GROUPS.length; i++) {
    if (FILE_TYPE_GROUPS[i][1].includes(ext)) {
      return { rank: i, ext, label: FILE_TYPE_GROUPS[i][0] };
    }
  }
  return { rank: FILE_TYPE_GROUPS.length + (ext ? 0 : 1), ext, label: ext ? '其他' : '无扩展名' };
}

function nameCmp(a, b) {
  return String(a.name || a.path || '').localeCompare(
      String(b.name || b.path || ''), 'zh-Hans-CN', { numeric: true, sensitivity: 'base' });
}

/** 排序：文件夹恒在最前，组内按当前列/方向；并列时按名称。 */
function compareEntries(a, b) {
  const dirA = a.type === 'dir', dirB = b.type === 'dir';
  if (dirA !== dirB) return dirA ? -1 : 1;
  let r = 0;
  if (state.sortBy === 'size') {
    r = (Number(a.size) || 0) - (Number(b.size) || 0);
  } else if (state.sortBy === 'mtime') {
    r = (Number(a.mtime) || 0) - (Number(b.mtime) || 0);
  } else if (state.sortBy === 'type') {
    const ta = dirA ? { rank: -1, ext: '' } : fileType(a.name || a.path);
    const tb = dirB ? { rank: -1, ext: '' } : fileType(b.name || b.path);
    r = ta.rank - tb.rank || ta.ext.localeCompare(tb.ext);
  }
  if (!r) r = nameCmp(a, b);
  return r * state.sortDir;
}

function sortedEntries(entries) {
  return entries.slice().sort(compareEntries);
}

/** 可排序表头（点击切换升/降序；首点 name/type 升序、size/mtime 降序）。 */
function sortableTh(key, label) {
  const active = state.sortBy === key;
  const mark = active ? (state.sortDir > 0 ? ' ▲' : ' ▼') : '';
  return '<th class="m-sortable' + (active ? ' m-sort-active' : '')
      + '" data-sort="' + key + '" title="点击按' + label + '排序">' + label + mark + '</th>';
}

function toggleSort(key) {
  if (state.sortBy === key) {
    state.sortDir = -state.sortDir;
  } else {
    state.sortBy = key;
    state.sortDir = (key === 'size' || key === 'mtime') ? -1 : 1;
  }
  render();
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
    + sortableTh('name', '名称')
    + sortableTh('type', '类型')
    + sortableTh('size', '大小')
    + sortableTh('mtime', '修改时间')
    + '<th>操作</th></tr></thead>';
  const tbody = document.createElement('tbody');
  sortedEntries(entries).forEach(x => {
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

    const tdType = document.createElement('td');
    tdType.className = 'm-type';
    if (isDir) {
      tdType.textContent = '文件夹';
    } else {
      const t = fileType(x.name || path);
      tdType.textContent = t.label;
      tdType.title = t.ext ? t.ext.toUpperCase() : '无扩展名';
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
    tr.appendChild(tdType);
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
    const resp = await fetch(url, {
      method: 'POST', body: fd, signal: ctrl.signal,
      headers: state.csrfToken ? { 'X-CSRF-Token': state.csrfToken } : undefined
    });
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
  // 表头排序（委托到列表容器：表格每次 render 重建，监听器不随之丢失）
  $('#mList').addEventListener('click', e => {
    const th = e.target.closest && e.target.closest('th[data-sort]');
    if (th) toggleSort(th.dataset.sort);
  });
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

// ======================== 用户管理 ========================
let usersLoadSeq = 0;     // 列表请求序号：快速输入/翻页时只应用最后一次请求的结果
let savingUser = false;   // 保存按钮防双击
let userNoticeTimer = null;

function userNotice(msg, isErr) {
  const n = $('#mUserNotice');
  if (!msg) {
    clearTimeout(userNoticeTimer);
    userNoticeTimer = null;
    n.classList.add('hidden');
    return;
  }
  n.textContent = msg;
  n.classList.toggle('m-err', !!isErr);
  n.classList.remove('hidden');
  clearTimeout(userNoticeTimer);
  userNoticeTimer = setTimeout(() => {
    n.classList.add('hidden');
    userNoticeTimer = null;
  }, 4000);
}

function fmtUserTime(ms) {
  const n = Number(ms) || 0;
  if (!n) return '—';
  const d = new Date(n);
  const p = x => String(x).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
      + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

async function loadUsers() {
  if (!state.isAdmin) return;
  const seq = ++usersLoadSeq;
  userNotice('');
  try {
    const u = state.users;
    const r = await apiJson('/api/users', {
      keyword: u.keyword,
      status: u.status === '' ? -1 : Number(u.status),
      page: u.page,
      pageSize: u.pageSize
    });
    if (seq !== usersLoadSeq) return;   // 已有更新的请求在飞，丢弃本次结果
    if (!r.success) throw new Error(r.error || '加载失败');
    u.list = Array.isArray(r.users) ? r.users : [];
    u.total = Number(r.total) || 0;
    const max = Math.max(1, Math.ceil(u.total / u.pageSize));
    if (u.page > max) {
      u.page = max;
      return loadUsers();
    }
    renderUsers();
  } catch (e) {
    if (seq !== usersLoadSeq) return;
    userNotice('加载用户失败：' + e.message, true);
  }
}

function renderUsers() {
  const u = state.users;
  const list = $('#mUserList');
  list.innerHTML = '';
  $('#mUserSummary').textContent = '共 ' + u.total + ' 个用户';
  const max = Math.max(1, Math.ceil(u.total / u.pageSize));
  $('#mUserPageInfo').textContent = u.page + ' / ' + max;
  $('#mUserPrev').disabled = u.page <= 1;
  $('#mUserNext').disabled = u.page >= max;

  if (!u.list.length) {
    const empty = document.createElement('div');
    empty.className = 'm-empty';
    empty.textContent = '没有匹配的用户';
    list.appendChild(empty);
    return;
  }
  const table = document.createElement('table');
  table.className = 'm-table';
  table.innerHTML = '<thead><tr><th>头像</th><th>登录名</th><th>昵称</th>'
    + '<th>角色</th><th>状态</th><th>最后登录</th><th>最后登录 IP</th><th>操作</th></tr></thead>';
  const tbody = document.createElement('tbody');
  u.list.forEach(user => {
    const st = Number(user.status);
    const tr = document.createElement('tr');
    const tdAvatar = document.createElement('td');
    if (user.hasAvatar) {
      const img = document.createElement('img');
      img.className = 'm-user-avatar';
      img.src = '/api/avatar?userId=' + encodeURIComponent(user.userId);
      img.alt = '';
      img.onerror = () => { img.replaceWith(avatarFallback(user)); };
      tdAvatar.appendChild(img);
    } else {
      tdAvatar.appendChild(avatarFallback(user));
    }
    const tdId = document.createElement('td');
    tdId.textContent = user.userId;
    const tdNick = document.createElement('td');
    tdNick.textContent = user.nickname || user.userId;
    const tdRoles = document.createElement('td');
    tdRoles.textContent = (user.roles || []).join(', ');
    const tdStatus = document.createElement('td');
    tdStatus.textContent = st === 1 ? '启用' : st === 2 ? '待审核' : '禁用';
    tdStatus.className = st === 1 ? 'm-status-on' : st === 2 ? 'm-status-pending' : 'm-status-off';
    const tdLast = document.createElement('td');
    tdLast.className = 'm-time';
    tdLast.textContent = fmtUserTime(user.lastLoginAt);
    const tdIp = document.createElement('td');
    tdIp.className = 'm-time';
    tdIp.textContent = user.lastLoginIp || '—';
    const tdAct = document.createElement('td');
    tdAct.className = 'm-actions';
    tdAct.appendChild(actBtn('编辑', () => openUserModal(user)));
    tdAct.appendChild(actBtn('重置密码', () => resetUserPassword(user)));
    tdAct.appendChild(actBtn(st === 1 ? '禁用' : st === 2 ? '通过' : '启用', () => toggleUser(user)));
    const purge = actBtn('彻底删除', () => purgeUser(user));
    purge.className = 'm-danger m-danger-strong';
    tdAct.appendChild(purge);
    const del = actBtn('删除', () => deleteUser(user));
    del.className = 'm-danger';
    tdAct.appendChild(del);
    tr.append(tdAvatar, tdId, tdNick, tdRoles, tdStatus, tdLast, tdIp, tdAct);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  list.appendChild(table);
}

function avatarFallback(user) {
  const span = document.createElement('span');
  span.className = 'm-user-avatar';
  const chars = Array.from(String(user.nickname || user.userId || '?'));
  span.textContent = (chars[0] || '?').toUpperCase();
  return span;
}

function actBtn(text, onclick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'm-act-btn';
  b.textContent = text;
  b.onclick = onclick;
  return b;
}

function openUserModal(user) {
  state.users.editing = user;
  $('#mUserModalTitle').textContent = user ? '编辑用户' : '新建用户';
  $('#muId').value = user ? user.userId : '';
  $('#muId').disabled = !!user;
  $('#muNickname').value = user ? (user.nickname || '') : '';
  $('#muPassword').value = '';
  $('#muPassword').placeholder = user ? '留空则不修改' : '新建时必填（至少 6 位）';
  $('#muRoles').value = user ? (user.roles || []).join(',') : 'member';
  $('#muGroup').value = user ? (user.group || '') : '';
  $('#muEmail').value = user ? (user.email || '') : '';
  $('#muBio').value = user ? (user.bio || '') : '';
  const sel = $('#muStatus');
  sel.innerHTML = '';
  sel.add(new Option('启用', '1'));
  sel.add(new Option('禁用', '0'));
  if (user && Number(user.status) === 2) sel.add(new Option('待审核', '2'));
  const st = user ? Number(user.status) : 1;
  sel.value = String(st === 0 || st === 2 ? st : 1);
  $('#mUserErr').classList.add('hidden');
  $('#mUserModal').classList.remove('hidden');
  $('#muId').focus();
}

async function saveUser() {
  if (savingUser) return;
  savingUser = true;
  $('#mUserSave').disabled = true;
  const editing = state.users.editing;
  const statusVal = $('#muStatus').value;
  const payload = {
    userId: editing ? editing.userId : $('#muId').value.trim(),
    nickname: $('#muNickname').value.trim(),
    roles: $('#muRoles').value.trim(),
    group: $('#muGroup').value.trim(),
    email: $('#muEmail').value.trim(),
    bio: $('#muBio').value.trim(),
    status: statusVal === '' ? 1 : Number(statusVal)   // 0 是合法值；待审核用户原样提交 2（后端保留）
  };
  const pwd = $('#muPassword').value;
  try {
    let r;
    if (editing) {
      r = await apiJson('/api/usersUpdate', payload);
      if (r.success && pwd) {
        r = await apiJson('/api/usersResetPassword', {
          userId: editing.userId, newPassword: pwd
        });
      }
    } else {
      payload.password = pwd;
      r = await apiJson('/api/usersCreate', payload);
    }
    if (!r.success) throw new Error(r.error || '保存失败');
    $('#mUserModal').classList.add('hidden');
    await loadUsers();
  } catch (e) {
    $('#mUserErr').textContent = e.message;
    $('#mUserErr').classList.remove('hidden');
  } finally {
    savingUser = false;
    $('#mUserSave').disabled = false;
  }
}

async function toggleUser(user) {
  try {
    const r = await apiJson('/api/usersUpdate', {
      userId: user.userId,
      status: Number(user.status) === 1 ? 0 : 1
    });
    if (!r.success) throw new Error(r.error || '操作失败');
    await loadUsers();
  } catch (e) { userNotice(e.message, true); }
}

async function resetUserPassword(user) {
  const pwd = prompt('为 ' + user.userId + ' 设置新密码（至少 6 位）：');
  if (!pwd) return;
  try {
    const r = await apiJson('/api/usersResetPassword', {
      userId: user.userId, newPassword: pwd
    });
    if (!r.success) throw new Error(r.error || '重置失败');
    userNotice('已重置 ' + user.userId + ' 的密码');
  } catch (e) { userNotice(e.message, true); }
}

async function deleteUser(user) {
  if (!confirm('删除用户 ' + user.userId + ' 的账号记录？\n\n'
      + '仅删除账号，其会话/记忆/工作空间/任务等数据会保留；'
      + '如需连数据一起删除，请使用「彻底删除」。此操作不可恢复。')) return;
  try {
    const r = await apiJson('/api/usersDelete', { userId: user.userId });
    if (!r.success) throw new Error(r.error || '删除失败');
    await loadUsers();
  } catch (e) { userNotice(e.message, true); }
}

/** 彻底删除：输入登录名确认，误输入不发请求 */
async function purgeUser(user) {
  const typed = prompt('彻底删除用户 ' + user.userId + ' 的全部数据：\n'
      + '会话历史、长期记忆、Token 统计、消息箱、定时/后台任务、审批、'
      + 'workspace 与 data 目录。\n\n此操作不可恢复。请输入登录名确认：');
  if (typed === null) return;
  if (typed.trim() !== user.userId) {
    userNotice('输入的用户名不匹配，未执行删除', true);
    return;
  }
  try {
    const r = await apiJson('/api/usersPurge', {
      userId: user.userId, confirmUserId: typed.trim()
    });
    const counts = formatPurgeCounts(r.counts);
    if (!r.success) {
      // 先刷新列表再提示：loadUsers 会清空 notice，顺序反了成功/失败提示会被立刻擦掉
      await loadUsers();
      userNotice((r.error || '彻底删除未完成') + (counts ? '（已清理：' + counts + '）' : ''), true);
      return;
    }
    await loadUsers();
    userNotice('已彻底删除 ' + user.userId + (counts ? '：' + counts : ''));
  } catch (e) { userNotice(e.message, true); }
}

function formatPurgeCounts(c) {
  if (!c) return '';
  const p = [];
  const n = v => Number(v) || 0;
  if (c.sessions) p.push('会话 ' + n(c.sessions.sessionsDeleted));
  if (c.memory) p.push('记忆 ' + (n(c.memory.dbEntries) + n(c.memory.cacheCleared)));
  if (c.tokenStats) p.push('Token 行 ' + n(c.tokenStats.rowsDeleted));
  if (c.inbox) p.push('消息 ' + n(c.inbox.inboxRows));
  if (c.tasks) p.push('任务 ' + n(c.tasks.recordsRemoved));
  if (c.files) p.push('文件 ' + n(c.files.filesDeleted));
  return p.join('，');
}

// ======================== 注册管理 ========================
let settingsLoadSeq = 0;   // 设置请求序号：切视图/重复进入时只应用最后一次结果
let pendingLoadSeq = 0;    // 待审核列表请求序号
let savingRegisterSettings = false;
let regNoticeTimer = null;

function regNotice(msg, isErr) {
  const n = $('#mRegNotice');
  if (!n) return;
  if (!msg) {
    clearTimeout(regNoticeTimer);
    regNoticeTimer = null;
    n.classList.add('hidden');
    return;
  }
  n.textContent = msg;
  n.classList.toggle('m-err', !!isErr);
  n.classList.remove('hidden');
  clearTimeout(regNoticeTimer);
  regNoticeTimer = setTimeout(() => {
    n.classList.add('hidden');
    regNoticeTimer = null;
  }, 4000);
}

async function loadRegisterSettings() {
  if (!state.isAdmin) return;
  const seq = ++settingsLoadSeq;
  try {
    const r = await apiJson('/api/registerSettings', {});
    if (seq !== settingsLoadSeq) return;
    if (!r.success) throw new Error(r.error || '加载设置失败');
    const g = state.registration;
    g.allowRegister = r.allowRegister !== false;
    g.registerNeedApproval = !!r.registerNeedApproval;
    g.loginCaptcha = r.loginCaptcha !== false;
    g.available = r.available !== false;
    g.persisted = !!r.persisted;
    g.updatedAt = Number(r.updatedAt) || 0;
    g.updatedBy = r.updatedBy || '';
    $('#mRegAllow').checked = g.allowRegister;
    $('#mRegNeedApproval').checked = g.registerNeedApproval;
    $('#mRegLoginCaptcha').checked = g.loginCaptcha;
    $('#mRegSave').disabled = !g.available;
    $('#mRegSettingsInfo').textContent = !g.available
      ? '设置存储不可用（aiSettings 表未声明或数据库不可用），修改不会持久化'
      : (g.persisted
          ? '最近保存：' + fmtUserTime(g.updatedAt) + (g.updatedBy ? ' · ' + g.updatedBy : '')
          : '尚未保存，当前使用配置文件默认值');
  } catch (e) {
    regNotice('加载注册设置失败：' + e.message, true);
  }
}

async function saveRegisterSettings() {
  if (savingRegisterSettings) return;
  if (!state.registration.available) { regNotice('设置存储不可用，无法保存', true); return; }
  savingRegisterSettings = true;
  $('#mRegSave').disabled = true;
  try {
    const r = await apiJson('/api/registerSettingsUpdate', {
      allowRegister: $('#mRegAllow').checked,
      registerNeedApproval: $('#mRegNeedApproval').checked,
      loginCaptcha: $('#mRegLoginCaptcha').checked
    });
    if (!r.success) throw new Error(r.error || '保存失败');
    const g = state.registration;
    g.allowRegister = r.allowRegister !== false;
    g.registerNeedApproval = !!r.registerNeedApproval;
    g.loginCaptcha = r.loginCaptcha !== false;
    g.persisted = true;
    g.updatedAt = Number(r.updatedAt) || Date.now();
    g.updatedBy = r.updatedBy || '';
    $('#mRegSettingsInfo').textContent =
      '最近保存：' + fmtUserTime(g.updatedAt) + (g.updatedBy ? ' · ' + g.updatedBy : '');
    regNotice('设置已保存');
  } catch (e) {
    regNotice(e.message, true);
    await loadRegisterSettings();   // 失败时回读服务端真值，避免界面停在与 DB 不一致的状态
  } finally {
    savingRegisterSettings = false;
    $('#mRegSave').disabled = !state.registration.available;
  }
}

async function loadPendingUsers() {
  if (!state.isAdmin) return;
  const seq = ++pendingLoadSeq;
  try {
    const g = state.registration;
    const r = await apiJson('/api/users', {
      keyword: '', status: 2, page: g.page, pageSize: g.pageSize
    });
    if (seq !== pendingLoadSeq) return;
    if (!r.success) throw new Error(r.error || '加载失败');
    g.pending = Array.isArray(r.users) ? r.users : [];
    g.total = Number(r.total) || 0;
    const max = Math.max(1, Math.ceil(g.total / g.pageSize));
    if (g.page > max) { g.page = max; return loadPendingUsers(); }
    renderPendingUsers();
  } catch (e) {
    regNotice('加载待审核用户失败：' + e.message, true);
  }
}

function renderPendingUsers() {
  const g = state.registration;
  const list = $('#mPendingList');
  if (!list) return;
  list.innerHTML = '';
  $('#mPendingCount').textContent = '共 ' + g.total + ' 人待审核';
  const max = Math.max(1, Math.ceil(g.total / g.pageSize));
  $('#mPendingPageInfo').textContent = g.page + ' / ' + max;
  $('#mPendingPrev').disabled = g.page <= 1;
  $('#mPendingNext').disabled = g.page >= max;

  if (!g.pending.length) {
    const empty = document.createElement('div');
    empty.className = 'm-empty';
    empty.textContent = '没有待审核用户';
    list.appendChild(empty);
    return;
  }
  const table = document.createElement('table');
  table.className = 'm-table';
  table.innerHTML = '<thead><tr><th>登录名</th><th>昵称</th><th>邮箱</th>'
    + '<th>注册时间</th><th>操作</th></tr></thead>';
  const tbody = document.createElement('tbody');
  g.pending.forEach(user => {
    const tr = document.createElement('tr');
    const tdId = document.createElement('td');
    tdId.textContent = user.userId;
    const tdNick = document.createElement('td');
    tdNick.textContent = user.nickname || user.userId;
    const tdEmail = document.createElement('td');
    tdEmail.textContent = user.email || '—';
    const tdTime = document.createElement('td');
    tdTime.className = 'm-time';
    tdTime.textContent = fmtUserTime(user.createdAt);
    const tdAct = document.createElement('td');
    tdAct.className = 'm-actions';
    tdAct.appendChild(actBtn('通过', () => reviewPending(user, true)));
    const rej = actBtn('拒绝', () => reviewPending(user, false));
    rej.className = 'm-danger';
    tdAct.appendChild(rej);
    tr.append(tdId, tdNick, tdEmail, tdTime, tdAct);
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  list.appendChild(table);
}

async function reviewPending(user, approve) {
  if (!approve && !confirm('拒绝 ' + user.userId + ' 的注册？拒绝后账号为禁用状态，可在用户管理中重新启用。'))
    return;
  try {
    const r = await apiJson('/api/usersUpdate', {
      userId: user.userId, status: approve ? 1 : 0
    });
    if (!r.success) throw new Error(r.error || '操作失败');
    regNotice(approve ? '已通过 ' + user.userId + ' 的注册' : '已拒绝 ' + user.userId + ' 的注册');
    await loadPendingUsers();
  } catch (e) { regNotice(e.message, true); }
}
