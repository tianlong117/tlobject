/* AI Agent Web 前端 — 原生 JS，零依赖 */
'use strict';

// ======================== 状态 ========================
const state = {
  userId: null,
  sessionId: localStorage.getItem('tlweb_session') || null,  // 当前显示会话（含义不变）
  streamEnabled: true,
  events: null,          // EventSource
  sessions: Object.create(null),   // sessionId -> 每会话视图（box/uploads/busy/abort/unread/tasks/draft）
                                   // 无原型：sessionId 用户可控（"__proto__"/"constructor" 会命中原型链 → curBox() 拿到坏值）
  currentBox: null,      // 当前会话的 DOM 盒子（挂到 #msgList 上的唯一内容盒子）
  drawer: null,          // 任务详情抽屉当前展示的任务 {tid, sid}；null=未打开
  autoRun: false,        // 当前会话的自动运行（通配授权）状态，由 refreshAutoState 同步
  pendingAbandonSessionId: null   // 断点弹框「放弃该操作」目标会话（仅 pending_approval 断点非空，见 promptCheckpoint）
};

// ======================== 每会话视图（状态分片） ========================
// 每会话一个独立 box：脱离 DOM 仍可写入（后台会话的流不丢），切回时挂上即可
function viewOf(sid) {
  if (!state.sessions[sid]) {
    const box = document.createElement('div');
    // 布局类不能少：box 是 #msgList（flex 列）的唯一 item，少了它 .msg 的
    // align-self/max-width/gap 全部失效（气泡左对齐、满宽、无间距）
    box.className = 'msg-box';
    state.sessions[sid] = {
      box: box,
      uploads: [],           // 已上传文件名（本会话草稿区，切会话各带各的）
      busy: false, abort: null, unread: 0, tasks: {}, draft: '',
      kicked: false,         // 被其他设备接管 → 本会话禁输入（切回来也不解锁；点「接管」夺回后清除）
      todo: null,            // 最近一次任务清单事件（todo 技能推送；null=无清单 → 清单条隐藏）
      todoExpanded: false    // 清单条展开态（按会话记忆，切回来保持）
    };
  }
  return state.sessions[sid];
}
function curBox() { return viewOf(state.sessionId).box; }
/** 切到目标会话视图：暂存草稿 → 摘旧 box → 挂新 box → 恢复草稿/忙碌态 */
function switchSessionView(sid) {
  if (!sid) return;
  const prev = state.sessionId;
  if (prev && state.sessions[prev]) {
    state.sessions[prev].draft = $('#chatInput').value;
  }
  // 摘旧 box 不依赖 prev：换用户登录等场景 sessionId 可能已清空而 box 仍挂在 #msgList 上
  if (state.currentBox && state.currentBox.parentNode)
    state.currentBox.parentNode.removeChild(state.currentBox);
  state.sessionId = sid;
  localStorage.setItem('tlweb_session', sid);
  $('#sessionId').value = sid;
  const v = viewOf(sid);
  v.unread = 0;
  state.currentBox = v.box;
  const ml = $('#msgList');
  ml.appendChild(v.box);
  ml.scrollTop = ml.scrollHeight;   // 切回会话直接停在最新消息处
  $('#chatInput').value = v.draft || '';
  // 被接管的会话保持禁输入（旧版无条件启用 → 切回被接管会话仍可输入）；点「接管」后由 claimSession 清除
  $('#chatInput').disabled = !!v.kicked;
  $('#sendBtn').disabled = !!v.kicked;
  setBusy(v.busy);
  renderUploadBar();                // 附件条随会话切换（各会话草稿区附件独立）
  renderTodoBar();                  // 任务清单条同理（各会话各看各的清单/展开态）
  renderSessionList();   // P11 已实现该函数（函数声明，hoisting 保证可用），原 typeof 守卫恒真故直接调用
  reportCurrentSession();
  // 自动运行态随会话走（通配授权按会话隔离）：收口在这里，覆盖全部切会话路径——
  // newSession / 手输 #sessionId / resumeCheckpoint / continueSession / switchSession / 登录。
  // 此前只在 continueSession/switchSession 两处刷，newSession 等路径漏刷 → 新会话误显示 🔓。
  // 函数声明 hoisting 保证本调用先于定义可用；内部有 sid 空守卫与"切走则丢弃迟到响应"守卫
  refreshAutoState();
}
/** 清空某会话内容（新会话/清除上下文用） */
function clearSessionBox(sid) { viewOf(sid).box.innerHTML = ''; }

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
/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前；时间戳缺失/非法 → 未知时间（勿冒充"刚刚"） */
function fmtAgo(ts) {
  const n = Number(ts) || 0;
  if (!n) return '未知时间';
  const s = Math.max(0, Math.floor((Date.now() - n) / 1000));
  if (s < 60) return '刚刚';
  if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
  if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
  return Math.floor(s / 86400) + ' 天前';
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
// ======================== 报告正文（极简 markdown 渲染） ========================
// 只覆盖报告用到的语法：标题 / 列表 / 表格 / 加粗。
// 所有内容先 esc() 再拼——用例名与 LLM 裁判理由都是外部输入，不能当 HTML 用。
function mdEl(tag, text, cls) {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  el.innerHTML = esc(text).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  return el;
}
function renderMarkdown(container, md) {
  container.innerHTML = '';
  if (!md) { container.innerHTML = '<div class="empty">（报告为空）</div>'; return; }
  const lines = String(md).split('\n');
  const splitCells = row => row.trim().replace(/^\||\|$/g, '').split('|')
      .map(s => s.trim().replace(/\\\|/g, '|'));
  let i = 0;
  while (i < lines.length) {
    const line = lines[i].trim();
    if (!line) { i++; continue; }
    // 连续的 | 行 = 一张表（第二行是 |---|---| 分隔行，跳过）
    if (line.startsWith('|')) {
      const block = [];
      while (i < lines.length && lines[i].trim().startsWith('|')) { block.push(lines[i]); i++; }
      const headers = splitCells(block[0]);
      const body = block.slice(1).filter(r => !/^\|[\s\-:|]+\|$/.test(r.trim()));
      const rows = body.map(r => {
        const cells = splitCells(r);
        const o = {};
        headers.forEach((h, k) => { o['c' + k] = cells[k] || ''; });
        return o;
      });
      const tableBox = document.createElement('div');
      renderTable(tableBox, headers.map((h, k) => ['c' + k, h]), rows);
      container.appendChild(tableBox);
      continue;
    }
    if (line.startsWith('### ')) { container.appendChild(mdEl('h5', line.slice(4))); i++; continue; }
    if (line.startsWith('## ')) { container.appendChild(mdEl('h4', line.slice(3))); i++; continue; }
    if (line.startsWith('# ')) { container.appendChild(mdEl('h3', line.slice(2))); i++; continue; }
    if (line.startsWith('- ')) { container.appendChild(mdEl('div', '• ' + line.slice(2), 'md-li')); i++; continue; }
    container.appendChild(mdEl('div', line, 'md-p'));
    i++;
  }
}
/** 把报告正文渲染到 box 末尾（有就显示；接口没带就算了） */
function appendReport(box, d) {
  if (!d || !d.reportMarkdown) return;
  const wrap = document.createElement('div');
  wrap.className = 'md-box';
  box.appendChild(wrap);
  renderMarkdown(wrap, d.reportMarkdown);
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
  // 幂等：当前就停在一个"从未发过消息的空白新会话"上时，不再生成新 ID——
  // 连点"新会话"原来会刷出一串悬空 ID（顶栏/localStorage 反复被覆盖，用户以为产生了很多会话）
  const cur = state.sessionId && state.sessions[state.sessionId];
  const isBlankNew = cur && !cur.busy && cur.box.childNodes.length === 0
      && !(lastSessionsData || []).some(x => x.sessionId === state.sessionId);
  if (isBlankNew) {
    $('#chatInput').focus();
    toast('已在新会话中（输入内容发送后才真正创建）', 'ok');
    return;
  }
  const sid = 'webchat_' + state.userId + '_' + Date.now();
  switchSessionView(sid);   // 新 sid 自然是全新空视图，无需清屏
  $('#chatInput').focus();  // 恢复输入（会话被接管时禁用了）并聚焦
  toast('已开新会话: ' + sid, 'ok');
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
  state.drawer = null;                                 // 登出收起任务详情抽屉（否则残留并挡住登录页）
  $('#taskDrawer').classList.add('hidden');
}
async function initSession() {
  try {
    const r = await fetch('/api/session').then(x => x.json());
    if (r.loggedIn) {
      state.userId = r.userId;
      const prevUser = localStorage.getItem('tlweb_userid');
      const prevSid = localStorage.getItem('tlweb_session');
      // 刷新页面：同用户才恢复上次会话（公共设备切换用户时不把上一用户的会话ID带进来）
      state.sessionId = (prevUser === r.userId) ? prevSid : null;
      if (prevUser && prevUser !== r.userId) {
        localStorage.removeItem('tlweb_session');
      }
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
    // 归属不一致（含 prevUser 为空但 localStorage 残留 session 的情况）一律重置，
    // 与 initSession 的 `prevUser === r.userId` 语义对齐
    if (prevUser !== userId) {
      state.sessionId = null;
      localStorage.removeItem('tlweb_session');
      renderUploadBar();     // sessionId 已清 → 附件条隐藏（各会话 uploads 随下方视图表重置）
      renderTodoBar();       // 同上：清单条也隐藏（视图表随后被整体重置）
      // 每会话视图随用户重置：清掉视图表（旧 box 由 switchSessionView 从 #msgList 摘除），
      // 避免上一用户的自定义会话ID/内容残留在本页面内存里
      state.sessions = Object.create(null);   // 同初始化：无原型，见 state 定义处说明
      lastSessionsData = null;   // 侧栏列表缓存同理重置（否则短暂显示上一用户的会话）
      lastSessionsError = null;  // 失败文案一并重置（换用户后显示新用户的加载态/结果）
    } else {
      state.sessionId = prevSid;
    }
    // 登录实例标识（会话占用互斥用）：同浏览器刷新/重登保留；没有则生成（首次登录/登出后再登/旧版升级）
    // 换用户时必须生成新的（旧 loginId 属于上一用户）
    state.loginId = localStorage.getItem('tlweb_loginid');
    if (!state.loginId || prevUser !== userId) {
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
  switchSessionView(state.sessionId);   // 挂载初始会话视图（登录/刷新）
  appendSysMsg('已登录：' + state.userId + '（管理命令在右侧面板）');
  showView('chat');
  openEvents();
  refreshInboxBadge();      // 登录即拉未读数（离线期间的任务结果会在消息箱提示）
  // 自动运行开关态不必在此显式刷：上面 switchSessionView 已收口（同 sid，重复发请求）
  await autoResumeLast();   // 先接续最近活跃会话并渲染历史
  promptCheckpoint();       // 再检测断点会话 → 弹窗提示（如命令行启动时的 ⚠ 提示）
  setTimeout(reportCurrentSession, 500);   // 会话恢复是异步的，等 autoResumeLast 落定后上报
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
    if (res.ok && res.busy) {
      appendSysMsg('💡 已切到正在运行的会话（流式输出进行中，历史可在完成后刷新查看）');
    } else if (res.ok) {
      appendSysMsg('💡 已自动接续最近活跃会话（最后消息 ' + fmtTs(last.savedAt) + '），可在左侧会话栏切换其他历史会话');
    } else {
      appendSysMsg('💡 自动接续失败：' + (res.error || '未知原因') + '。已开始新会话 ' + state.sessionId + '（可在左侧会话栏选一个会话点「接管」）');
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
  curBox().appendChild(d);
  scrollChat();
}
/** box 可选：指定目标会话的盒子（后台会话的流式收尾用），缺省 = 当前视图 */
function appendMsg(role, content, box) {
  const d = document.createElement('div');
  d.className = 'msg ' + role;
  d.textContent = content == null ? '' : content;
  (box || curBox()).appendChild(d);
  scrollChat(box);
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
/** 渲染工具结果卡片：browser 截图显示为图片，原始输出折叠（超长 base64 不刷屏）。
 *  box 可选：后台会话的流式工具卡片要落回它自己的盒子，缺省 = 当前视图 */
const TOOL_PROGRESS_TEXT = {
  starting: '运行中…', done: '完成', rejected: '已拒绝', pending: '等待审批',
  timeout: '超时', aborted: '已中断', error: '失败', skipped: '未执行', clarified: '需要澄清'
};
const TOOL_PROGRESS_ICON = {
  starting: '⚙', done: '✓', rejected: '✗', pending: '⏸',
  timeout: '✗', aborted: '⏹', error: '✗', skipped: '⏭', clarified: '❓'
};
const TOOL_PROGRESS_TERMINAL = new Set([
  'done', 'rejected', 'pending', 'timeout', 'aborted', 'error', 'skipped', 'clarified'
]);

/** 工具实时进度：按 executionId 分组、按 toolCallId upsert 的瞬时清单（不显示工具输出） */
function applyToolProgress(evt) {
  const sid = evt.sessionId;
  if (!sid) return;
  // 只渲染已知会话：当前会话或本标签页已打开的视图。不为侧栏/后台任务创建视图——
  // 否则空 box 里先塞进进度清单会让 switchSession 误判"已有内容"而跳过历史加载。
  const known = sid === state.sessionId
      || !!(state.sessions && state.sessions[sid]);
  if (!known) return;
  const v = viewOf(sid);
  // 只渲染本标签页正在运行的会话：/stop 的迟到 aborted、其他标签页、后台注入轮都不重建清单。
  if (!v.box || !v.busy) return;

  let panel = v.toolProgressEl;
  if (!panel || !panel.parentNode) {   // 不能用 isConnected：后台会话 box 本来就脱离 DOM
    panel = document.createElement('div');
    panel.className = 'tool-progress';
    v.box.appendChild(panel);
    v.toolProgressEl = panel;
    panel._rows = {};
    panel._done = 0;
    panel._total = 0;
    panel._executionId = '';
  }
  if (panel._executionId !== (evt.executionId || '')) {
    panel._executionId = evt.executionId || '';
    panel.innerHTML = '';
    panel._rows = {};
    panel._done = 0;
    panel._total = evt.total || 0;
    const head = document.createElement('div');
    head.className = 'tp-head';
    panel.appendChild(head);
    panel._head = head;
  }
  panel._total = evt.total || panel._total || 0;

  const id = evt.toolCallId || ((evt.toolName || 'tool') + '_' + evt.index);
  let row = panel._rows[id];
  if (!row) {
    row = document.createElement('div');
    row.className = 'tp-row';
    panel.appendChild(row);
    panel._rows[id] = row;
    row._terminal = false;
  }
  const terminal = TOOL_PROGRESS_TERMINAL.has(evt.status);
  if (row._terminal && !terminal) return;   // 终态优先：迟到的 starting 不降级
  if (terminal && !row._terminal) { row._terminal = true; panel._done++; }

  const icon = TOOL_PROGRESS_ICON[evt.status] || '·';
  const text = TOOL_PROGRESS_TEXT[evt.status] || evt.status || '';
  const dur = evt.durationMs > 0
      ? (evt.durationMs >= 1000 ? (evt.durationMs / 1000).toFixed(1) + 's' : evt.durationMs + 'ms')
      : '';
  const msg = evt.message ? ' · ' + evt.message : '';
  row.className = 'tp-row ' + (terminal
      ? ((evt.status === 'done' || evt.status === 'skipped') ? 'tp-done' : 'tp-error')
      : 'tp-running');
  row.textContent = icon + ' ' + (evt.toolName || 'tool') + ' ' + text
      + (dur ? ' ' + dur : '') + msg;
  if (panel._head) panel._head.textContent = '工具执行 ' + panel._done + '/' + panel._total;
  scrollChat(v.box);
}

/** 对话结束（正常/错误/停止）时移除该会话的瞬时清单 */
function clearToolProgress(sid) {
  const v = state.sessions && state.sessions[sid];   // 不 viewOf：未知会话不凭空建视图
  if (v && v.toolProgressEl) {
    v.toolProgressEl.remove();
    v.toolProgressEl = null;
  }
}

function renderToolResult(toolName, output, box) {
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
  (box || curBox()).appendChild(d);
  scrollChat(box);
  return d;
}
/** 消息附件缩略图：图片渲染 <img>（双击 lightbox），其它文件渲染下载链接。
 *  a 为服务端 toJsonable(TLAttachmentRef) 结构：{kind,name,mime,url,size,meta} */
function appendAttachmentThumbs(el, attachments) {
  if (!el || !attachments || !attachments.length) return;
  const box = document.createElement('div');
  box.className = 'attach-thumbs';
  attachments.forEach(a => {
    if (!a || !a.url) return;
    const imgLike = a.kind === 'IMAGE' || a.kind === 'URL'
      || /^image\//.test(a.mime || '') || /\.(png|jpe?g|gif|webp|bmp|tiff?)$/i.test(a.name || '');
    if (imgLike) {
      const img = document.createElement('img');
      img.className = 'browser-shot';
      img.src = a.url;
      img.loading = 'lazy';
      img.alt = a.name || '附件图片';
      img.title = (a.name || '') + '（双击查看原图）';
      img.addEventListener('dblclick', () => showLightbox(img.src));
      box.appendChild(img);
    } else {
      const link = document.createElement('a');
      link.className = 'attach-file';
      link.href = a.url;
      link.textContent = '📎 ' + (a.name || '附件');
      link.title = a.name || '';
      box.appendChild(link);
    }
  });
  if (box.childNodes.length) el.appendChild(box);
}
function startAssistantMsg() {
  const d = document.createElement('div');
  d.className = 'msg ai';
  const cursor = document.createElement('span');
  cursor.className = 'cursor';
  d.appendChild(cursor);
  curBox().appendChild(d);
  scrollChat();
  // holder.box：本次流所属会话的盒子——后台会话流式期间切走了，收尾也知道该写回哪个 box
  return { el: d, cursor, box: state.currentBox };
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
  scrollChat(holder.box);
}
function scrollChat(box) {
  box = box || state.currentBox;
  if (box !== state.currentBox) return;         // 后台会话不打扰当前视图
  const l = $('#msgList');
  l.scrollTop = l.scrollHeight;
}

// ======================== 聊天发送（流式/非流式）=======================
async function sendMessage() {
  const input = $('#chatInput');
  const msg = input.value.trim();
  const sid = state.sessionId;
  const view = viewOf(sid);
  if (!msg || view.busy) return;   // per-session 守卫：本会话在跑就不重发（其他会话不受影响）
  if (view.kicked) { toast('该会话正被其他设备使用，请开新会话，或点该会话的「接管」夺回', 'err'); return; }
  // 后台执行开关：本条消息不阻塞对话，挂到后台任务（结果稍后以事件/卡片回本会话）。
  // 在这个位置处理：输入已校验、kicked 守卫已过，尚未渲染用户气泡（后台任务不走对话流）。
  // 注意：本分支忽略本会话已上传的附件（附件与后台开关互斥，先不做混合语义）
  if ($('#bgToggle') && $('#bgToggle').checked) {
    input.value = '';
    $('#bgToggle').checked = false;
    const r = await apiCommand('createBackgroundTask', {
      prompt: msg, name: msg.slice(0, 20), sessionId: state.sessionId
    });
    if (r.success) appendSysMsg('🚀 已挂后台：' + (r.message || msg.slice(0, 20)));
    else appendSysMsg('[错误] ' + (r.error || r.message || '创建后台任务失败'));
    refreshSessionList();   // 任务创建会改变会话内容（结果稍后注入），刷新侧栏
    return;
  }
  input.value = '';   // 发送即清空输入框（放在守卫之后：被 busy/kicked 拒绝时不丢草稿）
  const userBubble = appendMsg('user', msg);
  // 本地即时缩略图：url 与服务端 toJsonable 生成同构（doImage 把相对路径解析到 data/{userId}/）
  if (view.uploads.length) {
    appendAttachmentThumbs(userBubble, view.uploads.map(u => ({
      name: u.name, url: '/api/image?path=' + encodeURIComponent('uploads/' + u.saved)
    })));
  }
  if (state.streamEnabled) await streamChat(msg);
  else await plainChat(msg);
  // 附件已随本条消息发出（或发送失败），清空 chip 条避免下次重复携带。
  // 按发送时会话清（等待期间可能已切走）；renderUploadBar 只渲染当前视图，不受影响
  viewOf(sid).uploads = [];
  renderUploadBar();
}
async function plainChat(msg) {
  let sid = state.sessionId;   // 发送时总是当前会话（服务端纠正归属时会更新）
  // 立即创建占位气泡（等待期间有可见反馈），完成后填充
  const holder = startAssistantMsg();
  holder.cursor.remove();
  const thinking = document.createElement('span');
  thinking.className = 'thinking';
  thinking.textContent = '🤔 思考中';
  holder.el.appendChild(thinking);
  setBusy(true, sid);
  try {
    const r = await apiJson('/api/chat', {
      message: msg, sessionId: sid, loginId: state.loginId || '',
      attachments: viewOf(sid).uploads.map(u => ({ path: 'uploads/' + u.saved, name: u.name, origin: 'user' }))
    });
    // 服务端纠正了会话ID（如换用户残留会话）：把已渲染内容整体挪到新会话 box 再切视图。
    // 不能只切视图——旧 box（含本轮用户气泡与正在填充的助手气泡）会被摘掉成孤儿，用户只看到空白
    if (r.sessionId && r.sessionId !== sid) {
      const oldBox = viewOf(sid).box;
      setBusy(false, sid);                 // 旧视图交还忙碌态（否则以后切回会误显示"停止"）
      switchSessionView(r.sessionId);
      while (oldBox.firstChild) state.currentBox.appendChild(oldBox.firstChild);
      holder.box = state.currentBox;       // 后续填充/滚动都落回新 box
      setBusy(true, r.sessionId);          // 忙碌态转移到新视图，由 finally 统一收尾
      sid = r.sessionId;
    }
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
      scrollChat(holder.box);
    } else {
      holder.el.remove();
      appendMsg('system', '[错误] ' + (r.error || r.response || '无响应'), holder.box);
    }
  } catch (e) {
    holder.el.remove();
    appendMsg('system', '[错误] ' + e.message, holder.box);
  } finally {
    clearToolProgress(sid);
    setBusy(false, sid);
  }
}
async function streamChat(msg, opts) {
  opts = opts || {};
  // 目标会话优先 opts.sessionId（resume 目标）；busy/abort/uploads 都必须记在真正收流的会话上
  const sid = opts.sessionId || state.sessionId;
  // 防御：opts 指定了会话但当前视图不一致时先切过去，否则 holder.el 会落进错误的 box
  if (opts.sessionId && opts.sessionId !== state.sessionId) switchSessionView(opts.sessionId);
  const holder = startAssistantMsg();
  setBusy(true, sid);
  const ctrl = new AbortController();
  viewOf(sid).abort = ctrl;
  // 空闲检测：长时间无 chunk（LLM 思考 / 工具调用循环）时显示"⏳ 思考中"。
  // 放在 try 外：catch/finally 才能 clearIdle，避免用户停止后"思考中"残留。
  let idleTimer = null;
  let idleSpan = null;
  const showIdle = () => {
    // 判 parentNode 而非 isConnected：box 脱离 DOM 的后台会话也要能挂"思考中"（切回来能看到）
    if (!idleSpan && holder.el.parentNode) {
      idleSpan = document.createElement('span');
      idleSpan.className = 'thinking';
      idleSpan.textContent = ' ⏳ 思考中';
      holder.el.appendChild(idleSpan);
      scrollChat(holder.box);
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
  try {
    const resp = await fetch('/api/chatStream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: msg,
        sessionId: sid,
        loginId: state.loginId || '',
        resume: !!opts.resume,   // 断点恢复：以断点时的用户消息继续执行（流式）
        attachments: viewOf(sid).uploads.map(u => ({ path: 'uploads/' + u.saved, name: u.name, origin: 'user' }))
      }),
      signal: ctrl.signal
    });
    if (resp.status === 401) { holder.el.remove(); showLogin(); return; }
    if (!resp.ok) {
      let err = 'HTTP ' + resp.status;
      try { const j = await resp.json(); err = j.error || err; } catch (e) { /* ignore */ }
      holder.el.remove();
      appendMsg('system', '[错误] ' + err, holder.box);
      return;
    }
    // 非 SSE 响应（JSON 错误，如"会话被占用"拒绝）→ 读 JSON 显示错误，不走流解析
    const ct = resp.headers.get('content-type') || '';
    if (!ct.includes('text/event-stream')) {
      let err = '';
      try { const j = await resp.json(); err = j.error || j.message || ''; } catch (e) { /* ignore */ }
      holder.el.remove();
      appendMsg('system', '[错误] ' + (err || '请求被拒绝'), holder.box);
      return;
    }
    const reader = resp.body.getReader();
    const dec = new TextDecoder('utf-8');
    let buf = '';
    let reasoningBuf = '';
    let t0 = Date.now();
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
          // 输出护栏改写：用净化后的全文替换当前气泡（不是追加）
          if (evt.replace) {
            clearIdle();
            if (holder.cursor.parentNode) holder.cursor.remove();
            holder.el.textContent = evt.replace;
            scrollChat(holder.box);
            armIdle();
          } else if (evt.chunk) {
            // 与 replace 互斥：replace 是整段覆盖，chunk 是追加（同帧时 replace 优先）
            clearIdle();
            if (holder.cursor.parentNode) holder.cursor.remove();
            holder.el.textContent = (holder.el.textContent || '') + evt.chunk;
            scrollChat(holder.box);
            armIdle();
          }
          if (evt.toolEvent) {
            clearIdle();
            renderToolResult(evt.toolName, evt.toolOutput, holder.box);
            armIdle();
          }
          if (evt.reasoning) reasoningBuf = evt.reasoning;
          if (evt.done) {
            clearIdle();
            evt.ms = Date.now() - t0;
            if (!evt.reasoning && reasoningBuf) evt.reasoning = reasoningBuf;
            finishAssistantMsg(holder, evt);
            if (evt.error) appendMsg('system', '[流式错误] ' + evt.error, holder.box);
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
      scrollChat(holder.box);
    } else {
      holder.el.remove();
      appendMsg('system', '[错误] ' + e.message, holder.box);
    }
  } finally {
    clearIdle();   // AbortError/异常路径也要清"思考中"，否则停止后残留
    clearToolProgress(sid);
    setBusy(false, sid);
    viewOf(sid).abort = null;
  }
}
async function stopChat() {
  const sid = state.sessionId;
  try { await apiJson('/api/stopChat', { sessionId: sid }); } catch (e) { /* ignore */ }
  const v = viewOf(sid);
  if (v.abort) v.abort.abort();
  setBusy(false, sid);
}
function setBusy(b, sid) {
  sid = sid || state.sessionId;
  const v = viewOf(sid);
  v.busy = b;
  scheduleRenderSessionList();                  // 侧栏 ● 运行中刷新（节流合并，后台会话也要更新）
  if (!b) ensureSessionListed(sid);             // 轮次结束：会话不在侧栏缓存（新会话首轮）→ 拉一次列表
  if (sid !== state.sessionId) return;          // 后台会话只记状态，不动当前 UI
  $('#sendBtn').classList.toggle('hidden', b);
  $('#stopBtn').classList.toggle('hidden', !b);
}
/** 该会话不在侧栏缓存列表里时拉一次列表（否则它会一直不出现，直到手动刷新） */
function ensureSessionListed(sid) {
  if (!lastSessionsData || !sid) return;
  if (!lastSessionsData.some(x => x.sessionId === sid)) refreshSessionList();
}

// ======================== 文件上传 ========================
const UPLOAD_MAX_MB = 50;   // 与后端 uploadSizeMaxMB 保持一致
async function uploadFiles(files) {
  const sid = state.sessionId;   // 附件归上传发起时的会话（上传期间切走也不会串到别的会话）
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
  okFiles.forEach((f, i) => { if (saved[i] != null) viewOf(sid).uploads.push({ name: f.name, saved: saved[i] }); });
  renderUploadBar();
  toast('上传成功 ' + saved.length + ' 个文件', 'ok');
}
function renderUploadBar() {
  const bar = $('#uploadBar');
  if (!state.sessionId) { bar.classList.add('hidden'); return; }
  const ups = viewOf(state.sessionId).uploads;
  if (!ups.length) { bar.classList.add('hidden'); return; }
  bar.classList.remove('hidden');
  $('#uploadChips').innerHTML = ups
    .map(u => '<span class="ub-chip" title="' + esc(u.name) + '">' + esc(u.name) + '</span>').join('');
}
function bindUpload() {
  $('#attachBtn').onclick = () => $('#fileInput').click();
  $('#fileInput').addEventListener('change', () => {
    const files = Array.from($('#fileInput').files || []);
    $('#fileInput').value = '';          // 允许连续选择同一文件
    if (files.length) uploadFiles(files);
  });
  $('#clearUploads').onclick = () => { viewOf(state.sessionId).uploads = []; renderUploadBar(); };
}

// ======================== 任务清单条（todo 技能 → msgBus todoEvent → SSE） ========================
// 按会话隔离（同附件草稿）：事件落 state.sessions[sid].todo，切会话各看各的；无清单整条隐藏。
/** 标记 → 着色 class（与 todo 技能的渲染标记同一套：[x] 完成 / [~] 进行中 / [ ] 待执行 / [✗] 失败） */
function todoMarkClass(mark) {
  if (mark === 'x') return 'tk-x';
  if (mark === '~') return 'tk-prog';
  if (mark === '✗') return 'tk-fail';
  return 'tk-pend';
}
/** 清单文本行 → 展开区 HTML（只认 "- [m] 文本" 行；首行"任务清单（…）："表头不匹配自然被滤掉） */
function todoItemsHtml(text) {
  return String(text == null ? '' : text).split('\n')
    .map(line => /^- \[(.)\]\s?(.*)$/.exec(line.trim()))
    .filter(Boolean)
    .map(m => '<div class="todo-item ' + todoMarkClass(m[1]) + '">'
      + '<span class="tk-mark">[' + esc(m[1]) + ']</span><span class="tk-text">' + esc(m[2]) + '</span></div>')
    .join('');
}
function renderTodoBar() {
  const bar = $('#todoBar');
  if (!bar) return;
  const v = state.sessionId ? state.sessions[state.sessionId] : null;
  if (!v || !v.todo) { bar.classList.add('hidden'); return; }
  bar.classList.remove('hidden');
  const t = v.todo;
  const completed = Number(t.completedCount) || 0;
  const total = completed + (Number(t.inProgressCount) || 0)
      + (Number(t.pendingCount) || 0) + (Number(t.failedCount) || 0);
  $('#todoSummary').textContent = '任务清单（' + completed + '/' + total + ' 完成）';
  $('#todoToggle').textContent = v.todoExpanded ? '▾' : '▸';
  const items = $('#todoItems');
  // 折叠时清空：长清单不必留在 DOM 里（展开再按最新事件重渲染，内容不会过期）
  if (!v.todoExpanded) { items.classList.add('hidden'); items.innerHTML = ''; return; }
  items.classList.remove('hidden');
  items.innerHTML = todoItemsHtml(t.text);
}
/** 落一条 todo 事件到其所属会话视图；命中当前显示会话才立即重渲染（非当前会话切过去时由
 *  switchSessionView 渲染；viewOf 对未知 sid 会建空视图——与 kicked 事件同一既有约定） */
function applyTodoEvent(evt) {
  if (!evt || !evt.sessionId) return;
  viewOf(evt.sessionId).todo = evt;
  if (evt.sessionId === state.sessionId) renderTodoBar();
}
function bindTodoBar() {
  $('#todoBar .todo-head').onclick = () => {
    const v = state.sessionId ? state.sessions[state.sessionId] : null;
    if (!v || !v.todo) return;          // 无清单时整条不可见，防误触
    v.todoExpanded = !v.todoExpanded;
    renderTodoBar();
  };
}

// 上报当前打开的会话（定时任务执行期需要知道"用户现在在看哪个会话"）
function reportCurrentSession() {
  if (!state.sessionId) return;
  apiCommand('setCurrentSession', { sessionId: state.sessionId }).catch(() => {});
}

// ======================== 事件通道（审批 / 定时任务结果推送）=======================
function openEvents() {
  if (state.events) state.events.close();
  const es = new EventSource('/api/events');
  es.onmessage = e => {
    let evt;
    try { evt = JSON.parse(e.data); } catch (err) { return; }
    if (evt.hb) return;
    if (evt.type === 'inbox') { updateInboxBadge(evt.unread || 0); return; }
    if (evt.type === 'approval') showApprovalModal(evt);
    if (evt.type === 'approvalResolved') {
      // 决策/超时/中断后服务端推送：命中的弹框立即关闭（根治"请求已死弹框还在，点任何按钮都报已处理"）
      if (currentApproval && currentApproval.approvalId === evt.approvalId) {
        const mine = pendingDecision === evt.approvalId;   // 自己刚点的决策：关框即可，别再提示"其他窗口"
        closeApprovalModal(evt.approvalId);
        if (!mine) toast(approvalEndedText(evt.state, evt.reason), 'err');
      } else {
        // 还在队列里、没轮到展示的审批被别处处理/超时：直接出队（不提示，避免 N 条重复 toast）
        const qi = approvalQueue.findIndex(a => a.approvalId === evt.approvalId);
        if (qi >= 0) { approvalQueue.splice(qi, 1); flushPendingAutoResume(); }
      }
      return;
    }
    if (evt.type === 'kicked') {
      // 事件按 userId 广播，只有 loginId 匹配自己（被踢的那一方）才响应禁用
      if (evt.loginId && evt.loginId !== state.loginId) return;
      const sid = evt.sessionId;
      if (!sid) {
        // 无 sessionId：退回旧全局行为（一律禁输入）
        toast(evt.text || '该会话已被其他设备接管', 'err');
        $('#chatInput').disabled = true;
        $('#sendBtn').disabled = true;
        appendSysMsg('⚠ ' + (evt.text || '会话已被其他设备接管') + '（可开启新会话或继续其他会话）');
      } else {
        // 接管标记落到会话视图：只有当前正在看的会话才提示到聊天窗，其他会话仅 toast
        viewOf(sid).kicked = true;
        toast(evt.text || '该会话已被其他设备接管', 'err');
        if (sid === state.sessionId) {
          $('#chatInput').disabled = true;
          $('#sendBtn').disabled = true;
          appendSysMsg('⚠ ' + (evt.text || '会话已被其他设备接管') + '（可开启新会话或继续其他会话）');
        }
      }
    }
    if (evt.type === 'taskStarted' || evt.type === 'taskProgress' || evt.type === 'taskResult') {
      handleTaskEvent(evt);
    }
    if (evt.type === 'todo') { applyTodoEvent(evt); }   // 任务清单变更（输入区上方清单条）
    if (evt.type === 'toolProgress') { applyToolProgress(evt); }   // 工具执行状态（无 output）
    if (evt.type === 'sessionTitle') {
      // 首轮完成后模型自动起名推送：命中缓存行则就地更新标题并节流重渲染。
      // 未命中（新会话首轮刚完成、侧栏缓存里还没有它）→ 拉一次列表让该行出现
      const row = (lastSessionsData || []).find(x => x.sessionId === evt.sessionId);
      if (row) row.title = evt.title;
      else refreshSessionList();
      scheduleRenderSessionList();
    }
  };
  es.onopen = () => { $('#connState').textContent = '●'; $('#connState').style.color = '#4ade80'; };
  es.onerror = () => { $('#connState').textContent = '○'; $('#connState').style.color = '#f87171'; };
  state.events = es;
}

// ======================== 后台任务 UI ========================
/** 任务状态英文枚举 → 中文文案（枚举与后端 TaskRecord.status 同源） */
function taskStatusText(s) {
  return ({running:'运行中…', done:'已完成', failed:'失败', stopped:'已停止', interrupted:'已中断', pending:'排队中'})[s] || s || '';
}
/** 任务卡片元素（停靠在该任务所属会话的 box 末尾） */
function taskCardEl(tid, t) {
  const d = document.createElement('div');
  d.className = 'task-card';
  d.id = 'tc_' + tid;
  d.innerHTML =
    '<span class="tc-name">🚀 ' + esc(t.name || tid) + '</span>' +
    '<span class="tc-status">' + esc(taskStatusText(t.status)) + '</span>' +
    '<button class="tc-open" data-tid="' + esc(tid) + '">查看</button>' +
    (t.status === 'running' ? '<button class="tc-stop" data-tid="' + esc(tid) + '">停止</button>' : '');
  return d;
}
/** 渲染/更新任务卡片：同 id 原位替换（不整段重绘，保住其它卡片与滚动位置） */
function renderTaskCard(sid, tid, t) {
  const box = viewOf(sid).box;
  const old = box.querySelector('#tc_' + CSS.escape(tid));
  const el = taskCardEl(tid, t);
  if (old) old.replaceWith(el); else box.appendChild(el);
  scrollChat(box);
}
/**
 * 打开任务详情抽屉（body 级元素，独立于会话 box 与侧栏重建）。
 * 先本地渲染（流式累积/事件状态），再拉 tasks get 补全量：
 * running 用 liveSnapshot；终态用 resultText（回退 progressSnapshot）。
 */
function openTaskDrawer(tid, sid) {
  state.drawer = { tid: tid, sid: sid };
  const t = viewOf(sid).tasks[tid] || {};
  $('#taskDrawer').classList.remove('hidden');
  $('#taskDrawerTitle').textContent = '任务：' + (t.name || tid) + '（' + taskStatusText(t.status) + '）';
  $('#taskDrawerBody').textContent = (t.output || '');
  const base = (t.output || '').length;   // 发起 get 时本地已累积长度：full 已含这部分，只能补它之后的增量（否则重复）
  apiCommand('tasks', { sub: 'get', task_id: tid }).then(r => {
    if (!r.success || !r.data) return;
    if (!state.drawer || state.drawer.tid !== tid) return;   // 抽屉已关闭/切到别的任务 → 丢弃迟到的响应
    const v = viewOf(sid);
    const tt = v.tasks[tid] || (v.tasks[tid] = {});
    if (!tt.name && r.data.name) tt.name = r.data.name;
    // 注意：get 返回的 status 是用户可见文案（"运行中/已完成"），不能拿来覆盖本地英文枚举；
    // localStatus 才是同源英文枚举 —— 本地已有枚举（事件最新）时以本地为准，仅有本地缺失时才补
    if (!tt.status && r.data.localStatus) tt.status = r.data.localStatus;
    $('#taskDrawerTitle').textContent = '任务：' + (tt.name || tid) + '（' + taskStatusText(tt.status) + '）';
    const full = r.data.running ? (r.data.liveSnapshot || '')
      : (r.data.resultText || r.data.progressSnapshot || '');
    // get 期间本地可能继续增长（事件仍在推）：slice(base) 只取新增部分（本地被 64KB 截断时结果为空串，安全）
    const tail = tt.output ? tt.output.slice(base) : '';
    $('#taskDrawerBody').textContent = full + tail;
  }).catch(() => {});
}
/**
 * 任务生命周期事件（SSE taskStarted/taskProgress/taskResult）：
 * 按 sessionId 落到发起会话的视图分片；当前会话渲染卡片/系统消息，其他会话只标未读（侧栏 ○）。
 */
function handleTaskEvent(evt) {
  const sid = evt.sessionId || evt.parentSessionId || '';
  if (!sid) return;
  const v = viewOf(sid);
  const t = v.tasks[evt.taskId] || (v.tasks[evt.taskId] = {});
  if (evt.name) t.name = evt.name;
  if (evt.type === 'taskStarted') { t.status = 'running'; }
  if (evt.type === 'taskProgress') {
    t.output = (t.output || '') + (evt.text || '');
    if (t.output.length > 65536) t.output = t.output.slice(-65536);   // 有界：只留最后 64KB
    if (evt.progressKind === 'tool') t.output += '\n🔧 ' + (evt.toolName || 'tool') + '\n';
  }
  if (evt.type === 'taskResult') { t.status = evt.status || 'done'; t.result = evt.text || ''; }
  if (sid === state.sessionId) {
    // 所有事件类型都会 renderTaskCard→scrollChat 滚底（不止 taskResult）：取样必须在渲染前、
    // 恢复在其后，否则任务运行期间 4 次/秒的进度事件会把翻看历史的用户不断拽到底
    const l = $('#msgList');
    const atBottom = l.scrollHeight - l.scrollTop - l.clientHeight < 40;
    const keepTop = l.scrollTop;
    // 卡片只属于后台任务（restoreTaskCards 也只恢复 background）：定时任务建卡会在刷新后凭空消失。
    // 无 kind 的事件（旧版负载）按后台兜底建卡，避免卡片功能整体失灵。
    if (evt.kind === 'background' || !evt.kind) renderTaskCard(sid, evt.taskId, t);
    if (evt.type === 'taskResult') {
      // 不整段重绘（continueSession 会清屏+强制滚底，高频任务下页面持续闪动）；
      // 用户停在底部时跟随新消息，翻看历史时不打扰（保持原滚动位置）
      // 终态消息不分 kind：定时任务不建卡，结果仍要可见
      appendSysMsg((t.status === 'done' ? '⏰ ' : '⚠ ') + (evt.kind === 'background' ? '后台任务' : '定时任务')
        + '【' + (t.name || evt.taskId) + '】'
        + taskStatusText(t.status) + (evt.text ? '：' + evt.text : ''));
    }
    if (!atBottom) l.scrollTop = keepTop;    // 进度/终态都不打扰翻历史的用户
    // 抽屉开着时的刷新策略：进度事件直接追写正文（原先每次都 openTaskDrawer→tasks get，
    // 服务端要读全部任务文件+引擎往返，正文 4 次/秒即 4 次请求）；终态才重拉一次对账收尾
    if (state.drawer && state.drawer.tid === evt.taskId) {
      if (evt.type === 'taskProgress') {
        const body = $('#taskDrawerBody');
        if (body) {
          body.textContent += (evt.progressKind === 'tool' ? ('\n🔧 ' + (evt.toolName || 'tool') + '\n') : (evt.text || ''));
          body.scrollTop = body.scrollHeight;
        }
      } else {           // 非进度事件：taskResult 终态重拉收尾；taskStarted 每任务仅一次，顺带对账
        openTaskDrawer(evt.taskId, sid);
      }
    }
  } else {
    v.unread++;                              // 非当前会话唯一未读写入方（侧栏状态图标据此出 ○）
    scheduleRenderSessionList();
    if (evt.type === 'taskResult') toast('🚀 ' + (evt.kind === 'background' ? '后台任务' : '定时任务')
      + '【' + (t.name || evt.taskId) + '】' + taskStatusText(t.status) + '（左侧会话栏可切换）', 'ok');
  }
}
/**
 * 进入会话时恢复该会话的后台任务卡片（页面刷新/切会话后 box 重建，卡片不随历史回来）。
 * 只认 background 类型 + parentSessionId 匹配 + 非 pending 的任务。
 */
async function restoreTaskCards(sid) {
  try {
    const r = await apiCommand('tasks', { sub: 'list' });
    if (!r.success || !Array.isArray(r.data)) return;
    const v = viewOf(sid);
    for (const row of r.data) {
      if (row.kind !== 'background' || row.parentSessionId !== sid) continue;
      if (row.localStatus === 'pending') continue;
      const t = v.tasks[row.taskId] || (v.tasks[row.taskId] = {});
      // 事件已写过（更新）则不覆盖；只补缺失/未知的字段（localStatus 是英文枚举，status 是文案不能用）
      if (!t.name && row.name) t.name = row.name;
      if (!t.status) t.status = row.localStatus || row.status || '';
      renderTaskCard(sid, row.taskId, t);
    }
  } catch (e) { /* 恢复失败静默：不打扰会话进入流程 */ }
}

// ======================== 右侧设置/调试面板（默认隐藏 + 可拖宽） ========================
// 顶栏两个入口：⚙ 设置（Agent/Skill、MCP）/ 🐞 调试（评测/测试、追踪）；Tab 栏只显示当前组的 Tab
let panelGroup = 'settings';                                            // 当前分组（settings | debug）
const PANEL_W_DEFAULT = 420;                                            // 默认宽度（双击把手复位到此值）
const PANEL_W_MIN = 280;                                                // 最小宽度（两个 Tab 标签排得下）
const PANEL_W_KEY = 'tlweb_panelw';                                     // localStorage 键
function panelWMax() { return Math.round(window.innerWidth * 0.7); }    // 上限动态算：保证聊天区不被压没

/** 宽度统一夹取：非法值（NaN/0/负）回退默认，越界夹到 [MIN, 70vw] */
function clampPanelWidth(w) {
  w = Number(w);
  if (!Number.isFinite(w) || w <= 0) w = PANEL_W_DEFAULT;
  return Math.max(PANEL_W_MIN, Math.min(w, panelWMax()));
}
/** 应用面板宽度：写 flex-basis + 记忆到 localStorage */
function setPanelWidth(w) {
  w = clampPanelWidth(w);
  $('#panelPane').style.flexBasis = w + 'px';
  localStorage.setItem(PANEL_W_KEY, String(w));
}
/** 激活指定 Tab：Tab 栏高亮 + 对应 section 显示 */
function showTab(tabId) {
  document.querySelectorAll('#tabBar button').forEach(b => b.classList.toggle('active', b.dataset.tab === tabId));
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === tabId));
}
/** 切换功能组：Tab 栏只显示该组的 Tab，并回到该组第一个 Tab（设置=Agent/Skill，调试=追踪） */
function setPanelGroup(group) {
  const tabs = [...document.querySelectorAll('#tabBar button')];
  const first = tabs.find(b => b.dataset.group === group) || tabs[0];
  panelGroup = first.dataset.group;                                    // 非法 group 回退到首个 Tab 所在组
  tabs.forEach(b => b.classList.toggle('hidden', b.dataset.group !== panelGroup));
  showTab(first.dataset.tab);
}
/** 顶栏入口高亮：面板关时两个都不亮；开时只亮当前组的入口 */
function syncPanelButtons() {
  const open = !$('#panelPane').classList.contains('hidden');
  $('#settingsBtn').classList.toggle('on', open && panelGroup === 'settings');
  $('#debugBtn').classList.toggle('on', open && panelGroup === 'debug');
}
/** 关闭面板：隐藏面板 + 收回聊天区右侧让位 + 熄灭两个入口 */
function closePanel() {
  $('#panelPane').classList.add('hidden');
  $('#chatView').classList.remove('panel-open');
  syncPanelButtons();
}
/** 顶栏开关：点同组入口 = 开/关；面板已开时点另一组 = 切换分组（不关） */
function togglePanel(group) {
  const pane = $('#panelPane');
  if (!pane.classList.contains('hidden') && panelGroup === group) { closePanel(); return; }
  pane.classList.remove('hidden');
  setPanelGroup(group);
  $('#chatView').classList.add('panel-open');               // 会话列右侧空栏让位（见 style.css）
  setPanelWidth(localStorage.getItem(PANEL_W_KEY));         // 打开时应用记忆宽度（此刻夹取，兜住"窗口变小了"）
  syncPanelButtons();
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
    const v = e.target.value.trim();
    if (v && v !== state.sessionId) { switchSessionView(v); toast('已切换会话ID: ' + v, 'ok'); }
  };
  // 断点提示弹窗：恢复执行（流式）/ 忽略
  $('#cpResumeBtn').onclick = () => {
    $('#checkpointModal').classList.add('hidden');
    resumeCheckpoint();
  };
  $('#cpIgnoreBtn').onclick = () => $('#checkpointModal').classList.add('hidden');
  // 放弃该操作（仅 pending_approval 断点可见）：撤销待批审批 + 丢弃断点，之后可正常对话。
  // 断点丢弃失败（checkpointDiscarded=false）如实提示——待批卡片可能仍在，重登会再提示
  $('#cpAbandonBtn').onclick = async () => {
    const sid = state.pendingAbandonSessionId;
    const btn = $('#cpAbandonBtn');
    if (!sid || btn.disabled) return;
    btn.disabled = true;   // 在途守卫：双击并发两次会给出一对矛盾提示（"已放弃"与"断点未清理"互串）
    try {
      const r = await apiCommand('abandonApproval', { sessionId: sid });
      if (!r.success) { toast(r.error || r.message || '放弃失败', 'err'); return; }
      $('#checkpointModal').classList.add('hidden');
      if (r.checkpointDiscarded === false) {
        toast('已放弃该操作，但断点未清理：' + (r.checkpointError || '待批卡片可能仍在'), 'err');
      } else {
        toast(r.message || '已放弃该操作，可继续对话', 'ok');
      }
    } catch (e) { toast('放弃失败: ' + e.message, 'err'); }
    finally { btn.disabled = false; }
  };
  $('#tabBar').addEventListener('click', e => {
    const btn = e.target.closest('button[data-tab]');
    if (btn) showTab(btn.dataset.tab);
  });
  // 右侧设置/调试面板：顶栏两个入口（设置/调试各一组 Tab）+ 左边缘拖拽调宽 + 双击复位
  $('#settingsBtn').onclick = () => togglePanel('settings');
  $('#debugBtn').onclick = () => togglePanel('debug');
  const resizer = $('#panelResizer');
  resizer.addEventListener('mousedown', e => {
    e.preventDefault();   // 阻止选中文本等默认行为；mousedown 的 preventDefault 不影响 dblclick
    document.body.classList.add('dragging');
    // 面板贴视口右缘：宽 = 视口宽 - 鼠标 x
    const move = ev => setPanelWidth(window.innerWidth - ev.clientX);
    const up = () => {
      document.body.classList.remove('dragging');
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      window.removeEventListener('blur', up);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    window.addEventListener('blur', up);   // 鼠标在窗口外松开时的兜底
  });
  resizer.addEventListener('dblclick', () => setPanelWidth(PANEL_W_DEFAULT));
  $('#apApproveBtn').onclick = () => approveAction('once');
  $('#apApproveRoundBtn').onclick = () => approveAction('round');
  $('#apApproveSessionBtn').onclick = () => approveAction('session');
  $('#apRejectBtn').onclick = rejectAction;
  $('#apCloseBtn').onclick = closeApproval;
  // 顶栏功能图标：定时任务 / 后台任务 / 消息箱（应用级功能 → 浮层；右侧面板留调试/测试）
  $('#tasksBtn').onclick = openTasks;
  $('#bgTasksBtn').onclick = openBgTasks;
  $('#inboxBtn').onclick = openInbox;
  // 消息箱单条删除（列表动态渲染 → 容器级委托）
  $('#inboxBox').addEventListener('click', e => {
    const btn = e.target.closest('.inbox-del');
    if (btn) deleteInbox({ id: btn.dataset.id });
  });
  // 自动运行开关 + 授权面板（顶栏）
  $('#autoBtn').onclick = toggleAuto;
  $('#permBtn').onclick = openPermissions;
  // 参数弹框：点遮罩关闭
  $('#paramModal').addEventListener('click', e => {
    if (e.target === $('#paramModal')) $('#paramModal').classList.add('hidden');
  });
  // 任务/后台任务/消息箱浮层：点遮罩关闭
  $('#tasksModal').addEventListener('click', e => { if (e.target === $('#tasksModal')) closeTasks(); });
  $('#bgTasksModal').addEventListener('click', e => { if (e.target === $('#bgTasksModal')) closeBgTasks(); });
  $('#inboxModal').addEventListener('click', e => { if (e.target === $('#inboxModal')) closeInbox(); });
  $('#permModal').addEventListener('click', e => { if (e.target === $('#permModal')) closePermissions(); });
  // ESC 保命键：按一次关一个"信息类"弹框（审批框除外——需在场决策，误关会让任务悬置等超时）
  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (!$('#bgTasksModal').classList.contains('hidden')) { closeBgTasks(); return; }
    if (!$('#tasksModal').classList.contains('hidden')) { closeTasks(); return; }
    if (!$('#inboxModal').classList.contains('hidden')) { closeInbox(); return; }
    if (!$('#permModal').classList.contains('hidden')) { closePermissions(); return; }
    if (!$('#checkpointModal').classList.contains('hidden')) { $('#checkpointModal').classList.add('hidden'); return; }
    if (state.drawer) { state.drawer = null; $('#taskDrawer').classList.add('hidden'); return; }
    if (!$('#panelPane').classList.contains('hidden')) { closePanel(); }
  });
  // 通用确认弹框
  $('#cfOkBtn').onclick = () => closeConfirm(true);
  $('#cfCancelBtn').onclick = () => closeConfirm(false);
  $('#confirmModal').addEventListener('click', e => {
    if (e.target === $('#confirmModal')) closeConfirm(false);
  });
  // 任务卡片按钮：body 级委托一次（卡片随任务事件动态增删，且各会话 box 会重建）
  document.addEventListener('click', async e => {
    const open = e.target.closest('.tc-open');
    if (open) { openTaskDrawer(open.dataset.tid, state.sessionId); return; }
    const stop = e.target.closest('.tc-stop');
    if (stop) {
      const r = await apiCommand('tasks', { sub: 'stop', task_id: stop.dataset.tid });
      if (r && r.success) { toast('已请求停止任务', 'ok'); refreshSessionList(); }
      else toast('[错误] ' + ((r && (r.error || r.message)) || '停止失败'), 'err');
      return;
    }
  });
  // 任务详情抽屉（body 级容器，见 chat.html #taskDrawer）
  $('#tdClose').onclick = () => { state.drawer = null; $('#taskDrawer').classList.add('hidden'); };
  $('#tdStop').onclick = async () => {
    if (!state.drawer) return;
    const r = await apiCommand('tasks', { sub: 'stop', task_id: state.drawer.tid });
    if (r && r.success) { toast('已请求停止任务', 'ok'); refreshSessionList(); }
    else toast('[错误] ' + ((r && (r.error || r.message)) || '停止失败'), 'err');
  };
  bindUpload();
  bindTodoBar();
}

