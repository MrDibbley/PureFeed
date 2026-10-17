/*
 * FaceBlockMobile feed filter (assets/filter.js)
 * =============================================
 *
 * Injected into every m.facebook.com page by FilterInjector.kt. It hides feed
 * posts (and a few page elements) that match the rules the user has enabled.
 *
 * HOW IT FITS TOGETHER
 *
 *   Kotlin ──configure({ rules, settings })──▶ this file
 *     rules    = the contents of rules.json (WHAT to look for: selectors, label text)
 *     settings = the user's choices (WHICH rules are on, keywords, muted names, debug)
 *
 *   this file ──FaceBlockBridge.onCounts(json)──▶ Kotlin   (hidden-post counter)
 *   this file ──FaceBlockBridge.onDomChunk(...)──▶ Kotlin  ("Dump DOM" debug action)
 *
 * WHERE TO EDIT WHEN FACEBOOK CHANGES THINGS
 *
 *   1. A label or selector changed (most common): edit rules.json, not this file.
 *      Push it to the remote rules URL and the app picks it up without a rebuild.
 *   2. A new KIND of signal is needed (e.g. "a unit containing an element with
 *      attribute X"): add a matcher type in compileMatchers() below and use it
 *      from rules.json.
 *   3. The overall page structure changed (feed units are no longer
 *      [data-tracking-duration-id] children of a vscroller): update "units" in
 *      rules.json; if that's not enough, look at findUnit()/allUnits().
 *
 * HOW HIDING WORKS
 *
 *   A matched element gets data-fbm-match="<ruleId>" and data-fbm-label="<label>".
 *   One injected stylesheet does the rest:
 *     normal mode → [data-fbm-match] { display: none }
 *     debug mode  → coloured outline plus a corner tag naming the rule
 *   Facebook rewrites inline styles when it re-renders, which would wipe out an
 *   inline display:none. A stylesheet rule keyed on our own attribute survives that.
 *
 * Facebook's mobile markup is documented in docs/selectors.md.
 */
