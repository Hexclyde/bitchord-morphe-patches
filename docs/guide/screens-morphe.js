/*
 * Bitchord+ install guide: the Morphe Manager screens, and the plain Android screens of the
 * files, install, testing and update tours (browser, Files, the file picker and the installer).
 *
 * The drawings follow Morphe Manager 2.2.0: its labels, layout and dark "Morphe" palette.
 * They are simple illustrations: no logos or brand artwork, only letter tiles and generic
 * icons. On Android 12 and newer the real Morphe takes its colours from the wallpaper.
 * Morphe Manager is made by the Morphe team. BitChord is made by kushagra (kushagrasinghx).
 *
 * Every icon below was drawn for this guide in the style of icons.js (24 x 24, stroke 2).
 */
(function () {
  'use strict';
  const G = window.Guide;
  if (!G || typeof G.screen !== 'function') return;
  const esc = G.esc;

  // ---------------------------------------------------------------------------------------
  // Icons this file needs that icons.js doesn't have
  // ---------------------------------------------------------------------------------------

  const EXTRA_ICONS = {
    doneAll: '<path d="M2.5 12.5l4.2 4.2L16 7.5"/><path d="M11.6 16.3l.4.4 9.5-9.2"/>',
    clearAll: '<path d="M5 7h14"/><path d="M7 12h12"/><path d="M9 17h10"/>',
    recommend: '<circle cx="12" cy="12" r="9"/><path d="M8.5 16.5v-5l2.6-4a1.3 1.3 0 0 1 2.3 1.1l-.6 2.4h3a1 1 0 0 1 1 1.2l-.9 3.6a1 1 0 0 1-1 .7z"/>',
    psychology: '<path d="M9 20.5v-3.2a7 7 0 1 1 9.6-6.2l1.4 2.9h-2v2.5a1.5 1.5 0 0 1-1.5 1.5H14v2.5"/><circle cx="11.5" cy="10" r="2"/>',
    memory: '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/><path d="M9.5 2.5v3.5"/><path d="M14.5 2.5v3.5"/><path d="M9.5 18v3.5"/><path d="M14.5 18v3.5"/><path d="M2.5 9.5H6"/><path d="M2.5 14.5H6"/><path d="M18 9.5h3.5"/><path d="M18 14.5h3.5"/>',
    signalBars: '<path d="M6 19v-4"/><path d="M12 19v-8"/><path d="M18 19V5"/>',
    storage: '<rect x="4" y="4.5" width="16" height="4" rx="1.2"/><rect x="4" y="10" width="16" height="4" rx="1.2"/><rect x="4" y="15.5" width="16" height="4" rx="1.2"/>',
    backup: '<path d="M7 18.5a4.5 4.5 0 0 1-.4-9A6 6 0 0 1 18 9a4.75 4.75 0 0 1-.5 9.5z"/><path d="M12 16v-5.5"/><path d="M9.5 12.8l2.5-2.5 2.5 2.5"/>',
    palette: '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1.1 0 1.7-.7 1.7-1.6 0-1-.8-1.4-.8-2.3 0-.9.8-1.6 1.7-1.6h2a3.9 3.9 0 0 0 3.9-3.9C20.5 7 16.7 3.5 12 3.5z"/><path d="M7.5 12h.01"/><path d="M9.5 8h.01"/><path d="M14.5 8h.01"/>',
    gamepad: '<rect x="2.5" y="7" width="19" height="11" rx="5.5"/><path d="M7.5 10.5v4"/><path d="M5.5 12.5h4"/><path d="M15.5 11.5h.01"/><path d="M18 14h.01"/>',
    article: '<rect x="4.5" y="3.5" width="15" height="17" rx="2"/><path d="M8 8.5h8"/><path d="M8 12h8"/><path d="M8 15.5h5"/>',
    inventory: '<rect x="3.5" y="4.5" width="17" height="4" rx="1"/><path d="M5 8.5V19a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8.5"/><path d="M10 12.5h4"/>',
    menu: '<path d="M4 7h16"/><path d="M4 12h16"/><path d="M4 17h16"/>',
    power: '<path d="M12 3.5v8"/><path d="M7.2 6.3a7.5 7.5 0 1 0 9.6 0"/>',
    arrowDown: '<path d="M12 5v14"/><path d="M6 13l6 6 6-6"/>',
    folderOpen: '<path d="M3.5 18V6.5a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2V10"/><path d="M3.5 18l2.6-6.6a1.5 1.5 0 0 1 1.4-.9h13a1 1 0 0 1 .9 1.4L19 18.4a1.5 1.5 0 0 1-1.4 1.1H5a1.5 1.5 0 0 1-1.5-1.5z"/>',
    zip: '<path d="M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M14 3v5h5"/><path d="M10 4v2"/><path d="M10 8v2"/><path d="M10 12v2"/><rect x="8.5" y="15" width="3" height="3.5" rx=".8"/>'
  };

  function icon(name, opts) {
    const body = EXTRA_ICONS[name];
    if (!body) return G.icon(name, opts);
    opts = opts || {};
    const size = opts.size || 24;
    const a11y = opts.label
      ? ' role="img" aria-label="' + esc(opts.label) + '"'
      : ' aria-hidden="true" focusable="false"';
    return '<svg class="g-icon' + (opts.cls ? ' ' + esc(opts.cls) : '') + '" width="' + size + '" height="' + size +
      '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + (opts.stroke || 2) +
      '" stroke-linecap="round" stroke-linejoin="round"' + a11y + '>' + body + '</svg>';
  }

  // ---------------------------------------------------------------------------------------
  // State this file adds (the shared keys are defined in guide.js)
  // ---------------------------------------------------------------------------------------

  G.defineState({
    'morphe.morpheEnabled': true,          // the pre-installed Morphe Patches source is on
    'morphe.morphePrerelease': false,      // its Pre-release patches switch
    'morphe.sourceName': '',               // custom name of the normal BitChord source after Rename
    'morphe.testingEnabled': true,         // the testing source is on
    'morphe.testingLocal': false,          // the testing source was added from a .mpp file
    'morphe.testingAppBitchord': true,     // BitChord ticked in the testing source's Apps list
    'morphe.localFile': '',                // file picked under Add patch source › Local
    'morphe.pickApps': false,              // "Pick which of its apps to keep…" tick
    'morphe.renameDraft': '',              // text in the Rename dialog
    'morphe.bringNewApps': true,           // Source Apps › Bring new apps
    'morphe.bgProtectionAsked': false,     // Background patching protection was answered
    'morphe.universalOpen': false,         // the Universal patches group is unfolded
    'morphe.selectedOnly': false,          // the patch list shows selected patches only
    'morphe.upatch.changeInstaller': false,
    'morphe.upatch.cloneApp': false,
    'morphe.upatch.disablePlayStore': false,
    'morphe.upatch.overridePinning': false,
    'morphe.upatch.spoofSignature': false,
    'morphe.installing': false,            // the result screen says Installing…
    'morphe.patchingTab': 'logs',          // Expert progress panel: 'logs' or 'games'
    'morphe.set.prereleaseUpdates': false, // Settings › Updates › Pre-release updates
    'morphe.set.meteredUpdates': false,    // Settings › Updates › Mobile data updates
    'morphe.set.customPicker': false,      // Settings › Files › Custom file picker
    'android.browserInstallAllowed': false, // Android lets the browser install apps
    'browser.tabs': ['browser.morpheSite'], // the browser's open tabs, by screen, oldest first
    'browser.morpheDownloaded': false,     // Morphe's APK is in Downloads
    'browser.bitchordDownloaded': false    // BitChord's APK is in Downloads
  });

  // ---------------------------------------------------------------------------------------
  // Facts drawn on the screens
  // ---------------------------------------------------------------------------------------

  const SOURCE_NAME = 'BitChord Morphe Patches';
  const PATCHES_VERSION = '1.1.0-beta.16';
  const MORPHE_VERSION = '2.2.0';
  const APP_VERSION = '1.9';
  const ORANGE = '255,137,68';
  const BITCHORD_APK = 'BitChord-v1.9_arm64-v8a.apk';
  const MORPHE_APK = 'morphe-manager-2.2.0.apk';
  const MPP_FILE = 'bitchord-patches-1.1.0-beta.16.mpp';
  const REPO_LINK = 'github.com/Hexclyde/bitchord-morphe-patches';
  const TESTING_LINK = 'https://raw.githubusercontent.com/Hexclyde/bitchord-morphe-patches/refs/heads/testing/patches-bundle.json';
  const NOT_DRAWN = 'This part of Morphe isn’t drawn in this guide.';

  // The patch names and descriptions exactly as the source shows them (TelegramPatch.kt).
  const WHERE = ' Settings are in Settings, Patches & tweaks.';
  const PATCHES = [
  {
    "id": "videoSwitch",
    "name": "Audio and video switch",
    "desc": "Adds a song and video switch to the top of the player, as in YouTube Music: one tap plays a song's music video, or the song again, in the same place in the queue. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "batterySaver",
    "name": "Battery saver",
    "desc": "Lets the phone's audio chip decode songs, so long listens use less battery. BitChord's effects don't run on songs played this way. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "djMode",
    "name": "DJ mode",
    "desc": "Picks the next song in the queue for a neighbouring key and a close tempo. Keys and tempos are learned as songs play. From Prism by Yigder. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "outputEq",
    "name": "Equaliser per output",
    "desc": "Remembers BitChord's equaliser for each output (speaker, wired, each Bluetooth device) and puts it back when that output is used; shares settings as codes. From Prism by Yigder. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "flacAudio",
    "name": "Flac Downloader Audio",
    "desc": "Optional native adapter for aztecempire's Spotube FLAC Audio source. Search, stream and download through flacdownloader.com, with explicit FLAC/MP3 choices. Starts disabled; enable in Sources or Patches & tweaks. Requested quality is not file verification. No Spotube runtime or account is installed."
  },
  {
    "id": "headphones",
    "name": "Headphone correction",
    "desc": "Flattens your headphones' sound with a measured profile, one per output, from AutoEq's thousands of models or pasted in. From Prism by Yigder. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "bitchordPlus",
    "name": "Install as Bitchord+",
    "desc": "Installs next to your regular BitChord as Bitchord+, or under a name you choose.",
    "def": true,
    "options": 1
  },
  {
    "id": "jamesdsp",
    "name": "JamesDSP",
    "desc": "Hands BitChord's audio to JamesDSP, the open-source effect engine: open it from BitChord, announce the audio session again and let it take over the effects. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "losslessBluetooth",
    "name": "Lossless addons over Bluetooth",
    "desc": "Keeps addons marked lossless in use over Bluetooth, where BitChord normally skips them. Lossless-first, as in Auris by OreoMuncher45. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "loudness",
    "name": "Loudness level",
    "desc": "Sets how loud loudness normalisation plays songs, from -23 to -8 LUFS instead of -14. From Prism by Yigder. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "mixedPlaylists",
    "name": "Mixed-source playlists",
    "desc": "Adds downloaded files, local audio and streamed tracks to device playlists without a YouTube account. Preserves file and addon source metadata across restarts. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "perDownload",
    "name": "Per-download quality",
    "desc": "Asks which quality to download each song in: Standard, High or Lossless. An album is asked about once. From Stash by Rawnald Clark. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "pitch",
    "name": "Pitch control",
    "desc": "Moves the key up or down by up to 12 semitones without changing the speed. From Prism by Yigder. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "microg",
    "name": "Sign in with MicroG",
    "desc": "Offers to sign in to YouTube Music with an account that's already in MicroG RE, instead of Google's sign-in page.",
    "def": true
  },
  {
    "id": "sing",
    "name": "Sing",
    "desc": "Turns the lead vocal down so you can sing over the song. Ported from the vocal remover in Auris by OreoMuncher45, a fork of BitChord. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "sleepFade",
    "name": "Sleep timer fade-out",
    "desc": "Fades the song out before the sleep timer pauses it, and fades the end of the last song with the timer's \"After this song\" option. From Prism by Yigder. Settings are in Settings, Patches & tweaks."
  },
  {
    "id": "streamseekPreview",
    "name": "Streamseek previews",
    "desc": "Preview an explicitly selected Soulseek MP3 or FLAC through Media3 while it transfers. Keep download reuses buffered bytes. Compare an excerpt with a known local reference and export transparent evidence. Optional; enable in Patches & tweaks. No automatic Soulseek playback or Mornye integration."
  },
  {
    "id": "soulseek",
    "name": "Unofficial Soulseek audio",
    "desc": "Adds player-integrated Downloads on BitChord 1.9, addon/Soulseek source choices, batch review, selected-file public Soulseek sharing, and verified Telegram archiving/restore. Telegram copies require the optional Telegram patch and your private main channel. Debrid services use your API keys. Live Soulseek playback stays disabled. Settings are in Patches & tweaks. Android 8 or newer."
  },
  {
    "id": "telegram",
    "name": "Unofficial Telegram music",
    "desc": "Search and play audio from your own Telegram channels, right on your phone. Android 8 or newer.",
    "def": true,
    "options": 2
  }
];
  const byName = (a, b) => a.name.localeCompare(b.name);
  // Morphe lists one alphabetical list. The guide draws the three default patches first, then
  // the extras, so each group can be pointed at on its own.
  const DEFAULT_PATCHES = PATCHES.filter((p) => p.def).sort(byName);
  const EXTRA_PATCHES = PATCHES.filter((p) => !p.def).sort(byName);
  const PATCH_BY_ID = {};
  PATCHES.forEach((p) => { PATCH_BY_ID[p.id] = p; });

  // Morphe Patches' universal patches, which apply to any app (Morphe Patches 1.48.0).
  const UNIVERSAL = [
    { id: 'changeInstaller', name: 'Change installer source',
      desc: 'Spoofs the installer source so the app appears to be installed from an app store.' },
    { id: 'cloneApp', name: 'Clone app', clone: true,
      desc: 'Changes the app package name to allow installing the same app multiple times. By default ".morphe" is appended to the package name. Each cloned install must use a unique package name. Cloning does not work with all apps and using this patch may cause app crashes or other unexpected behavior.' },
    { id: 'disablePlayStore', name: 'Disable Play Store updates',
      desc: 'Disables Play Store updates by setting the version code to the maximum allowed. This patch may cause unexpected issues with some apps and does not work if the app is installed by root mounting' },
    { id: 'overridePinning', name: 'Override certificate pinning',
      desc: 'Overrides certificate pinning, allowing to inspect traffic via a proxy.' },
    { id: 'spoofSignature', name: 'Spoof signature',
      desc: 'Spoofs the package signature of the original APK.' }
  ];

  // ---------------------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------------------

  function attrs(o) {
    let a = '';
    if (!o) return a;
    if (o.tg) a += ' data-tg="' + esc(o.tg) + '"';
    if (o.key) a += ' data-key="' + esc(o.key) + '"';
    if (o.action) a += ' data-action="' + esc(o.action) + '"';
    if (o.bind) a += ' data-bind="' + esc(o.bind) + '"';
    if (o.set) a += ' data-set="' + esc(JSON.stringify(o.set)) + '"';
    if (o.close) a += o.close === true ? ' data-close' : ' data-close="' + esc(o.close) + '"';
    if (o.open) a += ' data-open="' + esc(o.open) + '"';
    if (o.go) a += ' data-go="' + esc(o.go) + '"';
    if (o.params) a += ' data-params="' + esc(JSON.stringify(o.params)) + '"';
    if (o.toast) a += ' data-toast="' + esc(o.toast) + '"';
    if (o.label) a += ' aria-label="' + esc(o.label) + '"';
    if (o.title) a += ' title="' + esc(o.title) + '"';
    if (o.disabled) a += ' aria-disabled="true"';
    if (o.data) Object.keys(o.data).forEach((k) => { a += ' data-' + k + '="' + esc(o.data[k]) + '"'; });
    return a;
  }

  // A Morphe pill button. fill: the accent veil; solid: the solid accent; danger: red label.
  function btn(label, o) {
    o = o || {};
    const cls = 'm-btn' + (o.fill ? ' m-btn-fill' : '') + (o.solid ? ' m-btn-solid' : '') +
      (o.danger ? ' m-btn-danger' : '') + (o.cls ? ' ' + o.cls : '');
    return '<button type="button" class="' + cls + '"' + attrs(o) + '>' +
      (o.icon ? icon(o.icon, { size: 20 }) : '') + '<span>' + esc(label) + '</span></button>';
  }
  // A round 48 px "glass" button, as in Morphe's headers.
  function round(ic, label, o) {
    o = Object.assign({ label: label }, o || {});
    return '<button type="button" class="m-round' + (o.accent ? ' m-round-accent' : '') + (o.small ? ' m-round-sm' : '') + '"' +
      attrs(o) + '>' + icon(ic, { size: o.small ? 20 : 22 }) + '</button>';
  }
  function badge(text, tone, o) {
    o = o || {};
    return '<span class="m-badge m-badge-' + (tone || 'neutral') + '"' + (o.tg ? ' data-tg="' + esc(o.tg) + '"' : '') + '>' +
      (o.icon ? icon(o.icon, { size: 14, stroke: 2.4 }) : '') + esc(text) + '</span>';
  }
  // A switch drawn inside a row that is itself the switch (the row carries role="switch").
  function switchLook(on) {
    return '<span class="g-switch m-switch-look" aria-checked="' + !!on + '" aria-hidden="true"><span class="g-switch-thumb">' +
      G.icon(on ? 'check' : 'close', { size: 14, stroke: 2.6 }) + '</span></span>';
  }
  // BitChord's app icon, as Morphe shows it in its lists.
  function appIcon(size, letter, cls) {
    return '<span class="m-appicon' + (cls ? ' ' + cls : '') + '" style="--s:' + size + 'px" aria-hidden="true">' + G.bitchordMark() + '</span>';
  }
  function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }
  function dlg(inner, o) {
    o = o || {};
    return '<div class="m-dlg' + (o.orange ? ' m-tint-orange' : '') + (o.cls ? ' ' + o.cls : '') + '">' + inner + '</div>';
  }
  function row2(secondary, primary) { return '<div class="m-btns">' + secondary + primary + '</div>'; }
  function col(...buttons) { return '<div class="m-btns-col">' + buttons.join('') + '</div>'; }

  // ---------------------------------------------------------------------------------------
  // What the sources bring, worked out from the state
  // ---------------------------------------------------------------------------------------

  function normalBringsBitchord(s) {
    return s['morphe.sourceAdded'] && s['morphe.sourceStatus'] === 'ready' && s['morphe.sourceEnabled'] && s['morphe.sourceAppBitchord'];
  }
  function testingBringsBitchord(s) {
    return s['morphe.testingAdded'] && s['morphe.testingEnabled'] && s['morphe.testingAppBitchord'];
  }
  function bitchordOnHome(s) { return normalBringsBitchord(s) || testingBringsBitchord(s) || s['morphe.installed']; }
  // Whether a source that brings BitChord stands behind 1.9 (its Experimental app versions is on).
  function experimentalOn(s) {
    return (normalBringsBitchord(s) && s['morphe.experimental']) || (testingBringsBitchord(s) && s['morphe.testingExperimental']);
  }
  function selectedBitchord(s) { return PATCHES.filter((p) => s['morphe.patch.' + p.id]).length; }
  function selectedUniversal(s) { return UNIVERSAL.filter((p) => s['morphe.upatch.' + p.id]).length; }
  function patchTotal(s) { return s['morphe.expertMode'] ? selectedBitchord(s) + selectedUniversal(s) : DEFAULT_PATCHES.length; }
  function sourceList(s) {
    const list = ['morphe'];
    if (s['morphe.sourceAdded']) list.push('bitchord');
    if (s['morphe.testingAdded']) list.push('testing');
    return list;
  }
  function normalName(s) { return String(s['morphe.sourceName'] || '').trim() || SOURCE_NAME; }
  function testingName(s) { return String(s['morphe.testingName'] || '').trim() || SOURCE_NAME; }
  function installedName(s) { return String(s['morphe.opt.installationName'] || '').trim() || 'Bitchord+'; }

  // A source link the reader typed: '' (empty), 'repo', 'json' or 'bad', as Morphe checks it.
  function linkKind(text) {
    const u = String(text || '').trim();
    if (!u) return '';
    const s = u.replace(/^https?:\/\//i, '').replace(/\/+$/, '');
    if (/^(www\.)?(github|gitlab)\.com\/[\w.-]+\/[\w.-]+$/i.test(s)) return 'repo';
    if (/^[\w-]+(\.[\w-]+)+\/\S*\.json$/i.test(s)) return 'json';
    return 'bad';
  }
  // Which of my sources a link adds: 'normal', 'testing' or 'other'. Morphe changes other branch
  // links of a GitHub repository to main, so only the refs/heads/testing link gives the testing source.
  function whichSource(text) {
    const s = String(text || '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/+$/, '');
    const raw = 'raw.githubusercontent.com/hexclyde/bitchord-morphe-patches/';
    if (s === 'github.com/hexclyde/bitchord-morphe-patches' || s === 'www.github.com/hexclyde/bitchord-morphe-patches') return 'normal';
    if (s === raw + 'refs/heads/testing/patches-bundle.json') return 'testing';
    if (s.indexOf(raw) === 0 && /patches-bundle\.json$/.test(s)) return 'normal';
    return 'other';
  }

  // ---------------------------------------------------------------------------------------
  // The main pages share a header and the floating pill bar
  // ---------------------------------------------------------------------------------------

  const PAGE_ORDER = { sources: 0, apps: 1, settings: 2 };

  function navBar(s, current) {
    const items = [
      ['sources', 'source', 'Sources'],
      ['apps', 'apps', 'Apps'],
      ['settings', s['morphe.expertMode'] ? 'expert' : 'settings', 'Settings']
    ];
    return '<nav class="m-nav" aria-label="Morphe Manager pages"><div class="m-nav-pill">' + items.map((it) =>
      '<button type="button" class="m-nav-item" data-tg="morphe.nav.' + it[0] + '" data-action="morphe.nav" data-page="' + it[0] + '"' +
        (it[0] === current ? ' aria-current="page"' : '') + '>' + icon(it[1], { size: 22 }) + '<span>' + it[2] + '</span></button>').join('') +
      '</div></nav>';
  }
  function mainPage(s, pageId, header, body) {
    return '<div class="m-main">' + header + '<div class="m-body">' + body + '</div>' + navBar(s, pageId) + '</div>';
  }

  // Sources, Apps and Settings sit side by side, as in the real app: moving between them slides,
  // and Back from Sources or Settings returns to Apps.
  function goPage(ctx, page) {
    const target = 'morphe.' + page;
    const cur = G.current().screen || '';
    if (cur === target) return;
    const curPage = cur.indexOf('morphe.') === 0 ? cur.slice(7) : 'apps';
    const from = curPage in PAGE_ORDER ? PAGE_ORDER[curPage] : 1;
    ctx.go(target, null, { dir: PAGE_ORDER[page] < from ? 'back' : 'forward', noHistory: true });
  }
  G.action('morphe.nav', (ctx, node) => goPage(ctx, node.getAttribute('data-page')));

  // Start patching BitChord: the progress screen, then the result.
  function startPatching(ctx) {
    const s = ctx.s;
    ctx.set({
      'morphe.patching.status': 'running',
      'morphe.patching.progress': 0,
      'morphe.install.error': '',
      'morphe.installing': false,
      // A new build of an app that is already installed has to be installed over it.
      'morphe.appUpdateChip': s['morphe.installed'] ? true : s['morphe.appUpdateChip']
    });
    ctx.go('morphe.patching', null, { dir: 'fade', noHistory: true });
  }
  // After the APK is picked: the version check.
  function versionCheck(ctx) {
    ctx.set('morphe.apkPicked', true);
    ctx.open(experimentalOn(G.state()) ? 'morphe.experimentDialog' : 'morphe.unsupportedDialog');
  }

  // =======================================================================================
  // Morphe Manager › Apps
  // =======================================================================================

  function alertCard(tone, ic, title, sub, o) {
    return '<button type="button" class="m-alert m-alert-' + tone + '"' + attrs(o) + '>' +
      '<span class="m-alert-ic">' + icon(ic, { size: 22 }) + '</span>' +
      '<span class="m-alert-text"><span class="m-alert-title">' + esc(title) + '</span><span class="m-alert-sub">' + esc(sub) + '</span></span>' +
      icon('chevronRight', { size: 20, cls: 'm-alert-chev' }) + '</button>';
  }

  function appCard(o) {
    return '<button type="button" class="m-appcard" style="--c:' + o.rgb + '"' + attrs(o.attrs) + '>' +
      (o.iconHtml || '<span class="m-appcard-icon" aria-hidden="true">' + esc(o.letter) + '</span>') +
      '<span class="m-appcard-text"><span class="m-appcard-name">' + esc(o.name) + '</span>' +
      '<span class="m-appcard-sub">' + o.sub + '</span></span></button>';
  }

  function bitchordAppCard(s) {
    let sub;
    if (s['morphe.installed']) {
      sub = 'v' + APP_VERSION + (s['morphe.appUpdateChip'] ? '<span class="m-chip">' + icon('refresh', { size: 14, stroke: 2.4 }) + 'Update</span>' : '');
    } else {
      sub = (s['android.bitchordInstalled'] ? 'v' + APP_VERSION + ' • ' : '') + 'Not patched yet';
    }
    return appCard({
      rgb: ORANGE, name: 'BitChord', sub: sub,
      iconHtml: '<span class="m-appcard-icon m-appcard-icon-bc" aria-hidden="true">B</span>',
      attrs: { tg: 'morphe.apps.bitchordCard', key: 'app-bitchord', action: 'tapBitchord' }
    });
  }

  G.screen({
    id: 'morphe.apps',
    app: 'morphe',
    title: 'Morphe Manager: Apps',
    className: 'm-screen',
    render(ctx) {
      const s = ctx.s;
      const header = '<header class="m-top m-top-apps">' +
        '<p class="m-wordmark">Morphe</p>' +
        '<div class="m-top-actions">' +
          round('sort', 'Sort', { toast: NOT_DRAWN }) +
          round('fileOpen', 'Select other apps', { accent: true, action: 'otherApps' }) +
          round('search', 'Search apps', { toast: NOT_DRAWN }) +
        '</div></header>';

      let alerts = '';
      if (s['morphe.sourceAdded'] && s['morphe.sourceStatus'] === 'error') {
        alerts += alertCard('warn', 'cloudOff', 'Some patch sources are unavailable', 'Tap to review sources', { action: 'toSources', key: 'alert-unavailable' });
      }
      if (s['morphe.updateMorpheBadge']) {
        alerts += alertCard('warn', 'installPhone', 'Morphe is out of date', 'Some patch sources need a newer version', { action: 'toSources', key: 'alert-outdated' });
      }
      if (s['morphe.installed'] && s['morphe.appUpdateChip']) {
        alerts += alertCard('primary', 'refresh', '1 app can be re-patched', 'Tap to queue them all', { open: 'morphe.appInfo', key: 'alert-repatch' });
      }

      const builtins = s['morphe.morpheEnabled'] ? [
        { name: 'Reddit', letter: 'R', rgb: '255,69,0' },
        { name: 'YouTube', letter: 'Y', rgb: '255,0,0' },
        { name: 'YouTube Music', letter: 'M', rgb: '255,0,0' }
      ].map((a) => appCard({
        rgb: a.rgb, name: a.name, letter: a.letter, sub: 'Not patched yet',
        attrs: { key: 'app-' + a.name, toast: 'This guide only patches BitChord.' }
      })).join('') : '';

      // Apps Morphe has patched come first; before that BitChord sits after the built-in apps.
      const showBc = bitchordOnHome(s);
      const cards = (showBc && s['morphe.installed'] ? bitchordAppCard(s) : '') + builtins +
        (showBc && !s['morphe.installed'] ? bitchordAppCard(s) : '') +
        appCard({
          rgb: '30,90,168', name: 'More apps', sub: 'Community patches',
          iconHtml: '<span class="m-appcard-icon m-appcard-icon-add" aria-hidden="true">' + icon('add', { size: 26 }) + '</span>',
          attrs: { key: 'app-more', toast: 'Opens the community patches website.' }
        });

      return mainPage(s, 'apps', header,
        (alerts ? '<div class="m-alerts">' + alerts + '</div>' : '') +
        '<h2 class="m-greet">What app do you want to patch?</h2>' +
        '<div class="m-appcards">' + cards + '</div>');
    },
    actions: {
      tapBitchord(ctx) {
        if (ctx.get('morphe.installed')) ctx.open('morphe.appInfo');
        else ctx.open('morphe.apkHelp');
      },
      otherApps(ctx) {
        ctx.toast(ctx.get('morphe.expertMode')
          ? 'Here you pick an APK of any app. This guide only patches BitChord.'
          : 'This feature is only available in Expert mode');
      },
      toSources(ctx) { goPage(ctx, 'sources'); }
    }
  });

  // =======================================================================================
  // Morphe Manager › Sources
  // =======================================================================================

  function infoRow(ic, label, value, o) {
    o = o || {};
    const inner = '<span class="m-info-ic">' + icon(ic, { size: 20 }) + '</span>' +
      '<span class="m-info-text"><span class="m-info-label">' + esc(label) + '</span><span class="m-info-value">' + esc(value) + '</span></span>' +
      (o.chevron === false ? '' : icon('chevronRight', { size: 20, cls: 'm-info-chev' }));
    if (o.static) return '<div class="m-info-row"' + attrs(o) + '>' + inner + '</div>';
    return '<button type="button" class="m-info-row"' + attrs(o) + '>' + inner + '</button>';
  }
  function toggleRow(id, title, sub, switchHtml) {
    return '<div class="m-toggle-row" data-key="' + esc(id) + '"><span class="m-tr-text"><span class="m-tr-title" id="' + esc(id) + '">' + esc(title) + '</span>' +
      '<span class="m-tr-sub">' + esc(sub) + '</span></span>' + switchHtml + '</div>';
  }
  function actPill(ic, label, o) {
    return '<button type="button" class="m-act' + (o && o.danger ? ' m-act-danger' : '') + '"' + attrs(Object.assign({ label: label, title: label }, o)) + '>' + icon(ic, { size: 20 }) + '</button>';
  }
  function notice(tone, ic, text, o) {
    return '<div class="m-notice m-notice-' + tone + '"' + attrs(o) + '>' + icon(ic, { size: 20 }) + '<span>' + text + '</span></div>';
  }
  function linkPills(card, withShare) {
    return '<div class="m-connected" data-key="links-' + card + '">' +
      '<button type="button" class="m-cbtn m-cbtn-wide" data-toast="Opens the source’s page in your browser.">' + icon('openInNew', { size: 18 }) + '<span>Open in browser</span></button>' +
      (withShare ? '<button type="button" class="m-cbtn" aria-label="Share" title="Share" data-toast="Shares the link to this source.">' + icon('share', { size: 18 }) + '</button>' : '') +
      '<button type="button" class="m-cbtn" aria-label="Report an issue" title="Report an issue" data-toast="Opens the source’s issue page in your browser.">' + icon('bug', { size: 18 }) + '</button>' +
    '</div>';
  }

  function sourceCard(ctx, key, many) {
    const s = ctx.s;
    const open = !many || s['morphe.openCard'] === key;
    let name, avatar, meta, badges = '', notices = '', rows = '', links = '', toggles = '', acts = '', warn = false;
    const clock = icon('schedule', { size: 13, stroke: 2.2 });
    const metaLine = (version, ago) => (many && version ? '<span>' + esc(version) + '</span><span aria-hidden="true">•</span>' : '') +
      clock + '<span>' + esc(ago) + '</span>';

    if (key === 'morphe') {
      name = 'Morphe Patches';
      avatar = '<span class="m-avatar m-avatar-plain">' + icon('extension', { size: 24 }) + '</span>';
      meta = metaLine('1.48.0', '40 min. ago');
      badges = badge('Pre-installed') + (s['morphe.morpheEnabled'] ? '' : badge('Disabled', 'neutral', { icon: 'power' }));
      rows = infoRow('info', 'Patches', '190', { action: 'patchesRow', data: { count: '190' } }) +
        infoRow('history', 'Version', '1.48.0', { toast: 'Opens the changelog of Morphe Patches.' }) +
        infoRow('apps', 'Apps', '3', { toast: 'Lists the apps Morphe Patches brings.' });
      links = linkPills('morphe', true);
      toggles = toggleRow('m-src-morphe-pre', 'Pre-release patches', 'Receives early access to new experimental patch versions',
        ctx.ui.switch({ bind: 'morphe.morphePrerelease', action: 'askPrerelease', labelledby: 'm-src-morphe-pre', thumbIcons: true, key: 'sw-morphe-pre' }).replace('<button', '<button data-card="morphe"'));
      acts = (many ? (s['morphe.morpheEnabled']
        ? actPill('block', 'Disable', { action: 'disableSource', data: { card: 'morphe' } })
        : actPill('checkCircle', 'Enable', { action: 'enableSource', data: { card: 'morphe' } })) : '') +
        actPill('refresh', 'Update', { action: 'updateSource', data: { card: 'morphe' } });
    } else if (key === 'bitchord') {
      const status = s['morphe.sourceStatus'];
      const ready = status === 'ready';
      warn = status === 'error' || s['morphe.updateMorpheBadge'];
      name = status === 'loading' ? 'Unnamed' : normalName(s);
      avatar = status === 'ready'
        ? '<span class="m-avatar m-avatar-owner">' + icon('person', { size: 24 }) + '</span>'
        : '<span class="m-avatar m-avatar-warn">' + icon('cloudOff', { size: 22 }) + '</span>';
      meta = metaLine(ready ? PATCHES_VERSION : '', status === 'loading' ? '0 min. ago' : '3 min. ago');
      badges = badge('Remote') +
        (status === 'error' ? badge('Metadata N/A', 'error', { tg: 'morphe.sourceCard.metadataBadge' }) : '') +
        (s['morphe.updateMorpheBadge'] ? badge('Update Morphe', 'error', { tg: 'morphe.sourceCard.updateMorpheBadge' }) : '') +
        (s['morphe.sourceEnabled'] ? '' : badge('Disabled', 'neutral', { icon: 'power' }));
      if (status === 'error') {
        notices += notice('error', 'cloudOff', 'The patch bundle could not be downloaded. The source URL may be incorrect or the file does not exist', { key: 'n-missing' });
      }
      if (s['morphe.updateMorpheBadge']) {
        notices += '<button type="button" class="m-notice m-notice-warn" data-key="n-outdated" data-toast="Update Morphe Manager from its releases page.">' + icon('installPhone', { size: 20 }) +
          '<span>This source is built for patcher 2.1.0, but Morphe ' + MORPHE_VERSION + ' includes patcher 2.0.1. Update Morphe to use it</span></button>';
      }
      rows = infoRow('info', 'Patches', ready ? '14' : '0', { action: 'patchesRow', data: { count: ready ? '14' : '0' }, chevron: ready }) +
        infoRow('history', 'Version', ready ? PATCHES_VERSION : 'N/A', { tg: 'morphe.sourceCard.versionRow', action: 'versionRow', data: { card: 'bitchord' } }) +
        (ready ? infoRow('apps', 'Apps', s['morphe.sourceAppBitchord'] ? '1' : '0/1', { tg: 'morphe.sourceCard.appsRow', open: 'morphe.sourceApps', params: { card: 'bitchord' } }) : '');
      links = linkPills('bitchord', true);
      toggles = toggleRow('m-src-bc-pre', 'Pre-release patches', 'Receives early access to new experimental patch versions',
        ctx.ui.switch({ bind: 'morphe.prerelease', action: 'askPrerelease', labelledby: 'm-src-bc-pre', tg: 'morphe.sourceCard.prereleaseSwitch', thumbIcons: true }).replace('<button', '<button data-card="bitchord"')) +
        (s['morphe.prerelease'] ? toggleRow('m-src-bc-exp', 'Experimental app versions', 'Shows and patches experimental app targets',
          ctx.ui.switch({ bind: 'morphe.experimental', labelledby: 'm-src-bc-exp', tg: 'morphe.sourceCard.experimentalSwitch', thumbIcons: true })) : '');
      acts = (s['morphe.sourceEnabled']
        ? actPill('block', 'Disable', { tg: 'morphe.sourceCard.disableButton', action: 'disableSource', data: { card: 'bitchord' } })
        : actPill('checkCircle', 'Enable', { tg: 'morphe.sourceCard.enableButton', action: 'enableSource', data: { card: 'bitchord' } })) +
        actPill('refresh', 'Update', { tg: 'morphe.sourceCard.updateButton', action: 'updateSource', data: { card: 'bitchord' } }) +
        actPill('edit', 'Rename', { tg: 'morphe.sourceCard.renameButton', action: 'renameSource', data: { card: 'bitchord' } }) +
        actPill('delete', 'Delete', { tg: 'morphe.sourceCard.deleteButton', action: 'deleteSource', data: { card: 'bitchord' }, danger: true });
    } else {
      const local = s['morphe.testingLocal'];
      name = testingName(s);
      avatar = local
        ? '<span class="m-avatar m-avatar-plain">' + icon('file', { size: 22 }) + '</span>'
        : '<span class="m-avatar m-avatar-owner">' + icon('person', { size: 24 }) + '</span>';
      meta = metaLine(PATCHES_VERSION, '1 min. ago');
      badges = badge(local ? 'Local' : 'Remote') + (s['morphe.testingEnabled'] ? '' : badge('Disabled', 'neutral', { icon: 'power' }));
      rows = infoRow('info', 'Patches', '14', { action: 'patchesRow', data: { count: '14' } }) +
        infoRow('history', 'Version', PATCHES_VERSION, { tg: 'morphe.testingCard.versionRow', action: 'versionRow', data: { card: 'testing' } }) +
        infoRow('apps', 'Apps', s['morphe.testingAppBitchord'] ? '1' : '0/1', { tg: 'morphe.testingCard.appsRow', open: 'morphe.sourceApps', params: { card: 'testing' } });
      links = local ? '' : linkPills('testing', false);
      toggles = toggleRow('m-src-test-exp', 'Experimental app versions', 'Shows and patches experimental app targets',
        ctx.ui.switch({ bind: 'morphe.testingExperimental', labelledby: 'm-src-test-exp', tg: 'morphe.testingCard.experimentalSwitch', thumbIcons: true }));
      acts = (s['morphe.testingEnabled']
        ? actPill('block', 'Disable', { tg: 'morphe.testingCard.disableButton', action: 'disableSource', data: { card: 'testing' } })
        : actPill('checkCircle', 'Enable', { tg: 'morphe.testingCard.enableButton', action: 'enableSource', data: { card: 'testing' } })) +
        actPill('refresh', 'Update', { tg: 'morphe.testingCard.updateButton', action: 'updateSource', data: { card: 'testing' } }) +
        actPill('edit', 'Rename', { tg: 'morphe.testingCard.renameButton', action: 'renameSource', data: { card: 'testing' } }) +
        actPill('delete', 'Delete', { tg: 'morphe.testingCard.deleteButton', action: 'deleteSource', data: { card: 'testing' }, danger: true });
    }

    const headInner = avatar +
      '<span class="m-src-text"><span class="m-src-name">' + esc(name) + '</span>' +
      '<span class="m-src-meta">' + meta + '</span>' +
      '<span class="m-badges">' + badges + '</span></span>' +
      (many ? icon('chevronDown', { size: 22, cls: 'm-src-chev' }) : '');
    const headTg = key === 'testing' ? ' data-tg="morphe.testingCard.header"' : '';
    const head = many
      ? '<button type="button" class="m-src-head" aria-expanded="' + open + '" data-action="toggleCard" data-card="' + key + '"' + headTg + '>' + headInner + '</button>'
      : '<div class="m-src-head"' + headTg + '>' + headInner + '</div>';
    const body = open
      ? '<div class="m-src-body">' + notices + '<div class="m-info">' + rows + '</div>' + links +
        '<hr class="m-hr">' + toggles + '<hr class="m-hr">' + '<div class="m-acts">' + acts + '</div></div>'
      : '';
    const cardTg = key === 'bitchord' ? ' data-tg="morphe.sources.bitchordCard"' : '';
    // A folded card opens from a tap anywhere on it.
    const tapWhole = many && !open ? ' data-action="openCard" data-card="' + key + '"' : '';
    return '<article class="m-source' + (warn ? ' is-warn' : '') + (open ? ' is-open' : '') + '" data-key="src-' + key + '"' + cardTg + tapWhole + '>' +
      head + body + '</article>';
  }

  G.screen({
    id: 'morphe.sources',
    app: 'morphe',
    title: 'Morphe Manager: Sources',
    back: 'morphe.apps',
    className: 'm-screen',
    render(ctx) {
      const s = ctx.s;
      const list = sourceList(s);
      const many = list.length > 1;
      const header = '<header class="m-top">' +
        '<div class="m-top-text" data-tg="morphe.sources.header"><h2 class="m-title">Sources</h2>' +
          '<p class="m-sub">' + plural(list.length, 'source installed', 'sources installed') + '</p></div>' +
        '<div class="m-top-actions">' +
          (many ? round('sort', 'Sort', { toast: NOT_DRAWN }) : '') +
          round('add', 'Add', { accent: true, tg: 'morphe.sources.addButton', open: 'morphe.addSourceDialog' }) +
          round('search', 'Search sources and patches', { toast: NOT_DRAWN }) +
        '</div></header>';
      return mainPage(s, 'sources', header, '<div class="m-sources">' + list.map((k) => sourceCard(ctx, k, many)).join('') + '</div>');
    },
    onBack(ctx) { goPage(ctx, 'apps'); },
    actions: {
      toggleCard(ctx, node) {
        const key = node.getAttribute('data-card');
        ctx.set('morphe.openCard', ctx.get('morphe.openCard') === key ? '' : key);
      },
      openCard(ctx, node) { ctx.set('morphe.openCard', node.getAttribute('data-card')); },
      askPrerelease(ctx, node) {
        const card = node.getAttribute('data-card') || 'bitchord';
        const key = card === 'morphe' ? 'morphe.morphePrerelease' : 'morphe.prerelease';
        if (ctx.get(key)) ctx.set(key, false);
        else ctx.open('morphe.prereleaseDialog', { card: card });
      },
      versionRow(ctx, node) {
        if (ctx.get('morphe.sourceStatus') !== 'ready' && node.getAttribute('data-card') === 'bitchord') {
          ctx.toast('The source hasn’t loaded, so there are no release notes yet.');
          return;
        }
        ctx.open('morphe.changelog', { card: node.getAttribute('data-card') });
      },
      patchesRow(ctx, node) {
        const n = Number(node.getAttribute('data-count')) || 0;
        ctx.toast(n ? 'Lists the ' + n + ' patches of this source.' : 'No patches loaded yet.');
      },
      disableSource(ctx, node) { setEnabled(ctx, node.getAttribute('data-card'), false); },
      enableSource(ctx, node) { setEnabled(ctx, node.getAttribute('data-card'), true); },
      updateSource(ctx, node) {
        const card = node.getAttribute('data-card');
        if (card === 'testing' && ctx.get('morphe.testingLocal')) {
          ctx.open('android.filePicker', { mode: 'mpp', purpose: 'update' });
          return;
        }
        ctx.toast('Updating source');
        if (!ctx.inTour) G.later(() => G.toast('No update available'), 1500);
      },
      renameSource(ctx, node) {
        const card = node.getAttribute('data-card');
        ctx.set('morphe.renameDraft', card === 'testing' ? testingName(ctx.s) : normalName(ctx.s));
        ctx.open('morphe.renameSource', { card: card });
      },
      deleteSource(ctx, node) { ctx.open('morphe.deleteSource', { card: node.getAttribute('data-card') }); }
    }
  });

  function setEnabled(ctx, card, on) {
    const key = card === 'morphe' ? 'morphe.morpheEnabled' : card === 'testing' ? 'morphe.testingEnabled' : 'morphe.sourceEnabled';
    ctx.set(key, on);
    ctx.toast(on ? 'Source enabled' : 'Source disabled');
  }

  // The prompt Morphe shows for the "Add this source to Morphe" link in the README.
  G.screen({
    id: 'morphe.addSource',
    app: 'morphe',
    kind: 'full',
    title: 'Add source',
    render(ctx) {
      const added = ctx.s['morphe.sourceAdded'];
      return dlg(
        '<div class="m-dl-head" data-tg="morphe.addSource.header">' +
          '<span class="m-dl-avatar" aria-hidden="true">' + icon('person', { size: 58, stroke: 1.6 }) +
            '<span class="m-dl-badge' + (added ? ' is-added' : '') + '">' + icon(added ? 'check' : 'link', { size: 20 }) + '</span></span>' +
          '<h2 class="m-dlg-title">' + SOURCE_NAME + '</h2>' +
          '<p class="m-dl-owner">github.com/Hexclyde</p>' +
        '</div>' +
        '<p class="m-dl-question">' + (added ? 'This source is already added. Add this app from the existing source?' : 'Do you want to add this patch source to Morphe?') + '</p>' +
        '<div class="m-dl-app"><span class="m-dl-app-tile" aria-hidden="true">' + G.bitchordMark() + '</span>' +
          '<span class="m-dl-app-text"><span class="m-dl-app-name">com.music.bitchord</span>' +
          '<span class="m-dl-app-sub">Only this app is added. You can add other apps from the source\'s app list</span></span></div>' +
        '<p class="m-dl-warn">' + icon('warning', { size: 18 }) + '<span>Only add sources you trust</span></p>' +
        row2(btn('Cancel', { tg: 'morphe.addSource.cancelButton', close: true }),
          btn('Add', { fill: true, icon: 'extension', tg: 'morphe.addSource.addButton', action: 'add' })),
        { cls: 'm-dlg-deeplink' });
    },
    actions: {
      add(ctx) {
        const already = ctx.get('morphe.sourceAdded');
        // Out of a tour the new card says "Unnamed" until the bundle has loaded.
        const loading = !already && !ctx.inTour;
        ctx.set({ 'morphe.sourceAdded': true, 'morphe.sourceAppBitchord': true, 'morphe.sourceStatus': loading ? 'loading' : ctx.get('morphe.sourceStatus') });
        if (loading) G.later(() => { if (G.get('morphe.sourceStatus') === 'loading') G.set('morphe.sourceStatus', 'ready'); }, 1800);
        if (G.current().screen === 'morphe.sources') ctx.close('morphe.addSource');
        else ctx.go('morphe.sources', null, { dir: 'fade', noHistory: true });
      }
    }
  });

  // Sources › + : Add patch source, with the Remote and Local tabs.
  G.screen({
    id: 'morphe.addSourceDialog',
    app: 'morphe',
    kind: 'full',
    title: 'Add patch source',
    render(ctx) {
      const s = ctx.s;
      const tab = s['morphe.addSourceTab'] === 'local' ? 'local' : 'remote';
      const url = s['morphe.addSourceUrl'] || '';
      const kind = linkKind(url);
      let body;
      if (tab === 'remote') {
        body = '<div class="m-ofield" data-key="remote">' +
            '<label class="m-ofield-label" for="m-add-url">Source URL</label>' +
            ctx.ui.field({ bind: 'morphe.addSourceUrl', tg: 'morphe.addSourceDialog.sourceUrlField', id: 'm-add-url', inputmode: 'url', enter: 'add', cls: 'm-ofield-input' }) +
            (url
              ? '<button type="button" class="m-ofield-btn" aria-label="Clear" data-set="' + esc(JSON.stringify({ 'morphe.addSourceUrl': '' })) + '">' + icon('close', { size: 20 }) + '</button>'
              : '<button type="button" class="m-ofield-btn" aria-label="Paste" data-action="paste">' + icon('paste', { size: 20 }) + '</button>') +
          '</div>' +
          (kind ? '<p class="m-valid ' + (kind === 'bad' ? 'is-bad' : 'is-ok') + '" data-key="valid">' +
            icon(kind === 'bad' ? 'error' : 'checkCircle', { size: 16 }) +
            '<span>' + (kind === 'bad' ? 'URL must point to a GitHub/GitLab repo or .json file' : 'Valid repository or JSON URL') + '</span></p>' : '') +
          '<div class="m-hint" data-key="hint"><p class="m-hint-head">' + icon('info', { size: 18 }) +
            '<span>A repository or a link to a patch bundle .json. Paste several at once to add them all</span></p>' +
            '<p class="m-hint-ex">' + icon('source', { size: 16 }) + '<code>github.com/owner/repo</code></p>' +
            '<p class="m-hint-ex">' + icon('source', { size: 16 }) + '<code>gitlab.com/owner/repo</code></p>' +
            '<p class="m-hint-ex">' + icon('link', { size: 16 }) + '<code>example.com/patches-bundle.json</code></p></div>';
      } else {
        const file = s['morphe.localFile'];
        body = '<button type="button" class="m-drop" data-tg="morphe.addSourceDialog.localDropZone" data-key="local" data-open="android.filePicker" data-params="' + esc(JSON.stringify({ mode: 'mpp' })) + '">' +
            icon('upload', { size: 28 }) + '<span class="m-drop-title">Select patch source files</span><span class="m-drop-sub">Tap to browse storage</span></button>' +
          (file ? '<div class="m-file-chip" data-key="file">' + icon('file', { size: 20 }) + '<span>' + esc(file) + '</span>' +
            '<button type="button" class="m-ofield-btn" aria-label="Remove ' + esc(file) + '" data-set="' + esc(JSON.stringify({ 'morphe.localFile': '' })) + '">' + icon('close', { size: 18 }) + '</button></div>' : '');
      }
      const canAdd = tab === 'local' ? !!s['morphe.localFile'] : (kind === 'repo' || kind === 'json');
      return dlg(
        '<h2 class="m-dlg-title">Add patch source</h2>' +
        '<div class="m-seg" role="group" aria-label="Source type">' +
          '<button type="button" aria-pressed="' + (tab === 'remote') + '" data-bind="morphe.addSourceTab" data-value="remote" data-tg="morphe.addSourceDialog.remoteTab">' + icon('globe', { size: 18 }) + '<span>Remote</span></button>' +
          '<button type="button" aria-pressed="' + (tab === 'local') + '" data-bind="morphe.addSourceTab" data-value="local" data-tg="morphe.addSourceDialog.localTab">' + icon('file', { size: 18 }) + '<span>Local</span></button>' +
        '</div>' +
        '<div class="m-add-body">' + body + '</div>' +
        '<button type="button" class="m-check-row" role="checkbox" aria-checked="' + !!s['morphe.pickApps'] + '" data-bind="morphe.pickApps">' +
          icon(s['morphe.pickApps'] ? 'checkCircleFilled' : 'circle', { size: 22 }) + '<span>Pick which of its apps to keep once the source has loaded</span></button>' +
        (tab === 'remote' ? btn('Community patches', { icon: 'openInNew', cls: 'm-btn-wide', toast: 'Opens the community patches website.' }) : '') +
        row2(btn('Cancel', { tg: 'morphe.addSourceDialog.cancelButton', close: true }),
          btn('Add', { fill: true, tg: 'morphe.addSourceDialog.addButton', action: 'add', disabled: !canAdd })),
        { cls: 'm-dlg-top' });
    },
    actions: {
      paste(ctx) {
        // The pretend clipboard holds my link, or the testing link once my source is added.
        ctx.set('morphe.addSourceUrl', ctx.get('morphe.sourceAdded') ? TESTING_LINK : REPO_LINK);
      },
      add(ctx) {
        const s = ctx.s;
        if (s['morphe.addSourceTab'] === 'local') {
          if (!s['morphe.localFile']) return;
          if (s['morphe.testingAdded']) { ctx.toast('This source has already been added'); return; }
          ctx.set({ 'morphe.testingAdded': true, 'morphe.testingLocal': true, 'morphe.testingEnabled': true, 'morphe.localFile': '' });
          return finishAdd(ctx);
        }
        const url = s['morphe.addSourceUrl'];
        const kind = linkKind(url);
        if (kind !== 'repo' && kind !== 'json') {
          if (kind === 'bad') ctx.toast('Invalid source URL. Please check the address and try again');
          return;
        }
        const which = whichSource(url);
        if (which === 'normal') {
          if (s['morphe.sourceAdded']) { ctx.toast('This source has already been added'); return; }
          const loading = !ctx.inTour;
          ctx.set({ 'morphe.sourceAdded': true, 'morphe.sourceAppBitchord': true, 'morphe.sourceStatus': loading ? 'loading' : s['morphe.sourceStatus'] });
          if (loading) G.later(() => { if (G.get('morphe.sourceStatus') === 'loading') G.set('morphe.sourceStatus', 'ready'); }, 1800);
        } else if (which === 'testing') {
          if (s['morphe.testingAdded']) { ctx.toast('This source has already been added'); return; }
          ctx.set({ 'morphe.testingAdded': true, 'morphe.testingLocal': false, 'morphe.testingEnabled': true });
        } else {
          ctx.toast('In this guide, only my two sources can be added.');
          return;
        }
        ctx.set('morphe.addSourceUrl', '');
        finishAdd(ctx);
      }
    }
  });

  function finishAdd(ctx) {
    if (G.current().screen === 'morphe.sources') ctx.close('morphe.addSourceDialog');
    else ctx.go('morphe.sources', null, { dir: 'fade', noHistory: true });
  }

  // A plain Morphe confirmation: title, text, Cancel and the primary button side by side.
  function confirmDialog(def) {
    G.screen(Object.assign({ app: 'morphe', kind: 'full' }, def));
  }

  confirmDialog({
    id: 'morphe.prereleaseDialog',
    title: 'Enable pre-release patches?',
    render() {
      return dlg('<h2 class="m-dlg-title">Enable pre-release patches?</h2>' +
        '<p class="m-dlg-text">Pre-release builds are still in development. Patches may fail or break the app, and features can change without warning</p>' +
        row2(btn('Cancel', { tg: 'morphe.prereleaseDialog.cancelButton', close: true }),
          btn('Enable', { fill: true, tg: 'morphe.prereleaseDialog.enableButton', action: 'enable' })));
    },
    actions: {
      enable(ctx) {
        ctx.set(ctx.params.card === 'morphe' ? 'morphe.morphePrerelease' : 'morphe.prerelease', true);
        ctx.close('morphe.prereleaseDialog');
      }
    }
  });

  confirmDialog({
    id: 'morphe.deleteSource',
    title: 'Delete',
    render(ctx) {
      const card = ctx.params.card === 'bitchord' ? 'bitchord' : 'testing';
      const name = card === 'testing' ? testingName(ctx.s) : normalName(ctx.s);
      return dlg('<h2 class="m-dlg-title">Delete</h2><p class="m-dlg-text">This cannot be undone</p>' +
        '<div class="m-subject"><span class="m-avatar m-avatar-owner m-avatar-xl">' + icon('person', { size: 34 }) + '</span>' +
          '<span class="m-subject-name">' + esc(name) + '</span></div>' +
        row2(btn('Cancel', { close: true }), btn('Delete', { fill: true, danger: true, tg: 'morphe.deleteSource.deleteButton', action: 'del' })));
    },
    actions: {
      del(ctx) {
        if (ctx.params.card === 'bitchord') {
          ctx.set({ 'morphe.sourceAdded': false, 'morphe.prerelease': false, 'morphe.experimental': false, 'morphe.sourceEnabled': true,
            'morphe.sourceStatus': 'ready', 'morphe.sourceName': '', 'morphe.openCard': '' });
        } else {
          ctx.set({ 'morphe.testingAdded': false, 'morphe.testingExperimental': false, 'morphe.testingName': '', 'morphe.testingLocal': false,
            'morphe.testingEnabled': true, 'morphe.openCard': '' });
        }
        ctx.close('morphe.deleteSource');
      }
    }
  });

  confirmDialog({
    id: 'morphe.renameSource',
    title: 'Rename',
    render(ctx) {
      return dlg('<h2 class="m-dlg-title">Rename</h2><p class="m-dlg-text">Rename patch source</p>' +
        '<div class="m-ofield"><label class="m-ofield-label" for="m-rename">Display name</label>' +
          ctx.ui.field({ bind: 'morphe.renameDraft', id: 'm-rename', enter: 'ok', cls: 'm-ofield-input', tg: 'morphe.renameSource.field' }) + '</div>' +
        row2(btn('Cancel', { close: true }), btn('OK', { fill: true, tg: 'morphe.renameSource.okButton', action: 'ok' })));
    },
    actions: {
      ok(ctx) {
        const name = String(ctx.get('morphe.renameDraft') || '').trim();
        const v = name === SOURCE_NAME ? '' : name;
        ctx.set(ctx.params.card === 'testing' ? 'morphe.testingName' : 'morphe.sourceName', v);
        ctx.close('morphe.renameSource');
      }
    }
  });

  // The Apps row of a source card: which apps this source brings to the home screen.
  G.screen({
    id: 'morphe.sourceApps',
    app: 'morphe',
    kind: 'full',
    title: 'Apps',
    render(ctx) {
      const s = ctx.s;
      const testing = ctx.params.card === 'testing';
      const key = testing ? 'morphe.testingAppBitchord' : 'morphe.sourceAppBitchord';
      const on = !!s[key];
      return dlg(
        '<div class="m-sa-head"><span class="m-avatar m-avatar-owner">' + icon('person', { size: 24 }) + '</span>' +
          '<span class="m-sa-title"><span class="m-sa-name">' + esc(testing ? testingName(s) : normalName(s)) + '</span><span class="m-sa-sub">1 app</span></span>' +
          round('search', 'Search apps', { small: true, toast: NOT_DRAWN }) +
          round('doneAll', on ? 'Leave all out' : 'Bring all', { small: true, set: { [key]: !on } }) + '</div>' +
        '<p class="m-sa-text">Ticked apps come from this source. Tap an app to leave it out or bring it back. An app leaves the home screen once no source brings it, unless you have patched it</p>' +
        '<button type="button" class="m-row m-row-solo" role="switch" aria-checked="' + !!s['morphe.bringNewApps'] + '" data-bind="morphe.bringNewApps" aria-labelledby="m-sa-new">' +
          '<span class="m-row-text"><span class="m-row-title" id="m-sa-new">Bring new apps</span><span class="m-row-sub">Apps it adds later show up on the home screen</span></span>' +
          switchLook(s['morphe.bringNewApps']) + '</button>' +
        '<p class="m-list-head">' + (on ? 'Brought by this source' : 'Left out of this source') + '</p>' +
        '<button type="button" class="m-sa-row" role="checkbox" aria-checked="' + on + '" data-bind="' + key + '" data-tg="morphe.sourceApps.bitchordRow">' +
          '<span class="m-sa-check">' + icon(on ? 'checkCircleFilled' : 'circle', { size: 24 }) + '</span>' + appIcon(40) +
          '<span class="m-sa-app"><span class="m-sa-app-name">BitChord</span><span class="m-sa-app-sub">15 patches</span></span></button>' +
        btn('Close', { cls: 'm-btn-wide', tg: 'morphe.sourceApps.closeButton', close: true }),
        { cls: 'm-dlg-top m-dlg-left' });
    }
  });

  // The Version row: what changed in this version of my patches.
  G.screen({
    id: 'morphe.changelog',
    app: 'morphe',
    kind: 'full',
    title: 'What\'s new',
    render(ctx) {
      const s = ctx.s;
      const name = ctx.params.card === 'testing' ? testingName(s) : normalName(s);
      const items = [
        'New patch, Audio and video switch: a song and video switch at the top of the player.',
        'JamesDSP shows whether it can hear BitChord and helps with its Shizuku or ADB setup.',
        'Works with BitChord 1.9.'
      ];
      return dlg('<h2 class="m-dlg-title">What\'s new</h2><p class="m-dlg-text">' + esc(name) + '</p>' +
        '<div class="m-cl"><p class="m-cl-head"><span class="m-cl-version">' + PATCHES_VERSION + '</span>' + badge('Latest', 'primary') + '</p>' +
          '<ul class="m-cl-list">' + items.map((t) => '<li>' + esc(t) + '</li>').join('') + '</ul></div>' +
        btn('Close', { cls: 'm-btn-wide', close: true, tg: 'morphe.changelog.closeButton' }),
        { cls: 'm-dlg-left' });
    }
  });

  // =======================================================================================
  // Morphe Manager › Settings
  // =======================================================================================

  function sectionHead(ic, title) {
    return '<h3 class="m-sec-head">' + icon(ic, { size: 20 }) + '<span>' + esc(title) + '</span></h3>';
  }
  function navRow(ic, title, sub, o) {
    o = Object.assign({ toast: NOT_DRAWN }, o || {});
    if (o.open || o.action) delete o.toast;
    return '<button type="button" class="m-row"' + attrs(o) + '>' + '<span class="m-row-ic">' + icon(ic, { size: 24 }) + '</span>' +
      '<span class="m-row-text"><span class="m-row-title">' + esc(title) + '</span>' + (sub ? '<span class="m-row-sub">' + esc(sub) + '</span>' : '') + '</span>' +
      icon('chevronRight', { size: 20, cls: 'm-row-chev' }) + '</button>';
  }
  function switchRow(ic, id, title, sub, on, o) {
    return '<button type="button" class="m-row" role="switch" aria-checked="' + !!on + '" aria-labelledby="' + id + '"' + attrs(o) + '>' +
      '<span class="m-row-ic">' + icon(ic, { size: 24 }) + '</span>' +
      '<span class="m-row-text"><span class="m-row-title" id="' + id + '">' + esc(title) + '</span><span class="m-row-sub">' + esc(sub) + '</span></span>' +
      switchLook(on) + '</button>';
  }
  function rows(list) { return '<div class="m-rows">' + list.filter(Boolean).join('') + '</div>'; }

  G.screen({
    id: 'morphe.settings',
    app: 'morphe',
    title: 'Morphe Manager: Settings',
    back: 'morphe.apps',
    className: 'm-screen',
    render(ctx) {
      const s = ctx.s;
      const expert = s['morphe.expertMode'];
      const header = '<header class="m-top"><div class="m-top-text"><h2 class="m-title">Settings</h2></div>' +
        '<div class="m-top-actions">' + round('search', 'Search settings', { toast: NOT_DRAWN }) + '</div></header>';
      const body =
        sectionHead('settings', 'General') + rows([
          navRow('palette', 'Appearance', 'How Morphe looks'),
          navRow('bell', 'Notifications', 'How Morphe keeps you informed')
        ]) +
        sectionHead('wand', 'Patching') + rows([
          switchRow('psychology', 'm-set-expert', 'Expert mode', 'Unlocks advanced patching settings and patch options', expert,
            { tg: 'morphe.settings.expertModeSwitch', action: 'askExpert', key: 'set-expert' }),
          expert
            ? navRow('tune', 'Patch selections', 'Saved patch selections, retained after uninstall', { key: 'set-selections' })
            : navRow('tune', 'Patch options', 'What simple mode patches apps with', { key: 'set-options', toast: 'These options are for YouTube and YouTube Music. BitChord’s options are in Expert mode.' }),
          navRow('installPhone', 'Installation', 'System installer'),
          navRow('memory', 'Patcher', 'How apps are patched on this device')
        ]) +
        sectionHead('installPhone', 'Updates') + rows([
          switchRow('science', 'm-set-pre', 'Pre-release updates', 'Early access to new Morphe versions', s['morphe.set.prereleaseUpdates'], { bind: 'morphe.set.prereleaseUpdates' }),
          switchRow('signalBars', 'm-set-metered', 'Mobile data updates', 'Downloads Morphe and patch updates over mobile data', s['morphe.set.meteredUpdates'], { bind: 'morphe.set.meteredUpdates' }),
          expert ? navRow('key', 'GitHub PAT', 'For pull request sources, private repositories and a higher GitHub rate limit', { key: 'set-pat' }) : ''
        ]) +
        sectionHead('folder', 'Files') + rows([
          switchRow('fileOpen', 'm-set-picker', 'Custom file picker', 'Uses Morphe\'s built-in file picker instead of the system one', s['morphe.set.customPicker'], { bind: 'morphe.set.customPicker' }),
          navRow('storage', 'Storage management', 'Shows disk usage and clears cached files'),
          navRow('download', 'APK download helper', 'Helper apps can download original APKs for you')
        ]) +
        sectionHead('backup', 'Backup') + rows([
          navRow('key', 'Signing key', 'Backs up or restores the key that signs patched apps', { tg: 'morphe.settings.signingKeyRow', open: 'morphe.signingKey' }),
          navRow('settings', 'Morphe settings', 'Backs up and restores your Morphe settings')
        ]) +
        sectionHead('info', 'About') + rows([
          '<div class="m-row m-about"><span class="m-about-name">Morphe</span><span class="m-row-sub">Version ' + MORPHE_VERSION + '</span></div>',
          navRow('help', 'Help & info', 'More about Morphe and getting help')
        ]);
      return mainPage(s, 'settings', header, '<div class="m-settings">' + body + '</div>');
    },
    onBack(ctx) { goPage(ctx, 'apps'); },
    actions: {
      askExpert(ctx) {
        if (ctx.get('morphe.expertMode')) ctx.set('morphe.expertMode', false);
        else ctx.open('morphe.expertDialog');
      }
    }
  });

  confirmDialog({
    id: 'morphe.expertDialog',
    title: 'Enable Expert mode?',
    render() {
      return dlg('<h2 class="m-dlg-title">Enable Expert mode?</h2>' +
        '<p class="m-dlg-text">Expert mode gives more control over how patches are applied, but misconfiguring settings in expert mode can result in a non-functional app</p>' +
        row2(btn('Cancel', { tg: 'morphe.expertDialog.cancelButton', close: true }),
          btn('Enable', { fill: true, tg: 'morphe.expertDialog.enableButton', action: 'enable' })));
    },
    actions: {
      enable(ctx) {
        ctx.set('morphe.expertMode', true);
        ctx.close('morphe.expertDialog');
        ctx.toast('In Expert mode patch options will be available during the patching process');
      }
    }
  });

  G.screen({
    id: 'morphe.signingKey',
    app: 'morphe',
    kind: 'full',
    title: 'Signing key',
    render(ctx) {
      const has = ctx.s['morphe.installed'];
      return dlg('<h2 class="m-dlg-title">Signing key</h2>' +
        '<p class="m-dlg-text">Every app Morphe patches is signed with this key. Keep a copy: without it, patched apps can only be updated after uninstalling them</p>' +
        '<div class="m-info">' +
          infoRow('key', 'Signing key', has ? 'Created 30 Oct 2026' : 'Morphe creates one with your first patch', { static: true, chevron: false }) +
          (has ? infoRow('lock', 'SHA-256 fingerprint', '3A:91:C4:…:7E:02', { static: true, chevron: false }) : '') +
        '</div>' +
        row2(btn('Import', { icon: 'download', tg: 'morphe.signingKey.importButton', toast: 'Pick your saved key file, then enter its details.' }),
          btn('Export', { fill: true, icon: 'upload', tg: 'morphe.signingKey.exportButton', toast: 'Keystore exported' })) +
        btn('Close', { cls: 'm-btn-wide', close: true, tg: 'morphe.signingKey.closeButton' }));
    }
  });

  // =======================================================================================
  // Patching BitChord: the APK questions
  // =======================================================================================

  G.screen({
    id: 'morphe.apkHelp',
    app: 'morphe',
    kind: 'full',
    title: 'Need help finding an original unpatched APK?',
    render(ctx) {
      const s = ctx.s;
      const expert = s['morphe.expertMode'];
      const installed = s['android.bitchordInstalled'];
      const saved = s['morphe.installed'];
      const badges = expert
        ? badge('Experimental', 'warn', { icon: 'science' }) + (installed ? badge('Installed', 'success', { icon: 'installPhone' }) : '')
        : badge('Unpatched', 'warn', { icon: 'inventory' }) + (installed ? badge('Installed', 'success', { icon: 'installPhone' }) : '');
      const version = '<div class="m-vcard' + (expert ? ' is-pick' : '') + '"' + (expert ? ' role="radio" aria-checked="true"' : '') + ' data-tg="morphe.apkHelp.version">' +
        (expert ? '<span class="m-vcard-pick">' + icon('checkCircleFilled', { size: 22 }) + '</span>' : '') +
        '<span class="m-vcard-version">' + APP_VERSION + '</span><span class="m-badges">' + badges + '</span></div>';
      return dlg('<h2 class="m-dlg-title">Need help finding an original unpatched APK?</h2>' +
        '<p class="m-dlg-text">' + (expert ? 'To patch <b>BitChord</b>, you need an unpatched APK of versions:' : 'To patch <b>BitChord</b>, you need the unpatched APK version:') + '</p>' +
        version +
        col(
          btn('Yes, help me find an APK', { fill: true, icon: 'download', action: 'findApk' }),
          btn('No, I already have an APK', { icon: 'check', tg: 'morphe.apkHelp.alreadyHaveButton', action: 'alreadyHave' }),
          expert && installed ? btn('Use installed APK v' + APP_VERSION, { icon: 'phone', action: 'useApk' }) : '',
          saved ? btn('Use saved APK v' + APP_VERSION, { icon: 'history', tg: 'morphe.apkHelp.savedButton', action: 'useApk' }) : ''),
        { orange: true });
    },
    actions: {
      findApk(ctx) {
        ctx.toast('Get it from BitChord’s release page, then come back.');
        ctx.go('browser.bitchordRelease', null, { dir: 'fade' });
      },
      alreadyHave(ctx) {
        ctx.open('morphe.selectApk');
        ctx.close('morphe.apkHelp');
      },
      useApk(ctx) {
        versionCheck(ctx);
        ctx.close('morphe.apkHelp');
      }
    }
  });

  G.screen({
    id: 'morphe.selectApk',
    app: 'morphe',
    kind: 'full',
    title: 'Select downloaded APK',
    render() {
      return dlg('<span class="m-dlg-hero">' + icon('apk', { size: 40 }) + '</span>' +
        '<h2 class="m-dlg-title">Select downloaded APK</h2>' +
        '<p class="m-dlg-text">Select the <b>BitChord</b> APK file you just downloaded</p>' +
        col(btn('Open APK file', { fill: true, icon: 'folderOpen', tg: 'morphe.selectApk.openFileButton', open: 'android.filePicker', params: { mode: 'apk' } }),
          btn('Cancel', { close: true, tg: 'morphe.selectApk.cancelButton' })),
        { orange: true });
    }
  });

  G.screen({
    id: 'morphe.experimentDialog',
    app: 'morphe',
    kind: 'full',
    title: 'Do you want to experiment?',
    render() {
      return dlg('<h2 class="m-dlg-title">Do you want to experiment? 🧪</h2>' +
        '<p class="m-dlg-text">This version of <b>BitChord</b> has early experimental support<br><br>🔧 Expect quirky app behavior or unidentified bugs as the patches are refined for this app version</p>' +
        row2(btn('Cancel', { close: true, tg: 'morphe.experimentDialog.cancelButton' }),
          btn('Proceed anyway', { fill: true, tg: 'morphe.experimentDialog.proceedButton', action: 'proceed' })),
        { orange: true });
    },
    actions: {
      proceed(ctx) {
        const s = ctx.s;
        if (s['morphe.expertMode']) {
          ctx.open(!s['morphe.bgProtectionAllowed'] && !s['morphe.bgProtectionAsked'] ? 'morphe.bgProtection' : 'morphe.patchList');
          ctx.close('morphe.experimentDialog');
        } else {
          startPatching(ctx);
        }
      }
    }
  });

  G.screen({
    id: 'morphe.unsupportedDialog',
    app: 'morphe',
    kind: 'full',
    title: 'Unsupported Version',
    render() {
      return dlg('<h2 class="m-dlg-title">Unsupported Version</h2>' +
        '<p class="m-dlg-text">This APK is not a recommended app version. Proceeding may result in a non-functional app</p>' +
        '<div class="m-vcard"><span class="m-vcard-col"><span class="m-vcard-label">Selected version</span>' +
          '<span class="m-vcard-version">v' + APP_VERSION + '</span></span>' + badge('Experimental', 'warn', { icon: 'science' }) + '</div>' +
        '<p class="m-dlg-note">This version has experimental support and may be unstable or incomplete</p>' +
        row2(btn('Cancel', { close: true }), btn('Proceed anyway', { fill: true, action: 'proceed', tg: 'morphe.unsupportedDialog.proceedButton' })),
        { orange: true });
    },
    actions: {
      proceed(ctx) {
        ctx.close('morphe.unsupportedDialog');
        ctx.toast('Without Experimental app versions, my patches aren’t used. Turn it on under Sources.');
      }
    }
  });

  G.screen({
    id: 'morphe.bgProtection',
    app: 'morphe',
    kind: 'full',
    title: 'Background patching protection',
    render() {
      return dlg('<span class="m-dlg-hero">' + icon('battery', { size: 40 }) + '</span>' +
        '<h2 class="m-dlg-title">Background patching protection</h2>' +
        '<p class="m-dlg-text">Some devices may stop the patcher when the app is minimized. Allow Morphe to run without battery restrictions to ensure patching completes reliably</p>' +
        row2(btn('Not now', { action: 'answer', data: { allow: 'false' }, tg: 'morphe.bgProtection.notNowButton' }),
          btn('Allow', { fill: true, action: 'answer', data: { allow: 'true' }, tg: 'morphe.bgProtection.allowButton' })));
    },
    actions: {
      answer(ctx, node) {
        const allow = node.getAttribute('data-allow') === 'true';
        ctx.set({ 'morphe.bgProtectionAllowed': allow || ctx.get('morphe.bgProtectionAllowed'), 'morphe.bgProtectionAsked': true });
        ctx.open('morphe.patchList');
        ctx.close('morphe.bgProtection');
      }
    }
  });

  // =======================================================================================
  // Expert mode: the patch list and the patch options
  // =======================================================================================

  function checkMark(on) {
    return '<span class="m-check' + (on ? ' is-on' : '') + '" aria-hidden="true">' + (on ? icon('check', { size: 16, stroke: 3 }) : '') + '</span>';
  }

  function patchCard(s, p, universal) {
    const key = (universal ? 'morphe.upatch.' : 'morphe.patch.') + p.id;
    const on = !!s[key];
    const changed = (p.id === 'telegram' && (Number(s['morphe.opt.apiId']) !== 0 || !!s['morphe.opt.apiHash'])) ||
      (p.id === 'bitchordPlus' && !!String(s['morphe.opt.installationName'] || '').trim());
    const gearTg = p.id === 'telegram' ? 'morphe.patchList.telegramGear' : p.id === 'bitchordPlus' ? 'morphe.patchList.installAsGear' : '';
    return '<div class="m-patch' + (on ? ' is-on' : '') + '" data-tg="morphe.patchList.patch.' + p.id + '" data-action="togglePatch" data-patch="' + p.id + '"' +
        (universal ? ' data-universal="1"' : '') + '>' +
      '<button type="button" class="m-patch-main" role="checkbox" aria-checked="' + on + '" data-action="togglePatch" data-patch="' + p.id + '"' +
        (universal ? ' data-universal="1"' : '') + '>' + checkMark(on) +
        '<span class="m-patch-text"><span class="m-patch-name">' + esc(p.name) +
          (p.clone ? badge('Clone', 'warn', { icon: 'copy' }) : '') + '</span>' +
        '<span class="m-patch-desc">' + esc(p.desc) + '</span></span></button>' +
      (p.options ? '<button type="button" class="m-gear' + (on && changed ? ' is-changed' : '') + '" aria-label="' + esc(p.name) + ', Settings' + (on && changed ? ', Customized' : '') + '"' +
        (gearTg ? ' data-tg="' + gearTg + '"' : '') + ' data-action="openOptions" data-patch="' + p.id + '"' + (on ? '' : ' aria-disabled="true"') + '>' +
        icon('settings', { size: 18 }) + '</button>' : '') +
    '</div>';
  }

  G.screen({
    id: 'morphe.patchList',
    app: 'morphe',
    kind: 'full',
    title: 'BitChord: Expert mode patch selection',
    className: 'm-ov-fixed',
    render(ctx) {
      const s = ctx.s;
      const tab = s['morphe.patchTab'] === 'morphe' ? 'morphe' : 'bitchord';
      const nB = selectedBitchord(s);
      const nU = selectedUniversal(s);
      const onlySel = s['morphe.selectedOnly'];
      const keep = (p, universal) => !onlySel || s[(universal ? 'morphe.upatch.' : 'morphe.patch.') + p.id];
      let list;
      if (tab === 'bitchord') {
        list = notice('warn', 'science', 'This source has only experimental support for this app version. Its patches may be unstable or incomplete', { key: 'n-exp' }) +
          '<div class="m-group" data-tg="morphe.patchList.defaultPatches" data-key="g-default">' +
            DEFAULT_PATCHES.filter((p) => keep(p)).map((p) => patchCard(s, p)).join('') + '</div>' +
          '<div class="m-group" data-tg="morphe.patchList.extraPatches" data-key="g-extra">' +
            EXTRA_PATCHES.filter((p) => keep(p)).map((p) => patchCard(s, p)).join('') + '</div>';
      } else {
        const open = s['morphe.universalOpen'];
        list = '<div class="m-group" data-key="g-universal">' +
          '<button type="button" class="m-fold" aria-expanded="' + !!open + '" data-bind="morphe.universalOpen">' + icon('globe', { size: 20 }) +
            '<span class="m-fold-title">Universal patches</span><span class="m-fold-count">' + nU + '/' + UNIVERSAL.length + '</span>' +
            icon('chevronDown', { size: 20, cls: 'm-fold-chev' }) + '</button>' +
          (open ? UNIVERSAL.filter((p) => keep(p, true)).map((p) => patchCard(s, p, true)).join('') : '') + '</div>';
      }
      const saved = s['morphe.installed'];
      const tool = (ic, label, action, o) => '<button type="button" class="m-tool"' +
        attrs(Object.assign({ label: label, title: label, action: action }, o || {})) + '>' + icon(ic, { size: 20 }) + '</button>';
      return '<div class="m-pl m-tint-orange">' +
        '<div class="m-pl-head">' + appIcon(48, 'B', 'm-appicon-sq') +
          '<span class="m-pl-title"><span class="m-pl-name">BitChord</span><span class="m-pl-sub">Expert mode</span></span>' +
          '<button type="button" class="m-counter" aria-pressed="' + !!onlySel + '" data-bind="morphe.selectedOnly" data-tg="morphe.patchList.counter" aria-label="' + (nB + nU) + ' of ' + (PATCHES.length + UNIVERSAL.length) + ' patches selected. Show selected patches only">' +
            icon('filter', { size: 16 }) + '<span>' + (nB + nU) + '/' + (PATCHES.length + UNIVERSAL.length) + '</span></button>' +
          round('search', 'Search patches', { small: true, toast: NOT_DRAWN }) +
        '</div>' +
        '<div class="m-pl-tabs" role="tablist" aria-label="Patch sources">' +
          '<button type="button" class="m-tab" role="tab" aria-selected="' + (tab === 'bitchord') + '" data-bind="morphe.patchTab" data-value="bitchord" data-tg="morphe.patchList.bitchordTab">' +
            '<span class="m-tab-av m-avatar-owner">' + icon('person', { size: 14 }) + '</span><span>' + SOURCE_NAME + '</span><span class="m-tab-count">' + nB + '/' + PATCHES.length + '</span></button>' +
          '<button type="button" class="m-tab" role="tab" aria-selected="' + (tab === 'morphe') + '" data-bind="morphe.patchTab" data-value="morphe" data-tg="morphe.patchList.morpheTab">' +
            '<span class="m-tab-av m-avatar-plain">' + icon('extension', { size: 14 }) + '</span><span>Morphe Patches</span><span class="m-tab-count">' + nU + '/' + UNIVERSAL.length + '</span></button>' +
        '</div>' +
        '<div class="m-pl-tools" role="group" aria-label="Selection">' +
          tool('doneAll', 'Enable all', 'enableAll') +
          tool('recommend', 'Enable recommended patches', 'recommended') +
          tool('history', 'Restore saved selection', 'restore', { disabled: !saved }) +
          tool('copy', 'Copy selection from another bundle', 'copySel', { disabled: true }) +
          tool('clearAll', 'Disable all', 'disableAll') +
        '</div>' +
        '<div class="m-pl-list" role="tabpanel" aria-label="' + (tab === 'bitchord' ? SOURCE_NAME : 'Morphe Patches') + '">' + list + '</div>' +
        '<div class="m-pl-foot">' + btn('Patch', { solid: true, icon: 'wand', cls: 'm-btn-wide', tg: 'morphe.patchList.patchButton', action: 'patch', disabled: nB + nU === 0 }) + '</div>' +
      '</div>';
    },
    actions: {
      togglePatch(ctx, node) {
        const id = node.getAttribute('data-patch');
        const key = (node.hasAttribute('data-universal') ? 'morphe.upatch.' : 'morphe.patch.') + id;
        ctx.set(key, !ctx.get(key));
      },
      openOptions(ctx, node) { ctx.open('morphe.patchOptions', { patch: node.getAttribute('data-patch') }); },
      enableAll(ctx) {
        if (ctx.get('morphe.patchTab') === 'morphe') { ctx.toast('Tap again for universal patches'); return; }
        const set = {};
        PATCHES.forEach((p) => { set['morphe.patch.' + p.id] = true; });
        ctx.set(set);
        ctx.toast('All patches enabled');
      },
      recommended(ctx) {
        const set = {};
        if (ctx.get('morphe.patchTab') === 'morphe') UNIVERSAL.forEach((p) => { set['morphe.upatch.' + p.id] = false; });
        else PATCHES.forEach((p) => { set['morphe.patch.' + p.id] = !!p.def; });
        ctx.set(set);
        ctx.toast('Recommended patches applied');
      },
      restore(ctx) { ctx.toast('Saved selection restored'); },
      disableAll(ctx) {
        const set = {};
        if (ctx.get('morphe.patchTab') === 'morphe') UNIVERSAL.forEach((p) => { set['morphe.upatch.' + p.id] = false; });
        else PATCHES.forEach((p) => { set['morphe.patch.' + p.id] = false; });
        ctx.set(set);
        ctx.toast('All patches disabled');
      },
      patch(ctx) {
        const s = ctx.s;
        const nB = selectedBitchord(s), nU = selectedUniversal(s);
        if (!nB && !nU) return;
        if (nB && nU) { ctx.open('morphe.multiSourceDialog'); return; }
        startPatching(ctx);
      }
    }
  });

  confirmDialog({
    id: 'morphe.multiSourceDialog',
    title: 'Multiple patch sources selected',
    render() {
      return dlg('<span class="m-dlg-hero m-dlg-hero-warn">' + icon('warning', { size: 40 }) + '</span>' +
        '<h2 class="m-dlg-title">Multiple patch sources selected</h2>' +
        '<p class="m-dlg-text">You have selected patches from multiple patch sources. This may cause compatibility issues or unexpected behavior.</p>' +
        row2(btn('Cancel', { close: true }), btn('Proceed anyway', { fill: true, action: 'proceed' })));
    },
    actions: { proceed(ctx) { startPatching(ctx); } }
  });

  function optionCard(ctx, o) {
    return '<div class="m-opt" data-key="' + esc(o.id) + '">' +
      '<div class="m-opt-head"><span class="m-opt-title" id="' + esc(o.id) + '">' + esc(o.title) + '</span>' +
        (o.changed ? '<button type="button" class="m-badge m-badge-primary m-badge-btn" data-set="' + esc(JSON.stringify({ [o.bind]: o.reset })) + '" aria-label="Changed. Reset ' + esc(o.title) + '">' +
          icon('history', { size: 14, stroke: 2.4 }) + 'Changed</button>' : '') + '</div>' +
      '<p class="m-opt-desc">' + esc(o.desc) + '</p>' +
      '<div class="m-ofield m-ofield-plain">' +
        ctx.ui.field({ bind: o.bind, tg: o.tg, labelledby: o.id, placeholder: o.placeholder, type: o.type, inputmode: o.inputmode, cls: 'm-ofield-input' }).replace('<input', '<input' + (o.max ? ' maxlength="' + o.max + '"' : '')) +
        (o.hasText ? '<button type="button" class="m-ofield-btn" aria-label="Clear ' + esc(o.title) + '" data-set="' + esc(JSON.stringify({ [o.bind]: o.reset })) + '">' + icon('close', { size: 18 }) + '</button>' : '') +
      '</div></div>';
  }

  G.screen({
    id: 'morphe.patchOptions',
    app: 'morphe',
    kind: 'full',
    title: 'Patch options',
    className: 'm-ov-fixed',
    render(ctx) {
      const s = ctx.s;
      const id = ctx.params.patch === 'bitchordPlus' ? 'bitchordPlus' : 'telegram';
      const p = PATCH_BY_ID[id];
      let cards, changed;
      if (id === 'telegram') {
        const apiId = Number(s['morphe.opt.apiId']) || 0;
        const hash = String(s['morphe.opt.apiHash'] || '');
        changed = apiId !== 0 || hash !== '';
        cards = optionCard(ctx, { id: 'm-opt-apiid', title: 'Telegram application API ID', desc: 'Leave at 0 and enter your API ID in the app after installing.',
            bind: 'morphe.opt.apiId', tg: 'morphe.patchOptions.apiIdField', placeholder: 'Enter number', type: 'number', inputmode: 'numeric',
            changed: apiId !== 0, reset: 0, hasText: true }) +
          optionCard(ctx, { id: 'm-opt-apihash', title: 'Telegram application API hash', desc: 'Leave empty and enter your API hash in the app after installing.',
            bind: 'morphe.opt.apiHash', tg: 'morphe.patchOptions.apiHashField', placeholder: 'Enter value', changed: hash !== '', reset: '', hasText: hash !== '' });
      } else {
        const name = String(s['morphe.opt.installationName'] || '');
        changed = name !== '';
        cards = optionCard(ctx, { id: 'm-opt-name', title: 'Installation name',
          desc: 'The app name on your home screen, up to 80 characters. Leave blank for Bitchord+. A name containing Telegram must include Unofficial Telegram.',
          bind: 'morphe.opt.installationName', tg: 'morphe.patchOptions.installationNameField', placeholder: 'Enter value', changed: changed, reset: '', hasText: changed, max: 80 });
      }
      const reset = id === 'telegram' ? { 'morphe.opt.apiId': 0, 'morphe.opt.apiHash': '' } : { 'morphe.opt.installationName': '' };
      return '<div class="m-pl m-tint-orange">' +
        '<div class="m-pl-head">' + appIcon(48, 'B', 'm-appicon-sq') +
          '<span class="m-pl-title"><span class="m-pl-name">' + esc(p.name) + '</span><span class="m-pl-sub">BitChord · ' + plural(p.options, 'patch option', 'patch options') + '</span></span>' +
          round('history', 'Reset', { small: true, set: reset, disabled: !changed, tg: 'morphe.patchOptions.resetButton' }) +
        '</div>' +
        '<div class="m-pl-list m-po-list"><p class="m-po-desc">' + esc(p.desc) + '</p>' + cards + '</div>' +
        '<div class="m-pl-foot">' + btn('Close', { cls: 'm-btn-wide m-btn-opaque', close: true, tg: 'morphe.patchOptions.closeButton' }) + '</div>' +
      '</div>';
    }
  });

  // =======================================================================================
  // Patching progress and the result
  // =======================================================================================

  const PHRASES = [
    'Morphing your app into something awesome… 🏆',
    'Brewing digital magic… ☕',
    'Your app has a fever, and the only prescription is more patches… 🩺🔔'
  ];
  function stepName(p) {
    if (p >= 100) return 'Patching complete! ✨';
    if (p < 8) return 'Loading patches';
    if (p < 16) return 'Read APK file';
    if (p < 78) return 'Applying patches';
    if (p < 92) return 'Writing patched APK file';
    return 'Signing patched APK file';
  }
  function appliedCount(p, total) {
    if (p < 16) return 0;
    if (p >= 78) return total;
    return Math.min(total, Math.floor((p - 16) / 62 * total) + 1);
  }
  function selectedNames(s) {
    if (!s['morphe.expertMode']) return DEFAULT_PATCHES.map((p) => p.name);
    return PATCHES.filter((p) => s['morphe.patch.' + p.id]).map((p) => p.name)
      .concat(UNIVERSAL.filter((p) => s['morphe.upatch.' + p.id]).map((p) => p.name)).sort();
  }
  // Morphe's wavy progress ring: a faint track and a wavy arc for the part that is done.
  function wavyRing(pct, size) {
    const c = size / 2, R = c - 14, A = 3.2, N = 18;
    const track = '<circle cx="' + c + '" cy="' + c + '" r="' + R + '" class="m-ring-track"/>';
    let arc = '';
    const frac = Math.max(0, Math.min(1, pct / 100));
    if (frac > 0) {
      const steps = Math.max(12, Math.round(220 * frac));
      let d = '';
      for (let i = 0; i <= steps; i++) {
        const t = (i / steps) * frac * Math.PI * 2;
        const r = R + A * Math.sin(N * t);
        d += (i ? 'L' : 'M') + (c + r * Math.sin(t)).toFixed(1) + ' ' + (c - r * Math.cos(t)).toFixed(1);
      }
      arc = '<path d="' + d + '" class="m-ring-arc"/>';
    }
    return '<svg class="m-ring-svg" viewBox="0 0 ' + size + ' ' + size + '" aria-hidden="true">' + track + arc + '</svg>';
  }
  function wavyBar(pct) {
    const W = 300, H = 14, mid = H / 2, x = Math.max(0, Math.min(W, W * pct / 100));
    let d = '';
    for (let i = 0; i <= Math.ceil(x / 2); i++) {
      const px = Math.min(x, i * 2);
      d += (i ? 'L' : 'M') + px.toFixed(1) + ' ' + (mid + 3 * Math.sin(px / 5)).toFixed(1);
    }
    return '<svg class="m-bar-svg" viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" aria-hidden="true">' +
      (x < W - 6 ? '<path d="M' + Math.min(W, x + 6).toFixed(1) + ' ' + mid + 'L' + W + ' ' + mid + '" class="m-bar-track" vector-effect="non-scaling-stroke"/>' : '') +
      (x > 0 ? '<path d="' + d + '" class="m-bar-done" vector-effect="non-scaling-stroke"/>' : '') + '</svg>';
  }

  function finishPatching() {
    G.set({ 'morphe.patching.status': 'done', 'morphe.patching.progress': 100 });
    G.later(() => {
      if (G.current().screen === 'morphe.patching') G.go('morphe.result', null, { dir: 'fade', noHistory: true });
    }, 700);
  }

  G.screen({
    id: 'morphe.patching',
    app: 'morphe',
    title: 'Morphe Manager: Patching',
    className: 'm-screen m-screen-plain',
    render(ctx) {
      const s = ctx.s;
      const pct = Math.round(Math.max(0, Math.min(100, Number(s['morphe.patching.progress']) || 0)));
      const total = patchTotal(s);
      const done = appliedCount(pct, total);
      const step = stepName(pct);
      const slow = step === 'Applying patches';
      const aria = ' role="progressbar" aria-label="Patching progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + pct + '" aria-valuetext="' + pct + '%, ' + esc(step) + '"';
      if (!s['morphe.expertMode']) {
        return '<div class="m-patching m-tint-orange">' +
          '<p class="m-phrase">' + esc(PHRASES[Math.min(2, Math.floor(pct / 34))]) + '</p>' +
          '<div class="m-ring" data-tg="morphe.patching.progress"' + aria + '>' + wavyRing(pct, 260) +
            '<span class="m-ring-center"><span class="m-ring-pct">' + pct + '%</span><span class="m-ring-count">' + done + ' / ' + total + ' patches</span></span></div>' +
          '<p class="m-step">' + esc(step) + '</p>' +
          '<p class="m-step-hint"' + (slow ? '' : ' hidden') + '>This step may take a while. Please wait…</p>' +
          '<div class="m-patching-foot">' + btn('Cancel', { icon: 'close', danger: true, cls: 'm-btn-wide', tg: 'morphe.patching.cancelButton', open: 'morphe.stopPatcher' }) + '</div>' +
        '</div>';
      }
      // Expert mode shows the patcher's own view: steps, usage and the log.
      const names = selectedNames(s);
      const lines = [];
      if (pct >= 0) lines.push('Loading patches');
      if (pct >= 8) lines.push('Read APK file');
      if (pct >= 16) names.slice(0, done).forEach((n) => lines.push('"' + n + '" succeeded'));
      if (pct >= 78) lines.push('Writing patched APK file');
      if (pct >= 92) lines.push('Signing patched APK file');
      if (pct >= 100) lines.push('Patching succeeded');
      const mem = (0.25 + 0.75 * pct / 100).toFixed(1);
      const cpu = pct >= 100 ? 2 : 35 + (pct * 7) % 40;
      const io = pct >= 100 ? 0 : 6 + (pct * 3) % 24;
      const stat = (label, value, frac) => '<div class="m-stat"><span class="m-stat-label">' + esc(label) + '</span><span class="m-stat-value">' + esc(value) + '</span>' +
        '<span class="m-stat-bar"><span style="width:' + Math.round(frac * 100) + '%"></span></span></div>';
      const tab = s['morphe.patchingTab'] === 'games' ? 'games' : 'logs';
      return '<div class="m-xp m-tint-orange">' +
        '<div class="m-xp-head" data-tg="morphe.patching.progress"' + aria + '>' +
          '<p class="m-xp-title">' + (pct >= 100 ? 'All done! 🎉' : 'BitChord') + '</p>' +
          '<p class="m-xp-step">' + esc(step) + '</p>' +
          '<p class="m-xp-chips"><span class="m-chip">' + done + ' / ' + total + '</span><span class="m-chip">' + pct + '%</span></p>' +
          wavyBar(pct) +
        '</div>' +
        '<div class="m-stats">' + stat('Memory usage', mem + ' / 1.3 GB', mem / 1.3) + stat('Storage I/O', io + ' MB/s', io / 30) + stat('CPU usage', cpu + '%', cpu / 100) + '</div>' +
        '<div class="m-xp-panel">' +
          '<div class="m-xp-tabs" role="group" aria-label="Panel">' +
            '<button type="button" aria-pressed="' + (tab === 'logs') + '" data-bind="morphe.patchingTab" data-value="logs">' + icon('article', { size: 18 }) + '<span>Patcher logs</span></button>' +
            '<button type="button" aria-pressed="' + (tab === 'games') + '" data-bind="morphe.patchingTab" data-value="games">' + icon('gamepad', { size: 18 }) + '<span>Games</span></button></div>' +
          (tab === 'logs'
            ? '<ol class="m-log" aria-label="Patcher log">' + lines.map((l, i) => '<li data-key="l' + i + '">' + esc(l) + '</li>').join('') + '</ol>'
            : '<p class="m-games">A small game to pass the time. It isn’t drawn in this guide.</p>') +
        '</div>' +
        '<div class="m-xp-foot">' + btn('Cancel', { icon: 'close', danger: true, tg: 'morphe.patching.cancelButton', open: 'morphe.stopPatcher' }) +
          btn('Copy', { icon: 'copy', toast: 'Copied the log' }) + '</div>' +
      '</div>';
    },
    mount(node, ctx) {
      // Out of a tour the progress runs to the end and the result follows. In a tour it stops
      // short of the end, and the tour's Next moves on.
      const tick = () => {
        if (G.get('morphe.patching.status') !== 'running') return;
        const inTour = G.inTour();
        const p = Number(G.get('morphe.patching.progress')) || 0;
        const cap = inTour ? 86 : 100;
        if (p >= cap) {
          if (!inTour) finishPatching();
          return;
        }
        G.set('morphe.patching.progress', Math.min(cap, p + (p < 16 ? 4 : p < 78 ? 3 : 4)));
        ctx.later(tick, 230);
      };
      ctx.later(tick, 450);
    },
    onBack(ctx) {
      if (ctx.get('morphe.patching.status') === 'running') ctx.open('morphe.stopPatcher');
      else goPage(ctx, 'apps');
    }
  });

  confirmDialog({
    id: 'morphe.stopPatcher',
    title: 'Stop patcher',
    render() {
      return dlg('<h2 class="m-dlg-title">Stop patcher</h2><p class="m-dlg-text">Are you sure you want to stop the patching process?</p>' +
        row2(btn('Cancel', { close: true }), btn('Stop', { fill: true, danger: true, action: 'stop', tg: 'morphe.stopPatcher.stopButton' })));
    },
    actions: {
      stop(ctx) {
        ctx.set({ 'morphe.patching.status': 'idle', 'morphe.patching.progress': 0 });
        ctx.go('morphe.apps', null, { dir: 'back', noHistory: true });
      }
    }
  });

  G.screen({
    id: 'morphe.result',
    app: 'morphe',
    title: 'Morphe Manager: Patching result',
    className: 'm-screen m-screen-plain',
    render(ctx) {
      const s = ctx.s;
      const failed = s['morphe.patching.status'] === 'failed';
      const conflict = !failed && s['morphe.install.error'] === 'conflict';
      const installing = !failed && !conflict && s['morphe.installing'];
      // Open shows once this build is installed. A new build of an installed app (an update)
      // still shows Install until it is installed over it.
      const isInstalled = !failed && !conflict && !installing && s['morphe.installed'] && !s['morphe.appUpdateChip'];
      const expert = s['morphe.expertMode'];
      let status, sub, markIc, tone;
      if (failed) { status = 'Patching failed'; sub = 'Oops! Something went wrong'; markIc = 'close'; tone = 'error'; }
      else if (conflict) { status = 'Package conflict'; sub = 'You need to uninstall the existing version'; markIc = 'error'; tone = 'error'; }
      else if (installing) { status = 'Installing…'; sub = 'Almost ready to rock! 🎸'; markIc = 'installPhone'; tone = 'neutral'; }
      else if (isInstalled) { status = 'Installed'; sub = 'Your app is patched and installed!'; markIc = 'check'; tone = 'success'; }
      else { status = 'Patched'; sub = ''; markIc = 'check'; tone = 'primary'; }
      const n = patchTotal(s);
      let notices = '';
      if (failed) {
        notices = '<div class="m-notice m-notice-error m-notice-col" data-tg="morphe.result.errorNotice" data-key="n-failed">' +
          '<span class="m-notice-line">' + icon('error', { size: 20 }) + '<span>Unknown error occurred</span></span>' +
          '<button type="button" class="m-notice-action" data-toast="The error details show what failed. Remove private details before you share them.">Error details</button></div>';
      } else if (conflict) {
        notices = '<div class="m-notice m-notice-warn" data-tg="morphe.result.errorNotice" data-key="n-conflict">' + icon('warning', { size: 20 }) +
          '<span>The patched app uses a different certificate than the installed version. Uninstalling will erase all app data</span></div>';
      }
      let actions;
      if (failed) {
        actions = btn('Change patches', { icon: 'tune', cls: 'm-btn-wide', action: 'changePatches', tg: 'morphe.result.changePatchesButton' }) +
          '<div class="m-btns">' + btn('Logs', { icon: 'article', tg: 'morphe.result.logsButton', toast: 'Shows the patcher log. Remove private details before you share it.' }) +
            btn('Copy', { icon: 'copy', toast: 'Error copied to clipboard' }) + '</div>' +
          btn('Home', { icon: 'home', cls: 'm-btn-wide', tg: 'morphe.result.homeButton', action: 'home' });
      } else {
        let main;
        if (isInstalled) main = '<button type="button" class="m-cbtn m-cbtn-main m-btn-solid" data-tg="morphe.result.openButton" data-action="open">' + icon('openInNew', { size: 20 }) + '<span>Open</span></button>';
        else if (conflict) main = '<button type="button" class="m-cbtn m-cbtn-main m-btn-solid" data-tg="morphe.result.installButton" data-action="uninstall">' + icon('delete', { size: 20 }) + '<span>Uninstall</span></button>';
        else main = '<button type="button" class="m-cbtn m-cbtn-main m-btn-solid" data-tg="morphe.result.installButton" data-action="install"' + (installing ? ' aria-disabled="true"' : '') + '>' +
          icon('installPhone', { size: 20 }) + '<span>' + (installing ? 'Installing…' : 'Install') + '</span></button>';
        actions = '<div class="m-connected m-connected-tall">' + main +
            '<button type="button" class="m-cbtn" aria-label="Save" title="Save" data-toast="APK Saved">' + icon('save', { size: 20 }) + '</button>' +
            '<button type="button" class="m-cbtn" aria-label="Share" title="Share" data-toast="Shares the patched APK.">' + icon('share', { size: 20 }) + '</button></div>' +
          (expert ? '<div class="m-btns">' + btn('Logs', { icon: 'article', tg: 'morphe.result.logsButton', toast: 'Shows the patcher log. Remove private details before you share it.' }) +
            btn('Home', { icon: 'home', tg: 'morphe.result.homeButton', action: 'home' }) + '</div>'
            : btn('Home', { icon: 'home', cls: 'm-btn-wide', tg: 'morphe.result.homeButton', action: 'home' }));
      }
      return '<div class="m-res m-tint-orange">' +
        '<div class="m-res-main">' +
          '<span class="m-res-icon' + (failed || conflict ? ' is-bad' : '') + '">' + appIcon(104) +
            '<span class="m-res-mark m-res-mark-' + tone + '">' + icon(markIc, { size: 18, stroke: 3 }) + '</span></span>' +
          badge(status, tone, { icon: failed ? 'close' : conflict ? 'error' : installing ? 'installPhone' : 'check' }) +
          '<h2 class="m-res-name">BitChord</h2>' +
          (sub ? '<p class="m-res-sub">' + esc(sub) + '</p>' : '') +
          '<div class="m-info m-res-info">' +
            infoRow('history', 'Version', 'v' + APP_VERSION, { static: true, chevron: false }) +
            infoRow('doneAll', 'Patches', plural(n, 'patch', 'patches'), { static: true, chevron: false }) +
            infoRow('source', 'Source', (testingBringsBitchord(s) && !normalBringsBitchord(s) ? testingName(s) : normalName(s)) + ' ' + PATCHES_VERSION, { static: true, chevron: false }) +
          '</div>' + notices +
        '</div>' +
        '<div class="m-res-actions">' + actions + '</div>' +
      '</div>';
    },
    onBack(ctx) { goPage(ctx, 'apps'); },
    actions: {
      install(ctx) {
        if (ctx.get('android.unknownAppsAllowed')) ctx.open('android.installPrompt', { app: 'bitchordPlus' });
        else ctx.open('android.unknownApps', { source: 'morphe', app: 'bitchordPlus' });
      },
      uninstall(ctx) { ctx.open('android.uninstallPrompt'); },
      open(ctx) { G.launch('bitchordPlus'); },
      home(ctx) { goPage(ctx, 'apps'); },
      changePatches(ctx) {
        if (ctx.get('morphe.expertMode')) ctx.open('morphe.patchList');
        else ctx.toast('To change patches, turn on Expert mode in Settings, Patching.');
      }
    }
  });

  // The BitChord card on Apps once Bitchord+ is installed.
  G.screen({
    id: 'morphe.appInfo',
    app: 'morphe',
    kind: 'full',
    title: 'App info',
    render(ctx) {
      const s = ctx.s;
      const n = patchTotal(s);
      return dlg(
        '<div class="m-appinfo-head">' + appIcon(64) +
          '<span class="m-appinfo-title"><span class="m-appinfo-name">' + esc(installedName(s)) + '</span><span class="m-appinfo-sub">BitChord · v' + APP_VERSION + '</span></span></div>' +
        (s['morphe.appUpdateChip'] ? '<div class="m-notice m-notice-primary m-notice-col" data-tg="morphe.appInfo.updateNotice">' +
          '<span class="m-notice-line">' + icon('refresh', { size: 20 }) + '<b>Patch update available</b></span>' +
          '<span>A newer version of patches is available. Repatch your app to get the latest improvements and fixes</span></div>' : '') +
        '<div class="m-info">' +
          infoRow('history', 'Version', 'v' + APP_VERSION, { static: true, chevron: false }) +
          infoRow('source', 'Patch source used', normalName(s), { static: true, chevron: false }) +
          '<div class="m-info-row"><span class="m-info-ic">' + icon('doneAll', { size: 20 }) + '</span>' +
            '<span class="m-info-text"><span class="m-info-label">Applied patches</span><span class="m-info-value">' + plural(n, 'patch', 'patches') + '</span></span>' +
            '<button type="button" class="m-mini-btn" data-action="viewPatches">View</button></div>' +
          infoRow('installPhone', 'Install type', 'System installer', { static: true, chevron: false }) +
        '</div>' +
        row2(btn('Open', { icon: 'openInNew', tg: 'morphe.appInfo.openButton', action: 'open' }),
          btn('Patch', { fill: true, icon: 'wand', tg: 'morphe.appInfo.patchButton', action: 'patch' })) +
        '<div class="m-acts">' +
          actPill('upload', 'Export', { toast: 'APK Saved' }) + actPill('share', 'Share', { toast: 'Shares the patched APK.' }) +
          actPill('delete', 'Uninstall', { action: 'uninstall', danger: true }) + actPill('trash', 'Delete', { toast: 'Deletes the saved APKs and Morphe’s record of this app.', danger: true }) +
        '</div>' +
        btn('Close', { cls: 'm-btn-wide', close: true, tg: 'morphe.appInfo.closeButton' }),
        { orange: true, cls: 'm-dlg-left' });
    },
    actions: {
      open(ctx) { ctx.close('morphe.appInfo'); G.launch('bitchordPlus'); },
      viewPatches(ctx) { ctx.toast(selectedNames(ctx.s).join(', ')); },
      uninstall(ctx) { ctx.open('android.uninstallPrompt'); },
      patch(ctx) {
        // Morphe kept the original APK. Simple mode uses it right away; Expert mode asks.
        if (ctx.get('morphe.expertMode')) ctx.open('morphe.apkHelp');
        else versionCheck(ctx);
        ctx.close('morphe.appInfo');
      }
    }
  });

  // =======================================================================================
  // Android: the browser (two tabs), Files › Downloads, the file picker and the installer
  // =======================================================================================

  const FILES = [
    { name: BITCHORD_APK, size: '84.7 MB', time: '10:32', kind: 'apk', tg: 'bitchordApk' },
    { name: MORPHE_APK, size: '41.7 MB', time: '10:24', kind: 'apk', tg: 'morpheApk' }
  ];
  const MPP = { name: MPP_FILE, size: '35.7 MB', time: '10:36', kind: 'mpp', tg: 'mppFile' };

  function browserChrome(url, other) {
    return '<div class="ma-chrome">' +
      '<button type="button" class="ma-ic" aria-label="Home page" data-toast="This guide only draws two tabs.">' + icon('home', { size: 22 }) + '</button>' +
      '<div class="ma-url" aria-label="Address">' + icon('lock', { size: 16 }) + '<span>' + esc(url) + '</span></div>' +
      '<button type="button" class="ma-tabs" aria-label="Switch to the other tab: ' + esc(other.title) + '" data-go="' + other.id + '" data-dir="fade">2</button>' +
      '<button type="button" class="ma-ic" aria-label="More" data-toast="This guide only draws two tabs.">' + icon('moreVert', { size: 20 }) + '</button>' +
    '</div>';
  }
  function asset(o) {
    return '<button type="button" class="ma-asset' + (o.dim ? ' is-dim' : '') + '"' + attrs(o.attrs) + '>' +
      icon(o.icon || 'apk', { size: 20 }) + '<span class="ma-asset-name">' + esc(o.name) + '</span>' +
      (o.size ? '<span class="ma-asset-size">' + esc(o.size) + '</span>' : '') + '</button>';
  }

  // ---------------------------------------------------------------------------------------
  // The browser: morphe.software, its tabs, my README and Morphe's add-source page
  // ---------------------------------------------------------------------------------------

  G.launcherApp('browser', { entry: 'browser.morpheSite' });

  // The address bar with a tab button that shows how many tabs are open.
  function tabbedChrome(url, ctx) {
    const count = (ctx.s['browser.tabs'] || []).length || 1;
    return '<div class="ma-chrome">' +
      '<button type="button" class="ma-ic" aria-label="Home page" data-toast="This guide doesn’t draw the browser’s home page.">' + icon('home', { size: 22 }) + '</button>' +
      '<div class="ma-url" aria-label="Address" data-tg="browser.address">' + icon('lock', { size: 16 }) + '<span>' + esc(url) + '</span></div>' +
      '<button type="button" class="ma-tabs" aria-label="Tabs: ' + count + ' open" data-go="browser.tabs" data-dir="fade" data-tg="browser.tabsButton">' + count + '</button>' +
      '<button type="button" class="ma-ic" aria-label="More" data-toast="This guide doesn’t draw this menu.">' + icon('moreVert', { size: 20 }) + '</button>' +
    '</div>';
  }
  // Opens a page in a tab of its own, or switches to the tab that already has it.
  function openTab(ctx, id) {
    const tabs = (ctx.get('browser.tabs') || []).slice();
    if (tabs.indexOf(id) < 0) tabs.push(id);
    ctx.set('browser.tabs', tabs);
    ctx.go(id, null, { dir: 'fade' });
  }
  const TAB_INFO = {
    'browser.morpheSite': { title: 'Morphe', url: 'morphe.software' },
    'browser.bitchordRelease': { title: 'Releases · kushagrasinghx/BitChord', url: 'github.com/kushagrasinghx/BitChord/releases' },
    'browser.patchesRepo': { title: 'Hexclyde/bitchord-morphe-patches', url: REPO_LINK },
    'browser.addSourcePage': { title: 'Add source · Morphe', url: 'morphe.software/add-source' }
  };

  // morphe.software, where Morphe Manager is downloaded.
  G.screen({
    id: 'browser.morpheSite',
    app: 'android',
    title: 'Browser: morphe.software',
    render(ctx) {
      return '<div class="ma-browser">' +
        tabbedChrome('morphe.software', ctx) +
        '<div class="ma-web ma-site">' +
          '<p class="ma-site-brand">Morphe</p>' +
          '<h2 class="ma-site-h">Patch the Android apps you use, on your phone</h2>' +
          '<p class="ma-site-lead">Morphe Manager adds patches to apps and installs the result next to the original.</p>' +
          '<button type="button" class="ma-site-btn" data-action="download" data-tg="browser.morpheSite.downloadButton">' +
            icon('download', { size: 20 }) + '<span>Download</span></button>' +
          '<p class="ma-site-note">Morphe Manager ' + MORPHE_VERSION + ' · Android 8 and newer</p>' +
        '</div></div>';
    },
    actions: {
      download(ctx) {
        ctx.set('browser.morpheDownloaded', true);
        ctx.toast('Download complete: ' + MORPHE_APK);
      }
    }
  });

  // The browser's open tabs, with New tab.
  G.screen({
    id: 'browser.tabs',
    app: 'android',
    title: 'Browser: tabs',
    render(ctx) {
      const tabs = ctx.s['browser.tabs'] || [];
      return '<div class="ma-browser ma-tabview">' +
        '<header class="ma-top"><h2 class="ma-top-title">' + tabs.length + (tabs.length === 1 ? ' tab' : ' tabs') + '</h2></header>' +
        '<div class="ma-tabgrid">' + tabs.map((id) => {
          const t = TAB_INFO[id] || { title: id, url: '' };
          return '<button type="button" class="ma-tabcard" data-go="' + id + '" data-dir="fade" data-tg="browser.tabs.' + id.split('.')[1] + '">' +
            '<span class="ma-tabcard-title">' + esc(t.title) + '</span><span class="ma-tabcard-url">' + esc(t.url) + '</span>' +
            '<span class="ma-tabcard-page" aria-hidden="true"><span></span><span></span><span></span></span></button>';
        }).join('') + '</div>' +
        '<button type="button" class="ma-newtab" data-go="browser.newTab" data-dir="fade" data-tg="browser.tabs.newTab">' +
          icon('add', { size: 22 }) + '<span>New tab</span></button>' +
      '</div>';
    }
  });

  // A new tab: type an address. The guide offers the two addresses the tour uses.
  G.screen({
    id: 'browser.newTab',
    app: 'android',
    title: 'Browser: new tab',
    render() {
      const row = (id, label) => '<button type="button" class="ma-suggest" data-action="open" data-page="' + id + '" data-tg="browser.newTab.' + id.split('.')[1] + '">' +
        icon('globe', { size: 18 }) + '<span>' + esc(label) + '</span></button>';
      return '<div class="ma-browser">' +
        '<div class="ma-chrome"><div class="ma-url ma-url-typing" aria-label="Address" data-tg="browser.newTab.address">' +
          icon('search', { size: 16 }) + '<span>Search or type web address</span></div></div>' +
        '<div class="ma-web"><p class="ma-crumb">Type one of these addresses:</p>' +
          row('browser.bitchordRelease', 'github.com/kushagrasinghx/BitChord/releases') +
          row('browser.patchesRepo', REPO_LINK) +
        '</div></div>';
    },
    actions: {
      open(ctx, node) { openTab(ctx, node.getAttribute('data-page')); }
    }
  });

  // My repository on GitHub, scrolled to the README.
  G.screen({
    id: 'browser.patchesRepo',
    app: 'android',
    title: 'Browser: my repository on GitHub',
    render(ctx) {
      return '<div class="ma-browser">' +
        tabbedChrome(REPO_LINK, ctx) +
        '<div class="ma-web">' +
          '<p class="ma-crumb">Hexclyde / bitchord-morphe-patches</p>' +
          '<p class="ma-repo-meta"><span class="ma-pill">Public</span><span>GPL-3.0 license</span></p>' +
          '<div class="ma-skeleton" aria-label="The repository’s files (not drawn)" role="img"><span></span><span></span><span></span></div>' +
          '<article class="ma-readme" data-tg="browser.patchesRepo.readme">' +
            '<p class="ma-readme-tab">' + icon('file', { size: 16 }) + '<span>README</span></p>' +
            '<h2 class="ma-h1">BitChord Morphe Patches (Bitchord+)</h2>' +
            '<p class="ma-readme-p">Morphe patches for the BitChord Android music player. The main one, <strong>Unofficial Telegram music</strong>, lets BitChord search and play music from your own Telegram channels.</p>' +
            '<p class="ma-readme-links">' +
              '<button type="button" class="ma-link" data-action="addSource" data-tg="browser.patchesRepo.addSourceLink"><strong>Add this source to Morphe</strong></button>' +
              '<span> · Releases · Setup guide</span></p>' +
            '<p class="ma-readme-p ma-dim">Status: experimental (' + PATCHES_VERSION + ', a testing build). It works with BitChord ' + APP_VERSION + '.</p>' +
          '</article>' +
        '</div></div>';
    },
    actions: {
      addSource(ctx) { openTab(ctx, 'browser.addSourcePage'); }
    }
  });

  // morphe.software/add-source, which the README's link opens: it hands the source to Morphe.
  G.screen({
    id: 'browser.addSourcePage',
    app: 'android',
    title: 'Browser: Add source to Morphe',
    render(ctx) {
      return '<div class="ma-browser">' +
        tabbedChrome('morphe.software/add-source?github=Hexclyde/bitchord-morphe-patches', ctx) +
        '<div class="ma-web ma-site">' +
          '<p class="ma-site-brand">Morphe</p>' +
          '<div class="ma-addsrc" data-tg="browser.addSourcePage.card">' +
            '<h2 class="ma-site-h">Add source</h2>' +
            '<p class="ma-addsrc-name">' + SOURCE_NAME + '</p>' +
            '<p class="ma-addsrc-row">' + icon('link', { size: 16 }) + '<span>github.com/Hexclyde/bitchord-morphe-patches</span></p>' +
            '<p class="ma-addsrc-row">' + icon('apk', { size: 16 }) + '<span>com.music.bitchord</span></p>' +
          '</div>' +
          '<button type="button" class="ma-site-btn" data-action="openMorphe" data-tg="browser.addSourcePage.openButton">' +
            icon('openInNew', { size: 20 }) + '<span>Open in Morphe</span></button>' +
          '<p class="ma-site-note">Morphe has to be installed on this phone.</p>' +
        '</div></div>';
    },
    actions: {
      openMorphe(ctx) {
        if (!ctx.get('android.morpheInstalled')) { ctx.toast('Install Morphe first.'); return; }
        ctx.go('morphe.apps', null, { dir: 'fade' }).then(() => G.open('morphe.addSource'));
      }
    }
  });

  G.screen({
    id: 'browser.morpheRelease',
    app: 'android',
    title: 'Browser: Morphe Manager releases',
    render() {
      return '<div class="ma-browser">' +
        browserChrome('github.com/MorpheApp/morphe-manager/releases', { id: 'browser.bitchordRelease', title: 'BitChord v1.9' }) +
        '<div class="ma-web">' +
          '<p class="ma-crumb">MorpheApp / morphe-manager</p>' +
          '<h2 class="ma-h1">Releases</h2>' +
          '<article class="ma-rel ma-rel-folded"><h3 class="ma-rel-title">v2.3.0-dev.11</h3>' +
            '<span class="ma-pill ma-pill-pre">Pre-release</span></article>' +
          '<article class="ma-rel">' +
            '<div class="ma-rel-top"><h3 class="ma-rel-title">v2.2.0</h3>' +
              '<span class="ma-pill ma-pill-latest" data-tg="browser.morpheRelease.latestBadge">Latest</span></div>' +
            '<p class="ma-date">25 Oct 2026</p>' +
            '<h4 class="ma-h4">Bug Fixes</h4><ul class="ma-notes">' +
              '<li>Fail patching that runs out of memory instead of hanging</li>' +
              '<li>Give the patcher more memory on 64-bit devices</li>' +
              '<li>Stop the embedded VM from crashing on Android 11</li></ul>' +
            '<h4 class="ma-h4">Features</h4><ul class="ma-notes">' +
              '<li>More apps card on the home screen</li>' +
              '<li>Open an app\'s patch notes from its info dialog</li>' +
              '<li>Update a local source from an opened .mpp file</li></ul>' +
            '<h4 class="ma-h4 ma-assets-head">Assets <span class="ma-count">4</span></h4>' +
            '<div class="ma-assets">' +
              asset({ name: MORPHE_APK, size: '41.7 MB', attrs: { tg: 'browser.morpheRelease.apkAsset', action: 'getMorphe' } }) +
              asset({ name: MORPHE_APK + '.asc', icon: 'key', attrs: { toast: 'That’s a signature file, not the app. Tap the .apk.' } }) +
              asset({ name: 'Source code (zip)', icon: 'zip', attrs: { toast: 'That’s the source code, not the app. Tap the .apk.' } }) +
              asset({ name: 'Source code (tar.gz)', icon: 'zip', attrs: { toast: 'That’s the source code, not the app. Tap the .apk.' } }) +
            '</div>' +
          '</article>' +
        '</div></div>';
    },
    actions: {
      getMorphe(ctx) {
        ctx.toast('Download complete: ' + MORPHE_APK);
        if (ctx.get('android.browserInstallAllowed')) ctx.open('android.installPrompt', { app: 'morphe' });
        else ctx.open('android.unknownApps', { source: 'browser', app: 'morphe' });
      }
    }
  });

  G.screen({
    id: 'browser.bitchordRelease',
    app: 'android',
    title: 'Browser: BitChord v1.9 release',
    render(ctx) {
      const notSource = 'That’s the source code, not the app. Morphe needs the APK.';
      return '<div class="ma-browser">' +
        tabbedChrome('github.com/kushagrasinghx/BitChord/releases', ctx) +
        '<div class="ma-web">' +
          '<p class="ma-crumb">kushagrasinghx / BitChord</p>' +
          '<article class="ma-rel">' +
            '<h2 class="ma-h1 ma-rel-h" data-tg="browser.bitchordRelease.title">BitChord v1.9</h2>' +
            '<p class="ma-tagline"><span class="ma-pill">' + icon('history', { size: 14 }) + 'v1.9</span> <span class="ma-pill ma-pill-latest">Latest</span></p>' +
            '<div class="ma-skeleton" aria-label="Release notes (not drawn)" role="img"><span></span><span></span><span></span></div>' +
            '<h4 class="ma-h4 ma-assets-head">Assets <span class="ma-count">5</span></h4>' +
            '<div class="ma-assets" data-tg="browser.bitchordRelease.assetList">' +
              asset({ name: 'BitChord-v1.9_arm64-v8a.apk', size: '84.7 MB', attrs: { tg: 'browser.bitchordRelease.arm64Asset', action: 'download', data: { file: 'BitChord-v1.9_arm64-v8a.apk' } } }) +
              asset({ name: 'BitChord-v1.9_armeabi-v7a.apk', size: '74.9 MB', attrs: { tg: 'browser.bitchordRelease.armv7Asset', action: 'download', data: { file: 'BitChord-v1.9_armeabi-v7a.apk' } } }) +
              asset({ name: 'BitChord-v1.9_x86_64.apk', size: '91.1 MB', attrs: { tg: 'browser.bitchordRelease.x86Asset', action: 'download', data: { file: 'BitChord-v1.9_x86_64.apk' } } }) +
              '<div class="ma-assets-sub" data-tg="browser.bitchordRelease.sourceCodeAssets">' +
                asset({ name: 'Source code (zip)', icon: 'zip', dim: true, attrs: { toast: notSource } }) +
                asset({ name: 'Source code (tar.gz)', icon: 'zip', dim: true, attrs: { toast: notSource } }) +
              '</div>' +
            '</div>' +
          '</article>' +
        '</div></div>';
    },
    actions: {
      download(ctx, node) {
        ctx.set('browser.bitchordDownloaded', true);
        ctx.toast('Download complete: ' + node.getAttribute('data-file'));
      }
    }
  });

  function fileRow(f, o) {
    o = o || {};
    return '<button type="button" class="ma-file' + (o.dim ? ' is-dim' : '') + '"' + attrs(o.attrs) + '>' +
      '<span class="ma-file-ic ma-file-' + f.kind + '">' + icon(f.kind === 'mpp' ? 'extension' : 'apk', { size: 22 }) + '</span>' +
      '<span class="ma-file-text"><span class="ma-file-name">' + esc(f.name) + '</span><span class="ma-file-meta">' + esc(f.size + ' · ' + f.time) + '</span></span></button>';
  }

  G.screen({
    id: 'files.downloads',
    app: 'android',
    title: 'Files: Downloads',
    render() {
      return '<div class="ma-files">' +
        '<header class="ma-top">' +
          '<button type="button" class="ma-ic" aria-label="Show places" data-toast="This guide only draws Downloads.">' + icon('menu', { size: 22 }) + '</button>' +
          '<h2 class="ma-top-title">Downloads</h2>' +
          '<button type="button" class="ma-ic" aria-label="Search" data-toast="This guide only draws Downloads.">' + icon('search', { size: 22 }) + '</button>' +
        '</header>' +
        '<p class="ma-sort">Date modified ' + icon('arrowDown', { size: 16 }) + '</p>' +
        '<div class="ma-file-list">' + FILES.map((f) => fileRow(f, { attrs: { tg: 'files.downloads.' + f.tg, key: f.name, action: 'openFile', data: { file: f.name } } })).join('') + '</div>' +
      '</div>';
    },
    actions: {
      openFile(ctx, node) {
        const file = node.getAttribute('data-file');
        ctx.open('android.installPrompt', { app: file === MORPHE_APK ? 'morphe' : 'bitchord' });
      }
    }
  });

  // Android's file picker, opened by Morphe's "Open APK file" (mode apk) or the Local drop zone (mode mpp).
  G.screen({
    id: 'android.filePicker',
    app: 'android',
    kind: 'full',
    title: 'Android file picker: Downloads',
    render(ctx) {
      const mode = ctx.params.mode === 'mpp' ? 'mpp' : 'apk';
      const list = mode === 'mpp' ? [MPP].concat(FILES) : FILES;
      // With Settings › Files › Custom file picker on, Morphe opens its own file browser.
      const custom = !!ctx.s['morphe.set.customPicker'];
      return '<div class="ma-files ma-picker' + (custom ? ' ma-picker-morphe' : '') + '">' +
        '<header class="ma-top">' +
          '<button type="button" class="ma-ic" aria-label="Cancel" data-close>' + icon('close', { size: 22 }) + '</button>' +
          '<h2 class="ma-top-title">' + (custom ? 'Select file' : 'Downloads') + '</h2>' +
          '<button type="button" class="ma-ic" aria-label="Search" data-toast="This guide only draws Downloads.">' + icon('search', { size: 22 }) + '</button>' +
        '</header>' +
        (custom ? '<p class="ma-path" data-tg="android.filePicker.path">' + icon('folder', { size: 16 }) +
          '<span>Internal storage</span>' + icon('chevronRight', { size: 14 }) + '<span>Download</span></p>' : '') +
        '<p class="ma-sort">' + (mode === 'mpp' ? 'Select a .mpp file' : 'Select an APK') + '</p>' +
        '<div class="ma-file-list">' + list.map((f) => {
          const ok = f.kind === mode;
          return fileRow(f, { dim: !ok, attrs: { tg: 'android.filePicker.' + f.tg, key: f.name, action: 'pick', data: { file: f.name }, disabled: !ok } });
        }).join('') + '</div>' +
      '</div>';
    },
    actions: {
      pick(ctx, node) {
        const file = node.getAttribute('data-file');
        if (ctx.params.mode === 'mpp') {
          ctx.close('android.filePicker');
          if (ctx.params.purpose === 'update') { ctx.toast('Update successful'); return; }
          ctx.set('morphe.localFile', file);
          return;
        }
        if (file !== BITCHORD_APK) {
          ctx.toast('Wrong package selected');
          return;
        }
        versionCheck(ctx);
        ctx.close('android.filePicker');
        ctx.close('morphe.selectApk');
      }
    }
  });

  function appLabel(app, s) {
    if (app === 'morphe') return { name: 'Morphe', tile: 'M', tone: 'morphe' };
    if (app === 'bitchord') return { name: 'BitChord', tile: 'B', tone: 'bitchord' };
    return { name: installedName(s), tile: 'B+', tone: 'bitchord' };
  }
  function tile(t, size) {
    return '<span class="a-tile a-tile-' + t.tone + '" style="--tile:' + size + 'px" aria-hidden="true">' +
      (t.tone === 'bitchord' ? G.bitchordMark() : '<span class="a-tile-letter">' + esc(t.tile) + '</span>') + '</span>';
  }

  // "Do you want to install this app?"
  G.screen({
    id: 'android.installPrompt',
    app: 'android',
    kind: 'dialog',
    modal: true,
    title: 'Install app',
    render(ctx) {
      const s = ctx.s;
      const app = ctx.params.app || 'bitchordPlus';
      const t = appLabel(app, s);
      const update = (app === 'bitchordPlus' && s['morphe.installed']) || (app === 'bitchord' && s['android.bitchordInstalled']) ||
        (app === 'morphe' && s['android.morpheInstalled']);
      return '<div class="a-dialog ma-installer">' +
        '<div class="ma-app-head">' + tile(t, 32) + '<span>' + esc(t.name) + '</span></div>' +
        '<p class="ma-installer-q">' + (update ? 'Do you want to update this app?' : 'Do you want to install this app?') + '</p>' +
        '<div class="a-dialog-actions">' +
          '<button type="button" class="a-btn-text" data-close data-tg="android.installPrompt.cancelButton">Cancel</button>' +
          '<button type="button" class="a-btn-text ma-btn-strong" data-action="install" data-tg="android.installPrompt.installButton">' + (update ? 'Update' : 'Install') + '</button>' +
        '</div></div>';
    },
    actions: {
      install(ctx) {
        const app = ctx.params.app || 'bitchordPlus';
        ctx.close('android.installPrompt');
        if (app === 'morphe') { ctx.set('android.morpheInstalled', true); ctx.toast('App installed.'); return; }
        if (app === 'bitchord') { ctx.set('android.bitchordInstalled', true); ctx.toast('App installed.'); return; }
        const done = { 'morphe.installed': true, 'morphe.installing': false, 'morphe.appUpdateChip': false, 'morphe.install.error': '' };
        if (ctx.inTour) { ctx.set(done); return; }
        ctx.set('morphe.installing', true);
        G.later(() => G.set(done), 1400);
      }
    }
  });

  // Android asks first when an app isn't yet allowed to install other apps.
  G.screen({
    id: 'android.unknownApps',
    app: 'android',
    kind: 'dialog',
    modal: true,
    title: 'Install unknown apps',
    render(ctx) {
      const source = ctx.params.source === 'browser' ? { name: 'Browser', tile: '', tone: 'browser' } : { name: 'Morphe', tile: 'M', tone: 'morphe' };
      return '<div class="a-dialog">' +
        '<div class="ma-app-head">' + (source.tile ? tile(source, 32) : '<span class="a-tile a-tile-browser" style="--tile:32px" aria-hidden="true">' + icon('globe', { size: 18 }) + '</span>') +
          '<span>' + esc(source.name) + '</span></div>' +
        '<p class="a-dialog-text">For your security, your phone currently isn\'t allowed to install unknown apps from this source. You can change this in Settings.</p>' +
        '<div class="a-dialog-actions">' +
          '<button type="button" class="a-btn-text" data-close data-tg="android.unknownApps.cancelButton">Cancel</button>' +
          '<button type="button" class="a-btn-text ma-btn-strong" data-tg="android.unknownApps.settingsButton" data-action="settings">Settings</button>' +
        '</div></div>';
    },
    actions: {
      settings(ctx) {
        ctx.open('android.unknownAppsSettings', ctx.params);
        ctx.close('android.unknownApps');
      }
    }
  });

  function leaveUnknownSettings(ctx) {
    const key = ctx.params.source === 'browser' ? 'android.browserInstallAllowed' : 'android.unknownAppsAllowed';
    const allowed = ctx.get(key);
    if (allowed) ctx.open('android.installPrompt', { app: ctx.params.app || 'bitchordPlus' });
    ctx.close('android.unknownAppsSettings');
  }

  G.screen({
    id: 'android.unknownAppsSettings',
    app: 'android',
    kind: 'full',
    title: 'Install unknown apps',
    render(ctx) {
      const browser = ctx.params.source === 'browser';
      const key = browser ? 'android.browserInstallAllowed' : 'android.unknownAppsAllowed';
      const on = !!ctx.s[key];
      return '<div class="ma-settings">' +
        '<header class="ma-top"><button type="button" class="ma-ic" aria-label="Back" data-action="done">' + icon('arrowBack', { size: 22 }) + '</button>' +
          '<h2 class="ma-top-title">Install unknown apps</h2></header>' +
        '<div class="ma-set-app">' + (browser ? '<span class="a-tile a-tile-browser" style="--tile:56px" aria-hidden="true">' + icon('globe', { size: 28 }) + '</span>' : tile({ tile: 'M', tone: 'morphe' }, 56)) +
          '<span class="ma-set-app-name">' + (browser ? 'Browser' : 'Morphe') + '</span><span class="ma-set-app-ver">' + (browser ? '' : MORPHE_VERSION) + '</span></div>' +
        '<button type="button" class="ma-set-row" role="switch" aria-checked="' + on + '" data-bind="' + key + '" data-tg="android.unknownAppsSettings.allowSwitch" aria-labelledby="ma-allow">' +
          '<span id="ma-allow">Allow from this source</span>' +
          '<span class="g-switch" aria-checked="' + on + '" aria-hidden="true"><span class="g-switch-thumb"></span></span></button>' +
        '<p class="ma-set-info">' + icon('info', { size: 20 }) + '<span>Your phone and personal data are more vulnerable to attack by unknown apps. By installing apps from this source, you agree that you are responsible for any damage to your phone or loss of data that may result from their use.</span></p>' +
      '</div>';
    },
    actions: { done(ctx) { leaveUnknownSettings(ctx); } },
    onBack(ctx) { leaveUnknownSettings(ctx); }
  });

  G.screen({
    id: 'android.uninstallPrompt',
    app: 'android',
    kind: 'dialog',
    modal: true,
    title: 'Uninstall app',
    render(ctx) {
      return '<div class="a-dialog">' +
        '<div class="ma-app-head">' + tile({ tile: 'B+', tone: 'bitchord' }, 32) + '<span>' + esc(installedName(ctx.s)) + '</span></div>' +
        '<p class="ma-installer-q">Do you want to uninstall this app?</p>' +
        '<div class="a-dialog-actions"><button type="button" class="a-btn-text" data-close>Cancel</button>' +
          '<button type="button" class="a-btn-text ma-btn-strong" data-action="ok">OK</button></div></div>';
    },
    actions: {
      ok(ctx) {
        ctx.set({ 'morphe.installed': false, 'morphe.install.error': '', 'morphe.appUpdateChip': false });
        ctx.close('android.uninstallPrompt');
        ctx.close('morphe.appInfo');
        ctx.toast('Uninstalled');
      }
    }
  });
})();
