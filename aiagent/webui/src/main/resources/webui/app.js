/* AI Agent Web 前端 — 原生 JS，零依赖 */
'use strict';

// ======================== 状态 ========================
const state = {
  userId: null,
  sessionId: localStorage.getItem('tlweb_session') || null,
  streamEnabled: true,
  busy: false,           // 有活跃 chat
  abort: null,           // AbortController（流式聊天）
  events: null,          // EventSource
  uploads: []            // 已上传文件名 [{name}]（输入框上方显示条）
};

// ======================== 基础工具 ========================
const $ = sel => document.querySelector(sel);

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function fmtTs(ms) {
  if (!ms) return '未知';
  const d = new Date(ms);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' +
         String(d.getDate()).padStart(2, '0') + ' ' +
         String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function toast(msg, kind) {
  const box = $('#toastBox');
  const t = document.createElement('div');
  t.className = 'toast ' + (kind || 'info');
  t.textContent = msg;
  box.appendChild(t);
  setTimeout(() => t.remove(), 4000);
}
function showView(name) {
  $('#loginView').classList.toggle('hidden', name !== 'login');
  $('#chatView').classList.toggle('hidden', name !== 'chat');
}
function renderResult(r, container) {
  container.innerHTML = '';
  if (!r.success) {
    container.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message || '失败') + '</div>';
    return;
  }
  if (r.message) container.innerHTML = '<div class="ok-msg">✓ ' + esc(r.message) + '</div>';
  return r.data;
}
function renderPre(container, lines) {
  container.innerHTML = '<pre class="out">' + esc(Array.isArray(lines) ? lines.join('\n') : String(lines == null ? '' : lines)) + '</pre>';
}
function renderTable(container, headers, rows, curKey) {
  container.innerHTML = '';
  if (!rows || rows.length === 0) {
    container.innerHTML = '<div class="empty">（无数据）</div>';
    return;
  }
  let h = '<table class="tbl"><tr>' + headers.map(x => '<th>' + esc(x[1]) + '</th>').join('') + '</tr>';
  rows.forEach(r => {
    const cur = curKey && r[curKey] === state.sessionId ? ' class="cur"' : '';
    h += '<tr' + cur + '>' + headers.map(x => '<td>' + (r[x[0]] == null ? '' : esc(r[x[0]])) + '</td>').join('') + '</tr>';
  });
  container.innerHTML = h + '</table>';
}
function newSession() {
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
  state.sessionId = 'webchat_' + state.userId + '_' + Date.now();
  localStorage.setItem('tlweb_session', state.sessionId);
  $('#sessionId').value = state.sessionId;
  $('#msgList').innerHTML = '';
  // 恢复输入（会话被接管时禁用了）并聚焦
  $('#chatInput').disabled = false;
  $('#sendBtn').disabled = false;
  $('#chatInput').focus();
  toast('已开新会话: ' + state.sessionId, 'ok');
}

// ======================== API ========================
async function apiJson(path, body) {
  const opt = { method: 'POST', headers: { 'Content-Type': 'application/json' } };
  if (body !== undefined) opt.body = JSON.stringify(body);
  const resp = await fetch(path, opt);
  let data = null;
  try { data = await resp.json(); } catch (e) { /* 非 JSON 响应 */ }
  if (resp.status === 401) {
    showLogin();
    throw new Error(data && data.error ? data.error : '未登录');
  }
  if (!resp.ok || data === null) {
    throw new Error(data && data.error ? data.error : ('HTTP ' + resp.status));
  }
  return data;
}

// ======================== 登录 ========================
function showLogin() {
  showView('login');
  if (state.events) { state.events.close(); state.events = null; }
}
async function initSession() {
  try {
    const r = await fetch('/api/session').then(x => x.json());
    if (r.loggedIn) {
      state.userId = r.userId;
      // 刷新页面：恢复本地保存的会话与登录标识（同用户继续之前的会话，不触发"新会话"）
      state.sessionId = localStorage.getItem('tlweb_session');
      state.loginId = localStorage.getItem('tlweb_loginid');
      enterChat();
    } else {
      showLogin();
    }
  } catch (e) {
    showLogin();
  }
}
async function doLogin() {
  const userId = $('#loginUser').value.trim();
  const password = $('#loginPwd').value;
  if (!userId) { $('#loginErr').textContent = '请输入用户ID'; $('#loginErr').classList.remove('hidden'); return; }
  try {
    const r = await apiJson('/api/login', { userId, password });
    state.userId = r.userId;
    // 换用户登录：重置会话与上传状态，避免沿用上一用户的 sessionId / 文件名
    // 同用户登录：保留上次会话（localStorage 里的 tlweb_session），继续之前的会话
    const prevUser = localStorage.getItem('tlweb_userid');
    const prevSid = localStorage.getItem('tlweb_session');
    if (prevUser && prevUser !== userId) {
      state.sessionId = null;
      localStorage.removeItem('tlweb_session');
      state.uploads = [];
      renderUploadBar();
    } else {
      state.sessionId = prevSid;
    }
    // 登录实例标识（会话占用互斥用）：同浏览器刷新/重登保留；没有则生成（首次登录/登出后再登/旧版升级）
    // 换用户时必须生成新的（旧 loginId 属于上一用户）
    state.loginId = localStorage.getItem('tlweb_loginid');
    if (!state.loginId || (prevUser && prevUser !== userId)) {
      state.loginId = 'login_' + userId + '_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
      localStorage.setItem('tlweb_loginid', state.loginId);
    }
    localStorage.setItem('tlweb_userid', userId);
    $('#loginErr').classList.add('hidden');
    enterChat();
  } catch (e) {
    $('#loginErr').textContent = e.message;
    $('#loginErr').classList.remove('hidden');
  }
}
async function doLogout() {
  try { await apiJson('/api/logout', { loginId: state.loginId || '' }); } catch (e) { /* 忽略 */ }
  state.userId = null;
  state.loginId = null;
  localStorage.removeItem('tlweb_loginid');
  // 登出只退认证，保留会话（localStorage 的 tlweb_session/tlweb_userid）——
  // 重新登录同用户继续之前的会话；换用户由 doLogin 的 prevUser 比较重置
  showLogin();
}
async function enterChat() {
  if (!state.sessionId) newSession();
  $('#userTag').textContent = '👤 ' + state.userId;
  $('#sessionId').value = state.sessionId;
  $('#msgList').innerHTML = '';
  appendSysMsg('已登录：' + state.userId + '（管理命令在右侧面板）');
  showView('chat');
  openEvents();
  await autoResumeLast();   // 先接续最近活跃会话并渲染历史
  promptCheckpoint();       // 再检测断点会话 → 弹窗提示（如命令行启动时的 ⚠ 提示）
}