document.addEventListener('DOMContentLoaded', () => {
  bindEvents();
  initSession();
});

// ======================== 顶栏图标 / 定时任务 / 消息箱 ========================

/** 定时任务浮层 */
function openTasks() { $('#tasksModal').classList.remove('hidden'); loadTasks(); }
function closeTasks() { $('#tasksModal').classList.add('hidden'); }
/** 消息箱浮层 */
function openInbox() { $('#inboxModal').classList.remove('hidden'); loadInbox(); }
function closeInbox() { $('#inboxModal').classList.add('hidden'); }

/** 更新消息箱未读角标（0 隐藏） */
function updateInboxBadge(n) {
  const b = $('#inboxBadge');
  if (!b) return;
  if (n > 0) { b.textContent = n > 99 ? '99+' : String(n); b.classList.remove('hidden'); }
  else b.classList.add('hidden');
}

/** 拉取未读数并更新角标（登录后/收到 inbox 事件时调用） */
async function refreshInboxBadge() {
  try {
    const r = await apiCommand('inboxList', {});
    if (r.success && r.data) updateInboxBadge(r.data.unread || 0);
  } catch (e) { /* 未登录等场景静默 */ }
}

// 消息类别：复用消息的 source 字段（task/approval），其余归 system——无需改表
const INBOX_CATS = [['all', '全部'], ['task', '任务'], ['approval', '审批'], ['system', '系统']];
let inboxCategory = 'all';
function inboxCatOf(source) { return source === 'task' || source === 'approval' ? source : 'system'; }
function inboxCatLabel(cat) { return { task: '任务', approval: '审批', system: '系统' }[cat] || '系统'; }

