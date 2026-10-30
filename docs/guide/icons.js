/*
 * Bitchord+ install guide: simple inline icons for the pretend phone.
 *
 * Every icon here was drawn for this guide on a 24 x 24 grid. None is copied from an icon set,
 * so no extra licence applies. They are generic shapes, not logos.
 *
 * Use:  Guide.icon('search')                      -> decorative SVG (aria-hidden)
 *       Guide.icon('search', { label: 'Search' }) -> SVG with role="img" and a label
 *       Guide.icon('play', { size: 32, cls: 'big' })
 * Guide.icons lists the names.
 */
(function () {
  'use strict';
  var G = window.Guide = window.Guide || {};

  // Each entry is an SVG fragment drawn with stroke="currentColor" (outline icons),
  // or an object { fill: '...' } for solid icons drawn with fill="currentColor".
  var I = {
    // Arrows and chevrons
    arrowBack: '<path d="M19 12H5"/><path d="M11 18l-6-6 6-6"/>',
    arrowForward: '<path d="M5 12h14"/><path d="M13 6l6 6-6 6"/>',
    arrowUp: '<path d="M12 19V5"/><path d="M6 11l6-6 6 6"/>',
    chevronRight: '<path d="M9 6l6 6-6 6"/>',
    chevronLeft: '<path d="M15 6l-6 6 6 6"/>',
    chevronDown: '<path d="M6 9l6 6 6-6"/>',
    chevronUp: '<path d="M6 15l6-6 6 6"/>',
    openInNew: '<path d="M14 4h6v6"/><path d="M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',

    // Basic actions
    add: '<path d="M12 5v14"/><path d="M5 12h14"/>',
    close: '<path d="M6 6l12 12"/><path d="M18 6L6 18"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
    checkCircle: '<circle cx="12" cy="12" r="9"/><path d="M8 12.3l2.7 2.7L16.2 9.5"/>',
    checkCircleFilled: { fill: '<path fill-rule="evenodd" d="M12 2.5a9.5 9.5 0 1 1 0 19 9.5 9.5 0 0 1 0-19zm4.9 6.3a1 1 0 0 0-1.4 0L10.7 13.6 8.5 11.4a1 1 0 1 0-1.4 1.4l2.9 2.9a1 1 0 0 0 1.4 0l5.5-5.5a1 1 0 0 0 0-1.4z"/>' },
    circle: '<circle cx="12" cy="12" r="9"/>',
    radioOn: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4.5" fill="currentColor" stroke="none"/>',
    checkbox: '<rect x="4" y="4" width="16" height="16" rx="3"/>',
    checkboxOn: { fill: '<path fill-rule="evenodd" d="M7 3.5h10A3.5 3.5 0 0 1 20.5 7v10a3.5 3.5 0 0 1-3.5 3.5H7A3.5 3.5 0 0 1 3.5 17V7A3.5 3.5 0 0 1 7 3.5zm9.7 5.3a1 1 0 0 0-1.4 0l-5 5-2-2a1 1 0 1 0-1.4 1.4l2.7 2.7a1 1 0 0 0 1.4 0l5.7-5.7a1 1 0 0 0 0-1.4z"/>' },
    search: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
    settings: '<path d="M18.89 9.89L21.68 10.47 21.68 13.53 18.89 14.11 18.36 15.38 19.93 17.76 17.76 19.93 15.38 18.36 14.11 18.89 13.53 21.68 10.47 21.68 9.89 18.89 8.62 18.36 6.24 19.93 4.07 17.76 5.64 15.38 5.11 14.11 2.32 13.53 2.32 10.47 5.11 9.89 5.64 8.62 4.07 6.24 6.24 4.07 8.62 5.64 9.89 5.11 10.47 2.32 13.53 2.32 14.11 5.11 15.38 5.64 17.76 4.07 19.93 6.24 18.36 8.62Z"/><circle cx="12" cy="12" r="3"/>',
    refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.5 4.5v4.5H15"/>',
    edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>',
    delete: '<path d="M4 7h16"/><path d="M9 7V4.5h6V7"/><path d="M6 7l1 13h10l1-13"/><path d="M10 11v5.5"/><path d="M14 11v5.5"/>',
    block: '<circle cx="12" cy="12" r="9"/><path d="M5.7 5.7l12.6 12.6"/>',
    share: '<circle cx="17.5" cy="5.5" r="2.5"/><circle cx="6.5" cy="12" r="2.5"/><circle cx="17.5" cy="18.5" r="2.5"/><path d="M8.7 10.7l6.6-3.9"/><path d="M8.7 13.3l6.6 3.9"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    paste: '<rect x="5" y="4.5" width="14" height="16.5" rx="2"/><rect x="9" y="3" width="6" height="3.5" rx="1"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    sort: '<path d="M4 7h16"/><path d="M4 12h11"/><path d="M4 17h6"/>',
    filter: '<path d="M4 5h16l-6 7.5V19l-4-2v-4.5z"/>',
    list: '<path d="M4 6h16"/><path d="M4 12h16"/><path d="M4 18h10"/>',
    dragHandle: '<path d="M5 10h14"/><path d="M5 14h14"/>',
    moreVert: { fill: '<circle cx="12" cy="5" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="12" cy="19" r="1.9"/>' },
    moreHoriz: { fill: '<circle cx="5" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="19" cy="12" r="1.9"/>' },
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    eyeOff: '<path d="M4 4l16 16"/><path d="M9.9 5.8A9.6 9.6 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-2.6 3.4"/><path d="M6.4 7.4A15.6 15.6 0 0 0 2.5 12S6 18.5 12 18.5a9.3 9.3 0 0 0 4.7-1.3"/><path d="M10 10a3 3 0 0 0 4.1 4.1"/>',
    lock: '<rect x="5" y="10.5" width="14" height="10" rx="2"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
    key: '<circle cx="8" cy="15.5" r="4"/><path d="M11 12.5L19.5 4"/><path d="M16.5 7l2.5 2.5"/><path d="M14.3 9.2l2 2"/>',

    // Apps, places and navigation
    home: '<path d="M4 11l8-7 8 7"/><path d="M6 9.5V20h4.5v-5h3v5H18V9.5"/>',
    explore: '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
    library: '<path d="M7 3.5h10"/><path d="M5.5 6.5h13"/><rect x="4" y="9.5" width="16" height="11" rx="2.5"/>',
    apps: { fill: '<circle cx="6" cy="6" r="1.8"/><circle cx="12" cy="6" r="1.8"/><circle cx="18" cy="6" r="1.8"/><circle cx="6" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="18" cy="12" r="1.8"/><circle cx="6" cy="18" r="1.8"/><circle cx="12" cy="18" r="1.8"/><circle cx="18" cy="18" r="1.8"/>' },
    extension: '<path d="M10 5.5a2 2 0 1 1 4 0V7h4a1 1 0 0 1 1 1v3.5h-1.5a2 2 0 1 0 0 4H19V19a1 1 0 0 1-1 1h-3.5v-1.5a2 2 0 1 0-4 0V20H6a1 1 0 0 1-1-1v-3.5h1.5a2 2 0 1 0 0-4H5V8a1 1 0 0 1 1-1h4z"/>',
    source: '<path d="M3.5 7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/><path d="M10.3 11l-2 2 2 2"/><path d="M13.7 11l2 2-2 2"/>',
    folder: '<path d="M3.5 7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
    file: '<path d="M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M14 3v5h5"/>',
    fileOpen: '<path d="M13 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v5"/><path d="M14 3v5h5"/><path d="M15 20l5-5"/><path d="M16 15h4v4"/>',
    apk: '<path d="M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M14 3v5h5"/><path d="M12 11v6"/><path d="M9.5 14.5L12 17l2.5-2.5"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a14 14 0 0 1 0 18"/><path d="M12 3a14 14 0 0 0 0 18"/>',
    phone: '<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
    installPhone: '<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M12 7.5v7"/><path d="M9.5 12l2.5 2.5 2.5-2.5"/>',
    person: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5a7.5 7.5 0 0 1 15 0"/>',
    account: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="10" r="3"/><path d="M6.5 18.2a6.5 6.5 0 0 1 11 0"/>',
    group: '<circle cx="9" cy="8.5" r="3.2"/><path d="M3 19.5a6 6 0 0 1 12 0"/><path d="M15.5 5.6a3.2 3.2 0 0 1 0 6"/><path d="M17.5 14a6 6 0 0 1 3.5 5.5"/>',
    bell: '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2H4.5z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
    wand: '<path d="M4 20L15 9"/><path d="M13.5 7.5l3 3"/><path d="M18 3v3"/><path d="M16.5 4.5h3"/><path d="M20 9.5v2"/><path d="M19 10.5h2"/><path d="M9.5 3.5v2"/><path d="M8.5 4.5h2"/>',
    science: '<path d="M9 3h6"/><path d="M10 3v6.2L4.6 18.6A2 2 0 0 0 6.3 21.5h11.4a2 2 0 0 0 1.7-2.9L14 9.2V3"/><path d="M7.5 15h9"/>',
    expert: '<circle cx="10" cy="8" r="4"/><path d="M3 20a7 7 0 0 1 10.5-6"/><circle cx="18" cy="17" r="2.2"/><path d="M18 13v1.8"/><path d="M18 19.2V21"/><path d="M14.5 17h1.3"/><path d="M20.2 17h1.3"/>',
    history: '<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v4h4"/><path d="M12 8v4.3l3 1.7"/>',
    schedule: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    bug: '<rect x="7" y="8" width="10" height="12" rx="5"/><path d="M9.5 8a2.5 2.5 0 0 1 5 0"/><path d="M12 12v8"/><path d="M3.5 13h3.5"/><path d="M17 13h3.5"/><path d="M4.5 8.5L7.3 10"/><path d="M19.5 8.5L16.7 10"/><path d="M4.5 18.5l3-1.5"/><path d="M19.5 18.5l-3-1.5"/>',
    save: '<path d="M5 4h11l3 3v12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1z"/><path d="M8 4v5h7V4"/><rect x="8" y="13" width="8" height="5" rx="1"/>',
    upload: '<path d="M12 16V5"/><path d="M7 10l5-5 5 5"/><path d="M5 20h14"/>',
    send: '<path d="M4 12L20 4l-5 16-3.5-6.5z"/><path d="M11.5 13.5L20 4"/>',

    // Media and audio
    download: '<path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M5 20h14"/>',
    downloadDone: '<path d="M5 12.5l4.5 4.5L19 7.5"/><path d="M5 21h14"/>',
    play: { fill: '<path d="M8 5.6v12.8a1 1 0 0 0 1.5.87l10.2-6.4a1 1 0 0 0 0-1.74L9.5 4.73A1 1 0 0 0 8 5.6z"/>' },
    pause: { fill: '<rect x="6" y="5" width="4" height="14" rx="1.2"/><rect x="14" y="5" width="4" height="14" rx="1.2"/>' },
    skipNext: { fill: '<path d="M4 6.5v11a1 1 0 0 0 1.6.8l7.4-5.5a1 1 0 0 0 0-1.6L5.6 5.7A1 1 0 0 0 4 6.5z"/><path d="M12 6.5v11a1 1 0 0 0 1.6.8l7.4-5.5a1 1 0 0 0 0-1.6l-7.4-5.5a1 1 0 0 0-1.6.8z"/>' },
    skipPrev: { fill: '<path d="M20 6.5v11a1 1 0 0 1-1.6.8L11 12.8a1 1 0 0 1 0-1.6l7.4-5.5a1 1 0 0 1 1.6.8z"/><path d="M12 6.5v11a1 1 0 0 1-1.6.8L3 12.8a1 1 0 0 1 0-1.6l7.4-5.5a1 1 0 0 1 1.6.8z"/>' },
    musicNote: '<path d="M9 18V5.5l11-2V16"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
    album: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>',
    queue: '<path d="M4 6h12"/><path d="M4 11h12"/><path d="M4 16h7"/><path d="M15 14.5v6l5-3z"/>',
    tune: '<path d="M6 4v4"/><path d="M6 12v8"/><path d="M12 4v10"/><path d="M12 18v2"/><path d="M18 4v2"/><path d="M18 10v10"/><circle cx="6" cy="10" r="2"/><circle cx="12" cy="16" r="2"/><circle cx="18" cy="8" r="2"/>',
    equalizer: '<path d="M4 10v4"/><path d="M8 6v12"/><path d="M12 3v18"/><path d="M16 7v10"/><path d="M20 10v4"/>',
    volume: '<path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z"/><path d="M15.5 9a4 4 0 0 1 0 6"/><path d="M18 6.5a7.5 7.5 0 0 1 0 11"/>',
    bluetooth: '<path d="M7 7.5l10 9-5 4.5V3l5 4.5-10 9"/>',
    headphones: '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3.5" y="14" width="4.5" height="7" rx="1.5"/><rect x="16" y="14" width="4.5" height="7" rx="1.5"/>',
    speaker: '<rect x="6" y="3" width="12" height="18" rx="2.5"/><circle cx="12" cy="14.5" r="3.2"/><path d="M12 7h.01"/>',
    timer: '<circle cx="12" cy="13.5" r="7.5"/><path d="M12 10v3.8l2.5 1.6"/><path d="M9.5 2.5h5"/><path d="M18.2 5.8l1.4 1.4"/>',
    bedtime: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    battery: '<rect x="7" y="4.5" width="10" height="17" rx="2"/><path d="M10 2.5h4"/><path d="M10 15h4"/><path d="M10 11.5h4"/>',
    cloud: '<path d="M7 18.5a4.5 4.5 0 0 1-.4-9A6 6 0 0 1 18 9a4.75 4.75 0 0 1-.5 9.5z"/>',
    cloudOff: '<path d="M7 18.5a4.5 4.5 0 0 1-.4-9 6 6 0 0 1 1.2-2.7"/><path d="M10.4 4.5A6 6 0 0 1 18 9a4.75 4.75 0 0 1 2.3 8.1"/><path d="M17.5 18.5H7"/><path d="M3.5 3.5l17 17"/>',
    surround: '<circle cx="12" cy="12" r="2.5"/><path d="M7.8 7.8a6 6 0 0 0 0 8.4"/><path d="M16.2 7.8a6 6 0 0 1 0 8.4"/><path d="M5 5a10 10 0 0 0 0 14"/><path d="M19 5a10 10 0 0 1 0 14"/>',
    wave: '<path d="M2.5 12c2-4 3.5-4 5.5 0s3.5 4 5.5 0 3.5-4 5.5 0 1.5 2.5 2.5 2.5"/>',

    // Signs
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5"/><path d="M12 7.6v.01"/>',
    warning: '<path d="M10.3 4.3L2.9 17.5A2 2 0 0 0 4.6 20.5h14.8a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0z"/><path d="M12 9.5v4.5"/><path d="M12 17.2v.01"/>',
    error: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5"/><path d="M12 16.4v.01"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6"/><path d="M12 17v.01"/>',
    star: '<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/>',
    toggle: '<rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16.5" cy="12" r="2.8"/>',

    // Android system bars
    wifi: { fill: '<path d="M12 20.5L.8 8.4a16.2 16.2 0 0 1 22.4 0z"/>' },
    signal: { fill: '<path d="M21.5 2.5v19h-19z"/>' },
    batteryFull: { fill: '<path d="M10 2h4v1.5h1.5A1.5 1.5 0 0 1 17 5v15.5a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 20.5V5a1.5 1.5 0 0 1 1.5-1.5H10z"/>' },
    navBack: { fill: '<path d="M17 4.6v14.8a1 1 0 0 1-1.5.86L4.6 12.86a1 1 0 0 1 0-1.72L15.5 3.74A1 1 0 0 1 17 4.6z"/>' },
    navHome: { fill: '<circle cx="12" cy="12" r="7.5"/>' },
    navRecents: { fill: '<rect x="5" y="5" width="14" height="14" rx="2"/>' }
  };

  var aliases = {
    back: 'arrowBack', chevron: 'chevronRight', gear: 'settings', more: 'moreVert', grid: 'apps',
    puzzle: 'extension', equaliser: 'tune', sliders: 'tune', note: 'musicNote', trash: 'delete',
    external: 'openInNew', clock: 'schedule'
  };

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function icon(name, opts) {
    opts = opts || {};
    var key = I[name] ? name : aliases[name];
    var body = I[key];
    if (!body) body = I.help;
    var size = opts.size || 24;
    var solid = typeof body === 'object';
    var a11y = opts.label
      ? ' role="img" aria-label="' + esc(opts.label) + '"'
      : ' aria-hidden="true" focusable="false"';
    var paint = solid
      ? ' fill="currentColor" stroke="none"'
      : ' fill="none" stroke="currentColor" stroke-width="' + (opts.stroke || 2) + '" stroke-linecap="round" stroke-linejoin="round"';
    return '<svg class="g-icon' + (opts.cls ? ' ' + esc(opts.cls) : '') + '" width="' + size + '" height="' + size +
      '" viewBox="0 0 24 24"' + paint + a11y + '>' + (solid ? body.fill : body) + '</svg>';
  }

  // BitChord's own launcher icon (its adaptive icon's foreground, from the app's source), drawn
  // over the red gradient the tile's CSS paints. Bitchord+ keeps the same icon.
  var BITCHORD_WAVES =
    'M142.673,587.458C156.137,564.68 185.634,557.171 208.339,570.696H208.363C373.538,668.803 646.056,677.824 ' +
    '803.759,629.96C829.083,622.28 855.863,636.576 863.537,661.9C871.211,687.236 856.927,713.999 831.585,721.698C' +
    '650.553,776.654 349.61,766.036 159.441,653.143C136.614,639.624 129.148,610.206 142.673,587.458ZM213.749,425.386C' +
    '225.316,406.567 249.917,400.631 268.705,412.198C406.463,496.89 616.454,521.412 779.396,471.951C800.527,465.569 ' +
    '822.846,477.478 829.26,498.574C835.624,519.705 823.709,541.982 802.614,548.408C616.491,604.882 385.105,577.526 ' +
    '226.906,480.312C208.118,468.745 202.183,444.15 213.749,425.386ZM276.478,285.823C285.657,270.768 305.363,265.994 ' +
    '320.418,275.235C440.747,348.734 592.225,365.381 770.619,324.622C787.81,320.706 804.946,331.476 808.862,348.673C' +
    '812.797,365.87 802.069,383.005 784.836,386.922C589.612,431.542 422.154,412.32 287.064,329.763C272.01,320.522 ' +
    '267.237,300.877 276.478,285.823Z';

  function bitchordMark() {
    // The launcher shows the middle two thirds of the 108dp adaptive icon.
    return '<svg class="g-bitchord-mark" viewBox="170.67 170.67 682.67 682.67" aria-hidden="true" focusable="false">' +
      '<path fill="#ffffff" transform="translate(512 512) scale(.66) translate(-512 -512)" d="' + BITCHORD_WAVES + '"/></svg>';
  }

  G.icon = icon;
  G.icons = Object.keys(I);
  G.bitchordMark = bitchordMark;
})();