/** 登录后自动接续该用户最近活跃的会话（以最后消息时间 last_active 为准）。
 *  有则恢复上下文并渲染历史；无则提示新开始。
 *  注意：不以本浏览器 localStorage 的旧会话为准（那是"上次 webui 会话"，
 *  控制台等其他界面聊过后它已不是最新）——列表按 last_active 倒序，
 *  取第一条即最近活跃；若它恰好是自身（自身仍最新）行为与之前一致。 */
async function autoResumeLast() {
  try {
    const sessions = await loadSessions();
    // 取列表第一条 = 最近活跃会话（含自身）。
    // 不用 count/state 过滤——msg_count 可能被空轮覆盖为 0 导致误跳过；
    // 会话被删/换用户时（旧自身不在列表）同样自然落到第一条。
    let last = (sessions || []).find(s => s.sessionId);
    if (!last) {
      appendSysMsg('💡 暂无历史会话，直接输入消息即可（当前会话 ' + state.sessionId + '）');
      return;
    }
    const res = await continueSession(last.sessionId, true);   // 内部会清空并渲染历史
    if (res.ok) {
      appendSysMsg('💡 已自动接续最近活跃会话（最后消息 ' + fmtTs(last.savedAt) + '），可在右侧『会话』面板切换其他历史会话');
    } else {
      appendSysMsg('💡 自动接续失败：' + (res.error || '未知原因') + '。已开始新会话 ' + state.sessionId + '（可在右侧『会话』面板手动继续）');
    }
  } catch (e) {
    appendSysMsg('💡 历史会话恢复失败（' + e.message + '），已开始新会话 ' + state.sessionId);
  }
}

// ======================== 聊天渲染 ========================
function appendSysMsg(text) {
  const d = document.createElement('div');
  d.className = 'msg system';
  d.textContent = text;
  $('#msgList').appendChild(d);
  scrollChat();
}
function appendMsg(role, content) {
  const d = document.createElement('div');
  d.className = 'msg ' + role;
  d.textContent = content == null ? '' : content;
  $('#msgList').appendChild(d);
  scrollChat();
  return d;
}
/** 从工具输出 JSON 中提取 screenshot_base64（browser 截图），无则 null */
function extractScreenshot(output) {
  if (!output) return null;
  const m = output.match(/"screenshot_base64"\s*:\s*"([A-Za-z0-9+/=]+)"/);
  return m ? m[1] : null;
}
/** 双击原图：全屏 lightbox 查看，点击遮罩关闭（懒创建单例） */
function showLightbox(src) {
  let lb = document.getElementById('lightbox');
  if (!lb) {
    lb = document.createElement('div');
    lb.className = 'lightbox hidden';
    lb.innerHTML = '<img alt="原图">';
    lb.addEventListener('click', () => lb.classList.add('hidden'));
    document.body.appendChild(lb);
  }
  lb.querySelector('img').src = src;
  lb.classList.remove('hidden');
}
/** 渲染工具结果卡片：browser 截图显示为图片，原始输出折叠（超长 base64 不刷屏） */
function renderToolResult(toolName, output) {
  const d = document.createElement('div');
  d.className = 'msg tool-result';
  const title = document.createElement('div');
  title.className = 'tool-title';
  title.textContent = '🔧 ' + (toolName || '工具') + ' 完成';
  d.appendChild(title);
  const shot = extractScreenshot(output);
  if (shot) {
    const img = document.createElement('img');
    img.className = 'browser-shot';
    img.src = 'data:image/png;base64,' + shot;
    img.loading = 'lazy';
    img.title = '双击查看原图';
    img.addEventListener('dblclick', () => showLightbox(img.src));
    d.appendChild(img);
  }
  const det = document.createElement('details');
  det.className = 'tool-detail';
  det.innerHTML = '<summary>' + (shot ? '原始输出 (' + Math.round(output.length / 1024) + 'KB)' : '输出') + '</summary>' + esc(output);
  d.appendChild(det);
  $('#msgList').appendChild(d);
  scrollChat();
  return d;
}
function startAssistantMsg() {
  const d = document.createElement('div');
  d.className = 'msg ai';
  const cursor = document.createElement('span');
  cursor.className = 'cursor';
  d.appendChild(cursor);
  $('#msgList').appendChild(d);
  scrollChat();
  return { el: d, cursor };
}
function appendReasoning(el, reasoning) {
  if (!reasoning) return;
  const lines = reasoning.split('\n');
  const steps = lines.filter(l => l.trim().startsWith('💭')).length || lines.length;
  const det = document.createElement('details');
  det.className = 'reasoning';
  det.innerHTML = '<summary>💭 推理过程 (' + steps + '步) 点击展开</summary>' + esc(reasoning);
  det.open = false;
  el.insertBefore(det, el.firstChild);
}
function finishAssistantMsg(holder, evt) {
  holder.cursor.remove();
  if (evt.reasoning) appendReasoning(holder.el, evt.reasoning);
  const meta = document.createElement('div');
  meta.className = 'meta';
  const t = evt.tokens || {};
  let info = evt.ms ? '(' + evt.ms + 'ms' : '';
  if (t.sessionTotal || t.processTotal) info += (info ? '，' : '(') + '会话累计 ' + t.sessionTotal + '，总累计 ' + t.processTotal;
  info += (info ? ')' : '');
  meta.textContent = info;
  holder.el.appendChild(meta);
  scrollChat();
}
function scrollChat() {
  const l = $('#msgList');
  l.scrollTop = l.scrollHeight;
}

