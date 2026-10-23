/* Notification polyfill: WebView has no Notification API, so route it to native Android notifications. */
(function () {
  if (window.__androidShimInstalled) return;
  window.__androidShimInstalled = true;
  var waiting = [];
  function N(title, opts) {
    try { AndroidBridge.notify(String(title), opts && opts.body ? String(opts.body) : ''); } catch (e) {}
  }
  N.permission = 'default';
  N.requestPermission = function (cb) {
    return new Promise(function (resolve) {
      waiting.push(function (p) { if (typeof cb === 'function') cb(p); resolve(p); });
      try { AndroidBridge.requestNotificationPermission(); }
      catch (e) { window.__onNotifPermission('denied'); }
    });
  };
  window.__onNotifPermission = function (p) {
    N.permission = p;
    var q = waiting; waiting = [];
    q.forEach(function (f) { f(p); });
  };
  try { Object.defineProperty(window, 'Notification', { value: N, configurable: true, writable: true }); }
  catch (e) { window.Notification = N; }
})();