/** 类别筛选条：类别 + 总数（未读数），当前类别高亮 */
function renderInboxCats(d) {
  const el = $('#inboxCats');
  const counts = d.counts || {};
  el.innerHTML = '';
  INBOX_CATS.forEach(([key, label]) => {
    const c = key === 'all' ? { total: d.total || 0, unread: d.unread || 0 } : (counts[key] || {});
    const b = document.createElement('button');
    b.className = key === inboxCategory ? 'on' : '';
    b.dataset.cat = key;
    b.textContent = label + ' ' + (c.total || 0) + ((c.unread || 0) > 0 ? ' · ' + c.unread + '未读' : '');
    b.onclick = () => { inboxCategory = key; loadInbox(); };
    el.appendChild(b);
  });
}

/** 单条消息：类别标签 + 来源/时间 + 正文 + 删除 */
function renderInboxRow(m) {
  const cat = inboxCatOf(m.source);
  const row = document.createElement('div');
  row.className = 'inbox-row' + (m.is_read ? ' read' : '');
  const main = document.createElement('div');
  main.className = 'inbox-main';
  const meta = document.createElement('div');
  meta.className = 'inbox-meta';
  const tag = document.createElement('span');
  tag.className = 'inbox-cat ' + cat;
  tag.textContent = inboxCatLabel(cat);
  meta.appendChild(tag);
  const src = cat === 'task' ? '⏰ ' + (m.task_id || '定时任务')
            : cat === 'approval' ? '审批 ' + (m.task_id || '通知')
            : '系统通知';
  const info = document.createElement('span');
  info.textContent = src + (m.created_at ? ' · ' + fmtTs(Number(m.created_at)) : '');
  meta.appendChild(info);
  main.appendChild(meta);
  const text = document.createElement('div');
  text.className = 'inbox-text';
  text.textContent = String(m.text || '');
  main.appendChild(text);
  row.appendChild(main);
  const del = document.createElement('button');
  del.className = 'inbox-del';
  del.dataset.id = m.id;
  del.title = '删除这条消息';
  del.textContent = '删除';
  row.appendChild(del);
  return row;
}