// ======================== 聊天发送（流式/非流式）=======================
async function sendMessage() {
  const input = $('#chatInput');
  const msg = input.value.trim();
  if (!msg || state.busy) return;
  input.value = '';
  appendMsg('user', msg);
  if (state.streamEnabled) await streamChat(msg);
  else await plainChat(msg);
}
async function plainChat(msg) {
  // 立即创建占位气泡（等待期间有可见反馈），完成后填充
  const holder = startAssistantMsg();
  holder.cursor.remove();
  const thinking = document.createElement('span');
  thinking.className = 'thinking';
  thinking.textContent = '🤔 思考中';
  holder.el.appendChild(thinking);
  setBusy(true);
  try {
    const r = await apiJson('/api/chat', { message: msg, sessionId: state.sessionId, loginId: state.loginId || '' });
    if (r.sessionId) { state.sessionId = r.sessionId; $('#sessionId').value = r.sessionId; localStorage.setItem('tlweb_session', r.sessionId); }
    if (r.success) {
      thinking.remove();
      if (r.reasoning) appendReasoning(holder.el, r.reasoning);
      // FIX: 原计划用 holder.el.firstChild.textContent —— 无推理时 firstChild 是光标（随后被 remove 导致回复丢失）、有推理时是 details 元素（被 textContent 摧毁）。
      // 修正：先移除光标，再在推理块之后追加文本节点
      holder.el.appendChild(document.createTextNode(r.response || ''));
      if (r.tokens && r.tokens.total) {
        const meta = document.createElement('div');
        meta.className = 'meta';
        meta.textContent = 'tokens 输入 ' + r.tokens.prompt + ' / 输出 ' + r.tokens.completion + ' / 合计 ' + r.tokens.total;
        holder.el.appendChild(meta);
      }
      scrollChat();
    } else {
      holder.el.remove();
      appendMsg('system', '[错误] ' + (r.error || r.response || '无响应'));
    }
  } catch (e) {
    holder.el.remove();
    appendMsg('system', '[错误] ' + e.message);
  } finally {
    setBusy(false);
  }
}
async function streamChat(msg, opts) {
  opts = opts || {};
  const holder = startAssistantMsg();
  setBusy(true);
  const ctrl = new AbortController();
  state.abort = ctrl;
  try {
    const resp = await fetch('/api/chatStream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: msg,
        sessionId: opts.sessionId || state.sessionId,
        loginId: state.loginId || '',
        resume: !!opts.resume   // 断点恢复：以断点时的用户消息继续执行（流式）
      }),
      signal: ctrl.signal
    });
    if (resp.status === 401) { holder.el.remove(); showLogin(); return; }
    if (!resp.ok) {
      let err = 'HTTP ' + resp.status;
      try { const j = await resp.json(); err = j.error || err; } catch (e) { /* ignore */ }
      holder.el.remove();
      appendMsg('system', '[错误] ' + err);
      return;
    }
    // 非 SSE 响应（JSON 错误，如"会话被占用"拒绝）→ 读 JSON 显示错误，不走流解析
    const ct = resp.headers.get('content-type') || '';
    if (!ct.includes('text/event-stream')) {
      let err = '';
      try { const j = await resp.json(); err = j.error || j.message || ''; } catch (e) { /* ignore */ }
      holder.el.remove();
      appendMsg('system', '[错误] ' + (err || '请求被拒绝'));
      return;
    }
    const reader = resp.body.getReader();
    const dec = new TextDecoder('utf-8');
    let buf = '';
    let reasoningBuf = '';
    let t0 = Date.now();
    // 空闲检测：长时间无 chunk（LLM 思考 / 工具调用循环）时显示"⏳ 思考中"
    let idleTimer = null;
    let idleSpan = null;
    const showIdle = () => {
      if (!idleSpan && holder.el.isConnected) {
        idleSpan = document.createElement('span');
        idleSpan.className = 'thinking';
        idleSpan.textContent = ' ⏳ 思考中';
        holder.el.appendChild(idleSpan);
        scrollChat();
      }
    };
    const clearIdle = () => {
      if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
      if (idleSpan) { idleSpan.remove(); idleSpan = null; }
    };
    const armIdle = () => {
      clearIdle();
      idleTimer = setTimeout(showIdle, 4000);
    };
    // 提交后立即显示"思考中"（不等 4 秒），chunk 到来时消失；长等待（>4s 无 chunk）再次出现
    showIdle();
    armIdle();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data: ')) continue;
          let evt;
          try { evt = JSON.parse(line.slice(6)); } catch (e) { continue; }
          if (evt.hb) continue;
          if (evt.chunk) {
            clearIdle();
            if (holder.cursor.parentNode) holder.cursor.remove();
            holder.el.textContent = (holder.el.textContent || '') + evt.chunk;
            scrollChat();
            armIdle();
          }
          if (evt.toolEvent) {
            clearIdle();
            renderToolResult(evt.toolName, evt.toolOutput);
            armIdle();
          }
          if (evt.reasoning) reasoningBuf = evt.reasoning;
          if (evt.done) {
            clearIdle();
            evt.ms = Date.now() - t0;
            if (!evt.reasoning && reasoningBuf) evt.reasoning = reasoningBuf;
            finishAssistantMsg(holder, evt);
            if (evt.error) appendMsg('system', '[流式错误] ' + evt.error);
            return;
          }
        }
      }
    }
    // 流意外结束
    clearIdle();
    buf += dec.decode();  // 冲刷流尾可能残留的半字符
    finishAssistantMsg(holder, { ms: Date.now() - t0 });
  } catch (e) {
    if (e.name === 'AbortError') {
      holder.cursor.remove();
      holder.el.appendChild(document.createTextNode('（已停止）'));
      scrollChat();
    } else {
      holder.el.remove();
      appendMsg('system', '[错误] ' + e.message);
    }
  } finally {
    setBusy(false);
    state.abort = null;
  }
}
async function stopChat() {
  try { await apiJson('/api/stopChat', { sessionId: state.sessionId }); } catch (e) { /* ignore */ }
  if (state.abort) state.abort.abort();
  setBusy(false);
}
function setBusy(b) {
  state.busy = b;
  $('#sendBtn').classList.toggle('hidden', b);
  $('#stopBtn').classList.toggle('hidden', !b);
}

