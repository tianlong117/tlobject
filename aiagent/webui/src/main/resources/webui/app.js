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
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
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
  appendSysMsg('已登录：' + state.userId + '（管理命令在右侧面板）');
  showView('chat');
  openEvents();
  autoResumeLast();
}

/** 登录后自动接续最近活跃的会话（有则恢复上下文并渲染历史；无则提示新开始） */
async function autoResumeLast() {
  try {
    const sessions = await loadSessions();
    // 列表按 last_active 倒序：取第一个非当前会话（即最近一次用过的会话，不论内容多少——
    // 不用 count/state 过滤，msg_count 可能被空轮覆盖为 0 导致误跳过）
    const last = (sessions || []).find(s => s.sessionId && s.sessionId !== state.sessionId);
    if (!last) {
      appendSysMsg('💡 暂无历史会话，已开始新会话 ' + state.sessionId + '，直接输入消息即可');
      return;
    }
    const res = await continueSession(last.sessionId, true);   // 内部会清空并渲染历史
    if (res.ok) {
      appendSysMsg('💡 已自动接续最近会话，可在右侧『会话』面板切换其他历史会话');
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
    const r = await apiJson('/api/chat', { message: msg, sessionId: state.sessionId });
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
    if (resp.status === 401) { holder.el.remove(); showLogin(); return; }
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
    // 空闲检测：长时间无 chunk（LLM 思考 / 工具调用循环）时显示"⏳ 思考中"
    let idleTimer = null;
    let idleSpan = null;
    const clearIdle = () => {
      if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
      if (idleSpan) { idleSpan.remove(); idleSpan = null; }
    };
    const armIdle = () => {
      clearIdle();
      idleTimer = setTimeout(() => {
        if (!idleSpan && holder.el.isConnected) {
          idleSpan = document.createElement('span');
          idleSpan.className = 'thinking';
          idleSpan.textContent = ' ⏳ 思考中';
          holder.el.appendChild(idleSpan);
          scrollChat();
        }
      }, 4000);
    };
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
    if (e.isComposing || e.keyCode === 229) return;   // IME 组合中不发送
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
  });
  $('#streamToggle').onchange = e => { state.streamEnabled = e.target.checked; };
  $('#sessionId').onchange = e => {
    if (state.busy) { toast('有进行中的对话，请先停止', 'err'); e.target.value = state.sessionId; return; }
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
    renderTable(box, [['sessionId', '会话ID'], ['agent', 'Agent'], ['time', '时间'], ['count', '轮次'], ['state', '状态']], rows, 'sessionId');
    // 每行加操作按钮
    [...box.querySelectorAll('table.tbl tr')].slice(1).forEach((tr, i) => {
      const sid = rows[i] && rows[i].sessionId;
      if (!sid) return;
      const td = document.createElement('td');
      const b1 = document.createElement('button'); b1.textContent = '继续'; b1.onclick = () => continueSession(sid);
      const b2 = document.createElement('button'); b2.textContent = '切换'; b2.onclick = () => switchSession(sid);
      const b3 = document.createElement('button'); b3.textContent = '清除'; b3.onclick = () => clearSession(sid);
      td.appendChild(b1); td.appendChild(b2); td.appendChild(b3);
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
      $('#msgList').innerHTML = '';
      appendSysMsg('已恢复会话 ' + state.sessionId + ' (' + (r.data.count || 0) + ' 条历史)');
      (r.data.history || []).forEach(h => {
        if (h && h.role !== 'system' && h.content) appendMsg(h.role === 'user' ? 'user' : 'ai', h.content);
      });
    }
    if (!silent) toast(r.message || (r.error || ''), r.success ? 'ok' : 'err');
    return { ok: !!r.success, error: r.success ? null : (r.error || r.message || '未知错误') };
  } catch (e) {
    if (!silent) toast(e.message, 'err');
    return { ok: false, error: e.message };
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
async function resumeCheckpoint() {
  if (state.busy) { toast('有进行中的对话，请先停止', 'err'); return; }
  try {
    const r = await apiCommand('resume', { userId: state.userId });
    if (!r.success) { toast(r.error || r.message, 'err'); return; }
    const d = r.data || {};
    toast('找到断点会话 ' + d.sessionId + '，正在恢复执行...', 'info');
    // 断点恢复 = 以断点时的用户消息发起 resume chat（阻塞式，加 busy 保护防重入）
    setBusy(true);
    try {
      const cr = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: d.userMessage || '', sessionId: d.sessionId, resume: true })
      }).then(x => x.json());
      state.sessionId = d.sessionId || state.sessionId;
      localStorage.setItem('tlweb_session', state.sessionId);
      $('#sessionId').value = state.sessionId;
      if (cr.success) {
        appendMsg('user', d.userMessage || '（断点消息）');
        const holder = startAssistantMsg();
        // FIX: 先移除光标再追加文本（原计划 firstChild.textContent 会把回复写进光标后被 remove 丢失）
        holder.cursor.remove();
        holder.el.appendChild(document.createTextNode(cr.response || ''));
        if (cr.reasoning) appendReasoning(holder.el, cr.reasoning);
        scrollChat();
      } else {
        appendMsg('system', '[错误] ' + (cr.error || cr.response || ''));
      }
    } finally {
      setBusy(false);
    }
  } catch (e) { toast(e.message, 'err'); }
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
async function doTrace() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">查询中...</div>';
  try {
    const r = await apiCommand('trace', { sessionId: state.sessionId });
    box.innerHTML = '';
    if (r.success) {
      const lines = r.data;
      if (Array.isArray(lines) && lines.length) renderPre(box, lines);
      else renderPre(box, ['（无环节记录）']);
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