/** 消息箱面板：类别筛选 + 列表 + 标记已读/删除 */
async function loadInbox() {
  const box = $('#inboxBox');
  try {
    const r = await apiCommand('inboxList', { category: inboxCategory });
    if (!r.success) { box.innerHTML = '<div class="muted">加载失败: ' + esc(r.error || r.message || '') + '</div>'; return; }
    const d = r.data || {};
    const list = d.list || [];
    updateInboxBadge(d.unread || 0);
    renderInboxCats(d);
    if (!list.length) {
      box.innerHTML = '<div class="muted">' + (inboxCategory === 'all' ? '消息箱为空' : '该类别暂无消息') + '</div>';
      return;
    }
    const wrap = document.createElement('div');
    list.forEach(m => wrap.appendChild(renderInboxRow(m)));
    box.innerHTML = '';
    box.appendChild(wrap);
  } catch (e) {
    box.innerHTML = '<div class="muted">加载异常: ' + esc(e.message) + '</div>';
  }
}

/** 全部标记已读 */
async function readAllInbox() {
  try {
    await apiCommand('inboxRead', {});
    loadInbox();
  } catch (e) { toast('操作失败: ' + e.message, 'err'); }
}

/** 删除消息（单条 / 已读 / 全部）；成功后刷新列表与角标 */
async function deleteInbox(params, okText) {
  try {
    const r = await apiCommand('inboxDelete', params);
    if (!r.success) { toast(r.error || r.message || '删除失败', 'err'); return; }
    updateInboxBadge((r.data || {}).unread || 0);
    loadInbox();
    if (okText) toast(okText, 'ok');
  } catch (e) { toast('删除失败: ' + e.message, 'err'); }
}