// ======================== 文件上传 ========================
const UPLOAD_MAX_MB = 50;   // 与后端 uploadSizeMaxMB 保持一致
async function uploadFiles(files) {
  // 本地立即校验大小：超限文件不发请求，直接提示（大文件不上传，省流量）
  const over = files.filter(f => f.size > UPLOAD_MAX_MB * 1024 * 1024);
  const okFiles = files.filter(f => f.size <= UPLOAD_MAX_MB * 1024 * 1024);
  if (over.length) {
    toast('以下文件超过 ' + UPLOAD_MAX_MB + 'MB 限制，已跳过：' + over.map(f => f.name).join('、'), 'err');
  }
  if (!okFiles.length) return;
  const fd = new FormData();
  // 不同字段名 file_0/file_1...：uploadFile 的 filenames 是 Map<fieldName, savedName>，同 key 会互相覆盖
  okFiles.forEach((f, i) => fd.append('file_' + i, f));
  let resp;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 120000);   // 上传超时兜底（大文件慢速传输）
  try { resp = await fetch('/api/upload', { method: 'POST', body: fd, signal: ctrl.signal }); }  // 不设 Content-Type，浏览器自动带 boundary
  catch (e) {
    toast('上传失败：' + (e.name === 'AbortError' ? '上传超时（120秒）' : e.message), 'err');
    return;
  } finally { clearTimeout(timer); }
  let data = null;
  try { data = await resp.json(); } catch (e) { /* 非 JSON 响应 */ }
  if (resp.status === 401) { showLogin(); toast('未登录', 'err'); return; }
  if (!resp.ok || !data || data.success !== true) {
    toast((data && data.error) || ('上传失败 HTTP ' + resp.status), 'err');
    return;
  }
  const saved = data.filenames || [];
  okFiles.forEach((f, i) => { if (saved[i] != null) state.uploads.push({ name: f.name }); });
  renderUploadBar();
  toast('上传成功 ' + saved.length + ' 个文件', 'ok');
}
function renderUploadBar() {
  const bar = $('#uploadBar');
  if (!state.uploads.length) { bar.classList.add('hidden'); return; }
  bar.classList.remove('hidden');
  $('#uploadChips').innerHTML = state.uploads
    .map(u => '<span class="ub-chip" title="' + esc(u.name) + '">' + esc(u.name) + '</span>').join('');
}
function bindUpload() {
  $('#attachBtn').onclick = () => $('#fileInput').click();
  $('#fileInput').addEventListener('change', () => {
    const files = Array.from($('#fileInput').files || []);
    $('#fileInput').value = '';          // 允许连续选择同一文件
    if (files.length) uploadFiles(files);
  });
  $('#clearUploads').onclick = () => { state.uploads = []; renderUploadBar(); };
}

// ======================== 事件通道（审批推送）=======================
function openEvents() {
  if (state.events) state.events.close();
  const es = new EventSource('/api/events');
  es.onmessage = e => {
    let evt;
    try { evt = JSON.parse(e.data); } catch (err) { return; }
    if (evt.hb) return;
    if (evt.type === 'approval') showApprovalModal(evt);
    if (evt.type === 'kicked') {
      // 事件按 userId 广播，只有 loginId 匹配自己（被踢的那一方）才响应禁用
      if (evt.loginId && evt.loginId !== state.loginId) return;
      toast(evt.text || '该会话已被其他设备接管', 'err');
      $('#chatInput').disabled = true;
      $('#sendBtn').disabled = true;
      appendSysMsg('⚠ ' + (evt.text || '会话已被其他设备接管') + '（可开启新会话或继续其他会话）');
    }
  };
  es.onopen = () => { $('#connState').textContent = '●'; $('#connState').style.color = '#4ade80'; };
  es.onerror = () => { $('#connState').textContent = '○'; $('#connState').style.color = '#f87171'; };
  state.events = es;
}

// ======================== 事件绑定 ========================
function bindEvents() {
  $('#loginBtn').onclick = doLogin;
  $('#loginPwd').addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
  $('#logoutBtn').onclick = doLogout;
  $('#sendBtn').onclick = sendMessage;
  $('#stopBtn').onclick = stopChat;
  $('#chatInput').addEventListener('keydown', e => {
    if (e.isComposing || e.keyCode === 229) return;   // IME 组合中不发送
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
  });
  $('#streamToggle').onchange = e => { state.streamEnabled = e.target.checked; };
  $('#sessionId').onchange = e => {
    if (state.busy) { toast('有进行中的对话，请先停止', 'err'); e.target.value = state.sessionId; return; }
    const v = e.target.value.trim();
    if (v && v !== state.sessionId) { state.sessionId = v; localStorage.setItem('tlweb_session', v); toast('已切换会话ID: ' + v, 'ok'); }
  };
  // 断点提示弹窗：恢复执行（流式）/ 忽略
  $('#cpResumeBtn').onclick = () => {
    $('#checkpointModal').classList.add('hidden');
    resumeCheckpoint();
  };
  $('#cpIgnoreBtn').onclick = () => $('#checkpointModal').classList.add('hidden');
  $('#tabBar').addEventListener('click', e => {
    const btn = e.target.closest('button[data-tab]');
    if (!btn) return;
    document.querySelectorAll('#tabBar button').forEach(b => b.classList.toggle('active', b === btn));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === btn.dataset.tab));
  });
  $('#apApproveBtn').onclick = approveAction;
  $('#apRejectBtn').onclick = rejectAction;
  bindUpload();
}

