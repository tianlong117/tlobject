/* ===== 移动端增强（仅 mobile.html 加载）=====
   复用 app.js：本文件只做布局增强，不改任何业务逻辑、不重绑业务事件。
   - 会话栏 → 左抽屉（汉堡开合）
   - 低频顶栏控件 → 「更多」底部弹层（DOM 移动，ID/事件处理器原样保留）
   - 设置/调试面板 → 全屏 + 关闭按钮
   - visualViewport 高度 → --app-h（iOS 键盘/地址栏）
   执行顺序：app.js 先注册 DOMContentLoaded（bindEvents 同步接线），本文件注册在后。 */
(function () {
  'use strict';
  if (!document.body || !document.body.classList.contains('mobile')) return;

  function $(s) { return document.querySelector(s); }
  function setAppHeight() {
    var h = (window.visualViewport && window.visualViewport.height) || window.innerHeight;
    document.documentElement.style.setProperty('--app-h', Math.round(h) + 'px');
  }

  function init() {
    var header = document.querySelector('header');
    if (!header) return;

    // 品牌缩写，给顶栏省空间
    var brand = header.querySelector('.brand');
    if (brand) brand.textContent = '🤖';

    // 汉堡（会话抽屉）与「更多」
    var sessBtn = document.createElement('button');
    sessBtn.id = 'mobileSessBtn';
    sessBtn.type = 'button';
    sessBtn.className = 'mobile-ico-btn';
    sessBtn.textContent = '☰';
    sessBtn.title = '会话列表';
    sessBtn.setAttribute('aria-label', '打开会话列表');
    sessBtn.setAttribute('aria-expanded', 'false');
    header.insertBefore(sessBtn, header.firstChild);

    var moreBtn = document.createElement('button');
    moreBtn.id = 'mobileMoreBtn';
    moreBtn.type = 'button';
    moreBtn.className = 'mobile-ico-btn';
    moreBtn.textContent = '⋯';
    moreBtn.title = '更多功能';
    moreBtn.setAttribute('aria-label', '更多功能');
    moreBtn.setAttribute('aria-expanded', 'false');
    header.appendChild(moreBtn);

    // 「更多」底部弹层：把低频控件整节点搬进来（不克隆，ID 与处理器都保留）
    var more = document.createElement('div');
    more.id = 'mobileMore';
    more.className = 'hidden';
    more.setAttribute('aria-hidden', 'true');
    var title = document.createElement('div');
    title.className = 'more-title';
    title.textContent = '更多功能';
    more.appendChild(title);
    ['tasksBtn', 'bgTasksBtn', 'autoBtn', 'permBtn', 'inboxBtn',
     'manageBtn', 'debugBtn', 'settingsBtn'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) more.appendChild(el);
    });
    var sess = header.querySelector('.sess');
    if (sess) more.appendChild(sess);
    var toggle = header.querySelector('.toggle');
    if (toggle) more.appendChild(toggle);

    var desk = document.createElement('button');
    desk.type = 'button';
    desk.id = 'mobileDesktopBtn';
    desk.textContent = '🖥 电脑版';
    desk.addEventListener('click', function () {
      try { localStorage.setItem('tlweb_view', 'desktop'); } catch (e) { /* 忽略 */ }
      location.href = '/webui/chat.html' + location.search + location.hash;
    });
    more.appendChild(desk);

    // 退出放最后，避免误触
    var logout = document.getElementById('logoutBtn');
    if (logout) more.appendChild(logout);
    document.body.appendChild(more);

    // 登录页也给一个「电脑版」入口（还没登录时进不了「更多」）；放进登录框随表单滚动，不悬浮遮挡
    var loginBox = document.querySelector('#loginView .login-box');
    if (loginBox) {
      var deskLink = document.createElement('a');
      deskLink.className = 'mobile-desktop-link';
      deskLink.href = '/webui/chat.html' + location.search + location.hash;
      deskLink.textContent = '电脑版';
      deskLink.addEventListener('click', function () {
        try { localStorage.setItem('tlweb_view', 'desktop'); } catch (e) { /* 忽略 */ }
      });
      loginBox.appendChild(deskLink);
    }

    // 抽屉遮罩（抽屉 70 / 遮罩 65 / sess-menu 80 / 面板 90 / 更多 95 / modal 100 / 审批 110）
    var backdrop = document.createElement('div');
    backdrop.id = 'drawerBackdrop';
    backdrop.className = 'hidden';
    document.body.appendChild(backdrop);

    function setOpen(which) {
      document.body.classList.toggle('drawer-open', which === 'sess');
      document.body.classList.toggle('more-open', which === 'more');
      more.classList.toggle('hidden', which !== 'more');
      backdrop.classList.toggle('hidden', !which);
      more.setAttribute('aria-hidden', which === 'more' ? 'false' : 'true');
      sessBtn.setAttribute('aria-expanded', which === 'sess' ? 'true' : 'false');
      moreBtn.setAttribute('aria-expanded', which === 'more' ? 'true' : 'false');
    }

    sessBtn.addEventListener('click', function () {
      setOpen(document.body.classList.contains('drawer-open') ? null : 'sess');
    });
    moreBtn.addEventListener('click', function () {
      setOpen(document.body.classList.contains('more-open') ? null : 'more');
    });
    backdrop.addEventListener('click', function () { setOpen(null); });

    // 抽屉里「切换/改名」与新建/刷新/断点恢复后自动收起（捕获阶段，早于 app.js 的重渲染）
    document.addEventListener('click', function (e) {
      if (!document.body.classList.contains('drawer-open')) return;
      if (e.target.closest('#sessionsBox .sess-ops button:not(.more), #sessionPane .sess-tools button')) {
        setTimeout(function () { setOpen(null); }, 60);
      }
    }, true);

    // 「更多」里点任何按钮后收起（输入框/复选框不收起）
    more.addEventListener('click', function (e) {
      if (e.target.closest('button')) setTimeout(function () { setOpen(null); }, 0);
    });
    // 打开设置/调试面板前先收起抽屉与「更多」
    ['settingsBtn', 'debugBtn'].forEach(function (id) {
      var b = document.getElementById(id);
      if (b) b.addEventListener('click', function () { setOpen(null); });
    });

    // 设置/调试面板：全屏浮层 + 浮动关闭按钮
    var panel = document.getElementById('panelPane');
    if (panel) {
      var close = document.createElement('button');
      close.id = 'mobilePanelClose';
      close.type = 'button';
      close.className = 'mobile-panel-close';
      close.textContent = '✕';
      close.title = '关闭面板';
      close.setAttribute('aria-label', '关闭面板');
      close.addEventListener('click', function () {
        if (typeof closePanel === 'function') closePanel();
      });
      panel.appendChild(close);
    }

    // 回车键提示
    var input = document.getElementById('chatInput');
    if (input) input.setAttribute('enterkeyhint', 'send');

    // 极老浏览器不支持流式响应体：关掉流式开关（派发 change 让 app.js 的 state.streamEnabled 同步）
    try {
      if (!('body' in Response.prototype)) {
        var st = document.getElementById('streamToggle');
        if (st) { st.checked = false; st.dispatchEvent(new Event('change')); }
      }
    } catch (e) { /* 忽略 */ }

    // 视口高度（软键盘/地址栏）
    setAppHeight();
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', setAppHeight);
      window.visualViewport.addEventListener('scroll', setAppHeight);
    }
    window.addEventListener('resize', setAppHeight);
    window.addEventListener('orientationchange', function () { setTimeout(setAppHeight, 150); });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