async function deleteReadInbox() {
  const ok = await confirmDialog('删除全部已读消息？', '已读消息删除后不可恢复（未读消息保留）。', '删 除');
  if (ok) deleteInbox({ scope: 'read' }, '已删除全部已读消息');
}

async function clearInbox() {
  const ok = await confirmDialog('清空消息箱？', '全部消息（含未读）删除后不可恢复。', '清 空');
  if (ok) deleteInbox({ scope: 'all' }, '消息箱已清空');
}

/** 定时任务面板：列表 + 行内 暂停/恢复/删除（与控制台 /tasks 同语义）。
 *  只显示定时任务：后台任务有专属面板（🚀 后台任务），不在此重复；LLM 的 op=list 仍是全量。 */
async function loadTasks() {
  const box = $('#tasksBox');
  try {
    const r = await apiCommand('tasks', { sub: 'list' });
    if (!r.success) { box.innerHTML = '<div class="muted">' + esc(r.error || r.message || '加载失败') + '</div>'; return; }
    const all = (r.data || []);
    const list = all.filter(t => t.kind !== 'background');
    if (!list.length) {
      box.innerHTML = '<div class="muted">' + esc(all.length
        ? '当前没有定时任务（后台任务请在「🚀 后台任务」面板查看）'
        : (r.message || '当前没有定时任务')) + '</div>';
      return;
    }
    // renderTable 约定：headers = [[key, 显示名], ...]，rows = {key: 值} 对象
    const headers = [['taskId', '任务ID'], ['status', '状态'], ['schedule', '调度'],
                     ['content', '提示词/内容'], ['executedCount', '已执行'], ['_op', '操作']];
    const rows = list.map(t => {
      // 提示词优先取 prompt（agent 型）；message 型显示 目标模块.动作
      const content = t.prompt && t.prompt.length ? t.prompt
                    : (t.module ? (t.module + '.' + (t.action || '')) : (t.content || ''));
      return {
        taskId: t.taskId,
        status: t.status || '',
        schedule: t.schedule || '',
        content: content,
        executedCount: t.executedCount == null ? 0 : t.executedCount,
        _op: ''
      };
    });
    const wrap = document.createElement('div');
    renderTable(wrap, headers, rows, null);
    // 行内操作按钮（暂停/恢复/删除）
    const trs = wrap.querySelectorAll('table.tbl tr');
    list.forEach((t, idx) => {
      const tr = trs[idx + 1];
      if (!tr) return;
      const cell = tr.lastElementChild;
      cell.innerHTML = '';
      const mk = (label, sub, cls) => {
        const b = document.createElement('button');
        b.textContent = label; b.style.marginRight = '4px';
        if (cls) b.className = cls;
        b.onclick = async () => {
          const rr = await apiCommand('tasks', { sub: sub, task_id: t.taskId });
          toast(rr.success ? (rr.message || 'ok') : ('失败: ' + (rr.error || rr.message || '')), rr.success ? 'ok' : 'err');
          loadTasks();
        };
        return b;
      };
      if (t.enabled) cell.appendChild(mk('暂停', 'stop'));
      else cell.appendChild(mk('恢复', 'resume'));
      cell.appendChild(mk('删除', 'delete'));
    });
    box.innerHTML = '';
    box.appendChild(wrap);
    const hint = document.createElement('div');
    hint.className = 'muted';
    hint.textContent = '任务结果：在线时推送到当前会话；离线时进消息箱';
    box.appendChild(hint);
  } catch (e) {
    box.innerHTML = '<div class="muted">加载异常: ' + esc(e.message) + '</div>';
  }
}

// ======================== 后台任务面板（跨会话查看全部后台任务） ========================
// 与定时任务面板同数据源（tasks list 全量），只保留 kind=background；数据含
// startedAt/finishedAt（epoch 毫秒，未跑过为 0）与 running（运行态标记，后端任务表）。
let bgTasksTimer = null;
let bgDetailOpen = null;   // 当前展开明细行的 taskId（null=全部收起）；5s 自刷新重绘时按它恢复明细
let bgLastRows = [];       // 最近一次渲染的行数据（「查看」就地插入明细时本地取数，不额外发请求）
function openBgTasks() { $('#bgTasksModal').classList.remove('hidden'); loadBgTasks(); }
function closeBgTasks() {
  $('#bgTasksModal').classList.add('hidden');
  if (bgTasksTimer) { clearInterval(bgTasksTimer); bgTasksTimer = null; }
}
async function loadBgTasks() {
  const box = $('#bgTasksBox');
  if (!box) return;
  try {
    const r = await apiCommand('tasks', { sub: 'list' });
    if (!r.success) {
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message || '加载失败') + '</div>';
      return;
    }
    const rows = (r.data || []).filter(x => x.kind === 'background');
    renderBgTasks(rows);
    // 打开期间每 5s 自刷新（运行时长/状态走动）；关闭即停
    if (bgTasksTimer) clearInterval(bgTasksTimer);
    bgTasksTimer = setInterval(() => {
      if ($('#bgTasksModal').classList.contains('hidden')) { closeBgTasks(); return; }
      loadBgTasks();
    }, 5000);
  } catch (e) {
    box.innerHTML = '<div class="fail-msg">✗ ' + esc(e.message) + '</div>';
  }
}
/** 毫秒时长 → 人话（秒/分秒/时分） */
function fmtDur(ms) {
  if (ms == null || ms < 0) return '';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + ' 秒';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' 分 ' + (s % 60) + ' 秒';
  return Math.floor(m / 60) + ' 小时 ' + (m % 60) + ' 分';
}
function renderBgTasks(rows) {
  const box = $('#bgTasksBox');
  bgLastRows = rows;
  if (bgDetailOpen && !rows.some(x => x.taskId === bgDetailOpen)) bgDetailOpen = null;   // 任务被删→收起明细
  if (!rows.length) { box.innerHTML = '<div class="empty">暂无后台任务</div>'; return; }
  const now = Date.now();
  rows.sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));   // 新的在前
  let h = '<table class="tbl"><tr><th>任务</th><th>状态</th><th>启动时间</th><th>运行时长</th><th>操作</th></tr>';
  const liveOpen = [];   // 展开明细的运行中任务：innerHTML 落 DOM 后再补拉实时快照
  rows.forEach(t => {
    const running = bgRunning(t);
    const start = t.startedAt ? fmtTs(t.startedAt) : '—';
    const dur = t.startedAt ? fmtDur((running ? now : (t.finishedAt || now)) - t.startedAt) : '—';
    const name = esc(t.name || t.taskId);
    const p = t.prompt || '';
    const content = esc(p.length > 60 ? p.slice(0, 60) + '…' : p);
    h += '<tr data-tid="' + esc(t.taskId) + '">'
      + '<td title="' + esc(p) + '"><b>' + name + '</b><div class="sess-meta">' + content + '</div></td>'
      + '<td>' + esc(t.status || '') + '</td>'
      + '<td>' + esc(start) + '</td>'
      + '<td>' + esc(dur) + (running ? ' <span style="color:#4ade80">●</span>' : '') + '</td>'
      + '<td>'
      + '<button onclick="bgTaskView(\'' + esc(t.taskId) + '\')">查看</button> '
      + (running ? '<button onclick="bgTaskStop(\'' + esc(t.taskId) + '\')">停止</button> ' : '')
      + '<button class="del" onclick="bgTaskDelete(\'' + esc(t.taskId) + '\')">删除</button>'
      + '</td></tr>';
    // 展开中的明细行紧跟任务行之后（刷新重绘时按 bgDetailOpen 恢复）
    if (t.taskId === bgDetailOpen) {
      h += bgDetailRowHtml(t, now);
      if (running) liveOpen.push(t);
    }
  });
  box.innerHTML = h + '</table>';
  liveOpen.forEach(bgFillLive);   // 明细就位后再拉快照（只在展开时发 get）
}
/** 后台任务是否运行中（list/get 的 running 标记 或 本地状态枚举） */
function bgRunning(t) { return t.running === true || t.localStatus === 'running'; }
/** 明细正文：头部元信息 + 按状态分支的结果/错误/快照（全部 esc 转义） */
function bgDetailInner(t, now) {
  const running = bgRunning(t);
  const dur = t.startedAt ? fmtDur((running ? now : (t.finishedAt || now)) - t.startedAt) : '';
  let h = '<div class="sess-meta">状态：' + esc(t.status || '') + ' ｜ 耗时：' + esc(dur || '—')
        + ' ｜ 执行会话：' + esc(t.execSession || '—') + '</div>';
  if (running) {
    // 实时快照：先占位，bgFillLive 异步拉 tasks get 填充（完整过程在发起会话的任务卡片抽屉里）
    h += '<div class="sess-meta">【实时快照】</div>'
       + '<pre class="out" id="bgd_live_' + esc(t.taskId) + '">加载中…</pre>'
       + '<div class="sess-meta">完整过程可在发起会话的任务卡片里查看</div>';
  } else if (t.localStatus === 'done') {
    h += '<pre class="out">' + esc(t.resultText ? t.resultText : '（无输出）') + '</pre>';
  } else {   // 失败/已停止/已中断（及未知终态）：错误原因 + 部分输出
    if (t.errorText) h += '<pre class="out">' + esc(t.errorText) + '</pre>';
    if (t.progressSnapshot) h += '<div class="sess-meta">【部分输出】</div><pre class="out">' + esc(t.progressSnapshot) + '</pre>';
    if (!t.errorText && !t.progressSnapshot) h += '<pre class="out">（无输出）</pre>';
  }
  return h;
}
/** 明细整行（id=bgd_<tid>：点击收起 / 刷新恢复都按它定位） */
function bgDetailRowHtml(t, now) {
  return '<tr class="bg-detail" id="bgd_' + esc(t.taskId) + '"><td colspan="5">' + bgDetailInner(t, now) + '</td></tr>';
}
/** 「查看」：展开/收起明细行。展开就地插入（用最近一次数据，不重拉列表）；运行中再异步补实时快照 */
function bgTaskView(tid) {
  const openEl = document.getElementById('bgd_' + tid);
  if (bgDetailOpen === tid || openEl) {           // 再点一次→收起
    bgDetailOpen = null;
    if (openEl) openEl.remove();
    return;
  }
  bgDetailOpen = tid;
  const t = bgLastRows.find(x => x.taskId === tid);
  const rowEl = Array.from(document.querySelectorAll('#bgTasksBox tr[data-tid]')).find(r => r.dataset.tid === tid);
  if (!t || !rowEl) { loadBgTasks(); return; }    // 数据缺失兜底：整表重拉（渲染时按 bgDetailOpen 展开）
  rowEl.insertAdjacentHTML('afterend', bgDetailRowHtml(t, Date.now()));
  bgFillLive(t);
}
/** 运行中任务：tasks get 拉 liveSnapshot 填进已展开的明细行（仅明细打开时调用，5s 刷新重绘会重新触发） */
async function bgFillLive(t) {
  if (!bgRunning(t)) return;
  const pre = document.getElementById('bgd_live_' + t.taskId);
  if (!pre) return;                               // 明细没展开：不发请求
  try {
    const r = await apiCommand('tasks', { sub: 'get', task_id: t.taskId });
    const cur = document.getElementById('bgd_live_' + t.taskId);   // 期间可能被收起/重绘
    if (!cur) return;
    if (!r || !r.success || !r.data) { cur.textContent = '（快照获取失败：' + ((r && (r.error || r.message)) || '未知错误') + '）'; return; }
    cur.textContent = r.data.liveSnapshot || '（暂无输出）';
  } catch (e) {
    const cur = document.getElementById('bgd_live_' + t.taskId);
    if (cur) cur.textContent = '（快照获取失败：' + e.message + '）';
  }
}
async function bgTaskStop(tid) {
  const r = await apiCommand('tasks', { sub: 'stop', task_id: tid });
  if (r && r.success) { toast('已请求停止任务', 'ok'); refreshSessionList(); }
  else toast('[错误] ' + ((r && (r.error || r.message)) || '停止失败'), 'err');
  loadBgTasks();
}
async function bgTaskDelete(tid) {
  // 自绘确认弹框（全局单例 #confirmModal；window.confirm 会卡住 Playwright/自动化，项目已有统一弹框）
  const ok = await confirmDialog('删除后台任务？',
    '删除后台任务 ' + tid + '？任务记录删除；各会话里对应的任务卡片一并移除，已产生的结果消息保留。', '删 除');
  if (!ok) return;
  const r = await apiCommand('tasks', { sub: 'delete', task_id: tid });
  if (!r || !r.success) { toast('[错误] ' + ((r && (r.error || r.message)) || '删除失败'), 'err'); return; }
  // 同步移除各会话里的本地卡片（含脱离 DOM 的后台会话视图）
  Object.keys(state.sessions).forEach(sid => {
    const v = state.sessions[sid];
    if (v && v.tasks && v.tasks[tid]) {
      delete v.tasks[tid];
      const el = v.box.querySelector('#tc_' + CSS.escape(tid));
      if (el) el.remove();
    }
  });
  toast('已删除任务', 'ok');
  loadBgTasks(); refreshSessionList();
}

