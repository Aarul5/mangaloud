// Removes the ad layers that host blocking cannot stop: invisible full-screen links that turn
// any tap into a pop-under, and blur screens that hide the comic behind a "bonus" prompt.
(function () {
  if (window.__mrGuard) return;
  window.__mrGuard = true;

  var MAX_Z = 2147483000;

  function isTrap(el) {
    var style = getComputedStyle(el);
    if (style.position !== "fixed") return false;
    var rect = el.getBoundingClientRect();
    var coversScreen =
      rect.width >= innerWidth * 0.6 && rect.height >= innerHeight * 0.6;
    var invisible = parseFloat(style.opacity) < 0.2;
    var blurs =
      (style.backdropFilter || style.webkitBackdropFilter || "").indexOf(
        "blur",
      ) >= 0;
    if (coversScreen && (invisible || blurs)) return true;
    return (parseInt(style.zIndex, 10) || 0) >= MAX_Z;
  }

  function unblur(el) {
    if (getComputedStyle(el).filter.indexOf("blur") >= 0)
      el.style.setProperty("filter", "none", "important");
  }

  function sweep() {
    if (!document.body) return;
    unblur(document.documentElement);
    unblur(document.body);
    var candidates = document.querySelectorAll("body > *, body > * > *");
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (isTrap(el)) el.remove();
      else unblur(el);
    }
  }

  var queued = false;
  function sweepSoon() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      sweep();
    });
  }

  new MutationObserver(sweepSoon).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
  setInterval(sweep, 1500);
  sweep();
})();
