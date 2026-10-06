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
  drawer: null           // 任务详情抽屉当前展示的任务 {tid, sid}；null=未打开
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
      kicked: false          // 被其他设备接管 → 本会话禁输入（切回来也不解锁；点「继续」接管后清除）
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
  // 被接管的会话保持禁输入（旧版无条件启用 → 切回被接管会话仍可输入）；点「继续」接管后由 claimSession 清除
  $('#chatInput').disabled = !!v.kicked;
  $('#sendBtn').disabled = !!v.kicked;
  setBusy(v.busy);
  renderUploadBar();                // 附件条随会话切换（各会话草稿区附件独立）
  renderSessionList();   // P11 已实现该函数（函数声明，hoisting 保证可用），原 typeof 守卫恒真故直接调用
  reportCurrentSession();
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
      renderUploadBar();     // sessionId 已清 → 附件条隐藏（各会话 uploads 随下方视图表重置）
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
  switchSessionView(state.sessionId);   // 挂载初始会话视图（登录/刷新）
  appendSysMsg('已登录：' + state.userId + '（管理命令在右侧面板）');
  showView('chat');
  openEvents();
  refreshInboxBadge();      // 登录即拉未读数（离线期间的任务结果会在消息箱提示）
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
      appendSysMsg('💡 自动接续失败：' + (res.error || '未知原因') + '。已开始新会话 ' + state.sessionId + '（可在左侧会话栏手动继续）');
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
  if (view.kicked) { toast('该会话已被其他设备接管，请开新会话或点「继续」接管', 'err'); return; }
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
    setBusy(false, sid);
  }
}
async function streamChat(msg, opts) {
  opts = opts || {};
  const sid = state.sessionId;   // 发送时总是当前会话（opts.sessionId 是 resume 目标，调用前已切过来）
  const holder = startAssistantMsg();
  setBusy(true, sid);
  const ctrl = new AbortController();
  viewOf(sid).abort = ctrl;
  try {
    const resp = await fetch('/api/chatStream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: msg,
        sessionId: opts.sessionId || sid,
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
    // 空闲检测：长时间无 chunk（LLM 思考 / 工具调用循环）时显示"⏳ 思考中"
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
  if (sid !== state.sessionId) return;          // 后台会话只记状态，不动当前 UI
  $('#sendBtn').classList.toggle('hidden', b);
  $('#stopBtn').classList.toggle('hidden', !b);
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
  $('#tabBar').addEventListener('click', e => {
    const btn = e.target.closest('button[data-tab]');
    if (!btn) return;
    document.querySelectorAll('#tabBar button').forEach(b => b.classList.toggle('active', b === btn));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === btn.dataset.tab));
  });
  $('#apApproveBtn').onclick = approveAction;
  $('#apRejectBtn').onclick = rejectAction;
  // 顶栏功能图标：定时任务 / 消息箱（应用级功能 → 浮层；右侧面板留调试/测试）
  $('#tasksBtn').onclick = openTasks;
  $('#inboxBtn').onclick = openInbox;
  // 参数弹框：点遮罩关闭
  $('#paramModal').addEventListener('click', e => {
    if (e.target === $('#paramModal')) $('#paramModal').classList.add('hidden');
  });
  // 任务/消息箱浮层：点遮罩关闭
  $('#tasksModal').addEventListener('click', e => { if (e.target === $('#tasksModal')) closeTasks(); });
  $('#inboxModal').addEventListener('click', e => { if (e.target === $('#inboxModal')) closeInbox(); });
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

/** 消息箱面板：列表 + 标记已读 + 清角标 */
async function loadInbox() {
  const box = $('#inboxBox');
  try {
    const r = await apiCommand('inboxList', {});
    if (!r.success) { box.innerHTML = '<div class="muted">加载失败: ' + esc(r.error || '') + '</div>'; return; }
    const d = r.data || {};
    const list = d.list || [];
    updateInboxBadge(d.unread || 0);
    if (!list.length) { box.innerHTML = '<div class="muted">消息箱为空</div>'; return; }
    const wrap = document.createElement('div');
    list.forEach(m => {
      const row = document.createElement('div');
      row.style.cssText = 'border-bottom:1px solid #2a2f3d;padding:8px 4px;' + (m.is_read ? 'opacity:.55;' : '');
      const ts = m.created_at ? fmtTs(Number(m.created_at)) : '';
      const src = m.source === 'task' ? '⏰ ' + (m.task_id || '定时任务') : (m.source || '');
      row.innerHTML = '<div style="font-size:12px;color:#93c5fd">' + esc(src) + ' · ' + esc(ts) + '</div>'
                    + '<div style="white-space:pre-wrap;word-break:break-all">' + esc(String(m.text || '')) + '</div>';
      wrap.appendChild(row);
    });
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

/** 定时任务面板：列表 + 行内 暂停/恢复/删除（与控制台 /tasks 同语义） */
async function loadTasks() {
  const box = $('#tasksBox');
  try {
    const r = await apiCommand('tasks', { sub: 'list' });
    if (!r.success) { box.innerHTML = '<div class="muted">' + esc(r.error || r.message || '加载失败') + '</div>'; return; }
    const list = (r.data || []);
    if (!list.length) { box.innerHTML = '<div class="muted">' + esc(r.message || '当前没有定时任务') + '</div>'; return; }
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
    // 第一行：状态图标 + 会话ID（窄栏省略号截断，title 看全称）
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
    name.textContent = sid;
    name.title = sid;
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
    // 第三行：行内操作（与旧表格同四个 handler，行为不变）
    const ops = document.createElement('div');
    ops.className = 'sess-ops';
    const mkBtn = (label, cls, fn) => {
      const b = document.createElement('button');
      b.textContent = label;
      if (cls) b.className = cls;
      b.onclick = () => fn(sid);
      ops.appendChild(b);
    };
    mkBtn('继续', null, continueSession);
    mkBtn('切换', null, switchSession);
    mkBtn('清除上下文', null, clearSession);
    mkBtn('删除', 'del', deleteSessionBtn);
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
/** 继续历史会话：恢复 sessionId + 加载历史到聊天窗。silent=true 时（登录自动接续）不弹 toast。返回 {ok, error} */
async function continueSession(sid, silent) {
  try {
    const r = await apiCommand('continue', { userId: state.userId, sessionId: sid });
    if (r.success && r.data) {
      const target = r.data.sessionId || sid;
      switchSessionView(target);         // 切到目标会话视图（恢复输入/忙碌态由它统一处理）
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
      // 非 silent（面板"继续"按钮=明确接管意图）→ 直接 force 接管（踢对方下线），不弹窗
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
 * - force=true（面板"继续"=明确接管意图）→ 直接接管（踢对方下线）
 * - force=false（登录自动接续 silent）→ 被占用时仅提示，不接管、不切新会话
 */
async function claimSession(sid, force, silent) {
  if (!state.loginId) return;
  const r = await apiCommand('claimSession', { sessionId: sid, loginId: state.loginId, force: force || false });
  if (!r.success || !r.data) return;
  if (r.data.occupied) {
    appendSysMsg('⚠ 该会话正被另一登录使用（历史可查看，发消息将被拒绝；在左侧会话栏点该会话『继续』可直接接管）');
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
    const r = await apiCommand('session', { sessionId: sid });
    if (!r.success) { toast(r.error || r.message, 'err'); return; }
    switchSessionView(sid);   // 切视图（localStorage/#sessionId/占用上报统一在 switchSessionView 里做）
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
    if (!r.success) { toast(r.error || r.message, 'err'); return; }
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
let currentApproval = null;
function showApprovalModal(evt) {
  currentApproval = evt;
  const stale = $('#approvalModal .ap-source');
  if (stale) stale.remove();                        // 上一次审批可能带来源行 → 先清，防叠加
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
