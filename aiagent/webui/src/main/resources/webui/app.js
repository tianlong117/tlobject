/* AI Agent Web 前端 — 原生 JS，零依赖 */
'use strict';

// ======================== 状态 ========================
const state = {
  userId: null,
  sessionId: localStorage.getItem('tlweb_session') || null,
  streamEnabled: true,
  busy: false,           // 有活跃 chat
  abort: null,           // AbortController（流式聊天）
  events: null           // EventSource
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
  state.sessionId = 'webchat_' + state.userId + '_' + Date.now();
  localStorage.setItem('tlweb_session', state.sessionId);
  $('#sessionId').value = state.sessionId;
  $('#msgList').innerHTML = '';
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
    $('#loginErr').classList.add('hidden');
    enterChat();
  } catch (e) {
    $('#loginErr').textContent = e.message;
    $('#loginErr').classList.remove('hidden');
  }
}
async function doLogout() {
  try { await apiJson('/api/logout', {}); } catch (e) { /* 忽略 */ }
  state.userId = null;
  state.sessionId = null;
  localStorage.removeItem('tlweb_session');
  showLogin();
}
function enterChat() {
  if (!state.sessionId) newSession();
  $('#userTag').textContent = '👤 ' + state.userId;
  $('#sessionId').value = state.sessionId;
  $('#msgList').innerHTML = '';
  appendSysMsg('已登录：' + state.userId + '，会话 ' + state.sessionId + '（管理命令在右侧面板）');
  showView('chat');
  openEvents();
  loadSessions();
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
  setBusy(true);
  try {
    const r = await apiJson('/api/chat', { message: msg, sessionId: state.sessionId });
    if (r.sessionId) { state.sessionId = r.sessionId; $('#sessionId').value = r.sessionId; localStorage.setItem('tlweb_session', r.sessionId); }
    if (r.success) {
      const holder = startAssistantMsg();
      if (r.reasoning) appendReasoning(holder.el, r.reasoning);
      // FIX: 原计划用 holder.el.firstChild.textContent —— 无推理时 firstChild 是光标（随后被 remove 导致回复丢失）、有推理时是 details 元素（被 textContent 摧毁）。
      // 修正：先移除光标，再在推理块之后追加文本节点
      holder.cursor.remove();
      holder.el.appendChild(document.createTextNode(r.response || ''));
      if (r.tokens && r.tokens.total) {
        const meta = document.createElement('div');
        meta.className = 'meta';
        meta.textContent = 'tokens 输入 ' + r.tokens.prompt + ' / 输出 ' + r.tokens.completion + ' / 合计 ' + r.tokens.total;
        holder.el.appendChild(meta);
      }
      scrollChat();
    } else {
      appendMsg('system', '[错误] ' + (r.error || r.response || '无响应'));
    }
  } catch (e) {
    appendMsg('system', '[错误] ' + e.message);
  } finally {
    setBusy(false);
  }
}
async function streamChat(msg) {
  const holder = startAssistantMsg();
  setBusy(true);
  const ctrl = new AbortController();
  state.abort = ctrl;
  try {
    const resp = await fetch('/api/chatStream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: msg, sessionId: state.sessionId }),
      signal: ctrl.signal
    });
    if (resp.status === 401) { showLogin(); return; }
    if (!resp.ok) {
      let err = 'HTTP ' + resp.status;
      try { const j = await resp.json(); err = j.error || err; } catch (e) { /* ignore */ }
      holder.el.remove();
      appendMsg('system', '[错误] ' + err);
      return;
    }
    const reader = resp.body.getReader();
    const dec = new TextDecoder('utf-8');
    let buf = '';
    let reasoningBuf = '';
    let t0 = Date.now();
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
            if (holder.cursor.parentNode) holder.cursor.remove();
            holder.el.textContent = (holder.el.textContent || '') + evt.chunk;
            scrollChat();
          }
          if (evt.reasoning) reasoningBuf = evt.reasoning;
          if (evt.done) {
            evt.ms = Date.now() - t0;
            finishAssistantMsg(holder, evt);
            return;
          }
        }
      }
    }
    // 流意外结束
    finishAssistantMsg(holder, { ms: Date.now() - t0 });
  } catch (e) {
    if (e.name !== 'AbortError') {
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

// ======================== 事件通道（审批推送）=======================
function openEvents() {
  if (state.events) state.events.close();
  const es = new EventSource('/api/events');
  es.onmessage = e => {
    let evt;
    try { evt = JSON.parse(e.data); } catch (err) { return; }
    if (evt.hb) return;
    if (evt.type === 'approval') showApprovalModal(evt);
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
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
  });
  $('#streamToggle').onchange = e => { state.streamEnabled = e.target.checked; };
  $('#sessionId').onchange = e => {
    const v = e.target.value.trim();
    if (v && v !== state.sessionId) { state.sessionId = v; localStorage.setItem('tlweb_session', v); toast('已切换会话ID: ' + v, 'ok'); }
  };
  $('#tabBar').addEventListener('click', e => {
    const btn = e.target.closest('button[data-tab]');
    if (!btn) return;
    document.querySelectorAll('#tabBar button').forEach(b => b.classList.toggle('active', b === btn));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === btn.dataset.tab));
  });
  $('#apApproveBtn').onclick = approveAction;
  $('#apRejectBtn').onclick = rejectAction;
}

document.addEventListener('DOMContentLoaded', () => {
  bindEvents();
  initSession();
});

// ===== 第二部分（面板函数）在 Task 9 追加 =====
