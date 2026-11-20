// Injected into the comic page. Finds the chapter images, scrolls to bubbles and highlights them.
// Bubble positions are fractions of an image, so they stay right when lazy images resize the page.
(function () {
  if (window.__mr) return;

  // current: the bubble being highlighted, as fractions of its image, so the highlight can
  // follow it when images above finish loading and push the page down.
  var MR = { imgs: [], anim: 0, marker: null, current: null, follow: 0 };
  window.__mr = MR;

  var LAZY_ATTRS = [
    "data-src",
    "data-lazy-src",
    "data-original",
    "data-url",
    "data-srcset",
  ];

  function isHttp(url) {
    return typeof url === "string" && /^https?:\/\//i.test(url.trim());
  }

  function lazySource(img) {
    for (var i = 0; i < LAZY_ATTRS.length; i++) {
      var value = img.getAttribute(LAZY_ATTRS[i]);
      if (value) {
        var first = value.trim().split(/\s+/)[0];
        try {
          var absolute = new URL(first, document.baseURI).href;
          if (isHttp(absolute)) return absolute;
        } catch (e) {
          // Not a usable URL, try the next attribute.
        }
      }
    }
    return null;
  }

  function sourceOf(img) {
    var lazy = lazySource(img);
    if (lazy) return lazy;
    var current = img.currentSrc || img.src;
    if (isHttp(current)) return current;
    if (
      typeof current === "string" &&
      current.indexOf("data:image/") === 0 &&
      current.length > 5000
    )
      return current;
    return null;
  }

  function looksLikeComicImage(img, pageWidth) {
    var rect = img.getBoundingClientRect();
    if (rect.width < pageWidth * 0.5) return false;
    // A lazy image still showing its 1x1 placeholder is fine; a small loaded image is an icon.
    var tinyAndLoaded =
      img.complete && img.naturalWidth > 0 && img.naturalHeight < 150;
    if (tinyAndLoaded && !lazySource(img)) return false;
    return sourceOf(img) !== null;
  }

  // The chapter's pages share one container. Keeping only the images in the deepest element
  // that holds most of the candidates drops the site logo, banners and "related series" covers.
  function keepChapterContainer(candidates) {
    if (candidates.length < 3) return candidates;
    var counts = new Map();
    candidates.forEach(function (img) {
      for (var node = img.parentElement; node; node = node.parentElement) {
        counts.set(node, (counts.get(node) || 0) + 1);
      }
    });
    var needed = Math.ceil(candidates.length * 0.6);
    var best = null;
    var bestDepth = -1;
    counts.forEach(function (count, node) {
      if (count < needed) return;
      var depth = 0;
      for (var n = node; n; n = n.parentElement) depth++;
      if (depth > bestDepth) {
        best = node;
        bestDepth = depth;
      }
    });
    if (!best) return candidates;
    return candidates.filter(function (img) {
      return best.contains(img);
    });
  }

  MR.collect = function () {
    var pageWidth = document.documentElement.clientWidth;
    var picked = keepChapterContainer(
      Array.prototype.filter.call(document.images, function (img) {
        return looksLikeComicImage(img, pageWidth);
      }),
    );

    picked.sort(function (a, b) {
      var ra = a.getBoundingClientRect();
      var rb = b.getBoundingClientRect();
      return ra.top - rb.top || ra.left - rb.left;
    });

    // Force lazy images to load so the layout settles before reading reaches them.
    picked.forEach(function (img) {
      var lazy = lazySource(img);
      img.loading = "eager";
      if (lazy && img.src !== lazy) img.src = lazy;
    });

    MR.imgs = picked;
    return JSON.stringify({
      images: picked.map(function (img, index) {
        return { index: index, src: sourceOf(img) };
      }),
      start: readingStart(picked),
      next: nextChapter(),
    });
  };

  // The next chapter's address on the same site, so its text can be read before it is opened.
  // Tries a rel="next" link, then a link labelled "Next", then this address with the chapter
  // number one higher. Null when none is found.
  function nextChapter() {
    var here = location.href.split("#")[0];
    function sameSite(href) {
      try {
        var url = new URL(href, document.baseURI);
        if (!/^https?:$/.test(url.protocol) || url.hostname !== location.hostname) return null;
        url.hash = "";
        return url.href === here ? null : url.href;
      } catch (e) {
        return null;
      }
    }
    var rel = document.querySelector('link[rel~="next"][href], a[rel~="next"][href]');
    var found = rel && sameSite(rel.getAttribute("href"));
    if (found) return found;

    var anchors = document.querySelectorAll("a[href]");
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      var label = [a.textContent, a.getAttribute("aria-label"), a.getAttribute("title")]
        .join(" ")
        .replace(/\s+/g, " ")
        .trim()
        .toLowerCase();
      var cls = String(a.getAttribute("class") || "") + " " + (a.id || "");
      if (
        /^(next|next chapter|next episode|next chap|next ch)\b/.test(label) ||
        /(^|[\s_-])next([\s_-]|$)|next[_-]?(chap|chapter|ep|episode|btn|button|link)/i.test(cls)
      ) {
        found = sameSite(a.getAttribute("href"));
        if (found) return found;
      }
    }

    var match = here.match(/^(.*?(?:chapter|chap|ch|episode|ep)[-_.\/]?)(\d+)(.*)$/i);
    if (match) {
      var bumped = match[1] + (parseInt(match[2], 10) + 1);
      for (var j = 0; j < anchors.length; j++) {
        var href = sameSite(anchors[j].getAttribute("href"));
        if (href && href.indexOf(bumped) === 0 && !/^\d/.test(href.slice(bumped.length))) return href;
      }
      return sameSite(bumped + match[3]);
    }
    return null;
  }

  // Where the top of the screen is, as an image index and a fraction down that image,
  // so reading starts from what is on screen instead of the top of the chapter.
  function readingStart(images) {
    var viewTop = window.innerHeight * 0.1;
    for (var i = 0; i < images.length; i++) {
      var rect = images[i].getBoundingClientRect();
      if (rect.bottom > viewTop) {
        var frac = rect.height > 0 ? (viewTop - rect.top) / rect.height : 0;
        return { index: i, frac: Math.max(0, Math.min(1, frac)) };
      }
    }
    // Scrolled past the last image (comments, footer): nothing left to read, rather than
    // going back to the top of the chapter.
    if (images.length > 0) return { index: images.length - 1, frac: 1 };
    return { index: 0, frac: 0 };
  }

  function easeInOut(t) {
    return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  }

  // Glides to the place aim() gives. It is asked again on every frame: images still loading
  // above move the page during the glide, and a target worked out once would land off it.
  function glide(aim) {
    cancelAnimationFrame(MR.anim);
    function target() {
      var maxY = document.documentElement.scrollHeight - window.innerHeight;
      return Math.max(0, Math.min(maxY, aim()));
    }
    var startY = window.scrollY;
    var distance = target() - startY;
    // Long gaps (art-only panels) glide slower so the art can be seen on the way.
    var duration = Math.max(
      350,
      Math.min(3500, (Math.abs(distance) / window.innerHeight) * 1400),
    );
    var startTime = performance.now();

    function step(now) {
      var t = Math.min(1, (now - startTime) / duration);
      window.scrollTo(0, startY + (target() - startY) * easeInOut(t));
      if (t < 1) MR.anim = requestAnimationFrame(step);
    }
    MR.anim = requestAnimationFrame(step);
    return Math.round(duration);
  }

  function placeMarker(left, top, width, height) {
    if (!MR.marker) {
      var marker = document.createElement("div");
      marker.style.cssText = [
        "position:absolute",
        "pointer-events:none",
        "z-index:2147483647",
        "border:3px solid rgba(255,176,32,0.95)",
        "border-radius:10px",
        "background:rgba(255,176,32,0.12)",
        "box-sizing:border-box",
        "transition:all 250ms ease",
      ].join(";");
      document.documentElement.appendChild(marker);
      MR.marker = marker;
    }
    var pad = 6;
    MR.marker.style.left = left - pad + "px";
    MR.marker.style.top = top - pad + "px";
    MR.marker.style.width = width + pad * 2 + "px";
    MR.marker.style.height = height + pad * 2 + "px";
    MR.marker.style.display = "block";
  }

  // Puts the highlight on the current bubble where its image is now. Returns its page top
  // and bottom, or null when there is no bubble or image.
  function placeCurrent() {
    var c = MR.current;
    var img = c && MR.imgs[c.index];
    if (!img) return null;
    var rect = img.getBoundingClientRect();
    var docLeft = window.scrollX + rect.left;
    var docTop = window.scrollY + rect.top;
    var top = docTop + c.y0 * rect.height;
    var bottom = docTop + c.y1 * rect.height;
    placeMarker(docLeft + c.x0 * rect.width, top, (c.x1 - c.x0) * rect.width, bottom - top);
    return { top: top, bottom: bottom };
  }

  // Keeps the highlight on its bubble while the page settles.
  function followCurrent() {
    if (MR.follow) return;
    MR.follow = setInterval(function () {
      if (MR.current && MR.marker && MR.marker.style.display !== "none") placeCurrent();
    }, 300);
  }

  // Page Y of a chapter position: image index plus the fraction down that image.
  function docY(pos) {
    var imgs = MR.imgs || [];
    if (imgs.length === 0) return 0;
    var index = Math.floor(pos);
    var frac = pos - index;
    if (index >= imgs.length) {
      index = imgs.length - 1;
      frac = 1;
    }
    if (index < 0) {
      index = 0;
      frac = 0;
    }
    var rect = imgs[index].getBoundingClientRect();
    return window.scrollY + rect.top + frac * rect.height;
  }

  // Where to scroll so the story is seen, not just the bubble. A panel that fits is shown
  // whole and centred; a taller one shows as much as keeps the bubble on screen.
  // Null when the screen already shows it, so bubbles in one panel do not jolt the page.
  // With force, gives the place even when the screen already shows it (for a glide under way).
  function framePanel(panelTop, panelBottom, bubbleTop, bubbleBottom, force) {
    var vh = window.innerHeight;
    // A little room so a bubble on the panel's edge, and its highlight, are not clipped.
    var pad = 20;
    panelTop = Math.min(panelTop, bubbleTop) - pad;
    panelBottom = Math.max(panelBottom, bubbleBottom) + pad;
    var viewTop = window.scrollY;
    var height = panelBottom - panelTop;
    if (height <= vh) {
      if (!force && panelTop >= viewTop - 1 && panelBottom <= viewTop + vh + 1)
        return null;
      return panelTop - (vh - height) / 2;
    }
    var bubbleInView =
      bubbleTop >= viewTop + vh * 0.08 && bubbleBottom <= viewTop + vh * 0.92;
    var screenInPanel =
      viewTop >= panelTop - 1 && viewTop + vh <= panelBottom + 1;
    if (!force && bubbleInView && screenInPanel) return null;
    return Math.max(
      panelTop,
      Math.min(panelBottom - vh, bubbleTop - vh * 0.28),
    );
  }

  // Highlights the bubble and scrolls to its panel (chapter positions panelFrom..panelTo).
  // Without a panel the bubble goes to the upper third of the screen. Returns the glide time in ms.
  MR.show = function (index, x0, y0, x1, y1, panelFrom, panelTo) {
    if (!MR.imgs[index]) return 0;
    MR.current = { index: index, x0: x0, y0: y0, x1: x1, y1: y1 };
    placeCurrent();
    followCurrent();
    var hasPanel = typeof panelFrom === "number" && typeof panelTo === "number";
    function aim(force) {
      var bubble = placeCurrent();
      if (!bubble) return window.scrollY;
      return hasPanel
        ? framePanel(docY(panelFrom), docY(panelTo), bubble.top, bubble.bottom, force)
        : bubble.top - window.innerHeight * 0.28;
    }
    if (aim(false) === null) return 0;
    return glide(function () {
      return aim(true);
    });
  };

  // Shows a panel with no bubbles. One taller than the screen is shown in parts, top down.
  // Returns {ms, parts}: the glide time and how many parts the panel takes.
  MR.showArt = function (from, to, part) {
    MR.current = null;
    if (MR.marker) MR.marker.style.display = "none";
    var vh = window.innerHeight;
    var step = vh * 0.8;
    var startHeight = docY(to) - docY(from);
    var parts = startHeight <= vh ? 1 : Math.ceil((startHeight - vh) / step) + 1;
    function aim() {
      var top = docY(from);
      var height = docY(to) - top;
      return height <= vh
        ? top - (vh - height) / 2
        : top + Math.min(part * step, height - vh);
    }
    return JSON.stringify({ ms: glide(aim), parts: parts });
  };

  // Where the screen is now, in the same form collect() reports it.
  MR.screenPosition = function () {
    return JSON.stringify(readingStart(MR.imgs));
  };

  // Lets the reader's finger win: stops the glide without hiding the highlight.
  MR.stopGlide = function () {
    cancelAnimationFrame(MR.anim);
  };

  // True when any part of the bubble is inside the screen.
  MR.isOnScreen = function (index, y0, y1) {
    var img = MR.imgs[index];
    if (!img) return false;
    var rect = img.getBoundingClientRect();
    var top = rect.top + y0 * rect.height;
    var bottom = rect.top + y1 * rect.height;
    return bottom > 0 && top < window.innerHeight;
  };

  MR.clear = function () {
    cancelAnimationFrame(MR.anim);
    MR.current = null;
    if (MR.marker) MR.marker.style.display = "none";
  };
})();