// ======================== 侧栏：会话列表 ========================
// 数据源固定为服务端 loadSessions()（不能遍历 state.sessions——里面有孤儿条目）。
// 拉取与渲染分离：refreshSessionList() 先拉再渲染（首次/手动刷新）；
// renderSessionList() 用最近一次拉取的数据同步渲染（切会话/忙碌状态变化时调用，不产生请求）。
let lastSessionsData = null;   // 最近一次 loadSessions 拉到的会话数据（null=尚未拉过）
let lastSessionsError = null;  // 最近一次 loadSessions 的失败文案（非空时重渲染保留失败提示，不被顶成"加载中"）

/** 行首状态图标：● 运行中（本地忙或服务端 running 计数）/ ○ 有未读 / 空格 */
function sessionStatusIcon(sid, srvRunning) {
  const v = state.sessions[sid];
  if ((v && v.busy) || srvRunning) return '●';
  if (v && v.unread > 0) return '○';
  return ' ';
}

/** 会话行首名字的显示文本：标题 → 首句（截断约 22 字）→ 会话ID（优先级）。
 *  缺省兜底 sessionId：空标题/空首句/旧版负载（无这两个字段）均不会显示空白行。 */
function sessionDisplayName(s) {
  const t = (s.title || '').trim();
  if (t) return t;
  const m = (s.userMessage || '').trim();
  if (m) return m.length > 22 ? m.slice(0, 22) + '…' : m;
  return s.sessionId;
}

/** 渲染左侧会话栏（顺序即服务端次序：最近活动优先）。
 *  同步函数、只读缓存——switchSessionView/setBusy 等高频路径可安全调用。 */
function renderSessionList() {
  const box = $('#sessionsBox');
  if (!box) return;
  const keep = box.scrollTop;   // 整块重建前存滚动位置，重建后恢复（否则重绘后跳回顶部）
  if (lastSessionsData === null) {
    // 拉取失败时保留失败文案，不被后续重渲染（switchSessionView/setBusy 等）顶成"加载中"
    box.innerHTML = lastSessionsError
      ? '<div class="fail-msg">' + esc(lastSessionsError) + '</div>'
      : '<div class="empty">加载中...</div>';
    return;
  }
  box.innerHTML = '';
  if (!lastSessionsData.length) { box.innerHTML = '<div class="empty">（暂无历史会话）</div>'; return; }
  lastSessionsData.forEach(s => {
    const sid = s.sessionId;
    const icon = sessionStatusIcon(sid, !!s.running);
    const row = document.createElement('div');
    row.className = 'sess-row' + (sid === state.sessionId ? ' cur' : '');
    row.dataset.sid = sid;
    // 第一行：状态图标 + 会话名（标题 → 首句 → 会话ID；窄栏省略号截断，tooltip 看全称）
    const l1 = document.createElement('div');
    l1.className = 'sess-line';
    const ic = document.createElement('span');
    ic.className = 'sess-icon';
    ic.textContent = icon;
    if (icon === '●') ic.style.color = '#4ade80';
    else if (icon === '○') ic.style.color = '#93c5fd';
    ic.title = icon === '●' ? '运行中' : (icon === '○' ? '有未读' : '');
    const name = document.createElement('span');
    name.className = 'sess-sid';
    name.textContent = sessionDisplayName(s);
    // tooltip 三段全量：完整标题 / 完整首句 / 完整会话ID（缺哪项略哪项）
    name.title = [s.title, s.userMessage, sid].filter(x => x).join('\n');
    l1.appendChild(ic);
    l1.appendChild(name);
    row.appendChild(l1);
    // 第二行：Agent · 最后活动 · 轮次 · 状态
    const bits = [];
    if (s.agentName) bits.push(s.agentName);
    if (s.savedAt) bits.push(fmtTs(s.savedAt));
    bits.push((s.count == null ? 0 : s.count) + '条');
    if (s.state) bits.push(s.state);
    const meta = document.createElement('div');
    meta.className = 'sess-meta';
    meta.textContent = bits.join(' · ');
    meta.title = meta.textContent;
    row.appendChild(meta);
    // 第三行：行内操作（与旧表格同 handler，加"改名"，行为不变）
    const ops = document.createElement('div');
    ops.className = 'sess-ops';
    const mkBtn = (label, cls, fn, tip) => {
      const b = document.createElement('button');
      b.textContent = label;
      if (cls) b.className = cls;
      if (tip) b.title = tip;   // 悬浮说明：两个"进入会话"按钮语义差异靠它讲清楚
      b.onclick = () => fn(sid);
      ops.appendChild(b);
    };
    mkBtn('接管', null, continueSession, '接管该会话：载入历史并取得发言权；若正被其他设备使用，对方会被踢下线');
    mkBtn('切换', null, switchSession, '进入该会话查看历史（和平进入，不踢其他设备；首次进入自动载入历史）');
    mkBtn('改名', null, renameSessionFlow, '重命名这个会话');
    mkBtn('清除上下文', null, clearSession, '清空该会话的对话上下文（模型从此不再记得前面的对话；历史记录仍保留）');
    mkBtn('删除', 'del', deleteSessionBtn, '删除该会话（历史记录一并删除，不可恢复）');
    row.appendChild(ops);
    box.appendChild(row);
  });
  box.scrollTop = keep;
}

/** 拉取 + 渲染会话列表（首次/手动刷新/登录自动接续用）。返回原始会话数据 */
async function loadSessions() {
  const box = $('#sessionsBox');
  if (box) box.innerHTML = '<div class="empty">加载中...</div>';
  try {
    const r = await apiCommand('sessions', { userId: state.userId });
    lastSessionsError = null;
    lastSessionsData = (r.data || []).filter(s => s && s.sessionId);
    renderSessionList();
    return lastSessionsData;
  } catch (e) {
    lastSessionsError = e.message;   // 记住失败：后续 renderSessionList 重渲染时保住失败提示
    if (box) box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>';
    return [];
  }
}
/** 手动刷新（先拉再渲染） */
function refreshSessionList() { return loadSessions(); }

