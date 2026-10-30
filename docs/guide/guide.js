/*
 * Bitchord+ install guide: the engine.
 *
 * It owns the pretend phone (status bar, screens, dialogs, sheets, toasts, Android buttons),
 * the state store, the screen and tour registries, the tour runner around TourGuide JS,
 * the tour picker, deep links (#tour=<id>&step=<n>) and saved progress.
 *
 * Screens are added by screens-morphe.js and screens-bitchord.js with Guide.screen(), and tours
 * by tours.js with Guide.tour(). Everything here runs from a plain <script> tag, from file:// or
 * a web server, with no network requests.
 *
 * Load order: vendor/tourguide/tour.js, icons.js, guide.js, screens-*.js, tours.js.
 * The guide starts on DOMContentLoaded, after every script above has run.
 */
(function () {
  'use strict';

  const G = window.Guide = window.Guide || {};
  G.version = '1.1.0-beta.11';

  // The demo screens and the demo tour are for testing the engine only. Open the page with
  // ?demo=1 to turn them on. They stay off otherwise.
  const DEMO = /(?:^|[?&])demo=1(?:&|$)/.test(location.search);
  G.demo = DEMO;
  // ?debug=1 prints warnings about missing screens and elements to the console.
  const DEBUG = /(?:^|[?&])debug=1(?:&|$)/.test(location.search);
  G.debug = DEBUG;

  const APPS = ['morphe', 'bitchord', 'android'];
  // Which look a screen gets when its definition doesn't say (from the first part of its id).
  const PREFIX_APP = {
    morphe: 'morphe', bitchord: 'bitchord', tg: 'bitchord', tweaks: 'bitchord', downloadChoice: 'bitchord',
    android: 'android', browser: 'android', files: 'android', microg: 'android'
  };
  const KINDS = ['screen', 'dialog', 'sheet', 'full'];
  const GROUPS = ['start', 'use', 'tweaks', 'help'];
  const PROGRESS_KEY = 'bitchord-plus-guide.progress.v1';
  let booted = false;     // true once the phone is built and the guide has started

  // ---------------------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------------------

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  }
  function cssq(s) { return String(s).replace(/["\\]/g, '\\$&'); }
  function noop() {}
  function warn() { if (DEBUG) console.warn.apply(console, ['[guide]'].concat([].slice.call(arguments))); }
  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }
  function copy(v) {
    if (Array.isArray(v)) return v.slice();
    if (v && typeof v === 'object') return Object.assign({}, v);
    return v;
  }
  function same(a, b) {
    if (a === b) return true;
    if (a && b && typeof a === 'object' && typeof b === 'object') {
      try { return JSON.stringify(a) === JSON.stringify(b); } catch (e) { return false; }
    }
    return false;
  }
  function delay(ms) { return new Promise((r) => setTimeout(r, ms)); }
  function nextFrame() {
    return new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))));
  }
  function parseValue(str) {
    if (str === 'true') return true;
    if (str === 'false') return false;
    if (str === 'null') return null;
    if (str !== '' && !isNaN(Number(str)) && /^-?\d+(\.\d+)?$/.test(str)) return Number(str);
    return str;
  }
  const reduceMotionQuery = window.matchMedia ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  function motionOff() { return !!(reduceMotionQuery && reduceMotionQuery.matches); }

  G.esc = esc;

  // ---------------------------------------------------------------------------------------
  // Storage. It can throw (private windows, blocked site data), so every call is guarded,
  // and the page works the same without it.
  // ---------------------------------------------------------------------------------------

  const storage = {
    read(key) {
      try {
        const raw = window.localStorage.getItem(key);
        return raw ? JSON.parse(raw) : null;
      } catch (e) { return null; }
    },
    write(key, value) {
      try { window.localStorage.setItem(key, JSON.stringify(value)); return true; } catch (e) { return false; }
    }
  };

  // ---------------------------------------------------------------------------------------
  // State store. Keys are flat strings such as "morphe.prerelease". Defaults are declared
  // with Guide.defineState. Values are plain data (boolean, number, string, array, object).
  // Treat what Guide.get returns as read-only, and change it with Guide.set.
  // ---------------------------------------------------------------------------------------

  const defaults = Object.create(null);
  let values = Object.create(null);
  const subscribers = [];
  let renderPending = false;

  function defineState(obj) {
    Object.keys(obj || {}).forEach((k) => {
      if (k in defaults) {
        if (!same(defaults[k], obj[k])) warn('state key defined twice with different defaults:', k);
        return;
      }
      defaults[k] = copy(obj[k]);
    });
  }
  function get(key) {
    if (key in values) return values[key];
    if (!(key in defaults)) warn('unknown state key:', key);
    return defaults[key];
  }
  function all() {
    const out = Object.create(null);
    Object.keys(defaults).forEach((k) => { out[k] = defaults[k]; });
    Object.keys(values).forEach((k) => { out[k] = values[k]; });
    return out;
  }
  function set(key, value) {
    const obj = (key && typeof key === 'object') ? key : { [key]: value };
    const changed = [];
    Object.keys(obj).forEach((k) => {
      if (!(k in defaults) && !(k in values)) warn('setting a key with no default:', k);
      if (!same(get(k), obj[k])) {
        values[k] = copy(obj[k]);
        changed.push(k);
      }
    });
    if (changed.length) scheduleRender(changed);
    return changed.length > 0;
  }
  function toggle(key) { return set(key, !get(key)); }
  function toggleItem(key, item) {
    const list = Array.isArray(get(key)) ? get(key).slice() : [];
    const i = list.indexOf(item);
    if (i >= 0) list.splice(i, 1); else list.push(item);
    return set(key, list);
  }
  function resetState(extra) {
    values = Object.create(null);
    if (extra) Object.keys(extra).forEach((k) => { values[k] = copy(extra[k]); });
    scheduleRender(['*']);
  }
  function snapshot() {
    const out = {};
    Object.keys(values).forEach((k) => { out[k] = copy(values[k]); });
    return out;
  }
  function restore(snap) {
    values = Object.create(null);
    Object.keys(snap || {}).forEach((k) => { values[k] = copy(snap[k]); });
    scheduleRender(['*']);
  }
  function subscribe(fn) {
    subscribers.push(fn);
    return () => { const i = subscribers.indexOf(fn); if (i >= 0) subscribers.splice(i, 1); };
  }

  let changedSinceRender = [];
  function scheduleRender(keys) {
    changedSinceRender = changedSinceRender.concat(keys || []);
    if (renderPending) return;
    renderPending = true;
    Promise.resolve().then(flush);
  }

  Object.assign(G, {
    defineState, get, set, toggle, toggleItem, resetState, snapshot, restore, subscribe,
    state: all
  });

  // The state keys the tours use, with their defaults. Screen files may add their own keys
  // with Guide.defineState, named with their own prefix.
  defineState({
    // Android and the pretend phone
    'android.morpheInstalled': false,
    'android.bitchordInstalled': false,
    'android.microg': false,
    'android.unknownAppsAllowed': false,

    // Morphe Manager: sources
    'morphe.sourceAdded': false,
    'morphe.sourceStatus': 'ready',
    'morphe.sourceEnabled': true,
    'morphe.prerelease': false,
    'morphe.experimental': false,
    'morphe.sourceAppBitchord': true,
    'morphe.updateMorpheBadge': false,
    'morphe.openCard': '',
    'morphe.addSourceTab': 'remote',
    'morphe.addSourceUrl': '',
    'morphe.testingAdded': false,
    'morphe.testingExperimental': false,
    'morphe.testingName': '',

    // Morphe Manager: settings and the patching flow
    'morphe.expertMode': false,
    'morphe.bgProtectionAllowed': false,
    'morphe.apkPicked': false,
    'morphe.patchTab': 'bitchord',
    'morphe.patch.telegram': true,
    'morphe.patch.bitchordPlus': true,
    'morphe.patch.microg': true,
    'morphe.patch.sing': false,
    'morphe.patch.pitch': false,
    'morphe.patch.loudness': false,
    'morphe.patch.sleepFade': false,
    'morphe.patch.batterySaver': false,
    'morphe.patch.losslessBluetooth': false,
    'morphe.patch.perDownload': false,
    'morphe.patch.headphones': false,
    'morphe.patch.outputEq': false,
    'morphe.patch.djMode': false,
    'morphe.patch.jamesdsp': false,
    'morphe.opt.apiId': 0,
    'morphe.opt.apiHash': '',
    'morphe.opt.installationName': '',
    'morphe.patching.status': 'idle',
    'morphe.patching.progress': 0,
    'morphe.installed': false,
    'morphe.install.error': '',
    'morphe.appUpdateChip': false,

    // BitChord (Bitchord+)
    'bitchord.signedIn': false,
    'bitchord.playing': true,
    'bitchord.searchTab': 'youtube',
    'bitchord.searchQuery': '',
    'bitchord.loudnessNormalization': false,
    'bitchord.equalizer': false,
    'bitchord.output': 'headphones',
    'bitchord.songSheetView': 'actions',
    'bitchord.sleepTimer': '',

    // Our Unofficial Telegram page
    'tg.stage': 'setup',
    'tg.apiId': '',
    'tg.apiHash': '',
    'tg.showHash': false,
    'tg.phone': '',
    'tg.code': '',
    'tg.password': '',
    'tg.tab': 'basic',
    'tg.chatFolder': '',
    'tg.chats': [],
    'tg.addChat': '',
    'tg.error': '',

    // Sign in with MicroG
    'microg.consentGiven': false,

    // Patches & tweaks (defaults from the patches' code)
    'tweaks.sing.voice': 0,
    'tweaks.sing.wholeCentre': false,
    'tweaks.pitch': 0,
    'tweaks.loudness': -14,
    'tweaks.headphones.query': '',
    'tweaks.headphones.results': false,
    'tweaks.headphones.profile': '',
    'tweaks.headphones.strength': 100,
    'tweaks.outputEq.remember': true,
    'tweaks.dj.on': false,
    'tweaks.sleep.fade': true,
    'tweaks.sleep.length': 10,
    'tweaks.sleep.ending': true,
    'tweaks.battery.on': false,
    'tweaks.bluetooth.all': false,
    'tweaks.bluetooth.devices': [],
    'tweaks.downloads.ask': true,
    'tweaks.downloads.last': 'lossless',
    'tweaks.jamesdsp.installed': 'none',
    'tweaks.jamesdsp.effects': false
  });

  // ---------------------------------------------------------------------------------------
  // Registries: screens (and dialogs, sheets), launcher apps, actions and tours
  // ---------------------------------------------------------------------------------------

  const screens = Object.create(null);
  const launcher = Object.create(null);
  const globalActions = Object.create(null);
  const tours = Object.create(null);

  function screen(def) {
    if (!def || !def.id) throw new Error('Guide.screen needs an id');
    def.app = def.app || PREFIX_APP[String(def.id).split('.')[0]] || 'android';
    if (APPS.indexOf(def.app) < 0) throw new Error('Unknown app for ' + def.id + ': ' + def.app);
    def.kind = def.kind || 'screen';
    if (KINDS.indexOf(def.kind) < 0) throw new Error('Unknown kind for ' + def.id + ': ' + def.kind);
    if (typeof def.render !== 'function') throw new Error('Guide.screen ' + def.id + ' needs render()');
    if (screens[def.id]) warn('screen registered twice:', def.id);
    screens[def.id] = def;
    if (booted) scheduleRender(['*']);
    return def;
  }
  function action(name, fn) { globalActions[name] = fn; }
  function launcherApp(id, info) {
    launcher[id] = Object.assign({ id: id, order: 50 }, launcher[id] || {}, info || {});
    if (booted) scheduleRender(['*']);
  }

  function tour(def) {
    if (!def || !def.id || !Array.isArray(def.steps) || !def.steps.length) {
      throw new Error('Guide.tour needs an id and at least one step');
    }
    def.group = GROUPS.indexOf(def.group) >= 0 ? def.group : 'start';
    def.order = def.order == null ? 50 : def.order;
    tours[def.id] = def;
    if (booted) renderPicker();
    return def;
  }

  Object.assign(G, { screen, action, launcherApp, tour });
  G.screens = screens;
  G.tours = tours;

  // Launcher icons on the pretend home screen. Screen files may change an entry with
  // Guide.launcherApp('<id>', { entry: '<screen id>' }).
  launcherApp('morphe', { label: 'Morphe', entry: 'morphe.apps', tile: 'M', tone: 'morphe', order: 10, demoEntry: 'demo.morphe' });
  launcherApp('bitchordPlus', { label: 'Bitchord+', entry: 'bitchord.home', tile: 'B+', tone: 'bitchord', order: 20, demoEntry: 'demo.bitchord' });
  launcherApp('files', { label: 'Files', entry: 'files.downloads', icon: 'folder', tone: 'files', order: 30 });
  launcherApp('browser', { label: 'Browser', entry: 'browser.morpheRelease', icon: 'globe', tone: 'browser', order: 40 });

  // ---------------------------------------------------------------------------------------
  // DOM morphing. A re-render patches the existing nodes instead of replacing them, so focus,
  // scroll positions, running CSS transitions and the tour's highlighted element survive.
  // Elements are matched by position, tag and key (data-key, data-tg or id).
  // Classes starting with "tg-" (the tour library) or "rt-" (this engine) are kept.
  // An element with data-static keeps its children as they are.
  // ---------------------------------------------------------------------------------------

  function keyOf(n) { return n.getAttribute('data-key') || n.getAttribute('data-tg') || n.id || ''; }
  function sameNode(a, b) {
    if (a.nodeType !== b.nodeType) return false;
    if (a.nodeType !== 1) return true;
    return a.nodeName === b.nodeName && keyOf(a) === keyOf(b);
  }
  function isRuntimeClass(c) { return c.indexOf('tg-') === 0 || c.indexOf('rt-') === 0; }
  function morphAttrs(from, to) {
    for (let i = from.attributes.length - 1; i >= 0; i--) {
      const name = from.attributes[i].name;
      if (name !== 'class' && !to.hasAttribute(name)) from.removeAttribute(name);
    }
    for (let i = 0; i < to.attributes.length; i++) {
      const a = to.attributes[i];
      if (a.name === 'class') continue;
      if (from.getAttribute(a.name) !== a.value) from.setAttribute(a.name, a.value);
    }
    const keep = from.classList ? [].filter.call(from.classList, isRuntimeClass) : [];
    const want = (to.getAttribute('class') || '').split(/\s+/).filter(Boolean);
    keep.forEach((c) => { if (want.indexOf(c) < 0) want.push(c); });
    const next = want.join(' ');
    if ((from.getAttribute('class') || '') !== next) {
      if (next) from.setAttribute('class', next); else from.removeAttribute('class');
    }
  }
  function morphNode(from, to) {
    if (from.nodeType !== 1) {
      if (from.nodeValue !== to.nodeValue) from.nodeValue = to.nodeValue;
      return;
    }
    morphAttrs(from, to);
    const tag = from.nodeName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
      if (document.activeElement !== from) {
        if (from.value !== to.value) from.value = to.value;
        if (from.checked !== to.checked) from.checked = to.checked;
      }
      if (tag !== 'SELECT') return;
    }
    if (from.hasAttribute('data-static') && from.firstChild) return;
    morphChildren(from, to);
  }
  function morphChildren(from, to) {
    let f = from.firstChild;
    let t = to.firstChild;
    while (t) {
      const tNext = t.nextSibling;
      if (!f) {
        from.appendChild(t);
      } else if (sameNode(f, t)) {
        morphNode(f, t);
        f = f.nextSibling;
      } else {
        let match = null;
        if (t.nodeType === 1 && keyOf(t)) {
          for (let s = f.nextSibling; s; s = s.nextSibling) { if (sameNode(s, t)) { match = s; break; } }
        }
        if (match) {
          from.insertBefore(match, f);
          morphNode(match, t);
        } else {
          from.insertBefore(t, f);
        }
      }
      t = tNext;
    }
    while (f) { const n = f.nextSibling; from.removeChild(f); f = n; }
  }
  G.morph = morphChildren;

  // ---------------------------------------------------------------------------------------
  // The pretend phone
  // ---------------------------------------------------------------------------------------

  let phone = null;        // #phone (outer frame)
  let phoneScreen = null;  // the display: status bar, viewport, Android buttons
  let viewport = null;     // area between the status bar and the Android buttons
  let screenHost = null;
  let overlayHost = null;
  let toastHost = null;

  let cur = null;          // { def, params, node, cleanup }
  let transition = null;   // { id, promise, finish }
  const backStack = [];    // [{ id, params }]
  let overlays = [];       // [{ def, params, node, panel, cleanup, opening }]
  let timers = [];         // [{ handle, owner }]
  let lastHandledEvent = null;

  function buildPhone() {
    phone = document.getElementById('phone');
    if (!phone) return false;
    phone.innerHTML =
      '<div class="phone-screen" id="phone-screen" data-app="android">' +
        '<div class="sys-status" aria-hidden="true">' +
          '<span class="sys-time">10:41</span>' +
          '<span class="sys-icons">' + G.icon('wifi', { size: 15 }) + G.icon('signal', { size: 14 }) +
            '<span class="sys-battery">' + G.icon('batteryFull', { size: 15 }) + '</span></span>' +
        '</div>' +
        '<div class="phone-viewport" id="phone-viewport">' +
          '<div class="screen-host" id="screen-host"></div>' +
          '<div class="overlay-host" id="overlay-host"></div>' +
          '<div class="toast-host" id="toast-host" role="status" aria-live="polite"></div>' +
        '</div>' +
        '<div class="sys-nav" role="group" aria-label="Phone buttons">' +
          '<button type="button" class="sys-nav-btn" data-back aria-label="Back">' + G.icon('navBack', { size: 18 }) + '</button>' +
          '<button type="button" class="sys-nav-btn" data-home aria-label="Home">' + G.icon('navHome', { size: 18 }) + '</button>' +
          '<button type="button" class="sys-nav-btn" data-open="android.recents" aria-label="Recent apps">' + G.icon('navRecents', { size: 16 }) + '</button>' +
        '</div>' +
      '</div>';
    phoneScreen = document.getElementById('phone-screen');
    viewport = document.getElementById('phone-viewport');
    screenHost = document.getElementById('screen-host');
    overlayHost = document.getElementById('overlay-host');
    toastHost = document.getElementById('toast-host');

    phoneScreen.addEventListener('click', onTourClick, true);
    phoneScreen.addEventListener('click', onPhoneClick);
    phoneScreen.addEventListener('input', onPhoneInput);
    phoneScreen.addEventListener('change', onPhoneInput);
    phoneScreen.addEventListener('keydown', onPhoneKey);
    phoneScreen.addEventListener('scroll', onInnerScroll, true);
    return true;
  }

  function makeCtx(def, params, owner) {
    return {
      id: def.id,
      app: def.app,
      params: params || {},
      s: all(),
      get: get,
      set: set,
      toggle: toggle,
      icon: G.icon,
      ui: G.ui,
      esc: esc,
      inTour: inTour(),
      go: go,
      open: open,
      close: close,
      back: back,
      toast: toast,
      later: (fn, ms) => later(fn, ms, owner && owner.node)
    };
  }

  function renderInto(def, params, target, owner) {
    let html = '';
    try {
      html = def.render(makeCtx(def, params, owner));
    } catch (e) {
      warn('render failed for', def.id, e);
      html = '<p class="g-render-error">This screen could not be drawn.</p>';
    }
    if (html && typeof html === 'object' && html.nodeType) {
      target.innerHTML = '';
      target.appendChild(html);
    } else {
      target.innerHTML = html || '';
    }
  }

  function screenNode(def, params) {
    const node = document.createElement('section');
    node.className = 'screen app-' + def.app + (def.className ? ' ' + def.className : '');
    node.setAttribute('data-screen', def.id);
    node.setAttribute('aria-label', def.title || def.id);
    renderInto(def, params, node, { node: node });
    return node;
  }

  function overlayNode(def, params) {
    const node = document.createElement('div');
    node.className = 'overlay overlay-' + def.kind + ' app-' + def.app + (def.className ? ' ' + def.className : '');
    node.setAttribute('data-overlay', def.id);
    const scrim = def.kind === 'full' ? '' : '<div class="overlay-scrim" data-scrim></div>';
    node.innerHTML = scrim + '<div class="overlay-panel" role="dialog" aria-label="' + esc(def.title || def.id) + '"></div>';
    const panel = node.querySelector('.overlay-panel');
    renderInto(def, params, panel, { node: node });
    return node;
  }

  // Re-render the open screen and overlays from the current state.
  function flush() {
    renderPending = false;
    const changed = changedSinceRender;
    changedSinceRender = [];
    if (!booted) return;
    if (cur) {
      const fresh = document.createElement('section');
      renderInto(cur.def, cur.params, fresh, cur);
      morphChildren(cur.node, fresh);
    }
    overlays.forEach((o) => {
      const fresh = document.createElement('div');
      renderInto(o.def, o.params, fresh, o);
      morphChildren(o.panel, fresh);
    });
    subscribers.slice().forEach((fn) => { try { fn(changed); } catch (e) { warn(e); } });
    tourAfterRender();
  }
  G.flush = flush;
  G.refresh = () => scheduleRender(['*']);

  function setBars(def) {
    phoneScreen.setAttribute('data-app', def.app);
    const bars = def.bars || {};
    phoneScreen.style.setProperty('--sys-bg', bars.bg || '');
    phoneScreen.style.setProperty('--sys-fg', bars.fg || '');
    if (!bars.bg) phoneScreen.style.removeProperty('--sys-bg');
    if (!bars.fg) phoneScreen.style.removeProperty('--sys-fg');
  }

  function removeScreenRecord(rec) {
    if (!rec) return;
    clearTimers(rec.node);
    if (typeof rec.cleanup === 'function') { try { rec.cleanup(); } catch (e) { warn(e); } }
    if (rec.node && rec.node.parentNode) rec.node.parentNode.removeChild(rec.node);
  }

  // Show a screen. Resolves after its entry animation has ended (or at once with reduced motion).
  // Calling it again for the screen that is still sliding in returns the same promise.
  function go(id, params, opts) {
    opts = opts || {};
    const def = screens[id];
    if (!def || def.kind !== 'screen') {
      warn('no screen with id', id);
      return Promise.resolve(false);
    }
    if (!opts.keepOverlays) closeAll(true);
    params = params || {};
    if (cur && cur.def.id === id && same(cur.params, params)) {
      return transition && transition.id === id ? transition.promise : Promise.resolve(true);
    }
    if (transition) transition.finish();
    if (cur && !opts.noHistory) {
      backStack.push({ id: cur.def.id, params: cur.params });
      if (backStack.length > 40) backStack.shift();
    }
    const prev = cur;
    const node = screenNode(def, params);
    screenHost.appendChild(node);
    cur = { def: def, params: params, node: node, cleanup: null };
    setBars(def);
    if (typeof def.mount === 'function') {
      try { cur.cleanup = def.mount(node, makeCtx(def, params, cur)); } catch (e) { warn('mount failed', id, e); }
    }
    const dir = opts.dir || (prev && prev.def.app !== def.app ? 'fade' : 'forward');
    if (!prev || motionOff() || dir === 'none') {
      removeScreenRecord(prev);
      return nextFrame();
    }
    prev.node.setAttribute('aria-hidden', 'true');
    prev.node.inert = true;
    node.classList.add('rt-enter-' + dir);
    prev.node.classList.add('rt-leave-' + dir);
    let done = false;
    let resolveFn = noop;
    const promise = new Promise((resolve) => { resolveFn = resolve; });
    const finish = () => {
      if (done) return;
      done = true;
      clearTimeout(safety);
      node.classList.remove('rt-enter-' + dir);
      removeScreenRecord(prev);
      if (transition && transition.promise === promise) transition = null;
      requestAnimationFrame(() => resolveFn(true));
    };
    const safety = setTimeout(finish, 480);
    node.addEventListener('animationend', function onEnd(e) {
      if (e.target !== node) return;
      node.removeEventListener('animationend', onEnd);
      finish();
    });
    transition = { id: id, promise: promise, finish: finish };
    return promise;
  }

  function findOverlay(id) { return overlays.filter((o) => o.def.id === id)[0] || null; }

  // Open a dialog, sheet or full-screen dialog over the current screen.
  function open(id, params, opts) {
    opts = opts || {};
    const def = screens[id];
    if (!def || def.kind === 'screen') {
      warn('no dialog or sheet with id', id);
      return Promise.resolve(false);
    }
    params = params || {};
    const existing = findOverlay(id);
    if (existing) {
      if (!same(existing.params, params)) { existing.params = params; scheduleRender(['*']); }
      return existing.opening || Promise.resolve(true);
    }
    const node = overlayNode(def, params);
    const rec = { def: def, params: params, node: node, panel: node.querySelector('.overlay-panel'), cleanup: null, opening: null };
    overlayHost.appendChild(node);
    overlays.push(rec);
    if (typeof def.mount === 'function') {
      try { rec.cleanup = def.mount(node, makeCtx(def, params, rec)); } catch (e) { warn('mount failed', id, e); }
    }
    const focusInside = phoneScreen.contains(document.activeElement) && !inTour();
    if (focusInside) {
      const f = rec.panel.querySelector('button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])');
      if (f) f.focus({ preventScroll: true });
    }
    if (motionOff() || opts.immediate) {
      rec.opening = nextFrame();
    } else {
      node.classList.add('rt-opening');
      rec.opening = new Promise((resolve) => {
        let done = false;
        const finish = () => {
          if (done) return;
          done = true;
          node.classList.remove('rt-opening');
          requestAnimationFrame(() => resolve(true));
        };
        rec.panel.addEventListener('animationend', function onEnd(e) {
          if (e.target !== rec.panel) return;
          rec.panel.removeEventListener('animationend', onEnd);
          finish();
        });
        setTimeout(finish, 420);
      });
    }
    return rec.opening;
  }

  function removeOverlayRecord(rec) {
    overlays = overlays.filter((o) => o !== rec);
    clearTimers(rec.node);
    if (typeof rec.cleanup === 'function') { try { rec.cleanup(); } catch (e) { warn(e); } }
    if (rec.node.parentNode) rec.node.parentNode.removeChild(rec.node);
  }

  // Close one overlay (the top one if no id is given).
  function close(id, opts) {
    opts = opts || {};
    const rec = id ? findOverlay(id) : overlays[overlays.length - 1];
    if (!rec) return Promise.resolve(false);
    if (motionOff() || opts.immediate) {
      removeOverlayRecord(rec);
      return Promise.resolve(true);
    }
    overlays = overlays.filter((o) => o !== rec);
    rec.node.classList.add('rt-closing');
    return new Promise((resolve) => {
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        overlays.push(rec);
        removeOverlayRecord(rec);
        resolve(true);
      };
      rec.panel.addEventListener('animationend', function onEnd(e) {
        if (e.target !== rec.panel) return;
        rec.panel.removeEventListener('animationend', onEnd);
        finish();
      });
      setTimeout(finish, 300);
    });
  }
  function closeAll(immediate) {
    overlays.slice().forEach((o) => {
      if (immediate) removeOverlayRecord(o); else close(o.def.id);
    });
  }

  // The Android Back button.
  function back() {
    if (overlays.length) {
      const top = overlays[overlays.length - 1];
      if (typeof top.def.onBack === 'function') {
        try { if (top.def.onBack(makeCtx(top.def, top.params, top)) !== false) return; } catch (e) { warn(e); }
      }
      close(top.def.id);
      return;
    }
    if (cur && typeof cur.def.onBack === 'function') {
      try { if (cur.def.onBack(makeCtx(cur.def, cur.params, cur)) !== false) return; } catch (e) { warn(e); }
    }
    const prev = backStack.pop();
    if (prev && screens[prev.id]) { go(prev.id, prev.params, { dir: 'back', noHistory: true }); return; }
    if (cur && cur.def.back && screens[cur.def.back]) { go(cur.def.back, null, { dir: 'back', noHistory: true }); return; }
    if (!cur || cur.def.id !== 'android.home') home();
  }

  function home() {
    backStack.length = 0;
    return go('android.home', null, { dir: 'fade', noHistory: true });
  }

  function launch(appId) {
    const app = launcher[appId];
    if (!app) return Promise.resolve(false);
    const entry = screens[app.entry] ? app.entry : (DEMO && app.demoEntry && screens[app.demoEntry] ? app.demoEntry : null);
    if (!entry) {
      toast(app.label + ' is not drawn in this guide yet.');
      return Promise.resolve(false);
    }
    return go(entry, null, { dir: 'fade' });
  }

  // A short Android-style message at the bottom of the phone.
  function toast(text, opts) {
    opts = opts || {};
    if (!toastHost) return;
    const t = document.createElement('div');
    t.className = 'g-toast';
    t.textContent = text;
    toastHost.appendChild(t);
    while (toastHost.children.length > 2) toastHost.removeChild(toastHost.firstChild);
    setTimeout(() => {
      if (motionOff()) { t.remove(); return; }
      t.classList.add('rt-hide');
      setTimeout(() => t.remove(), 250);
    }, opts.duration || 2600);
  }

  // Timers that belong to a screen or overlay. They stop when it closes, and whenever
  // a tour step starts, so nothing changes behind the tour's back.
  function later(fn, ms, owner) {
    const rec = { owner: owner || null, handle: 0 };
    rec.handle = setTimeout(() => {
      timers = timers.filter((x) => x !== rec);
      try { fn(); } catch (e) { warn(e); }
    }, ms);
    timers.push(rec);
    return rec.handle;
  }
  function clearTimers(owner) {
    timers = timers.filter((rec) => {
      if (owner && rec.owner !== owner) return true;
      clearTimeout(rec.handle);
      return false;
    });
  }

  // The element with this data-tg name: the top overlay first, then the screen.
  function el(name) {
    if (!name || !phoneScreen) return null;
    const sel = '[data-tg="' + cssq(name) + '"]';
    for (let i = overlays.length - 1; i >= 0; i--) {
      const e = overlays[i].node.querySelector(sel);
      if (e) return e;
    }
    if (cur) { const e = cur.node.querySelector(sel); if (e) return e; }
    return phoneScreen.querySelector(sel);
  }

  function current() {
    return {
      screen: cur ? cur.def.id : null,
      params: cur ? cur.params : null,
      overlays: overlays.map((o) => o.def.id)
    };
  }

  Object.assign(G, {
    go, open, close, closeAll, back, home, launch, toast, el, current,
    later: (fn, ms) => later(fn, ms, null),
    clearTimers: () => clearTimers(null),
    isOpen: (id) => !!findOverlay(id)
  });

  // ---------------------------------------------------------------------------------------
  // Clicks, typing and keys inside the phone (free explore and tour taps alike)
  // ---------------------------------------------------------------------------------------

  const CLICK_SEL = '[data-action],[data-go],[data-open],[data-close],[data-back],[data-home],[data-bind],[data-set],[data-toast],[data-launch]';

  function ownerOf(node) {
    const ov = node.closest('[data-overlay]');
    if (ov) return findOverlay(ov.getAttribute('data-overlay'));
    const sc = node.closest('[data-screen]');
    if (sc && cur && cur.node === sc) return cur;
    return null;
  }

  function runAction(name, node, event) {
    const owner = ownerOf(node);
    const def = owner ? owner.def : null;
    const fn = (def && def.actions && def.actions[name]) || globalActions[name];
    if (!fn) { warn('no action called', name); return; }
    const ctx = def ? makeCtx(def, owner.params, owner) : makeCtx({ id: '', app: 'android' }, {}, null);
    try { fn(ctx, node, event); } catch (e) { warn('action failed', name, e); }
  }

  function activate(node, event) {
    if (node.getAttribute('aria-disabled') === 'true' || node.disabled) return false;
    const tag = node.nodeName;
    const isField = (tag === 'INPUT' && !/^(button|submit|checkbox|radio)$/i.test(node.type)) || tag === 'TEXTAREA' || tag === 'SELECT';
    if (node.hasAttribute('data-action')) {
      runAction(node.getAttribute('data-action'), node, event);
      return true;
    }
    if (node.hasAttribute('data-bind') && !isField) {
      const key = node.getAttribute('data-bind');
      if (node.hasAttribute('data-value')) set(key, parseValue(node.getAttribute('data-value')));
      else if (node.hasAttribute('data-item')) toggleItem(key, node.getAttribute('data-item'));
      else toggle(key);
    }
    if (node.hasAttribute('data-set')) {
      try { set(JSON.parse(node.getAttribute('data-set'))); } catch (e) { warn('bad data-set JSON', e); }
    }
    if (node.hasAttribute('data-close')) close(node.getAttribute('data-close') || null);
    if (node.hasAttribute('data-open')) {
      let p = null;
      try { p = node.hasAttribute('data-params') ? JSON.parse(node.getAttribute('data-params')) : null; } catch (e) { warn(e); }
      open(node.getAttribute('data-open'), p);
    }
    if (node.hasAttribute('data-go')) {
      let p = null;
      try { p = node.hasAttribute('data-params') ? JSON.parse(node.getAttribute('data-params')) : null; } catch (e) { warn(e); }
      go(node.getAttribute('data-go'), p, { dir: node.getAttribute('data-dir') || undefined });
    }
    if (node.hasAttribute('data-launch')) launch(node.getAttribute('data-launch'));
    if (node.hasAttribute('data-back')) back();
    if (node.hasAttribute('data-home')) { closeAll(true); home(); }
    if (node.hasAttribute('data-toast')) toast(node.getAttribute('data-toast'));
    return true;
  }

  function onPhoneClick(e) {
    const scrim = e.target.closest('[data-scrim]');
    if (scrim) {
      const ov = scrim.closest('[data-overlay]');
      const rec = ov && findOverlay(ov.getAttribute('data-overlay'));
      if (rec && !rec.def.modal) close(rec.def.id);
      return;
    }
    const node = e.target.closest(CLICK_SEL);
    if (!node || !phoneScreen.contains(node)) return;
    if (activate(node, e)) lastHandledEvent = e;
  }

  function onPhoneInput(e) {
    const node = e.target;
    if (!node.hasAttribute || !node.hasAttribute('data-bind')) return;
    const key = node.getAttribute('data-bind');
    let v = node.value;
    if (node.type === 'range' || node.type === 'number') {
      v = Number(node.value);
      if (isNaN(v)) v = 0;
    }
    if (node.type === 'range') {
      const min = Number(node.min || 0), max = Number(node.max || 100);
      node.style.setProperty('--pct', ((v - min) / ((max - min) || 1) * 100) + '%');
    }
    set(key, v);
    if (e.type === 'change' && node.hasAttribute('data-action')) runAction(node.getAttribute('data-action'), node, e);
  }

  function onPhoneKey(e) {
    if (e.key === 'Escape' && !inTour() && overlays.length) {
      e.preventDefault();
      close();
      return;
    }
    if (e.key === 'Enter' && e.target && e.target.nodeName === 'INPUT' && e.target.hasAttribute('data-enter')) {
      e.preventDefault();
      runAction(e.target.getAttribute('data-enter'), e.target, e);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Control helpers. They return HTML strings with the right roles, labels and data
  // attributes. The look comes from guide.css and the app's own CSS file.
  // ---------------------------------------------------------------------------------------

  function attrs(o) {
    let s = '';
    if (o.tg) s += ' data-tg="' + esc(o.tg) + '"';
    if (o.key) s += ' data-key="' + esc(o.key) + '"';
    if (o.label) s += ' aria-label="' + esc(o.label) + '"';
    if (o.labelledby) s += ' aria-labelledby="' + esc(o.labelledby) + '"';
    if (o.describedby) s += ' aria-describedby="' + esc(o.describedby) + '"';
    if (o.id) s += ' id="' + esc(o.id) + '"';
    if (o.disabled) s += ' aria-disabled="true"';
    if (o.action) s += ' data-action="' + esc(o.action) + '"';
    return s;
  }
  function bindAttr(o) { return (o.bind && !o.action) ? ' data-bind="' + esc(o.bind) + '"' : ''; }
  function pct(v, min, max) { return clamp((v - min) / ((max - min) || 1) * 100, 0, 100); }

  const ui = {
    // On/off switch: <button role="switch">. Tapping it flips the boolean state key in `bind`,
    // or runs `action` instead when one is given (for switches that ask first).
    switch(o) {
      const on = ('on' in o) ? !!o.on : !!get(o.bind);
      return '<button type="button" class="g-switch' + (o.cls ? ' ' + esc(o.cls) : '') + '" role="switch" aria-checked="' + on + '"' +
        attrs(o) + bindAttr(o) + '><span class="g-switch-thumb">' +
        (o.thumbIcons ? G.icon(on ? 'check' : 'close', { size: 14, stroke: 2.6 }) : '') + '</span></button>';
    },
    // Tick box: <button role="checkbox">. With `item`, it adds or removes that item in an array key.
    check(o) {
      const v = get(o.bind);
      const on = ('on' in o) ? !!o.on : (o.item != null ? (Array.isArray(v) && v.indexOf(o.item) >= 0) : !!v);
      return '<button type="button" class="g-check' + (o.cls ? ' ' + esc(o.cls) : '') + '" role="checkbox" aria-checked="' + on + '"' +
        attrs(o) + bindAttr(o) + (o.item != null && !o.action ? ' data-item="' + esc(o.item) + '"' : '') + '>' +
        (o.html != null ? o.html : G.icon(on ? (o.onIcon || 'checkboxOn') : (o.offIcon || 'checkbox'), { size: o.size || 22 })) + '</button>';
    },
    // One choice of several: <button role="radio"> that sets `bind` to `value`.
    radio(o) {
      const on = ('on' in o) ? !!o.on : same(get(o.bind), o.value);
      return '<button type="button" class="g-radio' + (o.cls ? ' ' + esc(o.cls) : '') + '" role="radio" aria-checked="' + on + '"' +
        attrs(o) + bindAttr(o) + (o.action ? '' : ' data-value="' + esc(o.value) + '"') + '>' +
        (o.html != null ? o.html : G.icon(on ? 'radioOn' : 'circle', { size: o.size || 22 })) + '</button>';
    },
    // Slider: <input type="range">. `text(v)` gives the spoken value, such as "40 %".
    slider(o) {
      const min = o.min == null ? 0 : o.min, max = o.max == null ? 100 : o.max;
      const v = ('value' in o) ? o.value : Number(get(o.bind));
      const spoken = typeof o.text === 'function' ? o.text(v) : String(v);
      return '<input type="range" class="g-slider' + (o.cls ? ' ' + esc(o.cls) : '') + '" min="' + min + '" max="' + max +
        '" step="' + (o.step || 1) + '" value="' + esc(v) + '" aria-valuetext="' + esc(spoken) + '" style="--pct:' + pct(v, min, max) + '%"' +
        attrs(Object.assign({}, o, { action: null })) + (o.bind ? ' data-bind="' + esc(o.bind) + '"' : '') + '>';
    },
    // Text field: <input>. The state key in `bind` follows what the reader types.
    field(o) {
      const v = ('value' in o) ? o.value : (o.bind ? get(o.bind) : '');
      const type = o.type || 'text';
      return '<input type="' + esc(type) + '" class="g-field' + (o.cls ? ' ' + esc(o.cls) : '') + '" value="' + esc(v == null ? '' : v) + '"' +
        (o.placeholder ? ' placeholder="' + esc(o.placeholder) + '"' : '') +
        (o.inputmode ? ' inputmode="' + esc(o.inputmode) + '"' : '') +
        (o.enter ? ' data-enter="' + esc(o.enter) + '"' : '') +
        ' autocomplete="off" autocapitalize="off" spellcheck="false"' +
        attrs(Object.assign({}, o, { action: null })) + (o.bind ? ' data-bind="' + esc(o.bind) + '"' : '') + '>';
    },
    // Multi-line text field.
    textarea(o) {
      const v = ('value' in o) ? o.value : (o.bind ? get(o.bind) : '');
      return '<textarea class="g-field g-textarea' + (o.cls ? ' ' + esc(o.cls) : '') + '" rows="' + (o.rows || 4) + '"' +
        (o.placeholder ? ' placeholder="' + esc(o.placeholder) + '"' : '') + ' spellcheck="false"' +
        attrs(Object.assign({}, o, { action: null })) + (o.bind ? ' data-bind="' + esc(o.bind) + '"' : '') + '>' + esc(v == null ? '' : v) + '</textarea>';
    },
    icon: (name, opts) => G.icon(name, opts),
    esc: esc
  };
  G.ui = ui;

  // ---------------------------------------------------------------------------------------
  // Built-in Android screens: the home screen (launcher) and the recent apps sheet.
  // ---------------------------------------------------------------------------------------

  function launcherList() {
    return Object.keys(launcher).map((k) => launcher[k])
      .filter((a) => screens[a.entry] || (DEMO && a.demoEntry && screens[a.demoEntry]) || a.id === 'morphe' || a.id === 'bitchordPlus')
      // A fresh phone: Morphe shows once it's installed, Bitchord+ once Morphe has patched and installed it.
      .filter((a) => a.id !== 'morphe' || get('android.morpheInstalled'))
      .filter((a) => a.id !== 'bitchordPlus' || get('morphe.installed'))
      .sort((a, b) => a.order - b.order);
  }
  function appLabel(a) {
    if (a.id === 'bitchordPlus') {
      const name = String(get('morphe.opt.installationName') || '').trim();
      return name || a.label;
    }
    return a.label;
  }
  function appTile(a, size) {
    return '<span class="a-tile a-tile-' + esc(a.tone || 'plain') + '" style="--tile:' + (size || 56) + 'px" aria-hidden="true">' +
      (a.tone === 'bitchord' ? G.bitchordMark() : a.icon ? G.icon(a.icon, { size: Math.round((size || 56) * 0.5) }) : '<span class="a-tile-letter">' + esc(a.tile || a.label.charAt(0)) + '</span>') +
      '</span>';
  }

  screen({
    id: 'android.home',
    app: 'android',
    title: 'Android home screen',
    render(ctx) {
      const apps = launcherList();
      return '<div class="a-home">' +
        '<div class="a-home-clock" aria-hidden="true"><span class="a-home-time">10:41</span><span class="a-home-date">Thursday, 30 October</span></div>' +
        '<div class="a-home-grid" data-tg="android.home" role="group" aria-label="Apps">' +
          apps.map((a) => '<button type="button" class="a-home-app" data-launch="' + esc(a.id) + '" data-tg="android.home.' + esc(a.id) + '">' +
            appTile(a, 56) + '<span class="a-home-label">' + esc(appLabel(a)) + '</span></button>').join('') +
        '</div>' +
        '<div class="a-home-search" aria-hidden="true">' + ctx.icon('search', { size: 20 }) + '<span>Search</span></div>' +
      '</div>';
    }
  });

  screen({
    id: 'android.recents',
    app: 'android',
    kind: 'sheet',
    title: 'Recent apps',
    render(ctx) {
      const apps = launcherList();
      return '<div class="a-sheet">' +
        '<div class="a-sheet-handle" aria-hidden="true"></div>' +
        '<h2 class="a-sheet-title">Recent apps</h2>' +
        '<div class="a-recents">' +
          apps.map((a) => '<button type="button" class="a-recents-app" data-close data-launch="' + esc(a.id) + '">' +
            appTile(a, 44) + '<span>' + esc(appLabel(a)) + '</span>' + ctx.icon('chevronRight', { size: 20 }) + '</button>').join('') +
        '</div>' +
        '<div class="a-dialog-actions"><button type="button" class="a-btn-text" data-close>Close</button></div>' +
      '</div>';
    }
  });

  // ---------------------------------------------------------------------------------------
  // Tour runner (TourGuide JS 0.0.27). Workarounds for its known bugs are marked "Bug n",
  // numbered as in the library notes.
  // ---------------------------------------------------------------------------------------

  let tg = null;           // the one TourGuideClient on the page (Bug 10)
  let run = null;          // { tour, steps, index, snaps, tapped, finished, anchorMode }
  let exiting = null;      // the run being closed, so its after-exit clean-up can't touch a new run
  let synthetic = false;   // true while the engine clicks an element for the reader
  let anchor = null;       // invisible element the tour dialog is placed against (see placement)
  let lastPrimary = null;  // element that moves the tour on, marked with rt-tap

  function inTour() { return !!(tg && tg.isVisible && run); }
  G.inTour = inTour;

  function stepAt(i) { return run && run.tour.steps[i]; }
  function currentStepDef() { return tg && run ? stepAt(tg.activeStep) : null; }

  // The element that moves the tour on: `tap: '<name>'` names one, otherwise the highlight.
  function tapElement(sd) {
    if (!sd) return null;
    if (typeof sd.tap === 'string') return el(sd.tap);
    return sd.target ? el(sd.target) : null;
  }

  // Do what a tap on the step's control does, for the Next button and the arrow key.
  function performTap(sd) {
    if (!sd || !sd.tap) return;
    const node = tapElement(sd);
    if (!node) { warn('tap element not found for step', sd.title); return; }
    synthetic = true;
    try {
      if (node.nodeName === 'INPUT' || node.nodeName === 'TEXTAREA') activate(node, null);
      else node.click();
    } catch (e) { warn(e); } finally { synthetic = false; }
  }
  function runThen(sd) {
    if (!sd || !sd.then) return;
    try {
      if (typeof sd.then === 'function') { sd.then(G); return; }
      const t = sd.then;
      if (t.state) set(t.state);
      if (t.close) close(t.close === true ? null : t.close, { immediate: true });
      if (t.open) open(t.open, t.openParams);
      if (t.go) go(t.go, t.params);
      if (t.toast) toast(t.toast);
    } catch (e) { warn('then() failed', e); }
  }

  // State for step i: the tour's base state, then every step's state up to and including i.
  function cumulativeState(tourDef, i) {
    const out = Object.assign({}, tourDef.state || {});
    for (let k = 0; k <= i; k++) Object.assign(out, tourDef.steps[k].state || {});
    return out;
  }

  function listOf(x) { return x == null ? [] : (Array.isArray(x) ? x : [x]); }

  // Make the overlays match the step: close the others at once, open the wanted ones.
  function syncOverlays(sd) {
    const wanted = listOf(sd.overlay);
    const params = listOf(sd.overlayParams);
    overlays.slice().forEach((o) => { if (wanted.indexOf(o.def.id) < 0) removeOverlayRecord(o); });
    const ps = wanted.map((id, k) => open(id, params[k] || null));
    return Promise.all(ps);
  }

  // Put the phone, its state and the overlays where step i of a tour needs them.
  // Shared by the tour runner and Guide.dev.walk().
  async function prepareStep(tourDef, i, opts) {
    opts = opts || {};
    const sd = tourDef.steps[i];
    clearTimers(null);
    if (opts.restore) restore(opts.restore);
    set(cumulativeState(tourDef, i));
    flush();
    const work = [];
    if (sd.screen) {
      if (!screens[sd.screen]) warn('step', i + 1, 'of', tourDef.id, 'names an unknown screen:', sd.screen);
      work.push(go(sd.screen, sd.params || null, { dir: opts.dir, keepOverlays: true }));
    }
    work.push(syncOverlays(sd));
    flush();
    await Promise.all(work);
    flush();
    const target = sd.target ? el(sd.target) : null;
    if (sd.target && !target) warn('step', i + 1, 'of', tourDef.id, 'highlights a missing element:', sd.target);
    return target;
  }

  // Scroll the page so the whole stage (bar and phone) is in view.
  function phoneIntoView() {
    const stage = document.getElementById('stage');
    if (!stage || !phone) return;
    const r = phone.getBoundingClientRect();
    if (r.top >= 0 && r.bottom <= innerHeight + 1) return;
    const sr = stage.getBoundingClientRect();
    const top = (sr.height <= innerHeight + 4 ? sr.top : r.top) + scrollY;
    window.scrollTo(0, Math.max(0, Math.round(top)));
  }

  function scrollParentIn(node) {
    for (let p = node.parentElement; p && p !== phoneScreen; p = p.parentElement) {
      const cs = getComputedStyle(p);
      if (/(auto|scroll)/.test(cs.overflowY) && p.scrollHeight > p.clientHeight + 1) return p;
    }
    return null;
  }
  function hScrollParentIn(node) {
    for (let p = node.parentElement; p && p !== phoneScreen; p = p.parentElement) {
      const cs = getComputedStyle(p);
      if (/(auto|scroll)/.test(cs.overflowX) && p.scrollWidth > p.clientWidth + 1) return p;
    }
    return null;
  }
  // Scroll only inside the phone, never the page (Bug 5).
  function scrollInPhone(node, where) {
    const box = scrollParentIn(node);
    if (box) {
      const br = box.getBoundingClientRect(), nr = node.getBoundingClientRect();
      const top = nr.top - br.top + box.scrollTop;
      let want = top - (box.clientHeight - nr.height) / 2;
      if (where === 'top') want = top - 12;
      box.scrollTop = Math.max(0, Math.round(want));
    }
    const hbox = hScrollParentIn(node);
    if (hbox) {
      const br = hbox.getBoundingClientRect(), nr = node.getBoundingClientRect();
      const left = nr.left - br.left + hbox.scrollLeft;
      hbox.scrollLeft = Math.max(0, Math.round(left - (hbox.clientWidth - nr.width) / 2));
    }
    return !!box;
  }

  // How tall the tour dialog will be for this step, measured on a hidden copy.
  function measureDialog(i) {
    if (!tg || !tg.dialog) return 240;
    const clone = tg.dialog.cloneNode(true);
    clone.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));
    clone.removeAttribute('id');
    const st = run.steps[i];
    const t = clone.querySelector('.tg-dialog-title');
    const b = clone.querySelector('.tg-dialog-body');
    if (t) t.innerHTML = st.title;
    if (b) b.innerHTML = st.content;
    Object.assign(clone.style, { display: 'block', visibility: 'hidden', position: 'absolute', top: '0', left: '0', transition: 'none' });
    clone.classList.remove('tg-dialog-loading');
    document.body.appendChild(clone);
    const h = clone.offsetHeight;
    clone.remove();
    return h || 240;
  }

  // Wide screens put the dialog beside the phone. Narrow screens put it above or below the target.
  function sideRoom() {
    if (!phone) return false;
    const r = phone.getBoundingClientRect();
    const need = Math.min(340, innerWidth - 32) + 34;
    return r.left >= need || innerWidth - r.right >= need;
  }

  // Decide where the tour dialog goes for this step, and scroll the phone screen to suit it.
  function placeForStep(i, target) {
    const sd = stepAt(i);
    if (sd.dialogAnchor) {
      const a = el(sd.dialogAnchor);
      if (target) scrollInPhone(target);
      return a ? { mode: 'element', el: a } : { mode: null };
    }
    if (!target) return { mode: null };
    scrollInPhone(target);
    if (sideRoom()) return { mode: 'side' };
    const need = measureDialog(i) + 30;
    const fits = () => {
      const r = target.getBoundingClientRect();
      return Math.max(r.top, innerHeight - r.bottom) >= need;
    };
    if (fits()) return { mode: null };
    if (scrollInPhone(target, 'top') && fits()) return { mode: null };
    // A tall target on a small screen: the dialog sits at the bottom of the phone and covers
    // the lower part of the target, so the top of it stays in view (Bug 3).
    scrollInPhone(target, 'top');
    return { mode: 'bottom' };
  }

  function ensureAnchor() {
    if (anchor) return anchor;
    anchor = document.createElement('div');
    anchor.className = 'tour-anchor';
    anchor.setAttribute('aria-hidden', 'true');
    document.body.appendChild(anchor);
    return anchor;
  }

  // The element the tour dialog is placed against, for a placement from placeForStep().
  // "side": an invisible strip as wide as the phone and very tall, centred on the target's
  // middle. The library's automatic placement can then only fit the dialog to the left or
  // right of the phone, level with the target, so the phone stays in full view.
  // "bottom": an invisible thin strip along the bottom of the phone screen.
  // "element": a named element in the phone. No mode: the target itself.
  function anchorFor(placement, target) {
    const mode = placement && placement.mode;
    if (mode === 'side' && target && phone) {
      const r = target.getBoundingClientRect();
      const pr = phone.getBoundingClientRect();
      const h = innerHeight * 4;
      const a = ensureAnchor();
      Object.assign(a.style, {
        left: (pr.left + scrollX) + 'px', width: pr.width + 'px',
        top: (r.top + r.height / 2 + scrollY - h / 2) + 'px', height: h + 'px'
      });
      return a;
    }
    if (mode === 'bottom' && viewport) {
      const r = viewport.getBoundingClientRect();
      const a = ensureAnchor();
      Object.assign(a.style, {
        left: (r.left + scrollX) + 'px', width: r.width + 'px',
        top: (Math.min(r.bottom, innerHeight) - 2 + scrollY) + 'px', height: '2px'
      });
      return a;
    }
    if (mode === 'element' && placement.el && placement.el.isConnected) return placement.el;
    return undefined;
  }
  function syncAnchor() {
    if (!run || !tg) return;
    const step = tg.tourSteps && tg.tourSteps[tg.activeStep];
    if (!step) return;
    const target = step.target && step.target !== document.body ? step.target : null;
    step.dialogTarget = anchorFor(run.placement, target);
  }

  function markPrimary(sd) {
    if (lastPrimary) lastPrimary.classList.remove('rt-tap');
    lastPrimary = null;
    if (!sd || !sd.tap) return;
    const node = tapElement(sd);
    if (node) { node.classList.add('rt-tap'); lastPrimary = node; }
  }

  async function enterStep(i, next) {
    const r = run;
    if (!r) return;
    const sd = r.tour.steps[i];
    const goingBack = r.index >= 0 && i < r.index;
    phoneIntoView();
    const target = await prepareStep(r.tour, i, {
      dir: goingBack ? 'back' : undefined,
      restore: goingBack && r.snaps[i] ? r.snaps[i] : null
    });
    if (run !== r) return;
    r.snaps[i] = snapshot();
    // Bug 1: point the step at the live element every time, never a node from an old render.
    next.target = target || null;
    // Bug 2 and Bug 3: resolve the dialog's anchor here, after the screen exists.
    r.placement = placeForStep(i, target);
    next.dialogTarget = anchorFor(r.placement, target);
    r.index = i;
    r.tapped = false;
    saveProgress(r.tour.id, i, false);
    writeHash(r.tour.id, i);
    updateStageBar();
  }

  function afterEnterStep(i) {
    if (!run || !tg) return;
    markPrimary(stepAt(i));
    fixDialogA11y();
    // Keyboard and screen reader users land on Next, so Enter goes on and Tab reaches Back and close.
    const nb = document.getElementById('tg-dialog-next-btn');
    if (nb) nb.focus({ preventScroll: true });
  }

  function leaveStep(i, next) {
    if (!run) return;
    const forward = next && typeof next._gi === 'number' && next._gi > i;
    const sd = stepAt(i);
    markPrimary(null);
    if (forward) {
      if (!run.tapped) performTap(sd);
      runThen(sd);
    }
    run.tapped = false;
  }

  function fixDialogA11y() {
    if (!tg || !tg.dialog) return;
    const d = tg.dialog;
    d.setAttribute('role', 'dialog');
    d.setAttribute('aria-live', 'polite');
    d.setAttribute('aria-labelledby', 'tg-dialog-title');
    d.setAttribute('aria-describedby', 'tg-dialog-body');
    // Bug 13: the close icon is a div. Make it a reachable, labelled button.
    const cb = document.getElementById('tg-dialog-close-btn');
    if (cb && cb.getAttribute('role') !== 'button') {
      cb.setAttribute('role', 'button');
      cb.tabIndex = 0;
      cb.setAttribute('aria-label', 'Close the tour');
      cb.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); stopTour(); }
      });
    }
    const pb = document.getElementById('tg-dialog-prev-btn');
    if (pb) pb.setAttribute('aria-label', 'Previous step');
  }

  function titleHtml(sd) {
    let badge = '';
    if (sd.optional) badge += '<span class="tour-badge">Optional</span>';
    if (sd.expert) badge += '<span class="tour-badge">Expert mode</span>';
    return '<span class="tour-title-text">' + esc(sd.title) + '</span>' + badge;
  }
  function bodyHtml(tourDef, sd, i) {
    let html = '<div class="tour-body">' + (sd.body || '') + '</div>';
    if (sd.tap) html += '<p class="tour-hint">Tap it on the phone, or press Next.</p>';
    else if (sd.interactive) html += '<p class="tour-hint">Try it on the phone, then press Next.</p>';
    if (i === tourDef.steps.length - 1) {
      const nextTours = listOf(tourDef.next).filter((id) => tours[id]);
      if (nextTours.length) {
        html += '<div class="tour-next"><p>When you’re ready:</p>' + nextTours.map((id) =>
          '<button type="button" class="tour-next-btn" data-start="' + esc(id) + '">' + esc(tours[id].title) + '</button>').join('') + '</div>';
      }
    }
    return html;
  }

  // Fresh library steps for every start, so no stale element is carried over (Bug 1).
  // Targets start as null (the library then uses <body>) and are set in beforeEnter.
  function buildLibrarySteps(tourDef) {
    return tourDef.steps.map((sd, i) => ({
      _gi: i,
      title: titleHtml(sd),
      content: bodyHtml(tourDef, sd, i),
      target: null,
      // Bug 7: hooks must never reject, or the tour freezes.
      beforeEnter: (cur, next) => enterStep(i, next).catch((e) => warn('step enter failed', e)),
      afterEnter: () => { try { afterEnterStep(i); } catch (e) { warn(e); } },
      beforeLeave: (cur, next) => { try { leaveStep(i, next); } catch (e) { warn(e); } }
    }));
  }

  function recoverClient() {
    if (!tg) return;
    tg._promiseWaiting = false;
    if (tg.dialog) tg.dialog.classList.remove('tg-dialog-loading');
  }

  // Close the tour, waiting for a running hook first (Bug 7, Bug 8).
  function safeExit() {
    return new Promise((resolve) => {
      let tries = 0;
      (function attempt() {
        if (!tg || !tg.isVisible) { resolve(); return; }
        if (tg._promiseWaiting && tries++ < 80) { setTimeout(attempt, 50); return; }
        if (tg._promiseWaiting) recoverClient();
        tg.exit().then(() => resolve(), () => { recoverClient(); resolve(); });
      })();
    });
  }

  let starting = null;
  async function startTour(id, stepIndex) {
    const tourDef = tours[id];
    if (!tourDef) { warn('no tour called', id); return false; }
    if (!tg) {
      setStageStatus('The step-by-step tour could not load. You can still tap around the phone.');
      return false;
    }
    if (starting) await starting.catch(noop);
    starting = (async () => {
      await safeExit();
      await delay(0);
      closeAll(true);
      resetState();
      const i = clamp(Number(stepIndex) || 0, 0, tourDef.steps.length - 1);
      run = { tour: tourDef, steps: null, index: -1, snaps: [], tapped: false, finished: false, placement: null };
      run.steps = buildLibrarySteps(tourDef);
      tg.options.steps = run.steps;
      tg.options.dialogAnimate = !motionOff();
      tg.activeStep = i;
      phoneIntoView();
      updateStageBar();
      try {
        await tg.start();
      } catch (e) {
        warn('tour start failed', e);
        recoverClient();
      }
    })();
    try { await starting; } finally { starting = null; }
    return true;
  }

  function stopTour() { return safeExit(); }

  function advance() {
    if (!tg || !tg.isVisible || tg._promiseWaiting) return;
    // Bug 9: on the last step this promise never settles, so it is never awaited.
    tg.nextStep().catch(noop);
  }

  // Taps inside the phone while a tour is open. Capture phase, so it runs before the mock.
  function onTourClick(e) {
    if (synthetic || !inTour()) return;
    const sd = currentStepDef();
    if (!sd) return;
    if (tg._promiseWaiting) { e.preventDefault(); e.stopPropagation(); return; }
    const hl = sd.target ? el(sd.target) : null;
    const tapEl = tapElement(sd);
    const inTap = tapEl && tapEl.contains(e.target);
    const inHl = hl && hl.contains(e.target);
    if (sd.tap && inTap) {
      // Let the mock react as the real app would, then move on.
      run.tapped = true;
      const ev = e;
      setTimeout(() => {
        if (!run) return;
        if (lastHandledEvent !== ev) run.tapped = false; // nothing reacted: Next will do the tap
        advance();
      }, 0);
      return;
    }
    // An interactive step lets the reader try the highlighted controls; Next goes on.
    if (sd.interactive && inHl) return;
    e.preventDefault();
    e.stopPropagation();
    if (inHl || inTap) advance(); // the highlighted part does the same as Next
  }

  function onInnerScroll() {
    if (!inTour()) return;
    if (onInnerScroll.pending) return;
    onInnerScroll.pending = true;
    // Bug 4: the library only follows window scrolling.
    requestAnimationFrame(() => { onInnerScroll.pending = false; reposition(); });
  }

  function reposition() {
    if (!tg || !tg.isVisible || tg._promiseWaiting || !run) return;
    const sd = currentStepDef();
    const step = tg.tourSteps[tg.activeStep];
    if (sd && step && sd.target) {
      const live = el(sd.target);
      if (live && step.target !== live) {
        if (step.target && step.target.classList) step.target.classList.remove('tg-active-element');
        step.target = live;
        live.classList.add('tg-active-element');
      }
    }
    if (sd && sd.tap) {
      const node = tapElement(sd);
      if (node && node !== lastPrimary) markPrimary(sd);
    }
    syncAnchor();
    tg.updatePositions().catch(noop);
  }

  // After a re-render during a tour, keep the highlight on the live element.
  function tourAfterRender() {
    if (!inTour() || tg._promiseWaiting) return;
    if (tourAfterRender.pending) return;
    tourAfterRender.pending = true;
    requestAnimationFrame(() => { tourAfterRender.pending = false; reposition(); });
  }

  function createClient() {
    if (!window.tourguide || !window.tourguide.TourGuideClient) return null;
    const client = new window.tourguide.TourGuideClient({
      steps: [],
      debug: false,
      completeOnFinish: false,     // keeps the library away from localStorage (Bug 14)
      rememberStep: false,
      exitOnClickOutside: false,   // only the dialog and the highlight take clicks (see Bug 12 in guide.css)
      exitOnEscape: false,         // a tour moves only with Back and Next; Stop tour leaves it
      keyboardControls: true,
      showStepDots: false,         // long tours: the "3/25" count and the bar are clearer
      showStepProgress: true,
      progressBar: '#5b7bd6',      // any value turns the bar on; guide.css colours it
      dialogClass: 'guide-tour',   // one class name only (Bug 11)
      backdropClass: 'guide-backdrop',
      backdropColor: 'rgba(6,8,14,0.62)',
      targetPadding: 10,
      dialogMaxWidth: 340,
      dialogAnimate: !motionOff(),
      autoScroll: false,           // Bug 5: the engine scrolls inside the phone instead
      autoScrollSmooth: false,
      closeButton: false,
      nextLabel: 'Next',
      prevLabel: 'Back',
      finishLabel: 'Done'
    });
    client.onFinish(() => {
      try {
        if (!run) return;
        const sd = stepAt(run.tour.steps.length - 1);
        markPrimary(null);
        if (!run.tapped) performTap(sd);
        runThen(sd);
        run.finished = true;
        saveProgress(run.tour.id, 0, true);
      } catch (e) { warn(e); }
    });
    client.onBeforeExit(() => { exiting = run; });
    client.onAfterExit(() => {
      try {
        const r = exiting;
        exiting = null;
        document.querySelectorAll('.tg-active-element').forEach((n) => n.classList.remove('tg-active-element'));
        markPrimary(null);
        if (run && run === r) {
          if (r.finished) setStageNote('Finished: ' + r.tour.title + '. Pick another tour, or keep tapping around.');
          run = null;
          clearHash();
          updateStageBar();
          renderPicker();
        }
      } catch (e) { warn(e); }
    });
    if (client.dialog) {
      client.dialog.setAttribute('role', 'dialog');
      // Bug 7 guard: a second click on Next or Back while a step is loading would reject.
      client.dialog.addEventListener('click', (e) => {
        const start = e.target.closest('[data-start]');
        if (start) {
          e.preventDefault();
          startTour(start.getAttribute('data-start'), 0);
          return;
        }
        const cp = e.target.closest('[data-copy]');
        if (cp && !client._promiseWaiting) {
          e.preventDefault();
          const label = cp.getAttribute('data-label') || cp.textContent;
          cp.setAttribute('data-label', label);
          copyText(cp.getAttribute('data-copy')).then((ok) => {
            cp.textContent = ok ? 'Copied' : 'Select and copy it by hand';
            setTimeout(() => { cp.textContent = label; }, 1800);
          });
          return;
        }
        if (client._promiseWaiting && e.target.closest('button, #tg-dialog-close-btn')) {
          e.preventDefault();
          e.stopPropagation();
        }
      }, true);
    }
    return client;
  }

  // Keys while the tour is open.
  window.addEventListener('keydown', (e) => {
    if (!tg || !tg.isVisible) return;
    // Bug 8: Escape while a hook runs would throw. Hold it and close once the hook is done.
    if (e.key === 'Escape' && tg._promiseWaiting) {
      e.stopImmediatePropagation();
      e.preventDefault();
      stopTour();
      return;
    }
    // Arrow keys belong to sliders and text fields when they have focus.
    if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && e.target && e.target.closest &&
        e.target.closest('input, textarea, select, [role="slider"], [contenteditable="true"]')) {
      e.stopImmediatePropagation();
    }
  }, true);

  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    if (!inTour() || !run) return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (!inTour() || tg._promiseWaiting) return;
      const step = tg.tourSteps[tg.activeStep];
      const target = step && step.target !== document.body ? step.target : null;
      run.placement = placeForStep(tg.activeStep, target);
      reposition();
    }, 120);
  });

  if (reduceMotionQuery && reduceMotionQuery.addEventListener) {
    reduceMotionQuery.addEventListener('change', () => { if (tg) tg.options.dialogAnimate = !motionOff(); });
  }

  Object.assign(G, {
    startTour, stopTour,
    restartTour: () => (run ? startTour(run.tour.id, 0) : Promise.resolve(false)),
    activeTour: () => (inTour() ? { id: run.tour.id, step: tg.activeStep, steps: run.tour.steps.length } : null),
    client: () => tg
  });

  // Copy text to the clipboard. Resolves to true when it worked.
  function copyText(text) {
    const fallback = () => {
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        Object.assign(ta.style, { position: 'fixed', top: '0', left: '0', opacity: '0' });
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        ta.remove();
        return ok;
      } catch (e) { return false; }
    };
    try {
      if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(String(text)).then(() => true, () => fallback());
      }
    } catch (e) { /* use the fallback */ }
    return Promise.resolve(fallback());
  }
  G.copy = copyText;

  // ---------------------------------------------------------------------------------------
  // Progress, deep links, the tour picker and the stage bar
  // ---------------------------------------------------------------------------------------

  let progress = (storage.read(PROGRESS_KEY) || {});
  if (!progress || typeof progress !== 'object') progress = {};

  function saveProgress(id, step, done) {
    const p = progress[id] || {};
    p.step = step;
    if (done) p.done = true;
    p.at = Date.now();
    progress[id] = p;
    storage.write(PROGRESS_KEY, progress);
  }

  let ignoreHash = '';
  function writeHash(id, i) {
    const h = '#tour=' + encodeURIComponent(id) + '&step=' + (i + 1);
    if (location.hash === h) return;
    try {
      history.replaceState(history.state, '', h);
    } catch (e) {
      ignoreHash = h;
      try { location.replace(h); } catch (e2) { /* the address just stays as it is */ }
    }
  }
  function clearHash() {
    if (!location.hash) return;
    try {
      history.replaceState(history.state, '', location.pathname + location.search);
    } catch (e) {
      ignoreHash = '#';
      try { location.replace('#'); } catch (e2) { /* keep the old address */ }
    }
  }
  function readHash() {
    const h = location.hash.replace(/^#/, '');
    if (!h) return null;
    let p;
    try { p = new URLSearchParams(h); } catch (e) { return null; }
    const id = p.get('tour');
    if (!id) return null;
    const step = parseInt(p.get('step'), 10);
    return { id: id, step: isNaN(step) ? 0 : step - 1 };
  }
  function followHash() {
    const want = readHash();
    if (!want || !tours[want.id]) return;
    const active = G.activeTour();
    if (active && active.id === want.id && active.step === want.step) return;
    if (active && active.id === want.id && tg && !tg._promiseWaiting) {
      tg.visitStep(clamp(want.step, 0, run.tour.steps.length - 1)).catch(noop);
      return;
    }
    startTour(want.id, want.step);
  }
  window.addEventListener('hashchange', () => {
    if (ignoreHash && location.hash === ignoreHash) { ignoreHash = ''; return; }
    followHash();
  });

  function stepsLabel(n) { return n + (n === 1 ? ' step' : ' steps'); }

  function cardHtml(t) {
    const n = t.steps.length;
    const p = progress[t.id] || {};
    const resumable = p.step > 0 && p.step < n;
    let status = '';
    if (resumable) status = '<span class="tour-card-status">Stopped at step ' + (p.step + 1) + '</span>';
    else if (p.done) status = '<span class="tour-card-status is-done">' + G.icon('check', { size: 16 }) + 'Done</span>';
    const startLabel = resumable ? 'Resume' : (p.done ? 'Start again' : 'Start');
    return '<article class="tour-card" data-tour="' + esc(t.id) + '">' +
      '<h4 class="tour-card-title" id="tour-title-' + esc(t.id) + '">' + esc(t.title) + '</h4>' +
      '<p class="tour-card-summary">' + esc(t.summary || '') + '</p>' +
      '<p class="tour-card-meta"><span>' + stepsLabel(n) + '</span>' + status + '</p>' +
      '<div class="tour-card-actions">' +
        '<button type="button" class="btn btn-primary" data-start="' + esc(t.id) + '" data-step="' + (resumable ? p.step : 0) +
          '" aria-describedby="tour-title-' + esc(t.id) + '">' + startLabel + '</button>' +
        (resumable ? '<button type="button" class="btn btn-quiet" data-start="' + esc(t.id) + '" data-step="0" aria-describedby="tour-title-' + esc(t.id) + '">Start over</button>' : '') +
      '</div>' +
    '</article>';
  }

  function renderPicker() {
    const root = document.getElementById('tours');
    if (!root) return;
    let total = 0;
    GROUPS.forEach((g) => {
      const box = root.querySelector('[data-group="' + g + '"]');
      if (!box) return;
      const list = Object.keys(tours).map((k) => tours[k]).filter((t) => t.group === g)
        .sort((a, b) => a.order - b.order || String(a.title).localeCompare(String(b.title)));
      total += list.length;
      box.innerHTML = list.map(cardHtml).join('');
      const wrap = box.closest('[data-group-wrap]');
      if (wrap) wrap.hidden = list.length === 0;
    });
    ['use'].forEach((g) => {
      const sec = root.querySelector('[data-section="' + g + '"]');
      if (!sec) return;
      sec.hidden = [].every.call(sec.querySelectorAll('[data-group-wrap]'), (w) => w.hidden);
    });
    root.querySelectorAll('.picker-note [data-start]').forEach((b) => { b.disabled = !tours[b.getAttribute('data-start')]; });
    const empty = document.getElementById('tours-empty');
    if (empty) empty.hidden = total > 0;
  }

  function setStageStatus(text) {
    const s = document.getElementById('stage-status');
    if (s) s.textContent = text;
  }
  let stageNote = '';
  function setStageNote(text) { stageNote = text; }

  function updateStageBar() {
    const active = run && run.tour;
    const restart = document.getElementById('stage-restart');
    const stop = document.getElementById('stage-stop');
    const reset = document.getElementById('stage-reset');
    const stage = document.getElementById('stage');
    if (restart) restart.hidden = !active;
    if (stop) stop.hidden = !active;
    if (reset) reset.hidden = !!active;
    if (stage) stage.classList.toggle('is-touring', !!active);
    if (active) {
      const i = Math.max(0, run.index);
      const s = document.getElementById('stage-status');
      if (s) {
        s.innerHTML = '<span class="stage-tour">' + esc(run.tour.title) + '</span>' +
          '<span class="stage-step">Step ' + (i + 1) + ' of ' + run.tour.steps.length + '</span>';
      }
    } else {
      setStageStatus(stageNote || 'Free explore: tap around the phone, or pick a tour.');
      stageNote = '';
    }
  }

  function resetPhone() {
    const after = () => {
      closeAll(true);
      clearTimers(null);
      resetState();
      backStack.length = 0;
      home();
      toast('The pretend phone is back to the start.');
    };
    if (inTour()) stopTour().then(() => setTimeout(after, 0));
    else after();
  }

  function wirePage() {
    document.addEventListener('click', (e) => {
      const start = e.target.closest('#tours [data-start], .picker-note [data-start]');
      if (start) {
        e.preventDefault();
        // One tour at a time: it's left only with Stop tour, never by picking another.
        if (inTour()) {
          toast('Finish this tour with Back and Next, or tap Stop tour first.');
          return;
        }
        startTour(start.getAttribute('data-start'), Number(start.getAttribute('data-step')) || 0);
      }
    });
    const restart = document.getElementById('stage-restart');
    if (restart) restart.addEventListener('click', () => G.restartTour());
    const stop = document.getElementById('stage-stop');
    if (stop) stop.addEventListener('click', () => stopTour());
    const reset = document.getElementById('stage-reset');
    if (reset) reset.addEventListener('click', resetPhone);
    const toList = document.getElementById('stage-tours');
    if (toList) {
      toList.addEventListener('click', (e) => {
        const target = document.getElementById('tours');
        if (!target) return;
        e.preventDefault();
        target.scrollIntoView({ behavior: motionOff() ? 'auto' : 'smooth', block: 'start' });
        const h = target.querySelector('h2');
        if (h) { h.setAttribute('tabindex', '-1'); h.focus({ preventScroll: true }); }
      });
    }
    // Fill <span data-icon="name"></span> placeholders in the page with icons.
    document.querySelectorAll('[data-icon]').forEach((n) => {
      if (!n.firstChild) n.innerHTML = G.icon(n.getAttribute('data-icon'), { size: Number(n.getAttribute('data-size')) || 20 });
    });
  }

  // ---------------------------------------------------------------------------------------
  // Checks for screen and tour authors (open the page with ?debug=1 to see warnings)
  // ---------------------------------------------------------------------------------------

  // Walks a tour without the tour dialog and reports, for every step, whether its screen,
  // overlays and elements exist. Returns a promise of an array of rows.
  async function walk(tourId) {
    const t = tours[tourId];
    if (!t) return [{ error: 'no tour called ' + tourId }];
    if (inTour()) await stopTour();
    resetState();
    closeAll(true);
    const rows = [];
    for (let i = 0; i < t.steps.length; i++) {
      const sd = t.steps[i];
      const target = await prepareStep(t, i, { dir: 'none' });
      const tapNode = typeof sd.tap === 'string' ? el(sd.tap) : null;
      const r = target ? target.getBoundingClientRect() : null;
      rows.push({
        step: i + 1,
        title: sd.title,
        screen: sd.screen || '',
        screenOk: !sd.screen || !!screens[sd.screen],
        overlays: listOf(sd.overlay).join(','),
        overlaysOk: listOf(sd.overlay).every((id) => !!screens[id] && !!findOverlay(id)),
        target: sd.target || '',
        targetOk: !sd.target || !!target,
        targetVisible: !!(r && r.width > 0 && r.height > 0),
        tap: typeof sd.tap === 'string' ? sd.tap : (sd.tap ? 'target' : ''),
        tapOk: typeof sd.tap !== 'string' || !!tapNode
      });
    }
    return rows;
  }
  function lint() {
    const problems = [];
    Object.keys(tours).forEach((id) => {
      const t = tours[id];
      const stateKeys = Object.keys(t.state || {});
      t.steps.forEach((sd, i) => {
        const where = id + ' step ' + (i + 1);
        if (sd.screen && !screens[sd.screen]) problems.push(where + ': unknown screen ' + sd.screen);
        listOf(sd.overlay).forEach((o) => { if (!screens[o] || screens[o].kind === 'screen') problems.push(where + ': unknown dialog or sheet ' + o); });
        Object.keys(sd.state || {}).concat(i === 0 ? stateKeys : []).forEach((k) => { if (!(k in defaults)) problems.push(where + ': state key without a default ' + k); });
        if (!sd.title) problems.push(where + ': no title');
        if (sd.target && typeof sd.target !== 'string') problems.push(where + ': target must be a data-tg name');
      });
      listOf(t.next).forEach((n) => { if (!tours[n]) problems.push(id + ': next tour not found ' + n); });
    });
    return problems;
  }
  G.dev = { walk, lint, screens: () => Object.keys(screens), tours: () => Object.keys(tours), defaults: () => Object.assign({}, defaults) };

  // ---------------------------------------------------------------------------------------
  // Demo (only with ?demo=1): one screen for each look and a three-step tour, to test the engine.
  // ---------------------------------------------------------------------------------------

  if (DEMO) {
    defineState({ 'demo.note': '' });

    screen({
      id: 'demo.morphe',
      app: 'morphe',
      title: 'Demo: Morphe-style screen',
      back: 'android.home',
      render(ctx) {
        const s = ctx.s;
        let rows = '';
        for (let n = 1; n <= 8; n++) {
          rows += '<div class="demo-row"><span>' + ctx.icon('extension', { size: 20 }) + '</span><span class="demo-grow">Filler row ' + n + '</span></div>';
        }
        return '<div class="demo-page">' +
          '<header class="demo-head"><div><h2 class="demo-title">Sources</h2><p class="demo-sub">Demo screen for testing</p></div>' +
            '<button type="button" class="demo-round" data-tg="demo.morphe.addButton" data-toast="The add button works." aria-label="Add a source">' + ctx.icon('add') + '</button></header>' +
          '<div class="demo-card" data-tg="demo.morphe.card">' +
            '<div class="demo-row"><span class="demo-grow"><strong id="demo-pre">Pre-release patches</strong><small>Receives early access to new experimental patch versions</small></span>' +
              ctx.ui.switch({ bind: 'morphe.prerelease', action: 'askPrerelease', labelledby: 'demo-pre', tg: 'demo.morphe.prereleaseSwitch', thumbIcons: true }) + '</div>' +
            (s['morphe.prerelease'] ? '<div class="demo-row"><span class="demo-grow"><strong id="demo-exp">Experimental app versions</strong><small>Shows and patches experimental app targets</small></span>' +
              ctx.ui.switch({ bind: 'morphe.experimental', labelledby: 'demo-exp', tg: 'demo.morphe.experimentalSwitch', thumbIcons: true }) + '</div>' : '') +
          '</div>' + rows +
          '<button type="button" class="demo-button" data-go="demo.bitchord" data-tg="demo.morphe.next">Open the Bitchord+ demo</button>' +
        '</div>';
      },
      actions: {
        askPrerelease(ctx) {
          if (ctx.get('morphe.prerelease')) ctx.set('morphe.prerelease', false);
          else ctx.open('demo.dialog');
        }
      }
    });

    screen({
      id: 'demo.dialog',
      app: 'morphe',
      kind: 'full',
      title: 'Demo: Enable pre-release patches?',
      render(ctx) {
        return '<div class="demo-full">' +
          '<h2 class="demo-title">Enable pre-release patches?</h2>' +
          '<p>Pre-release builds are still in development. Patches may fail or break the app, and features can change without warning</p>' +
          '<button type="button" class="demo-button" data-tg="demo.dialog.enableButton" data-action="enable">Enable</button>' +
          '<button type="button" class="demo-button demo-quiet" data-close>Cancel</button>' +
        '</div>';
      },
      actions: {
        enable(ctx) { ctx.set('morphe.prerelease', true); ctx.close('demo.dialog'); }
      }
    });

    screen({
      id: 'demo.bitchord',
      app: 'bitchord',
      title: 'Demo: BitChord-style screen',
      back: 'demo.morphe',
      render(ctx) {
        const s = ctx.s;
        let rows = '';
        for (let n = 1; n <= 10; n++) rows += '<div class="demo-row"><span class="demo-grow">Setting ' + n + '</span>' + ctx.icon('chevronRight', { size: 20 }) + '</div>';
        return '<div class="demo-page">' +
          '<h2 class="demo-title demo-big">Patches &amp; Tweaks</h2>' +
          '<div class="demo-card">' + rows + '</div>' +
          '<p class="demo-label">SLEEP TIMER</p>' +
          '<div class="demo-card" data-tg="demo.bitchord.sleepCard">' +
            '<div class="demo-row"><span class="demo-grow"><strong id="demo-fade">Fade out at the sleep timer</strong><small>' + (s['tweaks.sleep.fade'] ? 'On' : 'Off') + '</small></span>' +
              ctx.ui.switch({ bind: 'tweaks.sleep.fade', labelledby: 'demo-fade', tg: 'demo.bitchord.fadeToggle' }) + '</div>' +
            '<div class="demo-row demo-col" data-tg="demo.bitchord.fadeLength"><span class="demo-line"><strong id="demo-len">Fade length</strong><span>' + s['tweaks.sleep.length'] + ' s</span></span>' +
              ctx.ui.slider({ bind: 'tweaks.sleep.length', min: 3, max: 60, labelledby: 'demo-len', text: (v) => v + ' seconds' }) + '</div>' +
          '</div>' +
          '<button type="button" class="demo-button" data-open="demo.sheet">Open a sheet</button>' +
          '<button type="button" class="demo-button demo-quiet" data-open="demo.system">Open a system dialog</button>' +
        '</div>';
      }
    });

    screen({
      id: 'demo.sheet',
      app: 'bitchord',
      kind: 'sheet',
      title: 'Demo: sheet',
      render(ctx) {
        return '<div class="demo-sheet"><div class="a-sheet-handle" aria-hidden="true"></div><h2 class="demo-title">Sleep timer</h2>' +
          ['15 minutes', '30 minutes', '1 hour', 'After this song'].map((t) =>
            '<button type="button" class="demo-row demo-option" data-tg="demo.sheet.option" data-key="' + esc(t) + '" data-close data-toast="Sleep timer: ' + esc(t) + '">' + esc(t) + '</button>').join('') +
        '</div>';
      }
    });

    screen({
      id: 'demo.system',
      app: 'android',
      kind: 'dialog',
      title: 'Demo: system dialog',
      render() {
        return '<div class="a-dialog"><h2 class="a-dialog-title">Do you want to install this app?</h2>' +
          '<p class="a-dialog-text">This is a demo of a system dialog.</p>' +
          '<div class="a-dialog-actions"><button type="button" class="a-btn-text" data-close>Cancel</button>' +
          '<button type="button" class="a-btn-text" data-close data-toast="Installed (demo)." data-tg="demo.system.install">Install</button></div></div>';
      }
    });

    tour({
      id: 'demo',
      group: 'start',
      order: 0,
      title: 'Demo tour (testing only)',
      summary: 'Three steps that test taps, a dialog, a second app and inner scrolling.',
      state: { 'morphe.prerelease': false },
      steps: [
        {
          title: 'Open Morphe',
          body: 'Tap <strong>Morphe</strong> on the home screen.',
          screen: 'android.home',
          target: 'android.home.morphe',
          tap: true
        },
        {
          title: 'Confirm with Enable',
          body: 'Morphe asks first. Tap <strong>Enable</strong>.',
          screen: 'demo.morphe',
          overlay: 'demo.dialog',
          target: 'demo.dialog.enableButton',
          tap: true,
          state: { 'morphe.prerelease': false }
        },
        {
          title: 'Pick the fade length',
          body: 'This one is far down the page, in the other app. Move <strong>Fade length</strong> to anything from 3 to 60 seconds.',
          screen: 'demo.bitchord',
          target: 'demo.bitchord.fadeLength',
          state: { 'morphe.prerelease': true }
        }
      ]
    });
  }

  // ---------------------------------------------------------------------------------------
  // Start
  // ---------------------------------------------------------------------------------------

  function boot() {
    if (booted) return;
    if (!buildPhone()) return;
    booted = true;
    document.documentElement.classList.add('js');
    wirePage();
    try { tg = createClient(); } catch (e) { warn('tour library failed', e); tg = null; }
    renderPicker();
    go('android.home', null, { dir: 'none', noHistory: true });
    updateStageBar();
    if (!tg) setStageStatus('The step-by-step tour could not load. You can still tap around the phone.');
    if (DEBUG) { const p = lint(); if (p.length) console.warn('[guide] lint:\n' + p.join('\n')); }
    followHash();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else setTimeout(boot, 0);
})();