document.addEventListener('DOMContentLoaded', () => {
  bindEvents();
  initSession();
});

// ======================== 面板：会话 ========================
async function loadSessions() {
  const box = $('#sessionsBox');
  box.innerHTML = '<div class="empty">加载中...</div>';
  try {
    const r = await apiCommand('sessions', { userId: state.userId });
    const rows = (r.data || []).map(s => ({
      sessionId: s.sessionId, agent: s.agentName || '', time: fmtTs(s.savedAt),
      count: (s.count == null ? '' : s.count) + '条', state: s.state || ''
    }));
    renderTable(box, [['sessionId', '会话ID'], ['agent', 'Agent'], ['time', '最后消息'], ['count', '轮次'], ['state', '状态']], rows, 'sessionId');
    // 每行加操作按钮
    [...box.querySelectorAll('table.tbl tr')].slice(1).forEach((tr, i) => {
      const sid = rows[i] && rows[i].sessionId;
      if (!sid) return;
      const td = document.createElement('td');
      const b1 = document.createElement('button'); b1.textContent = '继续'; b1.onclick = () => continueSession(sid);
      const b2 = document.createElement('button'); b2.textContent = '切换'; b2.onclick = () => switchSession(sid);
      const b3 = document.createElement('button'); b3.textContent = '清除上下文'; b3.onclick = () => clearSession(sid);
      const b4 = document.createElement('button'); b4.textContent = '删除'; b4.className = 'del'; b4.onclick = () => deleteSessionBtn(sid);
      td.appendChild(b1); td.appendChild(b2); td.appendChild(b3); td.appendChild(b4);
      tr.appendChild(td);
    });
    return r.data || [];
  } catch (e) {
    box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>';
    return [];
  }
}
/** 继续历史会话：恢复 sessionId + 加载历史到聊天窗。silent=true 时（登录自动接续）不弹 toast。返回 {ok, error} */
async function continueSession(sid, silent) {
  if (state.busy) {
    if (!silent) toast('有进行中的对话，请先停止', 'err');
    return { ok: false, error: '有进行中的对话，请先停止' };
  }
  try {
    const r = await apiCommand('continue', { userId: state.userId, sessionId: sid });
    if (r.success && r.data) {
      state.sessionId = r.data.sessionId || sid;
      localStorage.setItem('tlweb_session', state.sessionId);
      $('#sessionId').value = state.sessionId;
      // 恢复输入（会话被接管时禁用了）
      $('#chatInput').disabled = false;
      $('#sendBtn').disabled = false;
      $('#msgList').innerHTML = '';
      appendSysMsg('已恢复会话 ' + state.sessionId + ' (' + (r.data.count || 0) + ' 条历史)');
      (r.data.history || []).forEach(h => {
        if (!h || h.role === 'system' || !h.content) return;
        if (h.role === 'tool') { renderToolResult('tool', h.content); return; }  // 工具结果消息（含截图）
        appendMsg(h.role === 'user' ? 'user' : 'ai', h.content);
      });
      // 会话占用声明（登录级互斥）：
      // silent（登录自动接续）→ 静默登记，被占用仅提示不接管；
      // 非 silent（面板"继续"按钮=明确接管意图）→ 直接 force 接管（踢对方下线），不弹窗
      await claimSession(state.sessionId, !silent, silent);
    }
    if (!silent) toast(r.message || (r.error || ''), r.success ? 'ok' : 'err');
    return { ok: !!r.success, error: r.success ? null : (r.error || r.message || '未知错误') };
  } catch (e) {
    if (!silent) toast(e.message, 'err');
    return { ok: false, error: e.message };
  }
}
/**
 * 声明会话占用：
 * - force=true（面板"继续"=明确接管意图）→ 直接接管（踢对方下线）
 * - force=false（登录自动接续 silent）→ 被占用时仅提示，不接管、不切新会话
 */