let renderListTimer = null;
/** 节流合并的列表重渲染：300ms 内多次调用只渲染一次（setBusy 等高频路径用） */
function scheduleRenderSessionList() {
  if (renderListTimer) return;
  renderListTimer = setTimeout(() => { renderListTimer = null; renderSessionList(); }, 300);
}
/** 接管/恢复历史会话：恢复 sessionId + 加载历史到聊天窗。silent=true 时（登录自动接续/切换自动补载）不弹 toast、不强制接管。返回 {ok, error} */
async function continueSession(sid, silent) {
  try {
    const r = await apiCommand('continue', { userId: state.userId, sessionId: sid });
    if (r.success && r.data) {
      const target = r.data.sessionId || sid;
      switchSessionView(target);         // 切到目标会话视图（恢复输入/忙碌态/自动运行开关态由它统一处理）
      if (viewOf(target).busy) {
        // 该会话正在流式：清屏重放会让进行中的回复变成孤儿节点（用户永远看不到），保留现场
        if (!silent) toast('该会话正在运行中，保留现场（完成后可刷新恢复完整历史）', 'err');
        restoreTaskCards(target);          // 现场保留但 box 可能是新页面的空盒：卡片仍需恢复
        return { ok: true, error: null, busy: true };
      }
      clearSessionBox(target);           // 重建该会话视图：避免与上次浏览时渲染的旧内容重复
      appendSysMsg('已恢复会话 ' + target + ' (' + (r.data.count || 0) + ' 条历史)');
      (r.data.history || []).forEach(h => {
        const hasAtts = !!(h && h.attachments && h.attachments.length);
        if (!h || h.role === 'system' || (!h.content && !hasAtts)) return;
        if (h.role === 'tool') { renderToolResult('tool', h.content); return; }  // 工具结果消息（含截图）
        const el = appendMsg(h.role === 'user' ? 'user' : 'ai', h.content);
        if (h.role === 'user') appendAttachmentThumbs(el, h.attachments);   // 历史回放：附件缩略图
      });
      restoreTaskCards(target);          // 历史渲染完再补该会话的后台任务卡片（清屏时被一并清掉）

      // 会话占用声明（登录级互斥）：
      // silent（登录自动接续）→ 静默登记，被占用仅提示不接管；
      // 非 silent（面板"接管"按钮=明确接管意图）→ 直接 force 接管（踢对方下线），不弹窗
      await claimSession(target, !silent, silent);
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
 * - force=true（面板"接管"=明确接管意图）→ 直接接管（踢对方下线）
 * - force=false（登录自动接续 silent）→ 被占用时仅提示，不接管、不切新会话
 */
async function claimSession(sid, force, silent) {
  // 登录标识缺失时补生成并持久化：localStorage 被清/旧页面恢复路径（initSession 只读不生成）
  // 会带着空 loginId 走到这里，此前直接 return——接管与占用提示全程静默失效（表现为"接管没反应"）
  if (!state.loginId) {
    state.loginId = 'login_' + state.userId + '_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
    localStorage.setItem('tlweb_loginid', state.loginId);
  }
  const r = await apiCommand('claimSession', { sessionId: sid, loginId: state.loginId, force: force || false });
  if (!r.success || !r.data) {
    // 响应异常不再静默：静默会让被踢的会话永久锁死（用户看不到任何反馈，也无法解锁）
    toast('接管失败：' + (r.error || r.message || '响应异常'), 'err');
    return;
  }
  if (r.data.occupied) {
    appendSysMsg('⚠ 该会话正被另一登录使用（历史可查看，发消息将被拒绝；点该会话『接管』可直接夺回）');
    return;
  }
  // 接管成功（force 且本机成为 owner）→ 清除本会话的 kicked 标记并恢复输入
  const v = viewOf(sid);
  if (v.kicked) {
    v.kicked = false;
    if (sid === state.sessionId) { $('#chatInput').disabled = false; $('#sendBtn').disabled = false; }
  }
  if (r.data.kicked) {
    appendSysMsg('💡 已接管会话，对方已下线');
  }
}
async function switchSession(sid) {
  try {
    const v = viewOf(sid);
    // 该会话从未载入过历史（空白盒）：走"继续"链路把历史补上——
    // 此前「切换」只换视图不载历史，切过去是空白页（登录后仅第一条会话被自动继续，其余全空）
    if (v.box.childNodes.length === 0 && !v.busy) {
      const res = await continueSession(sid, true);   // silent：不弹成功提示；被占用只提示、不强夺
      if (!res.ok) toast(res.error || '载入会话历史失败', 'err');
      restoreTaskCards(sid);
      return;
    }
    const r = await apiCommand('session', { sessionId: sid });
    if (!r.success) { toast(r.error || r.message, 'err'); return; }
    switchSessionView(sid);   // 切视图（localStorage/#sessionId/占用上报/自动运行开关态统一在 switchSessionView 里做）
    restoreTaskCards(sid);    // 「进会话」的另一条路径（不重放历史）：补该会话的后台任务卡片
    toast(r.message || sid, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}
async function clearSession(sid) {
  try {
    const r = await apiCommand('clear', { sessionId: sid });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
  } catch (e) { toast(e.message, 'err'); }
}
/** 手动改名（覆盖模式，不受 onlyIfEmpty 限制）：prompt 回填当前标题，成功后就地更新缓存并重渲染 */
async function renameSessionFlow(sid) {
  const row = (lastSessionsData || []).find(x => x.sessionId === sid);
  const cur = row && row.title ? row.title : '';
  const t = window.prompt('新的会话名称（留空取消）', cur);
  if (t === null) return;
  const v = t.trim();
  if (!v) return;
  try {
    const r = await apiCommand('renameSession', { sessionId: sid, title: v });
    if (r && r.success) {
      if (row) row.title = v;
      renderSessionList();
      toast('已改名: ' + v, 'ok');
    } else {
      toast('[错误] ' + ((r && (r.error || r.message)) || '改名失败'), 'err');
    }
  } catch (e) { toast('[错误] ' + e.message, 'err'); }
}
/** 删除会话：确认后删除该会话全部记录（DB/文件，不可恢复） */
async function deleteSessionBtn(sid) {
  if (!confirm('确认删除会话 ' + sid + ' ？\n将删除该会话的全部历史记录，不可恢复。')) return;
  try {
    const r = await apiCommand('deleteSession', { sessionId: sid });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success && state.sessionId === sid) {
      // 当前会话被删：开新会话（switchSessionView 顺带把被删会话的 box 从 #msgList 摘掉）
      newSession();
    }
    if (r.success) loadSessions();
  } catch (e) { toast(e.message, 'err'); }
}
async function resumeCheckpoint() {
  try {
    const r = await apiCommand('resume', { userId: state.userId });
    if (!r.success) {
      // 没断点：多半是"提示期间该轮已经跑完"（审批后继续执行完成、断点已被正常收口）——
      // 不当成失败：刷新当前会话，让用户看到已保存的结果（2026-10-17 实测场景）
      toast(r.error || r.message || '没有可恢复的断点', 'info');
      if (state.sessionId) { try { await continueSession(state.sessionId, true); } catch (e) { /* 忽略刷新失败 */ } }
      return;
    }
    const d = r.data || {};
    // 切到断点会话（恢复以断点时的用户消息重新发起，走流式管道）
    switchSessionView(d.sessionId || state.sessionId);
    restoreTaskCards(d.sessionId || state.sessionId);   // 与 continueSession/switchSession 一致：补该会话的后台任务卡片
    appendMsg('user', d.userMessage || '（断点消息）');
    toast('找到断点会话 ' + d.sessionId + '，正在恢复执行...', 'info');
    // 流式恢复：streamChat 内部管理 busy/光标/SSE 渲染
    await streamChat(d.userMessage || '', { sessionId: d.sessionId, resume: true });
  } catch (e) { toast(e.message, 'err'); }
}

// 同会话还有未处置的审批时，自动续跑先挂起（见 maybeAutoResume/flushPendingAutoResume）
const pendingAutoResume = new Set();

/** 该会话是否还有未处置的审批（当前弹框或排队中） */
function hasPendingApprovalFor(sid) {
  return (currentApproval && currentApproval.sessionId === sid)
      || approvalQueue.some(a => a.sessionId === sid);
}

/** 队列前进后统一放行延迟续跑：同会话已无任何待批弹框/排队项的，才真正 resume。
 *  resume 本身不会再产生审批，不会递归回这里（只做简单检查） */
function flushPendingAutoResume() {
  if (!pendingAutoResume.size) return;
  for (const sid of [...pendingAutoResume]) {
    if (hasPendingApprovalFor(sid)) continue;
    pendingAutoResume.delete(sid);
    autoResumeSession(sid);
  }
}

/** 断点态审批（deferred）决策后自动续跑：approve/reject/关框三处共用；非 deferred 不动 */
function maybeAutoResume(r, evt) {
  if (!r || !r.deferred) return;              // 缺席=非 deferred，勿测 ===false
  const sid = r.sessionId || (evt && evt.sessionId);
  if (!sid) return;
  // 同批多个审批：本单决策后同会话还有待批，此刻 resume 会被服务端"待批操作"守卫拦下
  // （整批工具仍在等其余决策）→ 先记账，等该会话最后一单处理完再续跑，避免会话卡 thinking
  if (hasPendingApprovalFor(sid)) { pendingAutoResume.add(sid); return; }
  pendingAutoResume.delete(sid);              // 已直跑：清掉可能的挂起记录，防 flush 二次续跑
  autoResumeSession(sid);
}

/** 断点态审批决策后自动续跑：查该会话断点 → 按「继续会话」同链路清屏渲染历史 → 流式 resume */
async function autoResumeSession(sid) {
  try {
    const r = await apiCommand('resume', { sessionId: sid });
    if (!r.success) {
      // 审批已处理但已无断点：通常是被另一处/上一轮先收口（该轮已完成）——
      // 如实提示并刷新会话，让用户看到已保存的结果，而不是停在旧画面
      toast(r.error || '未找到断点', 'err');
      try { await continueSession(sid, true); } catch (e) { /* 忽略刷新失败 */ }
      return;
    }
    const d = r.data || {};
    const target = d.sessionId || sid;
    // 先渲染目标会话历史再续跑（清屏重放 + 占用声明，与「继续会话」同一链路）：
    // 旧的 switchSessionView + appendMsg 有两个洞——已渲染过的盒会重复补用户气泡；
    // 空白盒只见续跑轮、更早历史不可见（要刷新才出来）
    const res = await continueSession(target, true);   // silent：不弹提示；被占用只提示、不强夺
    if (!res.ok) {
      // 历史加载失败（极端：会话记录异常）：退回仅切视图 + 手动补断点消息，保底续跑可见
      switchSessionView(target);
      appendMsg('user', d.userMessage || '（断点消息）');
      restoreTaskCards(target);
    }
    // 自动续跑即是对该会话断点的处置：关掉残留的断点弹框，避免 ▶/🗑 对进行中的续跑二次操作
    if (state.pendingAbandonSessionId === target) {
      $('#checkpointModal').classList.add('hidden');
      state.pendingAbandonSessionId = null;
    }
    toast('正在继续执行…', 'info');
    await streamChat(d.userMessage || '', { sessionId: target, resume: true });
  } catch (e) { toast('续跑失败: ' + e.message, 'err'); }
}

/** 登录后检测断点会话：有则弹窗提示（仿命令行启动时的 ⚠ 提示），点"恢复执行"走流式恢复 */
async function promptCheckpoint() {
  try {
    const r = await apiCommand('resume', { userId: state.userId });
    if (!r.success || !r.data || !r.data.sessionId) {
      // 无断点/响应异常：按钮与目标会话一并复位（防残留上一次的「放弃该操作」误触旧会话）
      $('#cpAbandonBtn').classList.add('hidden');
      state.pendingAbandonSessionId = null;
      return;
    }
    const d = r.data;
    // pending_approval 断点：显示「放弃该操作」（批准/拒绝走重放的审批弹框）；
    // 非 pending 时按钮隐藏并把会话 id 清空，防误触到上一次的断点会话
    const isPending = (d.state === 'pending_approval');
    $('#cpAbandonBtn').classList.toggle('hidden', !isPending);
    state.pendingAbandonSessionId = isPending ? (d.sessionId || null) : null;
    const timeStr = d.savedAt ? fmtTs(d.savedAt) : '未知';
    // 只预览消息开头：断点若落在"后台任务回执"这类长消息上，全文会把弹框撑满整屏（2026-10-06 卡死事故）
    const um = String(d.userMessage || '');
    const umPreview = um.length > 160 ? um.slice(0, 160) + '…（完整内容见会话）' : um;
    $('#cpDesc').textContent =
      'Agent:    ' + (d.agentName || '未知') + '\n' +
      '会话ID:   ' + d.sessionId + '\n' +
      '用户消息: ' + umPreview + '\n' +
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
      return { key, cls, type: 'agent' };
    });
    renderTable(box, [['key', '家族名'], ['cls', '类']], rows);
    attachToolOps(box, rows);
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
      // 目录脚本 skill 的实例类固定为 TLScriptExecutionSkill → 操作 type=skill；其余 Java 类 skill → baseSkill
      const type = cls === 'TLScriptExecutionSkill' ? 'skill' : 'baseSkill';
      return { key, cls, type };
    });
    renderTable(box, [['key', '家族名'], ['cls', '类']], rows);
    attachToolOps(box, rows);
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}

/** 列表表格右侧追加"操作"列：参数 / 卸载 / 重载，按行携带家族名自动操作。
 *  家族名无 ':' 的是顶层自注册模块（如 aiagent_master），无父路径可解析，不给按钮。 */
function attachToolOps(box, rows) {
  const trs = [...box.querySelectorAll('table.tbl tr')];
  if (!trs.length) return;
  trs[0].insertAdjacentHTML('beforeend', '<th>操作</th>');
  trs.slice(1).forEach((tr, i) => {
    const r = rows[i] || {};
    const td = document.createElement('td');
    if (r.key && r.key.indexOf(':') > 0) {
      const family = r.key;
      const type = r.type;
      const b1 = document.createElement('button');
      b1.textContent = '参数';
      b1.title = '查看参数: ' + family;
      b1.onclick = () => loadParam(family);
      const b2 = document.createElement('button');
      b2.textContent = '卸载';
      b2.title = '卸载: ' + family;
      b2.onclick = () => {
        if (!confirm('确认卸载 ' + family + ' ?')) return;
        runToolOp('uninstall', type, family);
      };
      const b3 = document.createElement('button');
      b3.textContent = '重载';
      b3.title = '重载: ' + family;
      b3.onclick = () => runToolOp('reload', type, family);
      td.appendChild(b1);
      td.appendChild(b2);
      td.appendChild(b3);
    } else {
      td.textContent = '—';
    }
    tr.appendChild(td);
  });
}

/** 行内卸载/重载：复用后端 /uninstall /reload 接口（type + 家族名），结果 toast，卸载成功后刷新列表 */
async function runToolOp(action, type, family) {
  try {
    const r = await apiCommand(action, { type, name: family });
    toast((r.success ? '✓ ' : '✗ ') + (r.message || r.error || action), r.success ? 'ok' : 'err');
    if (r.success && action === 'uninstall') {
      if (type === 'agent') loadAgents(); else loadSkills();
    }
  } catch (e) { toast(e.message, 'err'); }
}

/** 行内查看参数（家族名自动带入，无需手输）：弹框展示，点遮罩或"关闭"退出 */
async function loadParam(family) {
  const modal = $('#paramModal');
  const body = $('#pmBody');
  $('#pmTitle').textContent = '参数: ' + family;
  body.innerHTML = '<div class="empty">加载中...</div>';
  modal.classList.remove('hidden');
  try {
    const r = await apiCommand('param', { toolName: family });
    body.innerHTML = '';
    if (!r.success) {
      body.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
      return;
    }
    const p = r.data || {};
    const entries = Object.entries(p);
    if (!entries.length) { body.innerHTML = '<div class="empty">（无参数）</div>'; return; }
    const tab = document.createElement('div');
    body.appendChild(tab);
    renderTable(tab, [['k', '参数'], ['v', '值']], entries.map(([k, v]) => ({ k, v: typeof v === 'object' ? JSON.stringify(v) : v })));
  } catch (e) { body.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}
function closeParam() {
  $('#paramModal').classList.add('hidden');
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

// ======================== 通用确认弹框 ========================
let confirmResolve = null;
function closeConfirm(result) {
  $('#confirmModal').classList.add('hidden');
  const r = confirmResolve;
  confirmResolve = null;
  if (r) r(result);
}
/** 返回 Promise<boolean>：确定=true；取消或点遮罩=false */
function confirmDialog(title, text, okText) {
  return new Promise(resolve => {
    confirmResolve = resolve;
    $('#cfTitle').textContent = title;
    $('#cfText').textContent = text;
    $('#cfOkBtn').textContent = okText || '确 定';
    $('#confirmModal').classList.remove('hidden');
  });
}

// ======================== 面板：评测/测试 ========================
// 评测会真实调 LLM（耗 token、要等），误点代价不小 —— 除 list（只读用例清单）外先确认
const EVAL_CONFIRM = {
  suite:   ['开始全量评测？', '将按用例目录逐条调用被测 Agent，会真实消耗 LLM token 与时间。'],
  quick:   ['开始快速自检？', '将调用被测 Agent 跑一条内置用例，会消耗 LLM token。'],
  run:     ['运行这条用例？', '将调用被测 Agent（agent_chat 用例消耗 LLM token；skill_execute 用例直接调 Skill）。'],
  cascade: ['开始级联评测？', '将自动发现目标 Agent 的子 Agent/Skill 并逐个评测，会消耗较多 LLM token 与时间。'],
};
async function evalCmd(sub, arg) {
  const params = { subAction: sub };
  if (sub === 'run') params.caseId = $('#evalCase').value.trim();
  if (sub === 'cascade' && $('#evalAgent').value.trim()) params.agent = $('#evalAgent').value.trim();
  // 先校验再弹确认：参数不全时不该先让用户点一次"开始评测"
  if (sub === 'run' && !params.caseId) { toast('请输入 caseId', 'err'); return; }

  const conf = EVAL_CONFIRM[sub];
  if (conf) {
    const ok = await confirmDialog(conf[0], conf[1], '开始评测');
    if (!ok) return;
  }

  const box = $('#evalBox');
  box.innerHTML = '<div class="empty">执行中...</div>';
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
        appendReport(box, d);
      } else {
        box.innerHTML = '<div class="ok-msg">✓ ' + esc(r.message) + '</div>';
      }
    } else {
      // 失败也要看图：用例挂了的时候报告（失败详情那一节）恰恰最该看
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
      appendReport(box, r.data);
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
    if (!r.success) {
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
      appendReport(box, r.data);
      return;
    }
    if (mode === 'list') {
      const rows = (r.data || []).map(c => ({ c }));
      renderTable(box, [['c', '用例']], rows);
    } else {
      const d = r.data || {};
      box.innerHTML = '<div class="ok-msg">✓ 测试完成: ' + d.passed + '/' + d.total + ' 通过' +
        (d.failed ? '，失败 ' + d.failed : '') + '</div>';
      if (d.reportPath) box.innerHTML += '<div class="empty">报告: ' + esc(d.reportPath) + '</div>';
      appendReport(box, d);
    }
  } catch (e) { box.innerHTML = '<div class="fail-msg">' + esc(e.message) + '</div>'; }
}

// ======================== 面板：追踪/统计 ========================
// 环节英文 → 中文标签（服务层返回原始 stage，展示转换在 UI 层）
const STAGE_CN = { roundStart: '轮次开始', llmRequest: '发送LLM', llmResponse: 'LLM返回',
  toolStart: '工具开始', toolEnd: '工具结束', approvalRequested: '请求审批', roundEnd: '轮次结束' };
const TRACE_HEADERS = [['time', '时间'], ['stage', '环节'], ['agent', 'Agent'], ['dur', '耗时'], ['detail', '摘要']];
// 最近一次查询的原始 stages（重放按钮按下标取环节）
let lastTraceStages = null;
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
        lastTraceStages = arr;
        // 手拼表格（renderTable 不支持操作列）：每行末加「▶ 重放」（roundEnd 行无按钮）
        const rows = toTraceRows(arr);
        let h = '<table class="tbl"><tr>' + TRACE_HEADERS.map(x => '<th>' + esc(x[1]) + '</th>').join('') + '<th>操作</th></tr>';
        arr.forEach((s, i) => {
          const act = s.stage === 'roundEnd' ? '' : '<button onclick="replayTrace(' + i + ')">▶ 重放</button>';
          h += '<tr>' + TRACE_HEADERS.map(x => '<td>' + esc(rows[i][x[0]]) + '</td>').join('') + '<td>' + act + '</td></tr>';
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
async function doTraceLlm() {
  const box = $('#traceBox');
  box.innerHTML = '<div class="empty">查询中...</div>';
  try {
    const r = await apiCommand('traceLlm', { sessionId: state.sessionId });
    box.innerHTML = '';
    if (r.success) {
      const arr = Array.isArray(r.data) ? r.data : [];
      if (arr.length) {
        lastTraceStages = arr;
        // 表格行与 payload 折叠块交错：每条环节行下方紧跟其完整内容（跨列单元格）；
        // 每行末加「▶ 重放」（roundEnd 行无按钮）
        const rows = toTraceRows(arr);
        let h = '<table class="tbl"><tr>' + TRACE_HEADERS.map(x => '<th>' + esc(x[1]) + '</th>').join('') + '<th>操作</th></tr>';
        arr.forEach((s, i) => {
          const act = s.stage === 'roundEnd' ? '' : '<button onclick="replayTrace(' + i + ')">▶ 重放</button>';
          h += '<tr>' + TRACE_HEADERS.map(x => '<td>' + esc(rows[i][x[0]]) + '</td>').join('') + '<td>' + act + '</td></tr>';
          if (s.payload) {
            h += '<tr><td colspan="' + (TRACE_HEADERS.length + 1) + '"><details><summary>[' + (i + 1) + '] ' + esc(STAGE_CN[s.stage] || s.stage) + ' - ' + esc(s.detail || '') + '</summary><pre>' + esc(s.payload) + '</pre></details></td></tr>';
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

/** trace 定点重放：从第 i 个环节（0-based）重新执行后半段，结果作为新的一轮 */
async function replayTrace(i) {
  const s = lastTraceStages ? lastTraceStages[i] : null;
  if (!s || s.stage === 'roundEnd') return;
  if (!confirm('从环节 ' + (i + 1) + '（' + (STAGE_CN[s.stage] || s.stage) + '）重放？\n将重新执行该环节及之后的部分，结果作为新的一轮。')) return;
  const box = $('#replayResult');
  box.innerHTML = '<div class="empty">重放中（' + (STAGE_CN[s.stage] || s.stage) + '）...</div>';
  box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  try {
    const r = await apiCommand('traceReplay', { sessionId: state.sessionId, index: i + 1 });
    if (r.success) {
      // 重放结果进聊天交互界面（新的一轮）：系统标签 + AI 回答气泡
      appendSysMsg('↺ 已从环节 ' + (i + 1) + '（' + (STAGE_CN[s.stage] || s.stage) + '）重放：' + (r.message || ''));
      if (r.response) appendMsg('ai', r.response);
      // 结果区仅保留状态行
      box.innerHTML = '<div class="ok-msg">✓ ' + esc(r.message || '重放完成') + '</div>';
      doTrace();   // 刷新环节表（monitor 已切到新轮）
    } else {
      box.innerHTML = '<div class="fail-msg">✗ ' + esc(r.error || r.message) + '</div>';
    }
  } catch (e) {
    box.innerHTML = '<div class="fail-msg">✗ ' + esc(e.message) + '</div>';
  }
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
// 同一批工具调用可能同时产生多条审批（如 4 个 file_operation 一起到达）。弹框只有一套 DOM：
// 后到的事件若直接覆盖 currentApproval，前面的审批就"看不见"，其工具一直等不到决策 →
// 整批工具卡死、会话长时间 thinking。必须排队、逐个展示（弹框一次只显示一单）。
let currentApproval = null;
const approvalQueue = [];      // 已到达但还没轮到展示的审批事件（FIFO）
let pendingDecision = null;    // 正在提交决策的 approvalId：resolved 推送对自己刚点的决策不重复提示
function showApprovalModal(evt) {
  // 去重：SSE 重连会重放全部 pending 审批，同一 approvalId 不得重复入队/重渲染
  const dup = (currentApproval && currentApproval.approvalId === evt.approvalId)
      || approvalQueue.some(a => a.approvalId === evt.approvalId);
  if (dup) return;
  if (currentApproval) { approvalQueue.push(evt); return; }   // 已有弹框：排队等当前这单结束
  renderApprovalModal(evt);
}
function renderApprovalModal(evt) {
  currentApproval = evt;
  const stale = $('#approvalModal .ap-source');
  if (stale) stale.remove();                        // 上一次审批可能带来源行 → 先清，防叠加
  const staleAgo = $('#approvalModal .ap-stale');
  if (staleAgo) staleAgo.remove();                  // 陈举行同理：先清，防上一单的"发起于…前"跟到下一单
  if (evt.fromTask) {
    // 来自后台任务执行会话的审批：标注来源（本会话卡片里能查到名字就优先用）
    const local = (evt.parentSessionId && evt.taskId
      && viewOf(evt.parentSessionId).tasks[evt.taskId]) || null;
    const el = document.createElement('div');
    el.className = 'ap-source';
    el.textContent = '来自后台任务：' + (evt.name || (local && local.name) || evt.taskId || '');
    const desc = $('#apDesc');                      // 弹框结构：h3#apTitle → div#apDesc → 参数区
    if (desc && desc.parentNode) desc.parentNode.insertBefore(el, desc);
  }
  $('#apTitle').textContent = '⚠ 审批请求：' + (evt.toolName || '未知工具');
  $('#apDesc').textContent = evt.description || '需要您的确认';
  // 断点态补批：标注发起时间（避免把几小时前的请求当成刚刚的操作确认）
  if (evt.deferred) {
    const ago = fmtAgo(evt.createdAt);
    const hint = document.createElement('div');
    hint.className = 'ap-stale';
    hint.textContent = '⏳ 该请求发起于' + ago + '（你不在线时转为了待批断点，批准后将继续执行）';
    $('#apDesc').after(hint);
  }
  $('#apArgs').value = evt.args || '{}';
  $('#apReason').value = '';
  $('#apMsg').classList.add('hidden');
  $('#approvalModal').classList.remove('hidden');
}
// 关框统一出口：同时清 currentApproval，防关闭后残留点击再提交（resolved 事件也据此不重复提示）。
// expectedId：只关指定的那一单——决策 HTTP 响应与 approvalResolved 推送可能乱序，若 resolved 已
// 先关框并弹出了下一单，后到的响应不能再把下一单误关掉。
// 关框后队列前进放到 setTimeout 0：避免在触发本次关闭的点击处理（及其后的 maybeAutoResume）
// 还没跑完时就重入渲染下一单。
function closeApprovalModal(expectedId) {
  if (expectedId && currentApproval && currentApproval.approvalId !== expectedId) return;
  $('#approvalModal').classList.add('hidden');
  currentApproval = null;
  setTimeout(() => {
    if (!currentApproval && approvalQueue.length) renderApprovalModal(approvalQueue.shift());
    flushPendingAutoResume();     // 队列前进后再放行该会话的延迟续跑
  }, 0);
}
// 审批已结束时给用户的说明（后端 state：approved/rejected/expired + 结束原因 reason；
// reason 优先——如"审批被中断"（工具超时）比笼统的"其他窗口拒绝"准确）
function approvalEndedText(state, reason) {
  const s = (state || '').toLowerCase();
  if (reason) return '该审批已结束（' + reason + '），弹框已关闭';
  if (s === 'approved') return '该审批已在其他窗口通过，弹框已关闭';
  if (s === 'rejected') return '该审批已在其他窗口拒绝，弹框已关闭';
  if (s === 'expired') return '该审批已超时（按拒绝处理），弹框已关闭';
  return '该审批已结束（可能已在其他窗口处理或已超时），弹框已关闭';
}
async function approveAction(scope) {
  const evt = currentApproval;
  if (!evt) return;
  let modifiedArgs = null;
  try {
    const raw = $('#apArgs').value.trim();
    if (raw && raw !== (evt.args || '{}')) modifiedArgs = JSON.parse(raw);
  } catch (e) {
    $('#apMsg').textContent = '参数不是合法 JSON: ' + e.message;
    $('#apMsg').classList.remove('hidden');
    return;
  }
  try {
    const payload = { subAction: 'approve', approvalId: evt.approvalId, approvalModifiedArguments: modifiedArgs };
    if (scope && scope !== 'once') payload.approvalScope = scope;
    pendingDecision = evt.approvalId;      // 自己正在决策：resolved 推送先到也别提示"其他窗口"
    const r = await apiCommand('approve', payload);
    if (r.success) {
      closeApprovalModal(evt.approvalId);
      toast(r.message, 'ok');
      maybeAutoResume(r, evt);   // 断点态审批（deferred）：决策后自动续跑原会话
    }
    else if (r.approvalEnded) { closeApprovalModal(evt.approvalId); toast(approvalEndedText(), 'err'); }  // 请求已死：自愈关框
    else toast(r.message || r.error, 'err');                                                // 参数校验类失败：留框可改后重试
  } catch (e) { toast(e.message, 'err'); }
  finally { if (pendingDecision === evt.approvalId) pendingDecision = null; }
}
async function rejectAction() {
  const evt = currentApproval;
  if (!evt) return;
  const reason = $('#apReason').value.trim() || '用户拒绝';
  try {
    pendingDecision = evt.approvalId;
    const r = await apiCommand('approve', { subAction: 'reject', approvalId: evt.approvalId, reason });
    if (r.success) {
      closeApprovalModal(evt.approvalId);
      toast(r.message, 'ok');
      maybeAutoResume(r, evt);   // 断点态拒绝后也自动续跑（继续执行 = 让 agent 收下拒绝并收尾）
    }
    else if (r.approvalEnded) { closeApprovalModal(evt.approvalId); toast(approvalEndedText(), 'err'); }
    else toast(r.message || r.error, 'err');
  } catch (e) { toast(e.message, 'err'); }
  finally { if (pendingDecision === evt.approvalId) pendingDecision = null; }
}
// ✕ 关闭按钮：审批弹框是阻塞式决策，纯关界面会让任务悬挂到超时——关闭即按"拒绝"处理
// （结构化拒绝，agent 立即收尾）。先关框再提交：任何响应都不会把框卡住；提交异常时
// 请求可能仍未决，重连重放会再次弹出（自纠正）
async function closeApproval() {
  const evt = currentApproval;
  closeApprovalModal(evt && evt.approvalId);
  if (!evt) return;
  try {
    const r = await apiCommand('approve', { subAction: 'reject', approvalId: evt.approvalId, reason: '用户关闭审批弹框' });
    if (r.success) {
      toast('已按拒绝处理', 'ok');
      maybeAutoResume(r, evt);   // 同 rejectAction：断点态关闭后也自动续跑（继续执行 = 让 agent 收下拒绝并收尾）
    }
    else if (r.approvalEnded) toast(approvalEndedText(), 'err');
    else toast(r.message || r.error, 'err');
  } catch (e) { toast(e.message, 'err'); }
}

// ======================== 自动运行 & 授权面板 ========================
function openPermissions() { $('#permModal').classList.remove('hidden'); loadPermissions(); }
function closePermissions() { $('#permModal').classList.add('hidden'); }

async function toggleAuto() {
  try {
    const on = state.autoRun === true;             // 当前状态 → 点一下取反
    const r = await apiCommand('auto', { sub: on ? 'off' : 'on', sessionId: state.sessionId });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    if (r.success) { state.autoRun = !on; renderAutoBtn(); }
  } catch (e) { toast(e.message, 'err'); }        // 网络异常：如实提示，不静默
}
function renderAutoBtn() {
  const b = $('#autoBtn');
  if (!b) return;
  b.textContent = state.autoRun ? '🔓 自动运行' : '🔒 自动运行';
  b.classList.toggle('on', state.autoRun === true);
}
async function refreshAutoState() {
  const sid = state.sessionId;                 // 请求发出时的会话；期间可能被切走
  if (!sid) return;
  try {
    const r = await apiCommand('auto', { sub: 'status', sessionId: sid });
    if (state.sessionId !== sid) return;       // 已切走：丢弃迟到响应，别覆盖新会话的开关态
    if (r.success && r.data) { state.autoRun = r.data.on === true; renderAutoBtn(); }
  } catch (e) {}
}
async function loadPermissions() {
  const box = $('#permBox');
  try {
    const r = await apiCommand('permissions', { sub: 'list', userId: state.userId });
    if (!r.success) { box.innerHTML = '<div class="muted">加载失败: ' + esc(r.error || '') + '</div>'; return; }
    const list = r.data || [];
    if (!list.length) { box.innerHTML = '<div class="muted">当前无授权</div>'; return; }
    box.innerHTML = '';
    list.forEach(g => {
      const row = document.createElement('div');
      row.style.cssText = 'border-bottom:1px solid #2a2f3d;padding:8px 4px;';
      const scope = g.scope === 'session' ? '本会话' : '本轮';
      const revokeCmd = { sub: 'revoke', userId: state.userId, sessionId: g.sessionId, ruleId: g.ruleId };
      if (g.operation && g.operation !== '*') revokeCmd.operation = g.operation;
      row.innerHTML = '<span class="muted">[' + scope + ']</span> ' + esc(g.display || g.ruleId)
        + ' <span class="muted">session=' + esc(g.sessionId) + '</span> ';
      const btn = document.createElement('button');
      btn.textContent = '撤销';
      // 行内小按钮：本行不在 .btn-row/.row 里（拿不到样式表规则），内联沿用 .sess-ops/table.tbl 的同款深色小按钮
      btn.style.cssText = 'padding:2px 8px;font-size:12px;border-radius:4px;'
        + 'border:1px solid #2a2f3d;background:#232838;color:#d8dde6;cursor:pointer;';
      btn.onclick = async () => {
        const rr = await apiCommand('permissions', revokeCmd);
        toast(rr.message || rr.error, rr.success ? 'ok' : 'err');
        loadPermissions(); refreshAutoState();
      };
      row.appendChild(btn);
      box.appendChild(row);
    });
  } catch (e) { box.innerHTML = '<div class="muted">加载异常: ' + esc(e.message) + '</div>'; }
}
async function clearPermissions() {
  try {
    const r = await apiCommand('permissions', { sub: 'clear', userId: state.userId, sessionId: state.sessionId });
    toast(r.message || r.error, r.success ? 'ok' : 'err');
    loadPermissions(); refreshAutoState();
  } catch (e) { toast(e.message, 'err'); }        // 网络异常：如实提示，不静默
}

// ======================== 命令辅助 ========================
async function apiCommand(action, params) {
  return await apiJson('/api/command', { action, params: params || {} });
}