(function () {
  'use strict';

  // The feed only lives in the top frame; skip ad/tracking iframes.
  if (window.top !== window) return;
  // Kotlin may inject this file more than once per page (document-start script plus
  // a re-injection on SPA navigation). Only the first copy installs itself.
  if (window.__fbm) return;

  var VERSION = 1;
  var ATTR_MATCH = 'data-fbm-match';   // id of the rule that matched, e.g. "sponsored"
  var ATTR_LABEL = 'data-fbm-label';   // human label shown in debug mode
  var ATTR_DEBUG = 'data-fbm-debug';   // set on <html> while debug mode is on
  var BRIDGE = 'FaceBlockBridge';      // name of the @JavascriptInterface (FilterBridge.kt)

  var configJson = null;   // last configure() argument, to skip identical re-configures
  var compiled = null;     // config turned into ready-to-run tests; see compile()
  var observer = null;
  var fixedScanTimer = 0;
  var countTimer = 0;
  var lastCountsJson = '';

  // -------------------------------------------------------------------------
  // Text helpers
  // -------------------------------------------------------------------------

  /**
   * Normalises visible text for comparison: strips Facebook's icon-font glyphs
   * (Private Use Area characters, see rules.json "text.strip"), collapses
   * whitespace and lower-cases. "Ad" followed by two icon glyphs → "ad".
   */
  function clean(s) {
    return s.replace(compiled.strip, '').replace(/\s+/g, ' ').trim().toLowerCase();
  }

  function escapeRegExp(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  // -------------------------------------------------------------------------
  // Compiling the config into tests
  // -------------------------------------------------------------------------

  /**
   * Turns a rules.json "match" object into a test function over unit facts.
   * A rule matches if ANY of its matchers match.
   *
   * Matcher types (all case-insensitive, after clean()):
   *   headerText:    ["Ad", …]  one of the unit's first few TextAreas, or one PART of
   *                             one, equals one of these. Parts are the pieces between
   *                             icon glyphs: "Sponsored<glyph>Paid for by X" has the
   *                             parts "sponsored" and "paid for by x".
   *   headerPattern: "regex"    one of those header texts or parts matches this regex
   *   anyText:       ["…"]      any TextArea in the unit equals one of these
   *   selector:      "css"      the unit contains an element matching this selector
   */
  function compileMatchers(match) {
    var tests = [];
    if (match.headerText) {
      var header = match.headerText.map(function (t) { return t.toLowerCase(); });
      tests.push(function (f) { return f.headerParts.some(function (t) { return header.indexOf(t) >= 0; }); });
    }
    if (match.headerPattern) {
      var re = new RegExp(match.headerPattern, 'i');
      tests.push(function (f) { return f.headerParts.some(function (t) { return re.test(t); }); });
    }
    if (match.anyText) {
      var any = match.anyText.map(function (t) { return t.toLowerCase(); });
      tests.push(function (f) { return f.allTexts().some(function (t) { return any.indexOf(t) >= 0; }); });
    }
    if (match.selector) {
      var sel = match.selector;
      tests.push(function (f) { return f.unit.querySelector(sel) !== null; });
    }
    return function (facts) {
      for (var i = 0; i < tests.length; i++) if (tests[i](facts)) return true;
      return false;
    };
  }

  /**
   * Keyword filter: whole-word / whole-phrase, case-insensitive, anywhere in the
   * unit's visible text. "cat" matches "Cat videos" but not "category".
   * Note: Facebook truncates long posts ("… See more"); words past the cut are
   * not in the DOM and can't be matched.
   */
  function compileKeywords(keywords) {
    var parts = keywords
      .map(function (k) { return k.trim().toLowerCase(); })
      .filter(function (k) { return k.length > 0; })
      .map(escapeRegExp);
    if (!parts.length) return null;
    // Unicode-aware word boundaries: not preceded/followed by a letter or digit.
    var re = new RegExp('(?<![\\p{L}\\p{N}])(?:' + parts.join('|') + ')(?![\\p{L}\\p{N}])', 'iu');
    return function (f) { return re.test(f.fullText()); };
  }

  /** Mute by name: the author/page name is one of the first header texts. */
  function compileMuted(names, textsToCheck) {
    var list = names
      .map(function (n) { return n.trim().toLowerCase(); })
      .filter(function (n) { return n.length > 0; });
    if (!list.length) return null;
    return function (f) {
      return f.header.slice(0, textsToCheck).some(function (t) { return list.indexOf(t) >= 0; });
    };
  }

  /**
   * Builds everything the scanner needs from { rules, settings }.
   * Rules the user hasn't enabled are left out entirely.
   */
  function compile(cfg) {
    var rules = cfg.rules;
    var settings = cfg.settings;
    var enabled = settings.enabled || {};
    var c = {
      strip: new RegExp(rules.text.strip, 'gu'),
      unitSelector: rules.units.selector,
      parentSelector: rules.units.parentSelector,
      textSelector: rules.text.selector,
      headerLimit: rules.text.headerTextLimit || 5,
      unitRules: [],    // tested against each feed unit, in order; first match wins
      fixedRules: [],   // page-level: hide the position:fixed container around some text
      colors: {},       // rule id → colour, for debug outlines
      debug: !!settings.debug,
      customCss: settings.customCss || ''
    };

    Object.keys(rules.rules).forEach(function (id) {
      var rule = rules.rules[id];
      c.colors[id] = rule.color;
      if (!enabled[id]) return;
      if (rule.kind === 'fixed') {
        c.fixedRules.push({
          id: id,
          label: rule.label,
          texts: (rule.match.anyText || []).map(function (t) { return t.toLowerCase(); }),
          maxDepth: rule.maxAncestorDepth || 10
        });
      } else {
        c.unitRules.push({ id: id, label: rule.label, test: compileMatchers(rule.match) });
      }
    });

    var user = rules.userRules || {};
    if (user.muted) {
      c.colors.muted = user.muted.color;
      var mutedTest = enabled.muted && compileMuted(settings.muted || [], user.muted.headerTextsToCheck || 2);
      if (mutedTest) c.unitRules.unshift({ id: 'muted', label: user.muted.label, test: mutedTest });
    }
    if (user.keywords) {
      c.colors.keywords = user.keywords.color;
      var keywordTest = enabled.keywords && compileKeywords(settings.keywords || []);
      if (keywordTest) c.unitRules.push({ id: 'keywords', label: user.keywords.label, test: keywordTest });
    }
    return c;
  }

  // -------------------------------------------------------------------------
  // Styles
  // -------------------------------------------------------------------------

  function styleElement(id) {
    var el = document.getElementById(id);
    if (!el) {
      el = document.createElement('style');
      el.id = id;
    }
    // At document start <head> may not exist yet; <html> always does. Re-attach if
    // Facebook's navigation threw our element away.
    if (!el.isConnected) (document.head || document.documentElement).appendChild(el);
    return el;
  }

  function applyStyles() {
    var css = [
      // Normal mode: matched things are gone.
      'html:not([' + ATTR_DEBUG + ']) [' + ATTR_MATCH + '] { display: none !important; }',
      // Debug mode: outline plus a tag in the top-right corner naming the rule.
      'html[' + ATTR_DEBUG + '] [' + ATTR_MATCH + '] { outline: 3px solid #888 !important; outline-offset: -3px; }',
      'html[' + ATTR_DEBUG + '] [' + ATTR_MATCH + ']::before {' +
        ' content: attr(' + ATTR_LABEL + '); position: absolute; top: 0; right: 0; z-index: 2147483647;' +
        ' background: #888; color: #fff; font: 600 11px/18px sans-serif; padding: 0 6px;' +
        ' border-bottom-left-radius: 6px; pointer-events: none; }'
    ];
    Object.keys(compiled.colors).forEach(function (id) {
      var color = compiled.colors[id];
      var sel = 'html[' + ATTR_DEBUG + '] [' + ATTR_MATCH + '="' + id + '"]';
      css.push(sel + ' { outline-color: ' + color + ' !important; }');
      css.push(sel + '::before { background: ' + color + '; }');
    });
    styleElement('fbm-style').textContent = css.join('\n');
    styleElement('fbm-custom').textContent = compiled.customCss;

    if (compiled.debug) document.documentElement.setAttribute(ATTR_DEBUG, '');
    else document.documentElement.removeAttribute(ATTR_DEBUG);
  }

  // -------------------------------------------------------------------------
  // Finding and testing feed units
  // -------------------------------------------------------------------------

  /** True if el is a feed unit: matches the unit selector AND sits directly in a vscroller. */
  function isUnit(el) {
    return el.matches(compiled.unitSelector) &&
      el.parentElement !== null && el.parentElement.matches(compiled.parentSelector);
  }

  /** The feed unit containing el (or el itself), or null. */
  function findUnit(el) {
    var cur = el.closest(compiled.unitSelector);
    while (cur && !isUnit(cur)) cur = cur.parentElement && cur.parentElement.closest(compiled.unitSelector);
    return cur;
  }

  function allUnits(root) {
    return Array.prototype.filter.call(
      root.querySelectorAll(compiled.parentSelector + ' > ' + compiled.unitSelector), isUnit);
  }

  /**
   * Facts about a unit that rules test against. The header is computed eagerly
   * (every rule needs it); the full text is computed only if a rule asks.
   *
   *   header      the first few TextAreas, cleaned: ["reform uk", "sponsoredpaid for by reform uk", …]
   *   headerParts header plus each TextArea split at its icon glyphs:
   *               […, "sponsored", "paid for by reform uk", …]
   *               Facebook separates label pieces with glyphs, and clean() deleting
   *               them would glue "Sponsored" onto the next piece.
   */
  function unitFacts(unit) {
    var textEls = unit.querySelectorAll(compiled.textSelector);
    var header = [];
    var headerParts = [];
    for (var i = 0; i < textEls.length && header.length < compiled.headerLimit; i++) {
      var raw = textEls[i].textContent;
      var t = clean(raw);
      if (!t) continue;   // icon-only TextAreas clean to "" and don't use up a slot
      header.push(t);
      headerParts.push(t);
      var parts = raw.split(compiled.strip);
      if (parts.length > 1) {
        parts.forEach(function (p) {
          p = clean(p);
          if (p && p !== t) headerParts.push(p);
        });
      }
    }
    var all = null, full = null;
    return {
      unit: unit,
      header: header,
      headerParts: headerParts,
      allTexts: function () {
        if (!all) all = Array.prototype.map.call(textEls, function (e) { return clean(e.textContent); });
        return all;
      },
      fullText: function () {
        if (full === null) full = clean(unit.textContent);
        return full;
      }
    };
  }

  function mark(el, rule) {
    el.setAttribute(ATTR_MATCH, rule.id);
    el.setAttribute(ATTR_LABEL, rule.label);
  }

  /**
   * Tests one unit against the enabled rules. Once matched, a unit stays hidden
   * until the config changes. Units that don't match yet are re-tested whenever
   * their content changes, because Facebook often inserts an empty shell first
   * and fills in the header a moment later.
   */
  function testUnit(unit) {
    if (unit.hasAttribute(ATTR_MATCH)) return;
    var facts = unitFacts(unit);
    if (!facts.header.length) return;   // not filled in yet; the observer will bring us back
    for (var i = 0; i < compiled.unitRules.length; i++) {
      var rule = compiled.unitRules[i];
      if (rule.test(facts)) {
        mark(unit, rule);
        return;
      }
    }
  }

  // -------------------------------------------------------------------------
  // Page-level ("fixed") rules, e.g. the "Open app" bar
  // -------------------------------------------------------------------------

  /**
   * For each fixed rule: find TextAreas whose text is one of the rule's strings,
   * walk up to the nearest position:fixed ancestor, and hide that. Uses
   * getComputedStyle, so it runs debounced rather than on every mutation.
   */
  function scanFixed() {
    if (!compiled.fixedRules.length) return;
    var textEls = document.querySelectorAll(compiled.textSelector);
    compiled.fixedRules.forEach(function (rule) {
      for (var i = 0; i < textEls.length; i++) {
        var t = clean(textEls[i].textContent);
        if (rule.texts.indexOf(t) < 0) continue;
        var el = textEls[i];
        for (var d = 0; el && el !== document.body && d < rule.maxDepth; d++, el = el.parentElement) {
          if (getComputedStyle(el).position === 'fixed') {
            if (!el.hasAttribute(ATTR_MATCH)) mark(el, rule);
            break;
          }
        }
      }
    });
    scheduleCounts();
  }

  function scheduleFixedScan() {
    clearTimeout(fixedScanTimer);
    fixedScanTimer = setTimeout(scanFixed, 250);
  }

  // -------------------------------------------------------------------------
  // Counting (hidden-post counter in the app)
  // -------------------------------------------------------------------------

  /** Counts what is currently marked on the page, per rule, and tells Kotlin if it changed. */
  function reportCounts() {
    var counts = {};
    document.querySelectorAll('[' + ATTR_MATCH + ']').forEach(function (el) {
      var id = el.getAttribute(ATTR_MATCH);
      counts[id] = (counts[id] || 0) + 1;
    });
    var json = JSON.stringify(counts);
    if (json === lastCountsJson) return;
    lastCountsJson = json;
    var bridge = window[BRIDGE];
    if (bridge && bridge.onCounts) bridge.onCounts(json);
  }

  function scheduleCounts() {
    clearTimeout(countTimer);
    countTimer = setTimeout(reportCounts, 400);
  }

  // -------------------------------------------------------------------------
  // Scanning and observing
  // -------------------------------------------------------------------------

  /** Clears every mark and re-tests the whole page (used when the config changes). */
  function rescanAll(clearMarks) {
    if (clearMarks) {
      document.querySelectorAll('[' + ATTR_MATCH + ']').forEach(function (el) {
        el.removeAttribute(ATTR_MATCH);
        el.removeAttribute(ATTR_LABEL);
      });
    }
    allUnits(document).forEach(testUnit);
    scheduleFixedScan();
    scheduleCounts();
  }

  /**
   * MutationObserver callbacks run before the browser paints, so units are
   * hidden in the same frame they appear and ads don't flash on screen.
   * Each batch is reduced to the set of affected units, each tested once.
   */
  function onMutations(records) {
    var touched = new Set();
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      var target = rec.target.nodeType === 1 ? rec.target : rec.target.parentElement;
      if (!target) continue;
      var unit = findUnit(target);
      if (unit) touched.add(unit);
      for (var j = 0; j < rec.addedNodes.length; j++) {
        var node = rec.addedNodes[j];
        if (node.nodeType !== 1) continue;
        if (isUnit(node)) touched.add(node);
        else if (node.querySelector && node.querySelector(compiled.unitSelector)) {
          allUnits(node).forEach(function (u) { touched.add(u); });
        }
      }
    }
    touched.forEach(testUnit);
    scheduleFixedScan();
    scheduleCounts();
  }

  function startObserving() {
    if (observer) return;
    observer = new MutationObserver(onMutations);
    // Observe the whole document: at document start <body> doesn't exist yet, and
    // SPA navigations replace large parts of the tree.
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  // -------------------------------------------------------------------------
  // Debug: DOM dump
  // -------------------------------------------------------------------------

  /**
   * Sends a sanitised copy of the current page HTML to Kotlin in chunks (bridge
   * calls have size limits). Scripts, iframes and form values are removed, and
   * long attributes are cut, so the file doesn't carry session tokens. It still
   * contains whatever posts are on screen, so treat it as private.
   */
  function dumpDom(requestId) {
    var bridge = window[BRIDGE];
    if (!bridge || !bridge.onDomChunk) return;
    var clone = document.documentElement.cloneNode(true);
    clone.querySelectorAll('script, noscript, iframe, link[rel="preload"]').forEach(function (e) { e.remove(); });
    clone.querySelectorAll('input, textarea').forEach(function (e) {
      e.removeAttribute('value');
      e.textContent = '';
    });
    clone.querySelectorAll('*').forEach(function (e) {
      Array.prototype.slice.call(e.attributes).forEach(function (a) {
        if (a.name === 'style') return;
        if (a.value.length > 500 || /dtsg|token|lsd|jazoest/i.test(a.value)) {
          e.setAttribute(a.name, '[removed ' + a.value.length + ' chars]');
        }
      });
    });
    var html = '<!-- FaceBlockMobile DOM dump | ' + location.pathname + ' | ' + new Date().toISOString() +
      ' | ' + navigator.userAgent + ' -->\n' + clone.outerHTML;
    var size = 200000;
    var total = Math.ceil(html.length / size);
    for (var i = 0; i < total; i++) {
      bridge.onDomChunk(requestId, i, total, html.substr(i * size, size));
    }
  }

  // -------------------------------------------------------------------------
  // Public API (called from Kotlin via evaluateJavascript)
  // -------------------------------------------------------------------------

  window.__fbm = {
    version: VERSION,

    /**
     * Applies a { rules, settings } config. Called at document start and again
     * after every page load / SPA navigation. An identical config only re-scans;
     * a changed one clears all marks first so turning a rule off un-hides its posts.
     */
    configure: function (cfg) {
      var json = JSON.stringify(cfg);
      var changed = json !== configJson;
      if (changed) {
        configJson = json;
        compiled = compile(cfg);
      }
      applyStyles();
      startObserving();
      rescanAll(changed);
    },

    dumpDom: dumpDom,

    /** For poking at from chrome://inspect: what would each rule see for this unit? */
    explain: function (el) {
      var unit = findUnit(el);
      if (!unit) return 'not inside a feed unit';
      var f = unitFacts(unit);
      return {
        header: f.header,
        headerParts: f.headerParts,
        matched: unit.getAttribute(ATTR_MATCH),
        rules: compiled.unitRules.map(function (r) { return r.id + ': ' + r.test(f); })
      };
    }
  };
})();