async function claimSession(sid, force, silent) {
  if (!state.loginId) return;
  const r = await apiCommand('claimSession', { sessionId: sid, loginId: state.loginId, force: force || false });
  if (!r.success || !r.data) return;
  if (r.data.occupied) {
    appendSysMsg('⚠ 该会话正被另一登录使用（历史可查看，发消息将被拒绝；在会话面板点该会话『继续』可直接接管）');
    return;
  }
  if (r.data.kicked) {
    appendSysMsg('💡 已接管会话，对方已下线');
  }
}
async function switchSession(sid) {
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
  try {
    const r = await apiCommand('session', { sessionId: sid });
    if (!r.success) { toast(r.error || r.message, 'err'); return; }
    state.sessionId = sid;
    localStorage.setItem('tlweb_session', sid);
    $('#sessionId').value = sid;
    // 恢复输入（会话被接管时禁用了）
    $('#chatInput').disabled = false;
    $('#sendBtn').disabled = false;
    toast(r.message || sid, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}
async function clearSession(sid) {
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
  try {
    const r = await apiCommand('clear', { sessionId: sid });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
  } catch (e) { toast(e.message, 'err'); }
}
/** 删除会话：确认后删除该会话全部记录（DB/文件，不可恢复） */
async function deleteSessionBtn(sid) {
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
  if (!confirm('确认删除会话 ' + sid + ' ？\n将删除该会话的全部历史记录，不可恢复。')) return;
  try {
    const r = await apiCommand('deleteSession', { sessionId: sid });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success && state.sessionId === sid) {
      // 当前会话被删：切换到新会话
      state.sessionId = null;
      localStorage.removeItem('tlweb_session');
      $('#sessionId').value = '';
      $('#msgList').innerHTML = '';
      newSession();
    }
    if (r.success) loadSessions();
  } catch (e) { toast(e.message, 'err'); }
}
async function resumeCheckpoint() {
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
  try {
    const r = await apiCommand('resume', { userId: state.userId });
    if (!r.success) { toast(r.error || r.message, 'err'); return; }
    const d = r.data || {};
    // 切到断点会话（恢复以断点时的用户消息重新发起，走流式管道）
    state.sessionId = d.sessionId || state.sessionId;
    localStorage.setItem('tlweb_session', state.sessionId);
    $('#sessionId').value = state.sessionId;
    appendMsg('user', d.userMessage || '（断点消息）');
    toast('找到断点会话 ' + d.sessionId + '，正在恢复执行...', 'info');
    // 流式恢复：streamChat 内部管理 busy/光标/SSE 渲染
    await streamChat(d.userMessage || '', { sessionId: d.sessionId, resume: true });
  } catch (e) { toast(e.message, 'err'); }
}

/** 登录后检测断点会话：有则弹窗提示（仿命令行启动时的 ⚠ 提示），点"恢复执行"走流式恢复 */
async function promptCheckpoint() {
  try {
    const r = await apiCommand('resume', { userId: state.userId });
    if (!r.success || !r.data || !r.data.sessionId) return;   // 无断点
    const d = r.data;
    const timeStr = d.savedAt ? fmtTs(d.savedAt) : '未知';
    $('#cpDesc').textContent =
      'Agent:    ' + (d.agentName || '未知') + '\n' +
      '会话ID:   ' + d.sessionId + '\n' +
      '用户消息: ' + (d.userMessage || '') + '\n' +
      '中断时间: ' + timeStr + ' (第 ' + (d.iteration || '?') + ' 轮)\n\n' +
      '恢复将以断点时的用户消息继续执行（流式输出）；忽略则开始新对话。';
    $('#checkpointModal').classList.remove('hidden');
  } catch (e) { /* 静默：检测失败不打扰登录流程 */ }
}

// ======================== 面板：Agent/Skill ========================
async function loadAgents() {
  const box = $('#agentsBox');
  box.innerHTML = '<div class="empty">加载中...</div>';
  try {
    const r = await apiCommand('agents', {});
    const rows = (r.data || []).map(m => {
      const key = m.key != null ? m.key : (m.name || '?');
      const cls = m.className ? m.className.split('.').pop() : '?';
      return { key, cls };
    });
    renderTable(box, [['key', '家族名'], ['cls', '类']], rows);
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function loadSkills() {
  const box = $('#agentsBox');
  box.innerHTML = '<div class="empty">加载中...</div>';
  try {
    const r = await apiCommand('skills', {});
    const rows = (r.data || []).map(m => {
      const key = m.key != null ? m.key : (m.name || '?');
      const cls = m.className ? m.className.split('.').pop() : '?';
      return { key, cls };
    });
    renderTable(box, [['key', '家族名'], ['cls', '类']], rows);
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function loadParam() {
  const tool = $('#paramTool').value.trim();
  const box = $('#paramBox');
  if (!tool) { toast('请输入工具名', 'err'); return; }
  try {
    const r = await apiCommand('param', { toolName: tool });
    box.innerHTML = '';
    if (r.success) {
      const p = r.data || {};
      const entries = Object.entries(p);
      if (!entries.length) { box.innerHTML = '<div class="empty">（无参数）</div>'; return; }
      renderTable(box, [['k', '参数'], ['v', '值']], entries.map(([k, v]) => ({ k, v: typeof v === 'object' ? JSON.stringify(v) : v })));
    } else {
      box.innerHTML = '<div class="fail-msg">' + esc(r.error || r.message) + '</div>';
    }
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doInstall() {
  const type = $('#installType').value;
  const name = $('#installName').value.trim();
  const ref = $('#installRef').value.trim();
  const target = $('#installTarget').value.trim();
  const msgBox = $('#opMsg');
  if (!name) { toast('请输入名称/目录', 'err'); return; }
  const params = { type, name };
  if (ref) {
    if (type === 'skill') params.targetAgent = ref;
    else if (type === 'agent') params.classRef = ref;
    else params.classFile = ref;
  }
  if (target) params.targetAgent = target;
  try {
    const r = await apiCommand('install', params);
    msgBox.innerHTML = r.success
      ? '<div class="ok-msg">✓ ' + esc(r.message) + '</div>'
      : '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
  } catch (e) { msgBox.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doUninstall() {
  const type = $('#opType').value;
  const name = $('#opName').value.trim();
  const msgBox = $('#opMsg');
  if (!name) { toast('请输入名称/家族名', 'err'); return; }
  try {
    const r = await apiCommand('uninstall', { type, name });
    msgBox.innerHTML = r.success
      ? '<div class="ok-msg">✓ ' + esc(r.message) + '</div>'
      : '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
  } catch (e) { msgBox.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doReload() {
  const type = $('#opType').value;
  const name = $('#opName').value.trim();
  const msgBox = $('#opMsg');
  if (!name) { toast('请输入名称/家族名', 'err'); return; }
  try {
    const r = await apiCommand('reload', { type, name });
    msgBox.innerHTML = r.success
      ? '<div class="ok-msg">✓ ' + esc(r.message) + '</div>'
      : '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
  } catch (e) { msgBox.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}

// ======================== 面板：MCP ========================
let mcpLastKw = '';   // 最近一次搜索关键词（详情页返回时恢复）
async function mcpSearch() {
  const kw = $('#mcpKw').value.trim();
  mcpLastKw = kw;
  const box = $('#mcpSearchBox');
  box.innerHTML = '<div class="empty">搜索中...</div>';
  try {
    const r = await apiCommand('mcpSearch', kw ? { mcpKeyword: kw } : {});
    box.innerHTML = '';
    const list = r.data || [];
    if (!list.length) { box.innerHTML = '<div class="empty">（无结果）</div>'; return; }
    list.forEach((it, i) => {
      const d = document.createElement('div');
      d.className = 'box';
      d.innerHTML = '<div class="ok-msg">' + (i + 1) + '. ' + esc(it.name) + ' [' + esc(it.runtime || '?') + '/' + esc(it.category || '?') + ']</div>' +
        '<div class="empty">' + esc(it.description || '') + '</div>' +
        '<div class="empty">包: ' + esc(it.package) + ' 安装: ' + esc(it.installCmd || '') + (it.env ? ' 环境变量: ' + esc(it.env) : '') + '</div>';
      const b = document.createElement('button');
      b.textContent = '安装';
      b.onclick = () => mcpInstall(it.package);
      const d2 = document.createElement('div');
      d2.appendChild(b);
      const b2 = document.createElement('button');
      b2.textContent = '详情';
      b2.onclick = () => mcpInfo(it.package);
      d2.appendChild(b2);
      d.appendChild(d2);
      box.appendChild(d);
    });
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function mcpInfo(pkg) {
  const box = $('#mcpSearchBox');
  try {
    const r = await apiCommand('mcpInfo', { mcpPackage: pkg });
    if (!r.success) { box.innerHTML = '<div class="fail-msg">' + esc(r.error || r.message) + '</div>'; return; }
    const i = r.data || {};
    const lines = [];
    lines.push((i.name || i.package || pkg) + '  包: ' + (i.package || ''));
    if (i.version) lines.push('版本: ' + i.version);
    lines.push('运行时: ' + (i.runtime || '?') + ' / ' + (i.command || '?'));
    if (i.description) lines.push('\n【功能说明】\n' + i.description);
    if (i.keywords) lines.push('\n关键词: ' + i.keywords);
    if (i.tools) lines.push('\n【主要工具】\n' + i.tools);
    if (i.homepage) lines.push('\n主页: ' + i.homepage);
    if (i.repository) lines.push('仓库: ' + i.repository);
    if (i.env) lines.push('\n环境变量: ' + i.env);
    // 详情视图 + 返回按钮（返回时用上次关键词重跑搜索）
    box.innerHTML = '<div class="btn-row"><button onclick="mcpBackToList()">← 返回列表</button></div>'
      + '<pre class="out">' + esc(lines.join('\n')) + '</pre>';
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
/** 详情 → 返回列表：用上次关键词重新搜索 */
function mcpBackToList() {
  $('#mcpKw').value = mcpLastKw;
  mcpSearch();
}
async function mcpInstall(pkg) {
  try {
    const r = await apiCommand('mcpInstall', { mcpPackage: pkg });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success) mcpList();
  } catch (e) { toast(e.message, 'err'); }
}
async function mcpList() {
  const box = $('#mcpListBox');
  box.innerHTML = '<div class="empty">加载中...</div>';
  try {
    const r = await apiCommand('mcpList', {});
    const list = r.data || [];
    if (!list.length) { box.innerHTML = '<div class="empty">（无已安装 MCP Agent）</div>'; return; }
    const rows = list.map(m => ({
      name: m.familyName || m.name || '?',
      state: m.initialized ? 'OK' : '--',
      desc: m.description || ''
    }));
    renderTable(box, [['name', '家族名'], ['state', '状态'], ['desc', '描述']], rows);
    [...box.querySelectorAll('table.tbl tr')].slice(1).forEach((tr, i) => {
      const name = rows[i] && rows[i].name;
      if (!name) return;
      const td = document.createElement('td');
      const b = document.createElement('button');
      b.textContent = '卸载';
      b.onclick = () => {
        if (confirm('确认卸载 MCP Agent ' + name + ' ?')) mcpRemove(name);
      };
      td.appendChild(b);
      tr.appendChild(td);
    });
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function mcpRemove(name) {
  try {
    const r = await apiCommand('mcpRemove', { agentName: name });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success) mcpList();
  } catch (e) { toast(e.message, 'err'); }
}

// ======================== 面板：评测/测试 ========================
async function evalCmd(sub, arg) {
  const box = $('#evalBox');
  box.innerHTML = '<div class="empty">执行中...</div>';
  const params = { subAction: sub };
  if (sub === 'run') params.caseId = $('#evalCase').value.trim();
  if (sub === 'cascade' && $('#evalAgent').value.trim()) params.agent = $('#evalAgent').value.trim();
  if (sub === 'run' && !params.caseId) { toast('请输入 caseId', 'err'); box.innerHTML = ''; return; }
  try {
    const r = await apiCommand('eval', params);
    box.innerHTML = '';
    if (r.success) {
      if (sub === 'list') {
        const rows = (r.data || []).map(c => ({ c: typeof c === 'string' ? c : JSON.stringify(c) }));
        renderTable(box, [['c', '用例']], rows);
      } else if (r.data && typeof r.data === 'object') {
        const d = r.data;
        let html = '<div class="ok-msg">✓ 评测完成: ' + d.passed + '/' + d.total + ' 通过' +
          (d.passRate ? ' (' + Math.round(d.passRate * 100) + '%)' : '') + '</div>';
        if (d.reportPath) html += '<div class="empty">报告: ' + esc(d.reportPath) + '</div>';
        box.innerHTML = html;
      } else {
        box.innerHTML = '<div class="ok-msg">✓ ' + esc(r.message) + '</div>';
      }
    } else {
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
    }
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function testCmd(mode) {
  const box = $('#testBox');
  box.innerHTML = '<div class="empty">执行中...</div>';
  try {
    let params = {};
    if (mode === 'list') params = { caseName: 'list' };
    else if (mode === 'one') {
      const c = $('#testCase').value.trim();
      if (!c) { toast('请输入用例名', 'err'); box.innerHTML = ''; return; }
      params = { caseName: c };
    }
    const r = await apiCommand('test', params);
    box.innerHTML = '';
    if (!r.success) { box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>'; return; }
    if (mode === 'list') {
      const rows = (r.data || []).map(c => ({ c }));
      renderTable(box, [['c', '用例']], rows);
    } else {
      const d = r.data || {};
      box.innerHTML = '<div class="ok-msg">✓ 测试完成: ' + d.passed + '/' + d.total + ' 通过' +
        (d.failed ? '，失败 ' + d.failed : '') + '</div>';
    }
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}

// ======================== 面板：追踪/统计 ========================
// 环节英文 → 中文标签（服务层返回原始 stage，展示转换在 UI 层）
const STAGE_CN = { roundStart: '轮次开始', llmRequest: '发送LLM', llmResponse: 'LLM返回',
  toolStart: '工具开始', toolEnd: '工具结束', approvalRequested: '请求审批', roundEnd: '轮次结束' };
const TRACE_HEADERS = [['time', '时间'], ['stage', '环节'], ['agent', 'Agent'], ['dur', '耗时'], ['detail', '摘要']];
function fmtTraceTime(ts) {
  const d = new Date(ts);
  const p = x => String(x).padStart(2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}
function toTraceRows(arr) {
  return arr.map(s => ({
    time: fmtTraceTime(s.ts),
    stage: STAGE_CN[s.stage] || s.stage,
    agent: s.agentName,
    dur: s.durationMs > 0 ? s.durationMs + 'ms' : '',
    detail: s.detail || ''
  }));
}
async function doTrace() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">查询中...</div>';
  try {
    const r = await apiCommand('trace', { sessionId: state.sessionId });
    box.innerHTML = '';
    if (r.success) {
      const arr = Array.isArray(r.data) ? r.data : [];
      if (arr.length) {
        renderTable(box, TRACE_HEADERS, toTraceRows(arr), null);
      } else {
        renderPre(box, ['（无环节记录）']);
      }
    } else {
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
    }
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doTraceLlm() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">查询中...</div>';
  try {
    const r = await apiCommand('traceLlm', { sessionId: state.sessionId });
    box.innerHTML = '';
    if (r.success) {
      const arr = Array.isArray(r.data) ? r.data : [];
      if (arr.length) {
        // 表格行与 payload 折叠块交错：每条环节行下方紧跟其完整内容（跨列单元格）
        const rows = toTraceRows(arr);
        let h = '<table class="tbl"><tr>' + TRACE_HEADERS.map(x => '<th>' + esc(x[1]) + '</th>').join('') + '</tr>';
        arr.forEach((s, i) => {
          h += '<tr>' + TRACE_HEADERS.map(x => '<td>' + esc(rows[i][x[0]]) + '</td>').join('') + '</tr>';
          if (s.payload) {
            h += '<tr><td colspan="' + TRACE_HEADERS.length + '"><details><summary>[' + (i + 1) + '] ' + esc(STAGE_CN[s.stage] || s.stage) + ' - ' + esc(s.detail || '') + '</summary><pre>' + esc(s.payload) + '</pre></details></td></tr>';
          }
        });
        box.innerHTML = h + '</table>';
      } else {
        renderPre(box, ['（无环节记录）']);
      }
    } else {
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
    }
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doStats() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">统计中...</div>';
  try {
    const r = await apiCommand('stats', { sessionId: state.sessionId, userId: state.userId });
    renderPre(box, r.success ? (Array.isArray(r.data) ? r.data : [r.message]) : [r.error || r.message]);
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doStatsAll() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">统计中...</div>';
  try {
    const r = await apiCommand('statsAll', { userId: state.userId });
    renderPre(box, r.success ? (Array.isArray(r.data) ? r.data : [r.message]) : [r.error || r.message]);
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
async function doStatsAgent() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">统计中...</div>';
  try {
    const r = await apiCommand('statsAgent', { sessionId: state.sessionId, userId: state.userId });
    renderPre(box, r.success ? (Array.isArray(r.data) ? r.data : [r.message]) : [r.error || r.message]);
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}

// ======================== 审批弹框 ========================
let currentApproval = null;
function showApprovalModal(evt) {
  currentApproval = evt;
  $('#apTitle').textContent = '⚠ 审批请求：' + (evt.toolName || '未知工具');
  $('#apDesc').textContent = evt.description || '需要您的确认';
  $('#apArgs').value = evt.args || '{}';
  $('#apReason').value = '';
  $('#apMsg').classList.add('hidden');
  $('#approvalModal').classList.remove('hidden');
}
async function approveAction() {
  const evt = currentApproval;
  if (!evt) return;
  let modifiedArgs = null;
  try {
    modifiedArgs = JSON.parse($('#apArgs').value);
  } catch (e) {
    $('#apMsg').textContent = '参数 JSON 格式错误：' + e.message;
    $('#apMsg').classList.remove('hidden');
    return;
  }
  try {
    const r = await apiCommand('approve', { subAction: 'approve', approvalId: evt.approvalId, approvalModifiedArguments: modifiedArgs });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success) $('#approvalModal').classList.add('hidden');
  } catch (e) { toast(e.message, 'err'); }
}
async function rejectAction() {
  const evt = currentApproval;
  if (!evt) return;
  const reason = $('#apReason').value.trim() || '用户拒绝';
  try {
    const r = await apiCommand('approve', { subAction: 'reject', approvalId: evt.approvalId, reason });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success) $('#approvalModal').classList.add('hidden');
  } catch (e) { toast(e.message, 'err'); }
}

// ======================== 命令辅助 ========================
async function apiCommand(action, params) {
  return await apiJson('/api/command', { action, params: params || {} });
}
