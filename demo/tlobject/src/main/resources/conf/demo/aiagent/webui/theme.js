/* ===== 主题切换（零依赖，head 同步加载）=====
   1) 在样式表渲染前按 localStorage 设置 html[data-theme]，避免首帧闪烁；
   2) DOMContentLoaded 后绑定所有 .theme-toggle 按钮（顶栏 + 登录页）。
   偏好键 tlweb_theme 与登录/登出清理（tlweb_session/userid/loginid）互不相干。 */
(function () {
  'use strict';
  var KEY = 'tlweb_theme';
  var THEME_COLOR = { dark: '#1b1e27', light: '#ffffff' };

  function read() {
    try { return localStorage.getItem(KEY) === 'light' ? 'light' : 'dark'; }
    catch (e) { return 'dark'; }
  }

  /** 应用主题：html 属性 + 浏览器 toolbar 颜色 + 所有切换按钮文案 */
  function apply(theme) {
    var light = theme === 'light';
    document.documentElement.setAttribute('data-theme', light ? 'light' : 'dark');
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', light ? THEME_COLOR.light : THEME_COLOR.dark);
    var btns = document.querySelectorAll('.theme-toggle');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      b.textContent = light ? '🌙' : '☀';
      b.title = light ? '切换到深色主题' : '切换到浅色主题';
      b.setAttribute('aria-label', b.title);
      b.setAttribute('aria-pressed', light ? 'true' : 'false');
    }
  }

  function toggle() {
    var next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    try { localStorage.setItem(KEY, next); } catch (e) { /* 隐私模式等：仅本次生效 */ }
    apply(next);
    try { document.dispatchEvent(new CustomEvent('tlweb:themechange', { detail: { theme: next } })); }
    catch (e) { /* 旧浏览器无 CustomEvent 构造器时忽略 */ }
  }

  apply(read());   // head 中执行：先于样式表首帧渲染

  document.addEventListener('DOMContentLoaded', function () {
    var btns = document.querySelectorAll('.theme-toggle');
    for (var i = 0; i < btns.length; i++) btns[i].addEventListener('click', toggle);
    apply(read());  // 同步按钮文案（head 阶段按钮还不存在）
  });
})();
