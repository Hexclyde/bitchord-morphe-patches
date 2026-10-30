/*
 * Bitchord+ install guide: the BitChord screens, our own pages inside it (Unofficial Telegram,
 * Patches & tweaks, the download quality dialog), the MicroG sign-in offer and the generic web
 * pages that go with them.
 *
 * These are simple drawings that look like BitChord 1.9 (by kushagra, kushagrasinghx, and its
 * contributors) with the Bitchord+ patches added. No logos or brand artwork: app names are text,
 * artwork is a plain gradient tile, and the icons come from icons.js (drawn for this guide).
 * The labels are copied from BitChord's English strings and from our patch code.
 */
(function () {
  'use strict';
  const G = window.Guide;
  const esc = G.esc;
  const icon = (name, opts) => G.icon(name, opts);

  // ---------------------------------------------------------------------------------------
  // State keys of these screens (the shared ones are defined in guide.js)
  // ---------------------------------------------------------------------------------------

  G.defineState({
    'bitchord.tab': 'home',                 // selected tab in the floating bar
    'bitchord.nowPlaying': 's1',            // song in the player (ids in SONGS below)
    'bitchord.downloads': {},               // song id -> 'queued' | 'saved'
    'bitchord.downloadQuality': 'lossless', // Settings › Downloads › Download quality
    'bitchord.settingsQuery': '',           // "Search settings"
    'bitchord.searchRecent': ['Harbour', 'Northbound'],
    'bitchord.channel': 'Alex Example',     // YouTube Music profile chosen at sign-in
    'bitchord.volume': 70,
    'bitchord.eq.mode': 'dynamic',          // 'dynamic' | 'manual'
    'bitchord.eq.toneX': 0,                 // −5…5
    'bitchord.eq.toneY': 0,                 // −5…5
    'bitchord.eq.focused': false,           // Broad (false) or Focused
    'bitchord.opt.atmos': false,
    'bitchord.opt.wifiOnly': true,
    'bitchord.opt.exportDownloads': false,
    'bitchord.opt.musicOnly': false,
    'bitchord.opt.precision': 'pcm16',      // 'pcm16' | 'float32'
    'bitchord.opt.usbDac': false,
    'bitchord.opt.speakerOff': false,
    'bitchord.opt.crossfade': 0,
    'bitchord.opt.automix': false,
    'bitchord.opt.skipSilence': false,
    'bitchord.opt.spatial': false,
    'bitchord.opt.reduceAnimation': false,
    'bitchord.sources.telegram': true,
    'bitchord.sources.jiosaavn': false,
    'bitchord.exampleAddon': false,         // show an example lossless addon on Settings › Sources
    'bitchord.sources.example': true,       // its switch

    'tg.returnStage': '',                   // stage to go back to after "Change ›"
    'tg.twoStep': false,                    // the account has a two-step password
    'tg.statusNote': '',                    // last status line set by an action
    'tg.savedIndex': true,                  // on by default, as in the app
    'tg.indexing': false,                   // the saved index is reading newly chosen chats
    'tg.indexWifi': false,
    'tg.indexCharging': false,
    'tg.adv.import': false,
    'tg.adv.reportQuality': true,
    'tg.adv.checkLossless': false,
    'tg.adv.smooth': true,
    'tg.adv.cache': 4,                      // GiB
    'tg.namesQuery': '',

    'tweaks.headphones.note': '',           // '' | 'empty' (search with no text)
    'tweaks.headphones.paste': '',          // text in "Paste a profile"
    'tweaks.outputEq.saved': ['headphones', 'car'],
    'tweaks.outputEq.paste': ''             // text in "Paste code"
  });

  // ---------------------------------------------------------------------------------------
  // Shared data
  // ---------------------------------------------------------------------------------------

  const NOT_DRAWN = 'This part of the app isn’t drawn in this guide.';

  // Made-up songs. The artwork is a plain gradient tile.
  const SONGS = {
    s1: { title: 'Slow Lights', artist: 'Northbound', album: 'Slow Lights', length: '3:43', hue: 205 },
    s2: { title: 'Paper Moon', artist: 'Ada Wren', album: 'Lanterns', length: '4:05', hue: 28 },
    s3: { title: 'Harbour', artist: 'The Quiet Rooms', album: 'Low Tide', length: '3:18', hue: 165 },
    s4: { title: 'Afterglow', artist: 'Lumen Field', album: 'Afterglow', length: '5:02', hue: 318 },
    s5: { title: 'Blue Hour', artist: 'Mira Hale', album: 'Evenings', length: '3:51', hue: 248 }
  };
  const SONG_IDS = Object.keys(SONGS);
  const song = (id) => SONGS[id] || SONGS.s1;

  // Outputs. "name" is what BitChord's output picker and player show; "page" and "kind" are
  // what our Patches & tweaks page shows ("Playing through: Phone (Phone speaker)").
  const OUTPUTS = {
    speaker: { name: 'This phone', page: 'Phone', kind: 'Phone speaker', icon: 'phone' },
    wired: { name: 'Wired headphones', page: 'Wired headphones', kind: 'Wired', icon: 'headphones' },
    usb: { name: 'USB audio', page: 'USB audio', kind: 'USB', icon: 'speaker' },
    headphones: { name: 'My Headphones', page: 'My Headphones', kind: 'Bluetooth', icon: 'headphones', bt: true, codec: 'AAC' },
    car: { name: 'Car Audio', page: 'Car Audio', kind: 'Bluetooth', icon: 'bluetooth', bt: true, codec: 'SBC' }
  };
  const output = (key) => OUTPUTS[key] || OUTPUTS.speaker;
  const BT_SEEN = ['My Headphones', 'Car Audio'];

  const QUALITY = {
    standard: { label: 'Standard', sheet: '~128 kbps Opus · fits more on the device · ~4 MB per track', size: '~4 MB', dialog: 'About 128 kbps Opus · around 4 MB a song' },
    high: { label: 'High', sheet: 'Best audio on offer; source quality first · ~8 MB per track', size: '~8 MB', dialog: 'The best lossy copy on offer · around 8 MB a song' },
    lossless: { label: 'Lossless', sheet: 'Bit-exact if a source has it, best Opus if not · ~35 MB per track', size: '~35 MB', dialog: 'A source’s exact file when one has it · around 35 MB a song' }
  };

  const SLEEP = {
    end: { label: 'After this song', left: 'After this song' },
    15: { label: '15 minutes', left: '14:52' },
    30: { label: '30 minutes', left: '29:41' },
    45: { label: '45 minutes', left: '44:37' },
    60: { label: '1 hour', left: '59:48' }
  };
  const SLEEP_ORDER = ['end', '15', '30', '45', '60'];

  const CHAT_FOLDERS = [
    { value: 'all', label: 'All chats', tg: 'tg.chatFolders.allChats' },
    { value: 'Music', label: 'Music' },
    { value: 'archived', label: 'Archived chats' }
  ];
  const CHATS = {
    all: ['Lossless Library', 'Jazz Vinyl Rips', 'My Uploads', 'Saved Messages', 'Family'],
    Music: ['Lossless Library', 'Jazz Vinyl Rips', 'My Uploads'],
    archived: ['Old Mixes']
  };

  // A signed number with a true minus sign: "+2", "−1", "0".
  function signed(n) { return n > 0 ? '+' + n : n < 0 ? '−' + Math.abs(n) : '0'; }
  function attrJson(o) { return esc(JSON.stringify(o)); }
  function sameText(a, b) { return String(a).toLowerCase().indexOf(String(b).toLowerCase()) >= 0; }

  // ---------------------------------------------------------------------------------------
  // BitChord building blocks
  // ---------------------------------------------------------------------------------------

  // Artwork stand-in: a gradient tile with a note.
  function art(s, size, cls) {
    return '<span class="b-art' + (cls ? ' ' + cls : '') + '" style="--hue:' + s.hue + ';--size:' + size + 'px" aria-hidden="true">' +
      icon('musicNote', { size: Math.max(14, Math.round(size * 0.42)) }) + '</span>';
  }

  function avatar(ctx, size) {
    if (ctx.s['bitchord.signedIn']) {
      return '<span class="b-avatar b-avatar-in" style="--size:' + size + 'px" aria-hidden="true">' +
        esc(String(ctx.s['bitchord.channel'] || 'A').charAt(0)) + '</span>';
    }
    return '<span class="b-avatar" style="--size:' + size + 'px" aria-hidden="true">' + icon('person', { size: Math.round(size * 0.62) }) + '</span>';
  }

  function accountButton(ctx) {
    return '<button type="button" class="b-circle b-account" data-tg="bitchord.topbar.accountButton" data-action="bc.account" aria-label="' +
      (ctx.s['bitchord.signedIn'] ? 'Account' : 'Account and settings') + '">' + avatar(ctx, 28) + '</button>';
  }

  function backButton(label) {
    return '<button type="button" class="b-circle b-back" data-back aria-label="' + esc(label || 'Back') + '">' + icon('arrowBack', { size: 24 }) + '</button>';
  }

  const TABS = [
    { id: 'home', label: 'Home', icon: 'home', screen: 'bitchord.home' },
    { id: 'explore', label: 'Explore', icon: 'explore', screen: 'bitchord.explore' },
    { id: 'library', label: 'Library', icon: 'library', screen: 'bitchord.library' },
    { id: 'search', label: 'Search', icon: 'search', screen: 'bitchord.search' }
  ];

  function nav(ctx, current) {
    return '<nav class="b-nav" aria-label="BitChord tabs">' +
      TABS.map((t) => {
        const on = t.id === current;
        return '<button type="button" class="b-nav-tab" data-tg="bitchord.nav.' + t.id + '" data-action="bc.tab" data-tab="' + t.id + '"' +
          (on ? ' aria-current="page"' : '') + '>' + icon(t.icon, { size: 25, stroke: on ? 2.2 : 1.8 }) + '<span>' + t.label + '</span></button>';
      }).join('') +
    '</nav>';
  }

  function miniPlayer(ctx) {
    const s = song(ctx.s['bitchord.nowPlaying']);
    const playing = !!ctx.s['bitchord.playing'];
    return '<div class="b-mini" data-tg="bitchord.miniPlayer" data-go="bitchord.player">' +
      '<button type="button" class="b-mini-open" data-go="bitchord.player" aria-label="' + esc('Now playing: ' + s.title + ' by ' + s.artist + '. Open the player') + '">' +
        art(s, 44) + '<span class="b-mini-text"><span class="b-mini-title">' + esc(s.title) + '</span><span class="b-mini-artist">' + esc(s.artist) + '</span></span>' +
      '</button>' +
      '<button type="button" class="b-mini-btn" data-bind="bitchord.playing" aria-label="' + (playing ? 'Pause' : 'Play') + '">' + icon(playing ? 'pause' : 'play', { size: 26 }) + '</button>' +
      '<button type="button" class="b-mini-btn" data-action="bc.next" aria-label="Next song">' + icon('skipNext', { size: 26 }) + '</button>' +
    '</div>';
  }

  // The floating mini player and tab bar, last in a tab page.
  function dock(ctx, tab, withMini) {
    return '<div class="b-dock">' + (withMini ? miniPlayer(ctx) : '') + nav(ctx, tab) + '</div>';
  }

  // A song row: the row plays it, ⋮ opens its actions sheet.
  function songRow(ctx, id, opts) {
    opts = opts || {};
    const s = song(id);
    const sub = opts.sub || s.artist;
    return '<div class="b-song" data-key="' + esc(opts.key || 'song-' + id) + '" data-action="bc.play" data-song="' + id + '"' +
      (opts.tg ? ' data-tg="' + esc(opts.tg) + '"' : '') + '>' +
      '<button type="button" class="b-song-main" data-action="bc.play" data-song="' + id + '">' + art(s, 48) +
        '<span class="b-song-text"><span class="b-song-title">' + esc(opts.title || s.title) + '</span>' +
        '<span class="b-song-sub">' + (opts.badge ? '<span class="b-quality">' + esc(opts.badge) + '</span>' : '') + esc(sub) + '</span></span>' +
        (opts.length ? '<span class="b-song-length">' + esc(s.length) + '</span>' : '') +
      '</button>' +
      '<button type="button" class="b-song-more" data-open="bitchord.songSheet" data-params="' + attrJson({ song: id, from: 'row' }) + '" aria-label="' +
        esc('More options for ' + (opts.title || s.title)) + '">' + icon('moreVert', { size: 22 }) + '</button>' +
    '</div>';
  }

  // Settings group: optional header, a card of rows, optional footer.
  function group(o) {
    return '<section class="b-group" data-key="' + esc(o.key) + '"' + (o.tg ? ' data-tg="' + esc(o.tg) + '"' : '') + '>' +
      (o.header ? '<h3 class="b-group-h">' + esc(o.header) + '</h3>' : '<div class="b-group-gap" aria-hidden="true"></div>') +
      '<div class="b-card">' + o.rows.join('') + '</div>' +
      (o.footer ? '<p class="b-group-f">' + esc(o.footer) + '</p>' : '') +
    '</section>';
  }

  // Settings row. With `sw` it is a row with a switch; otherwise the whole row is a button.
  function row(o) {
    const id = o.id || ('b-r-' + String(o.key || o.title).replace(/[^a-z0-9]+/gi, '-').toLowerCase());
    const textHtml = '<span class="b-row-text"><span class="b-row-title"><span id="' + id + '">' + esc(o.title) + '</span>' +
      (o.badge ? '<span class="b-badge">' + esc(o.badge) + '</span>' : '') + '</span>' +
      (o.sub ? '<span class="b-row-sub">' + esc(o.sub) + '</span>' : '') + '</span>';
    const iconHtml = o.icon ? '<span class="b-row-icon">' + icon(o.icon, { size: 22 }) + '</span>' : '';
    const cls = 'b-row' + (o.sub2 ? ' b-row-subrow' : '') + (o.icon ? '' : ' b-row-noicon') + (o.disabled ? ' b-row-disabled' : '') + (o.cls ? ' ' + o.cls : '');
    if (o.sw) {
      return '<div class="' + cls + '" data-key="' + esc(o.key || id) + '">' + iconHtml + textHtml +
        G.ui.switch(Object.assign({ labelledby: id }, o.sw)) + '</div>';
    }
    if (o.static) {
      return '<div class="' + cls + '" data-key="' + esc(o.key || id) + '">' + iconHtml + textHtml + (o.after || '') + '</div>';
    }
    let a = '';
    if (o.go) a += ' data-go="' + esc(o.go) + '"';
    if (o.open) a += ' data-open="' + esc(o.open) + '"';
    if (o.action) a += ' data-action="' + esc(o.action) + '"';
    if (o.toast) a += ' data-toast="' + esc(o.toast) + '"';
    if (o.tg) a += ' data-tg="' + esc(o.tg) + '"';
    return '<button type="button" class="' + cls + '" data-key="' + esc(o.key || id) + '"' + a + '>' + iconHtml + textHtml +
      (o.value ? '<span class="b-row-value">' + esc(o.value) + '</span>' : '') +
      (o.chevron === false ? '' : '<span class="b-row-chev">' + icon('chevronRight', { size: 20 }) + '</span>') +
    '</button>';
  }

  // Segmented control (Theme, Output precision, Equalizer mode, Search).
  function segmented(o) {
    return '<div class="b-seg' + (o.cls ? ' ' + o.cls : '') + '" role="group" aria-label="' + esc(o.label) + '"' + (o.tg ? ' data-tg="' + esc(o.tg) + '"' : '') + '>' +
      o.items.map((it) => '<button type="button" class="b-seg-btn" aria-pressed="' + (it.value === o.value) + '"' +
        (it.action ? ' data-action="' + esc(it.action) + '" data-value="' + esc(it.value) + '"' : ' data-bind="' + esc(o.bind) + '" data-value="' + esc(it.value) + '"') +
        (it.tg ? ' data-tg="' + esc(it.tg) + '"' : '') + '>' + esc(it.label) + '</button>').join('') +
    '</div>';
  }

  // ---------------------------------------------------------------------------------------
  // Global actions (prefixed "bc." so they never meet another file's names)
  // ---------------------------------------------------------------------------------------

  // Start YouTube Music sign-in: with MicroG RE on the phone, Bitchord+ asks first.
  function startSignIn(ctx) {
    if (ctx.get('bitchord.signedIn')) { ctx.go('bitchord.accountIntegrations'); return; }
    if (ctx.get('android.microg')) ctx.open('microg.offer');
    else ctx.go('browser.googleSignIn');
  }

  G.action('bc.tab', (ctx, node) => {
    const t = TABS.filter((x) => x.id === node.getAttribute('data-tab'))[0];
    if (!t) return;
    ctx.set('bitchord.tab', t.id);
    const cur = G.current().screen;
    if (cur !== t.screen) ctx.go(t.screen, null, { dir: 'fade' });
  });
  G.action('bc.account', (ctx) => {
    if (ctx.get('bitchord.signedIn')) ctx.open('bitchord.accountSheet');
    else ctx.go('bitchord.settings');
  });
  G.action('bc.signIn', startSignIn);
  G.action('bc.play', (ctx, node) => {
    const id = node.getAttribute('data-song');
    if (id && SONGS[id]) ctx.set({ 'bitchord.nowPlaying': id, 'bitchord.playing': true });
    ctx.go('bitchord.player');
  });
  G.action('bc.next', (ctx) => {
    const i = SONG_IDS.indexOf(ctx.get('bitchord.nowPlaying'));
    ctx.set({ 'bitchord.nowPlaying': SONG_IDS[(i + 1) % SONG_IDS.length], 'bitchord.playing': true });
  });
  G.action('bc.prev', (ctx) => {
    const i = SONG_IDS.indexOf(ctx.get('bitchord.nowPlaying'));
    ctx.set({ 'bitchord.nowPlaying': SONG_IDS[(i - 1 + SONG_IDS.length) % SONG_IDS.length], 'bitchord.playing': true });
  });
  G.action('bc.notDrawn', (ctx) => ctx.toast(NOT_DRAWN));

  // ---------------------------------------------------------------------------------------
  // Home (Listen Now), Explore, Library, Search
  // ---------------------------------------------------------------------------------------

  G.screen({
    id: 'bitchord.home',
    title: 'Bitchord+: Listen Now',
    back: 'android.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      return '<div class="b-page">' +
        '<div class="b-topbar"><span></span>' + accountButton(ctx) + '</div>' +
        '<h2 class="b-title">Listen Now</h2>' +
        (s['bitchord.signedIn'] ? '' :
          '<div class="b-signin-card" data-key="signin-card" data-action="bc.signIn">' +
            '<p class="b-signin-title">Sign in to YouTube Music</p>' +
            '<button type="button" class="b-pill b-pill-white" data-tg="bitchord.home.signInButton" data-action="bc.signIn">Sign in with Google</button>' +
          '</div>') +
        '<h3 class="b-shelf">Quick picks</h3>' +
        '<div class="b-list" data-key="quick-picks">' + SONG_IDS.map((id) => songRow(ctx, id, { key: 'home-' + id })).join('') + '</div>' +
        dock(ctx, 'home', true) +
      '</div>';
    }
  });

  G.screen({
    id: 'bitchord.explore',
    title: 'Bitchord+: Explore',
    back: 'bitchord.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const tiles = ['s4', 's2', 's5', 's3'].map((id) => {
        const s = song(id);
        return '<button type="button" class="b-tile" data-key="nr-' + id + '" data-action="bc.play" data-song="' + id + '">' + art(s, 132, 'b-art-tile') +
          '<span class="b-tile-title">' + esc(s.album) + '</span><span class="b-tile-sub">' + esc(s.artist) + '</span></button>';
      }).join('');
      return '<div class="b-page">' +
        '<div class="b-topbar"><span></span>' + accountButton(ctx) + '</div>' +
        '<h2 class="b-title">Explore</h2>' +
        '<h3 class="b-shelf">New releases</h3>' +
        '<div class="b-hscroll" role="group" aria-label="New releases">' + tiles + '</div>' +
        '<h3 class="b-shelf">Charts</h3>' +
        '<div class="b-list">' + ['s3', 's1', 's5'].map((id) => songRow(ctx, id, { key: 'chart-' + id })).join('') + '</div>' +
        dock(ctx, 'explore', true) +
      '</div>';
    }
  });

  G.screen({
    id: 'bitchord.library',
    title: 'Bitchord+: Library',
    back: 'bitchord.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const signedIn = !!ctx.s['bitchord.signedIn'];
      return '<div class="b-page">' +
        '<div class="b-topbar"><span></span>' +
          '<div class="b-pillbar"><button type="button" class="b-pillbar-btn" data-toast="' + esc(NOT_DRAWN) + '" aria-label="History">' + icon('history', { size: 24 }) + '</button>' +
          accountButton(ctx) + '</div>' +
        '</div>' +
        '<h2 class="b-title">Library</h2>' +
        '<div class="b-plain">' +
          row({ key: 'lib-dl', icon: 'download', title: 'Downloads', toast: NOT_DRAWN }) +
          row({ key: 'lib-local', icon: 'folder', title: 'Local Music', toast: NOT_DRAWN }) +
        '</div>' +
        '<h3 class="b-shelf">Playlists</h3>' +
        '<div class="b-grid2">' +
          '<button type="button" class="b-ptile" data-toast="' + esc(NOT_DRAWN) + '"><span class="b-ptile-art" aria-hidden="true">' + icon('add', { size: 34 }) + '</span>' +
            '<span class="b-tile-title">New playlist</span><span class="b-tile-sub">Saved to YouTube Music</span></button>' +
          '<button type="button" class="b-ptile" data-toast="' + esc(NOT_DRAWN) + '"><span class="b-ptile-art" aria-hidden="true">' + icon('link', { size: 30 }) + '</span>' +
            '<span class="b-tile-title">Import Spotify</span><span class="b-tile-sub">From a playlist link</span></button>' +
        '</div>' +
        (signedIn ? '<div class="b-list" data-key="lib-liked">' + songRow(ctx, 's4', { key: 'lib-s4' }) + songRow(ctx, 's2', { key: 'lib-s2' }) + '</div>' :
          '<div class="b-empty" data-key="lib-signin"><p>Sign in to your Google account to see your liked songs, playlists, and YouTube Music history.</p>' +
          '<button type="button" class="b-pill b-pill-white" data-action="bc.signIn" data-tg="bitchord.library.signInButton">Sign in</button></div>') +
        dock(ctx, 'library', true) +
      '</div>';
    }
  });

  // Addon results: the files in your music chats that match, each with its quality tag.
  function addonResults(q) {
    const hit = SONG_IDS.filter((id) => sameText(SONGS[id].title, q) || sameText(SONGS[id].artist, q))[0];
    const tags = ['FLAC 24bit', 'FLAC 16bit', 'MP3 320'];
    const name = String(q).trim().replace(/\b\w/g, (c) => c.toUpperCase());
    const base = hit ? SONGS[hit].title : name;
    const ids = hit ? [hit, hit, hit] : ['s3', 's1', 's5'];
    return [base, base + ' (Live)', base + ' (Demo)'].map((title, i) => ({ id: ids[i], title, tag: tags[i] }));
  }

  G.screen({
    id: 'bitchord.search',
    title: 'Bitchord+: Search',
    back: 'bitchord.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const tab = s['bitchord.searchTab'];
      const q = String(s['bitchord.searchQuery'] || '');
      const placeholder = tab === 'addons' ? 'Search your addons' : tab === 'library' ? 'Songs, Artists and Albums' : 'Artists, Songs, Lyrics and More';
      let body = '';
      if (!q.trim()) {
        const empty = tab === 'addons' ? 'Search every enabled addon at once. Tracks play straight from the addon.' :
          tab === 'library' ? 'Search the songs in your Local Music folder.' : 'Search millions of songs on YouTube Music.';
        const recent = s['bitchord.searchRecent'] || [];
        body = '<p class="b-search-empty" data-key="empty-' + tab + '">' + esc(empty) + '</p>' +
          (tab === 'addons' && recent.length ?
            '<div class="b-recent" data-key="recent"><div class="b-recent-head"><h3 class="b-shelf b-shelf-small">Recent searches</h3>' +
              '<button type="button" class="b-textbtn" data-set="' + attrJson({ 'bitchord.searchRecent': [] }) + '">Clear</button></div>' +
              recent.map((r) => '<button type="button" class="b-recent-row" data-key="recent-' + esc(r) + '" data-set="' + attrJson({ 'bitchord.searchQuery': r }) + '">' +
                icon('history', { size: 20 }) + '<span>' + esc(r) + '</span></button>').join('') +
            '</div>' : '');
      } else if (tab === 'library') {
        body = '<p class="b-search-empty" data-key="lib-none">Nothing in Local Music matches “' + esc(q) + '”.</p>';
      } else if (tab === 'addons') {
        body = '<div class="b-list" data-key="addon-results">' + addonResults(q).map((r, i) =>
          songRow(ctx, r.id, { key: 'res-' + i, title: r.title, tg: i === 0 ? 'bitchord.search.resultRow' : null, badge: r.tag, sub: song(r.id).artist + ' · Unofficial Telegram' })).join('') + '</div>';
      } else {
        const hits = SONG_IDS.filter((id) => sameText(SONGS[id].title, q) || sameText(SONGS[id].artist, q));
        body = '<div class="b-list" data-key="yt-results">' + (hits.length ? hits : SONG_IDS.slice(0, 3)).map((id, i) =>
          songRow(ctx, id, { key: 'yt-' + id, tg: i === 0 ? 'bitchord.search.resultRow' : null, sub: 'Song · ' + song(id).artist })).join('') + '</div>';
      }
      return '<div class="b-page">' +
        '<div class="b-topbar b-topbar-search">' +
          '<div class="b-search-box">' + icon('search', { size: 20, cls: 'b-search-icon' }) +
            G.ui.field({ bind: 'bitchord.searchQuery', tg: 'bitchord.search.field', label: 'Search', placeholder: placeholder, cls: 'b-search-input', type: 'search' }) +
            (q ? '<button type="button" class="b-search-clear" data-set="' + attrJson({ 'bitchord.searchQuery': '' }) + '" aria-label="Clear search">' + icon('close', { size: 18 }) + '</button>' : '') +
          '</div>' + accountButton(ctx) +
        '</div>' +
        (q.trim() ? '' : segmented({
          label: 'Search in', bind: 'bitchord.searchTab', value: tab, cls: 'b-seg-search', items: [
            { value: 'youtube', label: 'YouTube' },
            { value: 'library', label: 'Library' },
            { value: 'addons', label: 'Addons', tg: 'bitchord.search.addonsSegment' }
          ]
        })) +
        body +
        dock(ctx, 'search', true) +
      '</div>';
    }
  });

  // Signed in: the account circle opens this sheet.
  G.screen({
    id: 'bitchord.accountSheet',
    kind: 'sheet',
    title: 'Switch account',
    render(ctx) {
      const name = ctx.s['bitchord.channel'] || 'Alex Example';
      return '<div class="b-sheet">' +
        '<div class="b-handle" aria-hidden="true"></div>' +
        '<h2 class="b-sheet-title">Switch account</h2>' +
        '<p class="b-sheet-sub">alex@example.com</p>' +
        '<div class="b-sheet-rows">' +
          '<div class="b-action b-action-static">' + avatar(ctx, 36) + '<span class="b-action-text"><span>' + esc(name) + '</span><span class="b-action-sub">' +
            (name === 'Alex Example' ? 'Personal' : 'Brand account') + '</span></span>' + icon('check', { size: 22 }) + '</div>' +
          '<button type="button" class="b-action" data-close data-toast="' + esc(NOT_DRAWN) + '">' + icon('add', { size: 22 }) + '<span class="b-action-text">Add account</span></button>' +
          '<button type="button" class="b-action" data-close data-go="bitchord.accountIntegrations">' + icon('account', { size: 22 }) + '<span class="b-action-text">Manage accounts</span></button>' +
          '<button type="button" class="b-action" data-close data-go="bitchord.settings" data-tg="bitchord.accountSheet.settingsRow">' + icon('settings', { size: 22 }) + '<span class="b-action-text">Settings</span></button>' +
        '</div>' +
      '</div>';
    }
  });

  // ---------------------------------------------------------------------------------------
  // Settings, Account & integrations, Sources, Equalizer
  // ---------------------------------------------------------------------------------------

  function eqDescribe(s) {
    if (!s['bitchord.equalizer']) return 'Equaliser off';
    if (s['bitchord.eq.mode'] === 'manual') return 'Sliders +0 +0 +0 +0 +0 +0 +0';
    return 'Tone pad ' + s['bitchord.eq.toneX'] + ', ' + s['bitchord.eq.toneY'];
  }

  function settingsGroups(ctx) {
    const s = ctx.s;
    const signedIn = !!s['bitchord.signedIn'];
    const dq = QUALITY[s['bitchord.downloadQuality']] || QUALITY.lossless;
    const out = output(s['bitchord.output']);
    return [
      { key: 'g-account', rows: [
        { key: 'r-account', icon: 'person', title: 'Account & integrations', sub: signedIn ? 'alex@example.com' : 'Not signed in', go: 'bitchord.accountIntegrations', tg: 'bitchord.settings.accountIntegrationsRow' },
        { key: 'r-together', icon: 'group', title: 'Listen together', sub: 'Share a code and play the same music, in time', toast: NOT_DRAWN }
      ] },
      { key: 'g-patches', header: 'Patches & tweaks', footer: 'Added to BitChord by patches.', always: true, rows: [
        { key: 'r-patches', title: 'Patches & tweaks', sub: 'Settings of the patches you added', go: 'tweaks.page', tg: 'bitchord.settings.patchesTweaksRow' }
      ] },
      { key: 'g-quality', header: 'Audio quality', rows: [
        { key: 'r-sources', icon: 'extension', title: 'Sources', sub: 'Where audio comes from and the order used', go: 'bitchord.sources', tg: 'bitchord.settings.sourcesRow' },
        { key: 'r-wifi', icon: 'wifi', title: 'On Wi-Fi', badge: 'In use', value: 'Lossless', toast: NOT_DRAWN },
        { key: 'r-mobile', icon: 'signal', title: 'On mobile data', value: 'Lossless', toast: NOT_DRAWN },
        { key: 'r-atmos', icon: 'surround', title: 'Dolby Atmos', sub: 'This device can’t play Atmos, so these songs play in their usual version', disabled: true,
          sw: { bind: 'bitchord.opt.atmos', disabled: true } }
      ] },
      { key: 'g-downloads', header: 'Downloads', rows: [
        { key: 'r-dlq', icon: 'download', title: 'Download quality', sub: dq.size + ' per track, whatever the connection', value: dq.label, open: 'bitchord.downloadQuality', tg: 'bitchord.settings.downloadQualityRow' },
        { key: 'r-wifionly', sub2: true, title: 'Download over Wi-Fi only', sw: { bind: 'bitchord.opt.wifiOnly' } },
        { key: 'r-export', sub2: true, title: 'Export compatible downloads', sub: s['bitchord.opt.exportDownloads'] ? 'Music/BitChord' : '', sw: { bind: 'bitchord.opt.exportDownloads' } }
      ] },
      { key: 'g-playback', header: 'Playback', rows: [
        { key: 'r-musiconly', icon: 'play', title: 'Prefer music-only version', sub: 'For music videos, show the video details while finding and loading the catalogue audio version', sw: { bind: 'bitchord.opt.musicOnly' } },
        { key: 'r-precision', icon: 'equalizer', title: 'Output precision', sub: 'AudioTrack · ' + out.name + ' · 48.0 kHz · ' + (s['bitchord.opt.precision'] === 'float32' ? '32-bit float' : '16-bit PCM'), static: true, cls: 'b-row-top' },
        { key: 'r-precision-seg', html: '<div class="b-row-extra" data-key="r-precision-seg">' + segmented({ label: 'Output precision', bind: 'bitchord.opt.precision', value: s['bitchord.opt.precision'],
          items: [{ value: 'pcm16', label: '16-bit PCM' }, { value: 'float32', label: '32-bit float' }] }) + '</div>' },
        { key: 'r-usbdac', sub2: true, title: 'Prefer USB DAC', sw: { bind: 'bitchord.opt.usbDac' } },
        { key: 'r-loudness', icon: 'volume', title: 'Loudness normalization', sub: 'Levels every track to the same loudness', sw: { bind: 'bitchord.loudnessNormalization', tg: 'bitchord.settings.loudnessNormalizationSwitch' } },
        { key: 'r-speakeroff', sub2: true, title: 'Disable on Phone Speaker', sw: { bind: 'bitchord.opt.speakerOff' } },
        { key: 'r-crossfade', icon: 'wave', title: 'Crossfade', sub: 'Blends one track into the next', static: true, cls: 'b-row-top',
          after: '<span class="b-row-value">' + (s['bitchord.opt.crossfade'] ? s['bitchord.opt.crossfade'] + 's' : 'Off') + '</span>' },
        { key: 'r-crossfade-slider', html: '<div class="b-row-extra b-row-extra-slider" data-key="r-crossfade-slider">' +
          G.ui.slider({ bind: 'bitchord.opt.crossfade', min: 0, max: 12, step: 1, labelledby: 'b-r-r-crossfade', cls: 'b-slider-bar', text: (v) => v ? v + ' seconds' : 'Off' }) + '</div>' },
        { key: 'r-automix', icon: 'wand', title: 'Automix [BETA]', sub: 'Times and blends transitions automatically, without a slider. Performance may vary on slower devices.', sw: { bind: 'bitchord.opt.automix' } },
        { key: 'r-automixperf', icon: 'tune', title: 'Automix performance', sub: 'Sets how much CPU background analysis may use', value: 'Balanced', toast: NOT_DRAWN },
        { key: 'r-silence', icon: 'volume', title: 'Skip silence', sub: 'Trim gaps longer than a second', sw: { bind: 'bitchord.opt.skipSilence' } },
        { key: 'r-spatial', icon: 'surround', title: 'Spatial audio', sub: 'Widens stereo tracks for a more immersive feel', sw: { bind: 'bitchord.opt.spatial' } },
        { key: 'r-eq', icon: 'tune', title: 'Equalizer', sub: 'Tone, seven bands and balance', go: 'bitchord.equalizer', tg: 'bitchord.settings.equalizerRow' }
      ] },
      { key: 'g-appearance', header: 'Appearance', rows: [
        { key: 'r-theme', icon: 'eye', title: 'Theme', static: true, cls: 'b-row-top' },
        { key: 'r-theme-seg', html: '<div class="b-row-extra" data-key="r-theme-seg">' + segmented({ label: 'Theme', value: 'dark', items: [
          { value: 'system', label: 'System', action: 'bc.theme' }, { value: 'light', label: 'Light', action: 'bc.theme' }, { value: 'dark', label: 'Dark', action: 'bc.theme' }] }) + '</div>' },
        { key: 'r-reduce', icon: 'refresh', title: 'Reduce animation', sw: { bind: 'bitchord.opt.reduceAnimation' } }
      ] }
    ];
  }

  G.screen({
    id: 'bitchord.settings',
    title: 'Bitchord+: Settings',
    back: 'bitchord.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const q = String(s['bitchord.settingsQuery'] || '').trim().toLowerCase();
      let matches = 0;
      const groupsHtml = settingsGroups(ctx).map((g) => {
        // Rows that belong to the row before them (segments, sliders, sub-rows) follow it.
        let rows = g.rows;
        if (q && !g.always) {
          const keep = [];
          let lastKept = false;
          rows.forEach((r) => {
            const attached = !!r.html || !!r.sub2;
            const hit = !attached && (sameText(r.title, q) || (r.sub && sameText(r.sub, q)));
            if (hit) { keep.push(r); lastKept = true; } else if (attached && lastKept) keep.push(r); else if (!attached) lastKept = false;
          });
          rows = keep;
          matches += keep.length;
        }
        if (!rows.length) return '';
        return group({ key: g.key, header: g.header, footer: g.footer, rows: rows.map((r) => r.html || row(r)) });
      }).join('');
      return '<div class="b-page">' +
        '<div class="b-topbar">' + backButton() + '</div>' +
        '<h2 class="b-title">Settings</h2>' +
        '<div class="b-settings-search">' + icon('search', { size: 22, cls: 'b-search-icon' }) +
          G.ui.field({ bind: 'bitchord.settingsQuery', label: 'Search settings', placeholder: 'Search settings', cls: 'b-search-input', type: 'search' }) + '</div>' +
        groupsHtml +
        (q && !matches ? '<p class="b-search-empty" data-key="no-match">No settings match “' + esc(s['bitchord.settingsQuery']) + '”</p>' : '') +
        (q ? '' : '<p class="b-version" data-key="version">bitchord 1.9 <span>GitHub</span> <span>Developer</span> <span>Discord</span> <span>Website</span><br>~YouTube Music &amp; Listen Together Backend</p>') +
        dock(ctx, s['bitchord.tab'], false) +
      '</div>';
    },
    actions: {
      'bc.theme'(ctx, node) {
        if (node.getAttribute('data-value') !== 'dark') ctx.toast('This guide only draws BitChord’s dark look.');
      }
    }
  });

  // BitChord's own Download quality sheet (Settings › Downloads).
  G.screen({
    id: 'bitchord.downloadQuality',
    kind: 'sheet',
    title: 'Download quality',
    render(ctx) {
      const cur = ctx.s['bitchord.downloadQuality'];
      return '<div class="b-sheet b-dq">' +
        '<div class="b-handle" aria-hidden="true"></div>' +
        '<div class="b-dq-head">' + icon('download', { size: 26 }) + '<div><h2 class="b-sheet-title">Download quality</h2><p class="b-sheet-sub">For files kept on this device</p></div></div>' +
        '<div class="b-hair" aria-hidden="true"></div>' +
        '<div role="radiogroup" aria-label="Download quality">' +
          ['lossless', 'high', 'standard'].map((k) => '<button type="button" class="b-choice" role="radio" aria-checked="' + (cur === k) + '" data-key="dq-' + k + '"' +
            ' data-set="' + attrJson({ 'bitchord.downloadQuality': k }) + '" data-close>' +
            '<span class="b-choice-text"><span>' + QUALITY[k].label + '</span><span class="b-choice-sub">' + esc(QUALITY[k].sheet) + '</span></span>' +
            (cur === k ? icon('check', { size: 26 }) : '<span class="b-choice-pad"></span>') + '</button>').join('') +
        '</div>' +
      '</div>';
    }
  });

  G.screen({
    id: 'bitchord.accountIntegrations',
    title: 'Bitchord+: Account & integrations',
    back: 'bitchord.settings',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const signedIn = !!s['bitchord.signedIn'];
      const account = signedIn ?
        '<div class="b-card">' +
          '<div class="b-account-row" data-tg="bitchord.accountIntegrations.accountRow">' + avatar(ctx, 52) +
            '<span class="b-row-text"><span class="b-account-name">' + esc(s['bitchord.channel'] || 'Alex Example') + '</span><span class="b-row-sub">alex@example.com</span></span></div>' +
          row({ key: 'listen-as', title: 'Listen as', value: s['bitchord.channel'] || 'Alex Example', go: 'bitchord.signInChannel' }) +
          '<button type="button" class="b-center-btn" data-key="signout" data-set="' + attrJson({ 'bitchord.signedIn': false }) + '">Sign out</button>' +
        '</div><p class="b-group-f">A Google account can have Personal and Brand profiles. Each has its own library, likes and history.</p>' :
        '<div class="b-card">' +
          '<button type="button" class="b-account-row" data-tg="bitchord.accountIntegrations.accountRow" data-action="bc.signIn">' + avatar(ctx, 52) +
            '<span class="b-row-text"><span class="b-account-name">Not signed in</span><span class="b-row-sub">Tap to sign in with Google</span></span>' +
            '<span class="b-row-chev">' + icon('chevronRight', { size: 20 }) + '</span></button>' +
        '</div>';
      return '<div class="b-page">' +
        '<div class="b-topbar">' + backButton() + '</div>' +
        '<h2 class="b-title">Account &amp; integrations</h2>' +
        '<section class="b-group" data-key="g-acc"><div class="b-group-gap" aria-hidden="true"></div>' + account + '</section>' +
        group({ key: 'g-tg', header: 'Unofficial Telegram', footer: 'An unofficial client using the Telegram API.', rows: [
          row({ key: 'r-tg', icon: 'cloud', title: 'Unofficial Telegram', sub: 'Sign in, search music chats and manage cache', go: 'tg.page', tg: 'bitchord.accountIntegrations.unofficialTelegramRow' })
        ] }) +
        group({ key: 'g-spotify', header: 'Spotify', footer: 'Sign in to load your playlists. Playback uses YouTube Music.', rows: [
          row({ key: 'r-spotify', icon: 'link', title: 'Spotify', sub: 'Tap to connect', toast: NOT_DRAWN })
        ] }) +
        group({ key: 'g-presence', header: 'Rich presence', footer: 'Show what you are playing on your Discord profile.', rows: [
          row({ key: 'r-discord', icon: 'group', title: 'Discord', sub: 'Tap to connect', toast: NOT_DRAWN })
        ] }) +
        group({ key: 'g-scrobble', header: 'Scrobbling', footer: 'Scrobble your listens to Last.fm and ListenBrainz.', rows: [
          row({ key: 'r-lastfm', icon: 'history', title: 'Last.fm', sub: 'Tap to connect', toast: NOT_DRAWN }),
          row({ key: 'r-lb', icon: 'history', title: 'ListenBrainz', sub: 'Tap to connect', toast: NOT_DRAWN })
        ] }) +
        dock(ctx, s['bitchord.tab'], false) +
      '</div>';
    }
  });

  // Is a lossless-only addon held back on this output?
  function losslessHeldBack(s) {
    const out = output(s['bitchord.output']);
    if (!out.bt) return false;
    if (s['morphe.patch.losslessBluetooth'] && (s['tweaks.bluetooth.all'] || (s['tweaks.bluetooth.devices'] || []).indexOf(out.page) >= 0)) return false;
    return true;
  }

  G.screen({
    id: 'bitchord.sources',
    title: 'Bitchord+: Sources',
    back: 'bitchord.settings',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const out = output(s['bitchord.output']);
      const srcRow = (o) => '<div class="b-src' + (o.off ? ' b-src-off' : '') + '" data-key="' + o.key + '">' +
        '<button type="button" class="b-src-main"' + (o.go ? ' data-go="' + o.go + '"' : ' data-toast="' + esc(NOT_DRAWN) + '"') + (o.tg ? ' data-tg="' + o.tg + '"' : '') + '>' +
          '<span class="b-src-num">' + o.num + '</span><span class="b-row-icon">' + icon(o.icon, { size: 22 }) + '</span>' +
          '<span class="b-row-text"><span class="b-row-title" id="b-src-' + o.key + '">' + esc(o.title) + '</span><span class="b-row-sub' + (o.red ? ' b-src-red' : '') + '">' + esc(o.sub) + '</span></span>' +
        '</button>' +
        (o.sw ? G.ui.switch({ bind: o.sw, labelledby: 'b-src-' + o.key }) : '<span class="b-src-always">Always on</span>') +
        (o.drag ? '<span class="b-src-drag" aria-hidden="true">' + icon('dragHandle', { size: 22 }) + '</span>' : '') +
      '</div>';
      const rows = [
        srcRow({ key: 'telegram', num: 1, icon: 'extension', title: 'Unofficial Telegram', sub: 'Private on-device Telegram audio · FLAC · Lossless · Hi-Res',
          sw: 'bitchord.sources.telegram', drag: true, go: 'tg.page', tg: 'bitchord.sources.telegramRow', off: !s['bitchord.sources.telegram'] }),
        srcRow({ key: 'jiosaavn', num: 2, icon: 'equalizer', title: 'JioSaavn', sub: 'May match the wrong version of a song. Enable at your own risk.',
          sw: 'bitchord.sources.jiosaavn', drag: true, off: !s['bitchord.sources.jiosaavn'] })
      ];
      let n = 3;
      if (s['bitchord.exampleAddon']) {
        const held = losslessHeldBack(s);
        rows.push(srcRow({ key: 'example', num: n++, icon: 'extension', title: 'Lossless addon (example)',
          sub: held ? 'Needs a lossless output · Bluetooth is on ' + out.codec + '; use a lossless supported device' : 'FLAC · Lossless',
          red: held, drag: true, sw: 'bitchord.sources.example', tg: 'bitchord.sources.exampleRow', off: held || !s['bitchord.sources.example'] }));
      }
      rows.push(srcRow({ key: 'youtube', num: n, icon: 'play', title: 'YouTube Music', sub: 'Lossy · Full catalogue · Radio' }));
      rows.push(row({ key: 'add-addon', icon: 'add', title: 'Add an addon', sub: 'Point BitChord at an addon server you host or were given a link to', toast: NOT_DRAWN, chevron: false }));
      return '<div class="b-page">' +
        '<div class="b-topbar">' + backButton() + '</div>' +
        '<h2 class="b-title">Sources</h2>' +
        group({ key: 'g-src', header: 'Sources, tried in this order', tg: 'bitchord.sources.list',
          footer: 'If a source does not have a track or cannot be reached, BitChord tries the next one. Sources above YouTube can replace its recording when they return a better version.',
          rows: rows }) +
        group({ key: 'g-match', header: 'Source matching', footer: 'A larger tolerance accepts more source matches; a smaller tolerance is stricter. The default is 3 seconds.', rows: [
          row({ key: 'r-tol', icon: 'tune', title: 'Track length tolerance', sub: 'Maximum duration difference allowed for a replacement stream', static: true, after: '<span class="b-row-value">3s</span>' })
        ] }) +
        dock(ctx, s['bitchord.tab'], false) +
      '</div>';
    }
  });

  // The tone pad: 11 × 11 dots, the puck snaps in five steps each way.
  function padPosition(ctx, node, event) {
    if (!event || !event.detail) return; // a key press, not a pointer tap
    const r = node.getBoundingClientRect();
    if (!r.width || !r.height) return;
    const fx = Math.min(1, Math.max(0, (event.clientX - r.left) / r.width));
    const fy = Math.min(1, Math.max(0, (event.clientY - r.top) / r.height));
    ctx.set({ 'bitchord.eq.toneX': Math.round(fx * 10) - 5, 'bitchord.eq.toneY': 5 - Math.round(fy * 10) });
  }

  G.screen({
    id: 'bitchord.equalizer',
    title: 'Bitchord+: Equalizer',
    back: 'bitchord.settings',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const on = !!s['bitchord.equalizer'];
      const x = s['bitchord.eq.toneX'], y = s['bitchord.eq.toneY'];
      const manual = s['bitchord.eq.mode'] === 'manual';
      const focused = !!s['bitchord.eq.focused'];
      const bands = ['60', '150', '400', '1k', '2.5k', '6k', '14k'];
      const dynamic =
        '<section class="b-group" data-key="eq-tone"><h3 class="b-group-h">Tone</h3><div class="b-card b-eq-tone">' +
          '<button type="button" class="b-eq-pad" data-action="bc.pad" aria-label="' + esc('Tone pad: tilt ' + signed(x) + ', contour ' + signed(y) + '. Arrow keys move it.') + '">' +
            '<span class="b-eq-puck" style="--x:' + ((x + 5) * 10) + '%;--y:' + ((5 - y) * 10) + '%"></span></button>' +
          '<div class="b-eq-readout" aria-hidden="true"><span>' + icon('arrowForward', { size: 18 }) + ' ' + signed(x) + '</span><span>' + icon('arrowUp', { size: 18 }) + ' ' + signed(y) + '</span></div>' +
          '<div class="b-hair" aria-hidden="true"></div>' +
          '<button type="button" class="b-choice b-choice-in" role="radio" aria-checked="' + !focused + '" data-bind="bitchord.eq.focused" data-value="false"><span class="b-choice-text"><span>Broad</span></span>' +
            (focused ? '<span class="b-choice-pad"></span>' : icon('check', { size: 22 })) + '</button>' +
          '<div class="b-hair" aria-hidden="true"></div>' +
          '<button type="button" class="b-choice b-choice-in" role="radio" aria-checked="' + focused + '" data-bind="bitchord.eq.focused" data-value="true"><span class="b-choice-text"><span>Focused</span></span>' +
            (focused ? icon('check', { size: 22 }) : '<span class="b-choice-pad"></span>') + '</button>' +
        '</div><p class="b-group-f">' + (focused ?
          'Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward. Focused works on narrower stretches, so it changes less around what it moves.' :
          'Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward. Broad moves wide stretches of the spectrum at once.') + '</p></section>';
      const manualHtml =
        group({ key: 'eq-preset', rows: [row({ key: 'r-preset', icon: 'equalizer', title: 'Preset', value: 'Flat', toast: NOT_DRAWN })] }) +
        '<section class="b-group" data-key="eq-bands"><h3 class="b-group-h">Bands</h3><div class="b-card b-eq-bands">' +
          '<div class="b-eq-faders" aria-hidden="true">' + bands.map((b) => '<span class="b-eq-fader"><span class="b-eq-val">0</span><span class="b-eq-track"><span class="b-eq-knob"></span></span><span class="b-eq-freq">' + b + '</span></span>').join('') + '</div>' +
          '<div class="b-hair" aria-hidden="true"></div><button type="button" class="b-center-btn" data-toast="' + esc(NOT_DRAWN) + '">Reset all bands</button>' +
        '</div><p class="b-group-f">Moving a band leaves the preset behind and the curve becomes your own.</p></section>';
      return '<div class="b-page">' +
        '<div class="b-topbar">' + backButton() + '</div>' +
        '<section class="b-group" data-key="eq-switch"><div class="b-group-gap" aria-hidden="true"></div><div class="b-card">' +
          row({ key: 'eq-on', id: 'b-eq-title', icon: 'tune', title: 'Equalizer', sw: { bind: 'bitchord.equalizer', tg: 'bitchord.equalizer.switch' } }) +
        '</div><p class="b-group-f">Shapes the sound inside BitChord, before it reaches the system.</p></section>' +
        '<div class="b-eq-body' + (on ? '' : ' b-eq-dim') + '"' + (on ? '' : ' inert') + ' data-key="eq-body">' +
          segmented({ label: 'Equalizer mode', bind: 'bitchord.eq.mode', value: s['bitchord.eq.mode'], cls: 'b-seg-eq',
            items: [{ value: 'dynamic', label: 'Dynamic' }, { value: 'manual', label: 'Manual' }] }) +
          (manual ? manualHtml : dynamic) +
          '<section class="b-group" data-key="eq-balance"><h3 class="b-group-h">Balance</h3><div class="b-card b-eq-balance">' +
            '<div class="b-eq-bal-labels"><span>L</span><span>0.00</span><span>R</span></div><div class="b-eq-bal-track" aria-hidden="true"><span></span></div>' +
          '</div><p class="b-group-f">Shifts the sound towards one ear by quietening the other.</p></section>' +
        '</div>' +
        group({ key: 'eq-system', footer: 'Runs after BitChord’s, on the whole audio session. Using both applies both.', rows: [
          row({ key: 'r-syseq', icon: 'equalizer', title: 'System equalizer', sub: 'Your device’s own panel', toast: NOT_DRAWN })
        ] }) +
        dock(ctx, s['bitchord.tab'], false) +
      '</div>';
    },
    actions: {
      'bc.pad': padPosition
    },
    mount(node, ctx) {
      // Arrow keys move the puck on the tone pad.
      const onKey = (e) => {
        if (!e.target.classList || !e.target.classList.contains('b-eq-pad')) return;
        const d = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1] }[e.key];
        if (!d) return;
        e.preventDefault();
        const clampTo = (v) => Math.max(-5, Math.min(5, v));
        ctx.set({ 'bitchord.eq.toneX': clampTo(G.get('bitchord.eq.toneX') + d[0]), 'bitchord.eq.toneY': clampTo(G.get('bitchord.eq.toneY') + d[1]) });
      };
      node.addEventListener('keydown', onKey);
      return () => node.removeEventListener('keydown', onKey);
    }
  });

  // ---------------------------------------------------------------------------------------
  // Now Playing, its sheets and the output picker
  // ---------------------------------------------------------------------------------------

  G.screen({
    id: 'bitchord.player',
    title: 'Bitchord+: Now Playing',
    back: 'bitchord.home',
    bars: { bg: '#15131c', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const sg = song(s['bitchord.nowPlaying']);
      const playing = !!s['bitchord.playing'];
      const out = output(s['bitchord.output']);
      return '<div class="b-player" style="--hue:' + sg.hue + '">' +
        '<div class="b-player-art">' + art(sg, 300, 'b-art-xl') + '</div>' +
        '<div class="b-player-meta">' +
          '<div class="b-player-text"><h2 class="b-player-title">' + esc(sg.title) + '</h2><p class="b-player-artist">' + esc(sg.artist) + '</p></div>' +
          (s['bitchord.signedIn'] ? '<button type="button" class="b-glass" data-toast="' + esc(NOT_DRAWN) + '" aria-label="Like">' + icon('star', { size: 19 }) + '</button>' : '') +
          '<button type="button" class="b-glass" data-tg="bitchord.player.moreButton" data-action="bc.more" aria-label="More">' + icon('moreHoriz', { size: 19 }) + '</button>' +
        '</div>' +
        '<div class="b-scrub" aria-hidden="true"><div class="b-scrub-bar"><span style="width:33%"></span></div>' +
          '<div class="b-scrub-times"><span>1:12</span><span class="b-scrub-q">Lossless</span><span>−2:31</span></div></div>' +
        '<div class="b-transport">' +
          '<button type="button" class="b-tbtn" data-action="bc.prev" aria-label="Previous song">' + icon('skipPrev', { size: 38 }) + '</button>' +
          '<button type="button" class="b-tbtn b-tbtn-main" data-bind="bitchord.playing" data-tg="bitchord.player.playPauseButton" aria-label="' + (playing ? 'Pause' : 'Play') + '">' +
            icon(playing ? 'pause' : 'play', { size: 56 }) + '</button>' +
          '<button type="button" class="b-tbtn" data-action="bc.next" aria-label="Next song">' + icon('skipNext', { size: 38 }) + '</button>' +
        '</div>' +
        '<div class="b-volume">' + icon('speaker', { size: 16 }) + G.ui.slider({ bind: 'bitchord.volume', min: 0, max: 100, step: 1, label: 'Volume', cls: 'b-volume-slider', text: (v) => v + '%' }) + icon('volume', { size: 18 }) + '</div>' +
        '<div class="b-pactions">' +
          '<button type="button" class="b-picon" data-toast="' + esc(NOT_DRAWN) + '" aria-label="Lyrics">' + icon('musicNote', { size: 22 }) + '</button>' +
          '<div class="b-capsule">' +
            '<button type="button" class="b-capsule-btn" data-open="bitchord.outputSheet" data-tg="bitchord.player.outputButton" aria-label="Audio output">' + icon(out.icon, { size: 20 }) + '</button>' +
            '<button type="button" class="b-capsule-btn" data-toast="' + esc(NOT_DRAWN) + '" aria-label="Listen together">' + icon('person', { size: 20 }) + '</button>' +
          '</div>' +
          '<button type="button" class="b-picon" data-open="bitchord.queue" data-tg="bitchord.player.queueButton" aria-label="Up next">' + icon('queue', { size: 22 }) + '</button>' +
        '</div>' +
        '<button type="button" class="b-output-caption" data-open="bitchord.outputSheet" data-tg="bitchord.player.outputCaption">' + esc(out.name) + '</button>' +
      '</div>';
    },
    actions: {
      'bc.more'(ctx) {
        ctx.set('bitchord.songSheetView', 'actions');
        ctx.open('bitchord.songSheet');
      }
    }
  });

  function downloadRow(ctx, id, s) {
    const state = (s['bitchord.downloads'] || {})[id] || '';
    let label = 'Download', ic = 'download', value = '';
    if (state === 'queued') { label = 'Queued'; value = 'Cancel'; }
    if (state === 'saved') { label = 'Saved to Downloads'; ic = 'downloadDone'; value = 'Delete'; }
    return '<button type="button" class="b-action" data-key="act-download" data-tg="bitchord.songSheet.downloadRow" data-action="bc.download">' +
      icon(ic, { size: 22 }) + '<span class="b-action-text">' + label + '</span>' + (value ? '<span class="b-action-value">' + value + '</span>' : '') + '</button>';
  }

  function setDownload(id, state) {
    const map = Object.assign({}, G.get('bitchord.downloads') || {});
    if (state) map[id] = state; else delete map[id];
    G.set('bitchord.downloads', map);
  }

  // Queue a song: it is saved a moment later (not during a tour, so each step stays as drawn).
  function queueDownload(id) {
    setDownload(id, 'queued');
    if (!G.inTour()) G.later(() => { if ((G.get('bitchord.downloads') || {})[id] === 'queued') setDownload(id, 'saved'); }, 1800);
  }

  G.screen({
    id: 'bitchord.songSheet',
    kind: 'sheet',
    title: 'Song actions',
    className: 'b-songsheet-ov',
    render(ctx) {
      const s = ctx.s;
      const id = ctx.params.song || s['bitchord.nowPlaying'];
      const sg = song(id);
      const fromPlayer = ctx.params.from !== 'row';
      const view = fromPlayer ? s['bitchord.songSheetView'] : 'actions';
      const timer = String(s['bitchord.sleepTimer'] || '');
      const head = '<div class="b-handle b-handle-light" aria-hidden="true"></div>';
      if (view === 'sleep') {
        const sub = !timer ? 'Pause playback after a while' : timer === 'end' ? 'Pausing when this song ends' : (SLEEP[timer] ? SLEEP[timer].left : '') + ' until playback pauses';
        return '<div class="b-sheet b-songsheet" style="--hue:' + sg.hue + '">' + head +
          '<div class="b-sleep-head" data-key="sleep-head">' +
            '<button type="button" class="b-iconbtn" data-set="' + attrJson({ 'bitchord.songSheetView': 'actions' }) + '" aria-label="Back">' + icon('arrowBack', { size: 24 }) + '</button>' +
            '<div><h2 class="b-sheet-title">Sleep timer</h2><p class="b-sheet-sub">' + esc(sub) + '</p></div>' +
          '</div>' +
          '<div class="b-hair" aria-hidden="true"></div>' +
          '<div class="b-sleep-options" role="radiogroup" aria-label="Sleep timer" data-tg="bitchord.sleepTimer.options">' +
            SLEEP_ORDER.map((k) => '<button type="button" class="b-choice" role="radio" aria-checked="' + (timer === k) + '" data-key="sleep-' + k + '" data-action="bc.sleepPick" data-v="' + k + '"' +
              (k === '30' ? ' data-tg="bitchord.sleepTimer.option30"' : '') + '><span class="b-choice-text"><span>' + SLEEP[k].label + '</span></span>' +
              (timer === k ? icon('check', { size: 22 }) : '<span class="b-choice-pad"></span>') + '</button>').join('') +
          '</div>' +
          (timer ? '<button type="button" class="b-action" data-key="sleep-off" data-action="bc.sleepPick" data-v="" data-tg="bitchord.sleepTimer.turnOff">' + icon('close', { size: 22 }) + '<span class="b-action-text">Turn off timer</span></button>' : '') +
        '</div>';
      }
      const a = (key, ic, label, extra) => '<button type="button" class="b-action" data-key="act-' + key + '"' + (extra || ' data-close') + '>' + icon(ic, { size: 22 }) + '<span class="b-action-text">' + label + '</span></button>';
      const signedIn = !!s['bitchord.signedIn'];
      return '<div class="b-sheet b-songsheet" style="--hue:' + sg.hue + '">' + head +
        '<div class="b-sheet-track" data-key="track">' + art(sg, 52) + '<div><p class="b-sheet-track-title">' + esc(sg.title) + '</p><p class="b-sheet-track-artist">' + esc(sg.artist) + '</p></div></div>' +
        '<div class="b-hair" aria-hidden="true"></div>' +
        (signedIn ? a('like', 'star', 'Like') + a('dislike', 'block', 'Dislike') + a('addpl', 'add', 'Add to playlist') + '<div class="b-hair" data-key="hair-signed" aria-hidden="true"></div>' : '') +
        downloadRow(ctx, id, s) +
        a('radio', 'wave', 'Start radio') +
        a('next', 'queue', 'Play next') +
        a('queue', 'list', 'Add to queue') +
        a('album', 'album', 'Open album') +
        a('artist', 'person', 'Open artist') +
        a('info', 'info', 'Song Info') +
        (fromPlayer ? '<button type="button" class="b-action" data-key="act-sleep" data-tg="bitchord.songSheet.sleepTimerRow" data-set="' + attrJson({ 'bitchord.songSheetView': 'sleep' }) + '">' +
          icon('bedtime', { size: 22 }) + '<span class="b-action-text">Sleep timer</span>' + (timer && SLEEP[timer] ? '<span class="b-action-value b-action-accent">' + SLEEP[timer].left + '</span>' : '') + '</button>' : '') +
        (fromPlayer ? a('offset', 'tune', 'Lyrics offset') : '') +
        a('share', 'share', 'Share') +
        (fromPlayer ? a('log', 'bug', 'Copy log') : '') +
      '</div>';
    },
    actions: {
      'bc.sleepPick'(ctx, node) {
        ctx.set({ 'bitchord.sleepTimer': node.getAttribute('data-v') || '', 'bitchord.songSheetView': 'actions' });
      },
      'bc.download'(ctx) {
        const id = ctx.params.song || ctx.get('bitchord.nowPlaying');
        const state = (ctx.get('bitchord.downloads') || {})[id] || '';
        if (state) { setDownload(id, ''); return; }   // Cancel or Delete
        ctx.close('bitchord.songSheet');
        if (ctx.get('morphe.patch.perDownload') && ctx.get('tweaks.downloads.ask')) {
          ctx.open('downloadChoice', { title: 'Download “' + song(id).title + '”', song: id });
        } else {
          queueDownload(id);
        }
      }
    }
  });

  G.screen({
    id: 'bitchord.outputSheet',
    kind: 'sheet',
    title: 'Audio output',
    className: 'b-drawer-ov',
    render(ctx) {
      const s = ctx.s;
      const cur = s['bitchord.output'];
      const keys = ['speaker', 'headphones', 'car', 'wired'];
      if (cur === 'usb') keys.push('usb');
      return '<div class="b-sheet b-drawer">' +
        '<div class="b-handle b-handle-light" aria-hidden="true"></div>' +
        '<h2 class="b-drawer-title">Audio output</h2>' +
        '<div class="b-outputs" role="radiogroup" aria-label="Audio output" data-tg="bitchord.outputSheet.list">' +
          keys.map((k) => {
            const o = output(k), on = k === cur;
            return '<button type="button" class="b-out' + (on ? ' b-out-on' : '') + '" role="radio" aria-checked="' + on + '" data-key="out-' + k + '" data-bind="bitchord.output" data-value="' + k + '"' +
              (k === 'headphones' ? ' data-tg="bitchord.outputSheet.headphones"' : '') + '>' +
              '<span class="b-out-icon">' + icon(o.icon, { size: 22 }) + '</span>' +
              '<span class="b-out-text"><span class="b-out-name">' + esc(o.name) + '</span>' + (on ? '<span class="b-out-sub">Playing here</span>' : '') + '</span>' +
              (on ? icon('check', { size: 22 }) : '') + '</button>';
          }).join('') +
          '<button type="button" class="b-out" data-key="out-cast" data-toast="' + esc(NOT_DRAWN) + '"><span class="b-out-icon">' + icon('surround', { size: 22 }) + '</span>' +
            '<span class="b-out-text"><span class="b-out-name">Cast</span><span class="b-out-sub">Chromecast and Google Cast speakers</span></span></button>' +
        '</div>' +
        '<div class="b-volume b-volume-drawer">' + icon('speaker', { size: 16 }) + G.ui.slider({ bind: 'bitchord.volume', min: 0, max: 100, step: 1, label: 'Volume', cls: 'b-volume-slider', text: (v) => v + '%' }) + icon('volume', { size: 18 }) + '</div>' +
        '<button type="button" class="b-out b-out-pipe" data-toast="' + esc(NOT_DRAWN) + '"><span class="b-out-icon">' + icon('equalizer', { size: 22 }) + '</span>' +
          '<span class="b-out-text"><span class="b-out-name">Audio Pipeline</span><span class="b-out-sub">Live signal path, decoder to output</span></span>' + icon('chevronRight', { size: 20 }) + '</button>' +
      '</div>';
    }
  });

  G.screen({
    id: 'bitchord.queue',
    kind: 'sheet',
    title: 'Up next',
    render(ctx) {
      const s = ctx.s;
      const cur = s['bitchord.nowPlaying'];
      const i = Math.max(0, SONG_IDS.indexOf(cur));
      const order = SONG_IDS.slice(i).concat(SONG_IDS.slice(0, i));
      return '<div class="b-sheet b-queue">' +
        '<div class="b-handle" aria-hidden="true"></div>' +
        '<h2 class="b-sheet-title">Up next</h2>' +
        '<div class="b-queue-list" data-tg="bitchord.queue.list">' +
          order.map((id, k) => {
            const sg = song(id);
            return '<div class="b-qrow' + (k === 0 ? ' b-qrow-now' : '') + '" data-key="q-' + id + '">' + art(sg, 44) +
              '<span class="b-song-text"><span class="b-song-title">' + esc(sg.title) + '</span><span class="b-song-sub">' + esc(k === 0 ? 'Now playing' : sg.artist) + '</span></span>' +
              (k === 0 ? icon('equalizer', { size: 20, cls: 'b-qrow-eq' }) : '<span class="b-src-drag" aria-hidden="true">' + icon('dragHandle', { size: 22 }) + '</span>') + '</div>';
          }).join('') +
        '</div>' +
      '</div>';
    }
  });

  // ---------------------------------------------------------------------------------------
  // YouTube Music sign-in: the MicroG offer, MicroG RE, Google's page, the channel choice
  // ---------------------------------------------------------------------------------------

  // A platform alert dialog (the phone's own dialog look), shown over BitChord.
  G.screen({
    id: 'microg.offer',
    app: 'android',
    kind: 'dialog',
    modal: true,
    title: 'Sign in to YouTube Music',
    render() {
      return '<div class="a-dialog">' +
        '<h2 class="a-dialog-title">Sign in to YouTube Music</h2>' +
        '<p class="a-dialog-text">MicroG RE is on this phone. Sign in with a Google account that’s already in MicroG, or use Google’s sign-in page.</p>' +
        '<div class="a-dialog-actions">' +
          '<button type="button" class="a-btn-text" data-tg="microg.offer.googlePageButton" data-go="browser.googleSignIn">Google sign-in page</button>' +
          '<button type="button" class="a-btn-text" data-tg="microg.offer.useMicroGButton" data-go="microg.chooser">Use MicroG</button>' +
        '</div></div>';
    }
  });

  // MicroG RE's account chooser and consent screen are drawn plainly (not MicroG RE's own look).
  G.screen({
    id: 'microg.chooser',
    app: 'android',
    title: 'MicroG RE: choose an account',
    back: 'bitchord.home',
    render(ctx) {
      return '<div class="ba-mg">' +
        '<p class="ba-mg-app">MicroG RE</p>' +
        '<h2 class="ba-mg-title">Choose an account</h2>' +
        '<div class="ba-mg-list">' +
          '<button type="button" class="ba-mg-row" data-tg="microg.chooser.accountRow" data-action="bc.mgPick">' +
            '<span class="ba-mg-avatar" aria-hidden="true">A</span><span class="ba-mg-text"><span>Alex Example</span><span class="ba-mg-sub">alex@example.com</span></span></button>' +
          '<button type="button" class="ba-mg-row" data-toast="' + esc(NOT_DRAWN) + '">' +
            '<span class="ba-mg-avatar ba-mg-add" aria-hidden="true">' + ctx.icon('add', { size: 22 }) + '</span><span class="ba-mg-text"><span>Add another account</span></span></button>' +
        '</div>' +
        '<div class="ba-mg-actions"><button type="button" class="a-btn-text" data-go="browser.googleSignIn">Cancel</button></div>' +
      '</div>';
    },
    actions: {
      'bc.mgPick'(ctx) {
        if (ctx.get('microg.consentGiven')) ctx.go('bitchord.signInChannel');
        else ctx.go('microg.consent');
      }
    }
  });

  G.screen({
    id: 'microg.consent',
    app: 'android',
    title: 'MicroG RE: allow access',
    back: 'microg.chooser',
    render() {
      return '<div class="ba-mg ba-mg-consent">' +
        '<p class="ba-mg-app">MicroG RE</p>' +
        '<span class="ba-mg-avatar ba-mg-avatar-big" aria-hidden="true">A</span>' +
        '<h2 class="ba-mg-title">Allow BitChord to use your Google account?</h2>' +
        '<p class="ba-mg-sub ba-mg-center">alex@example.com</p>' +
        '<div class="ba-mg-actions">' +
          '<button type="button" class="a-btn-text" data-go="browser.googleSignIn">Deny</button>' +
          '<button type="button" class="ba-mg-allow" data-tg="microg.consent.allowButton" data-set="' + attrJson({ 'microg.consentGiven': true }) + '" data-go="bitchord.signInChannel">Allow</button>' +
        '</div>' +
      '</div>';
    }
  });

  // BitChord's sign-in browser (a full-screen web view) with Google's page in it, drawn plainly.
  function webTopBar(subtitle, actionHtml) {
    return '<div class="b-web-bar">' +
      '<button type="button" class="b-iconbtn" data-back aria-label="Close">' + icon('close', { size: 24 }) + '</button>' +
      '<div class="b-web-bar-text"><p class="b-web-bar-title">Sign in to YouTube Music</p>' + (subtitle ? '<p class="b-web-bar-sub">' + esc(subtitle) + '</p>' : '') + '</div>' +
      (actionHtml || '') +
    '</div>';
  }

  G.screen({
    id: 'browser.googleSignIn',
    app: 'android',
    title: 'Google sign-in page',
    back: 'bitchord.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      return '<div class="ba-web">' +
        '<div class="app-bitchord ba-web-top">' + webTopBar('') + '</div>' +
        '<div class="ba-web-page" data-tg="browser.googleSignIn.page">' +
          '<div class="ba-web-card">' +
            '<h2 class="ba-web-h">Sign in</h2>' +
            '<p class="ba-web-p">Google’s own sign-in page opens here. You type your email and password on Google’s page, not in Bitchord+.</p>' +
            '<label class="ba-web-label" for="ba-gs-email">Email or phone</label>' +
            '<input id="ba-gs-email" class="ba-web-input" type="email" autocomplete="off" placeholder="you@example.com">' +
            '<div class="ba-web-row"><button type="button" class="ba-web-btn" data-toast="This is only a drawing. Sign in on your phone.">Next</button></div>' +
          '</div>' +
        '</div>' +
      '</div>';
    }
  });

  function finishSignIn(ctx, channel) {
    ctx.set({ 'bitchord.signedIn': true, 'bitchord.channel': channel, 'bitchord.tab': 'library' });
    ctx.go('bitchord.library', null, { dir: 'fade' });
  }

  G.screen({
    id: 'bitchord.signInChannel',
    title: 'Bitchord+: choose a YouTube Music profile',
    back: 'bitchord.home',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const people = [
        { name: 'Alex Example', handle: '@alexexample', tg: 'bitchord.signIn.channel' },
        { name: 'Alex’s Music', handle: '@alexsmusic' }
      ];
      return '<div class="b-web">' +
        webTopBar('Switch using the avatar, then use this profile.',
          '<button type="button" class="b-web-use" data-action="bc.useProfile">Use this profile</button>') +
        '<div class="b-web-page">' +
          '<div class="b-web-panel">' +
            '<div class="b-web-who"><span class="b-avatar b-avatar-in" style="--size:40px" aria-hidden="true">A</span><div><p class="b-web-name">Alex Example</p><p class="b-web-mail">alex@example.com</p></div></div>' +
            '<div class="b-web-channels" role="group" aria-label="Channels" data-tg="bitchord.signIn.channelList">' +
              people.map((p) => '<button type="button" class="b-web-channel" data-key="' + esc(p.handle) + '" data-action="bc.pickChannel" data-name="' + esc(p.name) + '"' +
                (p.tg ? ' data-tg="' + p.tg + '"' : '') + '><span class="b-avatar b-avatar-in" style="--size:36px" aria-hidden="true">' + esc(p.name.charAt(0)) + '</span>' +
                '<span class="b-row-text"><span class="b-row-title">' + esc(p.name) + '</span><span class="b-row-sub">' + esc(p.handle) + '</span></span></button>').join('') +
            '</div>' +
          '</div>' +
        '</div>' +
      '</div>';
    },
    actions: {
      'bc.pickChannel'(ctx, node) { finishSignIn(ctx, node.getAttribute('data-name') || 'Alex Example'); },
      'bc.useProfile'(ctx) { finishSignIn(ctx, 'Alex Example'); }
    }
  });

  // ---------------------------------------------------------------------------------------
  // my.telegram.org/apps in a plain browser
  // ---------------------------------------------------------------------------------------

  G.screen({
    id: 'browser.tgApps',
    app: 'android',
    title: 'Browser: my.telegram.org/apps',
    back: 'tg.page',
    bars: { bg: '#f1f3f4', fg: '#1f1f1f' },
    render() {
      const field = (label, value, mono) => '<div class="ba-tga-field"><span class="ba-tga-label">' + esc(label) + '</span>' +
        '<span class="ba-tga-value' + (mono ? ' ba-tga-mono' : '') + '">' + esc(value) + '</span></div>';
      const input = (label, value) => '<div class="ba-tga-field ba-tga-edit"><span class="ba-tga-label">' + esc(label) + '</span>' +
        '<span class="ba-tga-input">' + esc(value) + '</span></div>';
      return '<div class="ba-browser">' +
        '<div class="ba-chrome"><span class="ba-chrome-url">' + icon('lock', { size: 15 }) + '<span>my.telegram.org/apps</span></span>' +
          '<button type="button" class="ba-chrome-btn" data-toast="' + esc(NOT_DRAWN) + '" aria-label="Browser menu">' + icon('moreVert', { size: 20 }) + '</button></div>' +
        '<div class="ba-tga">' +
          '<h2 class="ba-tga-h">App configuration</h2>' +
          '<div class="ba-tga-box" data-tg="browser.tgApps.apiFields">' +
            field('App api_id:', '1234567', true) +
            field('App api_hash:', '0123456789abcdef0123456789abcdef', true) +
          '</div>' +
          '<p class="ba-tga-note">These are made-up values. Use the ones on your own page.</p>' +
          '<div class="ba-tga-form" data-tg="browser.tgApps.form" role="group" aria-label="App configuration form">' +
            input('App title:', 'My Client') +
            input('Short name:', 'myclient') +
            input('URL:', '') +
            input('Platform:', 'Android') +
            input('Description:', 'A personal Telegram client for my own account.') +
            '<button type="button" class="ba-tga-btn" data-toast="This is only a drawing of the page.">Save changes</button>' +
          '</div>' +
        '</div>' +
      '</div>';
    }
  });

  // ---------------------------------------------------------------------------------------
  // Our pages: shared look (TelegramStyle: cards with a label, paragraphs, pill buttons)
  // ---------------------------------------------------------------------------------------

  function twCard(label, inner, o) {
    o = o || {};
    return (label ? '<h3 class="b-tw-label" data-key="lbl-' + esc(o.key || label) + '">' + esc(label) + '</h3>' : '') +
      '<div class="b-tw-card' + (label ? '' : ' b-tw-card-nolabel') + (o.cls ? ' ' + o.cls : '') + '" data-key="card-' + esc(o.key || label) + '"' +
      (o.tg ? ' data-tg="' + esc(o.tg) + '"' : '') + '>' + inner + '</div>';
  }
  function twP(text, o) {
    o = o || {};
    return '<p class="b-tw-p' + (o.cls ? ' ' + o.cls : '') + '"' + (o.tg ? ' data-tg="' + esc(o.tg) + '"' : '') + (o.key ? ' data-key="' + esc(o.key) + '"' : '') +
      (o.live ? ' aria-live="polite"' : '') + '>' + esc(text) + '</p>';
  }
  const twDiv = (key) => '<hr class="b-tw-div"' + (key ? ' data-key="' + key + '"' : '') + '>';
  function twHeading(text) { return '<h4 class="b-tw-h">' + esc(text) + '</h4>'; }
  function twBtn(label, o) {
    o = o || {};
    let a = '';
    ['go', 'open', 'action', 'toast', 'tg', 'bind', 'value', 'key'].forEach((k) => { if (o[k] != null) a += ' data-' + k + '="' + esc(o[k]) + '"'; });
    if (o.set) a += ' data-set="' + attrJson(o.set) + '"';
    return '<button type="button" class="b-btn ' + (o.primary ? 'b-btn-primary' : 'b-btn-secondary') + (o.cls ? ' ' + o.cls : '') + '"' + a + '>' + esc(label) + '</button>';
  }
  function twSmall(label, o) {
    let a = '';
    ['action', 'tg', 'key', 'item'].forEach((k) => { if (o[k] != null) a += ' data-' + k + '="' + esc(o[k]) + '"'; });
    if (o.set) a += ' data-set="' + attrJson(o.set) + '"';
    return '<button type="button" class="b-small"' + a + (o.label ? ' aria-label="' + esc(o.label) + '"' : '') + '>' + esc(label) + '</button>';
  }

  // A toggle row: the whole row is the switch (TelegramSwitch inside, drawn by .g-switch).
  function twToggle(o) {
    const on = o.on != null ? !!o.on : !!G.get(o.bind);
    const base = 'b-tw-' + o.id;
    let a = '';
    if (o.action) a += ' data-action="' + esc(o.action) + '"';
    else {
      a += ' data-bind="' + esc(o.bind) + '"';
      if (o.item != null) a += ' data-item="' + esc(o.item) + '"';
    }
    if (o.tg) a += ' data-tg="' + esc(o.tg) + '"';
    return '<button type="button" class="b-tw-toggle" role="switch" aria-checked="' + on + '" aria-labelledby="' + base + '-t" aria-describedby="' + base + '-s' +
      (o.details ? ' ' + base + '-d' : '') + '" data-key="' + esc(o.key || base) + '"' + a + '>' +
      '<span class="b-tw-toggle-text"><span class="b-tw-toggle-title" id="' + base + '-t">' + esc(o.title) + '</span>' +
        '<span class="b-tw-toggle-state" id="' + base + '-s">' + esc(on ? o.yes : o.no) + '</span>' +
        (o.details ? '<span class="b-tw-toggle-details" id="' + base + '-d"' + (o.detailsTg ? ' data-tg="' + esc(o.detailsTg) + '"' : '') + '>' + esc(o.details) + '</span>' : '') +
      '</span>' +
      '<span class="g-switch" aria-checked="' + on + '" aria-hidden="true"><span class="g-switch-thumb"></span></span>' +
    '</button>';
  }

  // A slider with its title and value (an Android SeekBar on our pages: round white thumb).
  function twSlider(o) {
    const v = Number(G.get(o.bind));
    const id = 'b-tw-' + o.id + '-t';
    return '<div class="b-tw-slider" data-tg="' + esc(o.tg) + '" data-key="' + esc(o.tg) + '">' +
      '<div class="b-tw-slider-head"><span class="b-tw-slider-title" id="' + id + '">' + esc(o.title) + '</span><span class="b-tw-slider-value">' + esc(o.text(v)) + '</span></div>' +
      G.ui.slider({ bind: o.bind, min: o.min, max: o.max, step: o.step || 1, labelledby: id, cls: 'b-tw-range', text: o.text }) +
    '</div>';
  }

  function ourTop(title, tg, o) {
    o = o || {};
    return '<div class="b-tw-top' + (o.cls ? ' ' + o.cls : '') + '" data-tg="' + tg + '">' +
      '<button type="button" class="b-ourback"' + (o.backAction ? ' data-action="' + o.backAction + '"' : ' data-back') + ' aria-label="Back">' + icon('arrowBack', { size: 24, stroke: 2.2 }) + '</button>' +
      '<h2 class="b-tw-title">' + esc(title) + '</h2>' +
      (o.sub ? '<p class="b-tw-sub">' + esc(o.sub) + '</p>' : '') +
    '</div>';
  }

  // ---------------------------------------------------------------------------------------
  // Unofficial Telegram page (TelegramActivity)
  // ---------------------------------------------------------------------------------------

  function tgStatus(s) {
    const stage = s['tg.stage'];
    if (stage === 'setup') return s['tg.returnStage'] === 'connected' ? 'Telegram connected. Select music chats, then search in BitChord.' : 'Add your application credentials to connect.';
    if (stage === 'phone') return 'WaitPhoneNumber';
    if (stage === 'code') return 'WaitCode';
    if (stage === 'password') return 'WaitPassword';
    if (s['tg.statusNote']) return s['tg.statusNote'];
    const n = (s['tg.chats'] || []).length;
    return n ? 'Selected ' + n + ' music chats. Search in BitChord → Addons.' : 'Telegram connected. Select music chats, then search in BitChord.';
  }

  function credentialError(id, hash) {
    const number = String(id || '').trim(), secret = String(hash || '').trim();
    if (!/^[0-9]+$/.test(number)) return 'Enter the numeric App API ID.';
    const value = Number(number);
    if (value > 2147483647) return 'The App API ID is outside the supported range.';
    if (value <= 0) return 'Enter an App API ID greater than zero.';
    if (!/^[a-fA-F0-9]{32}$/.test(secret)) return 'App API hash must contain exactly 32 hexadecimal characters.';
    return '';
  }

  function tgRow(o) {
    const inner = '<span class="b-tg-row-text"><span class="b-tg-row-title">' + esc(o.title) + '</span><span class="b-tg-row-sub">' + esc(o.sub) + '</span></span>' +
      (o.more ? '<span class="b-tg-row-more">' + esc(o.more) + '  ›</span>' : '');
    let a = '';
    ['go', 'open', 'action', 'toast', 'tg'].forEach((k) => { if (o[k] != null) a += ' data-' + k + '="' + esc(o[k]) + '"'; });
    if (o.params) a += ' data-params="' + attrJson(o.params) + '"';
    if (o.go || o.open || o.action || o.toast) return '<button type="button" class="b-tg-row" data-key="' + esc(o.key || o.title) + '"' + a + '>' + inner + '</button>';
    return '<div class="b-tg-row" data-key="' + esc(o.key || o.title) + '"' + a + '>' + inner + '</div>';
  }

  function tgField(o) {
    return '<label class="b-tg-label" for="' + o.id + '">' + esc(o.label) + '</label>' +
      G.ui.field({ id: o.id, bind: o.bind, tg: o.tg, placeholder: o.label, type: o.type, inputmode: o.inputmode, enter: o.enter, cls: 'b-tg-input' });
  }

  function tgAbout() {
    return twCard('About',
      twP('An unofficial add-on that uses the Telegram API with your own account. It isn’t made by or affiliated with Telegram or BitChord. Channel View can show Telegram’s own sponsored messages.') +
      tgRow({ key: 'privacy', title: 'Privacy and your account', sub: 'Storage, sessions and signing out', more: 'Read', open: 'tg.privacy', tg: 'tg.about.privacyRow', params: { which: 'privacy' } }) +
      twDiv() +
      tgRow({ key: 'audio', title: 'Your audio', sub: 'Only use recordings you’re allowed to share', more: 'Read', open: 'tg.privacy', params: { which: 'audio' } }) +
      twDiv() +
      tgRow({ key: 'terms', title: 'Telegram API terms', sub: 'Telegram’s rules for apps using its API', more: 'Open', toast: 'Opens Telegram’s API terms in your browser.' }) +
      twP('Free and provided as-is. Use it responsibly: you’re responsible for your Telegram account and channels.', { cls: 'b-tw-p-top' }),
      { key: 'about' });
  }

  function tgApplication(s) {
    return twCard('Application',
      tgRow({ key: 'app-id', title: 'App API ID', sub: String(s['tg.apiId'] || '').trim() || '12345', more: 'Change', action: 'tgChange', tg: 'tg.application.changeRow' }) +
      twDiv() +
      tgRow({ key: 'app-hash', title: 'App API hash', sub: '••••••••••••••••' }),
      { key: 'application' });
  }

  function tgAccount(s) {
    const stage = s['tg.stage'];
    if (stage === 'connected') {
      return twCard('Account', tgRow({ key: 'connected', title: 'Connected to Telegram', sub: 'Your account is ready to stream music.', tg: 'tg.account.connectedRow' }), { key: 'account' });
    }
    if (stage === 'phone') {
      return twCard('Account',
        twP('Sign in with your own Telegram account. Include your country code.', { key: 'acc-p-phone' }) +
        tgField({ id: 'b-tg-phone', label: 'Phone number', bind: 'tg.phone', tg: 'tg.account.phoneField', type: 'tel', inputmode: 'tel', enter: 'tgContinue' }) +
        twBtn('Continue', { primary: true, action: 'tgContinue', tg: 'tg.account.continueButton', cls: 'b-btn-block' }),
        { key: 'account' });
    }
    if (stage === 'code') {
      return twCard('Account',
        twP('Enter the code delivered by Telegram. Check your existing Telegram app.', { key: 'acc-p-code' }) +
        tgField({ id: 'b-tg-code', label: 'Verification code', bind: 'tg.code', tg: 'tg.account.codeField', type: 'password', inputmode: 'numeric', enter: 'tgVerify' }) +
        twBtn('Verify and continue', { primary: true, action: 'tgVerify', tg: 'tg.account.verifyButton', cls: 'b-btn-block' }),
        { key: 'account' });
    }
    return twCard('Account',
      twP('Enter the password for your Telegram account.', { key: 'acc-p-password' }) +
      tgField({ id: 'b-tg-password', label: 'Two-step verification', bind: 'tg.password', tg: 'tg.account.passwordField', type: 'password', enter: 'tgVerify' }) +
      twBtn('Verify and continue', { primary: true, action: 'tgVerify', tg: 'tg.account.verifyButton', cls: 'b-btn-block' }),
      { key: 'account' });
  }

  function tgLibrary(s) {
    const n = (s['tg.chats'] || []).length;
    return twCard('Music library',
      tgRow({ key: 'chats', title: 'Music chats', sub: n + ' selected · Live Telegram search', more: 'Choose', open: 'tg.chatFolders', tg: 'tg.library.musicChatsChoose' }) +
      twDiv() +
      tgRow({ key: 'channel-view', title: 'Channel View', sub: 'Read selected channels and official sponsored messages.', more: 'Browse', toast: NOT_DRAWN }) +
      twDiv() +
      tgRow({ key: 'playlists', title: 'Playlists and downloads', sub: 'Save a chat’s music as a BitChord playlist, or download all of it.', more: 'Choose', toast: NOT_DRAWN }) +
      twDiv() +
      tgField({ id: 'b-tg-addchat', label: '@channel or numeric chat ID', bind: 'tg.addChat', tg: 'tg.library.addChatField', enter: 'tgAddChat' }) +
      twBtn('Add chat', { action: 'tgAddChat', tg: 'tg.library.addChatButton', cls: 'b-btn-block' }) +
      twP('Search your selected chats from BitChord’s Addons tab. Telegram searches the channel history. Audio is downloaded when played.', { cls: 'b-tw-p-top' }) +
      twP('Each query shows up to 200 results across audio and music documents. Refine the title or artist for more specific matches.') +
      twDiv() +
      twToggle({ id: 'tg-index', bind: 'tg.savedIndex', title: 'Saved index', tg: 'tg.library.savedIndex',
        yes: !n ? 'No music chats selected'
          : s['tg.indexing'] ? (n * 214).toLocaleString('en-GB') + ' files indexed from ' + n + (n === 1 ? ' chat' : ' chats') + ' (0 of ' + n + ' chats done)'
          : (n * 642).toLocaleString('en-GB') + ' files indexed from ' + n + (n === 1 ? ' chat' : ' chats'),
        no: 'Only Telegram’s own search is used' }) +
      twP('Keeps a list of the music in your selected chats on this phone, built slowly in the background and topped up every few hours. It answers when Telegram’s search is rate limited or busy, and the format legend uses it instead of searching Telegram. Turning it off deletes it.') +
      twDiv() +
      twToggle({ id: 'tg-wifi', bind: 'tg.indexWifi', title: 'Index only on Wi-Fi', yes: 'The index waits for Wi-Fi to read your chats', no: 'The index also uses mobile data',
        details: 'Search and playback still use mobile data; only the background indexing waits.' }) +
      twDiv() +
      twToggle({ id: 'tg-charging', bind: 'tg.indexCharging', title: 'Index only while charging', yes: 'The index waits for a charger', no: 'The index also runs on battery' }),
      { key: 'library' });
  }

  function tgAdvanced(s) {
    return twCard('Main channel',
        tgRow({ key: 'main', title: 'Main channel', sub: 'A private channel you own', more: 'Choose', toast: NOT_DRAWN }) + twDiv() +
        tgRow({ key: 'community', title: 'Community channels', sub: 'Where music is copied from', more: 'Choose', toast: NOT_DRAWN }) + twDiv() +
        twToggle({ id: 'tg-import', bind: 'tg.adv.import', title: 'Copy into main channel', yes: 'Starting…', no: 'Nothing is copied' }),
        { key: 'adv-main' }) +
      twCard('Track details',
        twToggle({ id: 'tg-quality', bind: 'tg.adv.reportQuality', title: 'Report audio quality', yes: 'FLAC, WAV and ALAC count as lossless; others by bitrate', no: 'BitChord guesses from the file type',
          details: 'BitChord uses this to choose between your file and other sources, so a low-bitrate MP3 may lose to a better copy elsewhere.' }) + twDiv() +
        twToggle({ id: 'tg-lossless', bind: 'tg.adv.checkLossless', title: 'Check lossless files', yes: 'FLAC and WAV files that play are checked for a lossy source', no: 'Files are taken at their word' }),
        { key: 'adv-details' }) +
      twCard('Playback',
        tgRow({ key: 'source', title: 'Telegram source', sub: 'Added automatically to BitChord.', more: 'Repair', toast: NOT_DRAWN, tg: 'tg.advanced.repairRow' }) + twDiv() +
        twToggle({ id: 'tg-smooth', bind: 'tg.adv.smooth', title: 'Smooth quality switch', yes: 'A better copy fades in without a gap', no: 'BitChord switches copies with a short pause' }),
        { key: 'adv-playback' }) +
      twCard('Storage',
        twP('Telegram audio cache target', { cls: 'b-tg-cache-label' }) +
        '<div class="b-tg-cache" data-tg="tg.advanced.cacheSlider"><p class="b-tg-cache-value" id="b-tg-cache-v">' + s['tg.adv.cache'] + ' GiB</p>' +
          G.ui.slider({ bind: 'tg.adv.cache', min: 1, max: 16, labelledby: 'b-tg-cache-v', label: 'Telegram audio cache target', cls: 'b-tw-range', text: (v) => v + ' GiB' }) + '</div>' +
        twP('64 MiB to 16 GiB · A soft target, not reserved space. Active audio may exceed it. BitChord’s separate song cache and downloads use additional storage.') +
        twBtn('Apply cache size', { toast: NOT_DRAWN, cls: 'b-btn-block' }) +
        twBtn('Clear Telegram audio cache', { action: 'tgClearCache', tg: 'tg.advanced.clearCacheButton', cls: 'b-btn-block' }),
        { key: 'adv-storage' }) +
      twCard('Backup and sharing',
        tgRow({ key: 'backup', title: 'Back up', sub: 'Settings, corrected names and main channel, and the saved index if you like', more: 'Back up', toast: NOT_DRAWN }) + twDiv() +
        tgRow({ key: 'restore', title: 'Restore', sub: 'From a backup, or add an index file someone shared', more: 'Choose', toast: NOT_DRAWN }) + twDiv() +
        tgRow({ key: 'share-index', title: 'Share a chat’s index', sub: 'Post it in Telegram, or save it as a file', more: 'Choose', toast: NOT_DRAWN }),
        { key: 'adv-backup' });
  }

  G.screen({
    id: 'tg.page',
    title: 'Bitchord+: Unofficial Telegram',
    back: 'bitchord.accountIntegrations',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const stage = s['tg.stage'];
      const setup = stage === 'setup';
      let body = '';
      if (setup) {
        body = twCard('Application credentials',
          twP('Enter the App API ID and App API hash from your Telegram application. The hash stays masked and is stored encrypted on this device.') +
          tgField({ id: 'b-tg-apiid', label: 'App API ID', bind: 'tg.apiId', tg: 'tg.setup.apiIdField', inputmode: 'numeric' }) +
          tgField({ id: 'b-tg-hash', label: 'App API hash (secret)', bind: 'tg.apiHash', tg: 'tg.setup.hashField', type: s['tg.showHash'] ? 'text' : 'password', enter: 'tgSave' }) +
          '<div class="b-tg-showrow"><button type="button" class="b-tg-show" data-bind="tg.showHash" aria-pressed="' + !!s['tg.showHash'] + '" aria-controls="b-tg-hash">' +
            (s['tg.showHash'] ? 'Hide hash' : 'Show hash') + '</button></div>' +
          twBtn('Save and continue', { primary: true, action: 'tgSave', tg: 'tg.setup.saveButton', cls: 'b-btn-block' }) +
          twP('Create your own application with a generic, descriptive name, such as My Client (short name: myclient). Telegram may show that name in sign-in alerts.', { cls: 'b-tw-p-top' }) +
          '<button type="button" class="b-tg-link" data-go="browser.tgApps" data-tg="tg.setup.createCredentialsLink">Create your Telegram API credentials ↗</button>',
          { key: 'credentials' }) + tgAbout();
      } else {
        const connected = stage === 'connected';
        const advanced = connected && s['tg.tab'] === 'advanced';
        const tabs = connected ? '<div class="b-tg-tabs" role="group" aria-label="Page" data-key="tabs">' +
          '<button type="button" class="b-btn ' + (advanced ? 'b-btn-secondary' : 'b-btn-primary') + '" aria-pressed="' + !advanced + '" data-bind="tg.tab" data-value="basic" data-tg="tg.tabs.basic">Basic</button>' +
          '<button type="button" class="b-btn ' + (advanced ? 'b-btn-primary' : 'b-btn-secondary') + '" aria-pressed="' + advanced + '" data-bind="tg.tab" data-value="advanced" data-tg="tg.tabs.advanced">Advanced</button>' +
          '</div>' : '';
        if (advanced) body = tabs + tgAdvanced(s);
        else {
          body = tabs + tgApplication(s) + tgAccount(s) +
            (connected ? tgLibrary(s) +
              twCard('Track names',
                tgField({ id: 'b-tg-names', label: 'Search your music chats', bind: 'tg.namesQuery' }) +
                twBtn('Fix a track name', { toast: NOT_DRAWN, cls: 'b-btn-block' }) + twDiv() +
                twToggle({ id: 'tg-acoustid', on: false, action: 'bc.notDrawnToggle', title: 'Name untagged files by sound', yes: 'Files without tags are looked up in AcoustID', no: 'Untagged files keep the name from the file' }),
                { key: 'names' }) +
              twCard('Session', twBtn('Sign out', { open: 'tg.signOut', cls: 'b-btn-block b-btn-flush' }), { key: 'session' }) : '') +
            tgAbout();
        }
      }
      const status = tgStatus(s);
      return '<div class="b-our">' +
        ourTop(setup ? 'Application setup' : 'Unofficial Telegram', 'tg.page.header', { cls: 'b-tg-top', backAction: 'tgBack', sub: 'An unofficial client using the Telegram API. Your account, inside BitChord.' }) +
        body +
        twCard(null,
          '<p class="b-tg-status" role="status">' + esc(status) + '</p>' +
          '<p class="b-tg-error"' + (s['tg.error'] ? '' : ' hidden') + '>' + esc(s['tg.error']) + '</p>',
          { key: 'status', tg: 'tg.status' }) +
      '</div>';
    },
    actions: {
      tgBack(ctx) {
        if (ctx.get('tg.stage') === 'setup' && ctx.get('tg.returnStage')) {
          ctx.set({ 'tg.stage': ctx.get('tg.returnStage'), 'tg.returnStage': '', 'tg.error': '' });
        } else ctx.back();
      },
      tgChange(ctx) {
        ctx.set({ 'tg.returnStage': ctx.get('tg.stage'), 'tg.stage': 'setup', 'tg.error': '' });
      },
      tgSave(ctx) {
        const err = credentialError(ctx.get('tg.apiId'), ctx.get('tg.apiHash'));
        if (err) { ctx.set('tg.error', err); return; }
        const back = ctx.get('tg.returnStage');
        ctx.set({ 'tg.stage': back || 'phone', 'tg.returnStage': '', 'tg.error': '', 'tg.showHash': false, 'tg.statusNote': '' });
      },
      tgContinue(ctx) {
        if (!String(ctx.get('tg.phone') || '').trim()) return;
        ctx.set({ 'tg.stage': 'code', 'tg.error': '' });
      },
      tgVerify(ctx) {
        const stage = ctx.get('tg.stage');
        const value = String(ctx.get(stage === 'password' ? 'tg.password' : 'tg.code') || '').trim();
        if (!value) return;
        const next = stage === 'code' && ctx.get('tg.twoStep') ? 'password' : 'connected';
        ctx.set({ 'tg.stage': next, 'tg.tab': 'basic', 'tg.error': '', 'tg.statusNote': '' });
      },
      tgAddChat(ctx) {
        const value = String(ctx.get('tg.addChat') || '').trim();
        if (!value) return;
        const name = /^-?[0-9]+$/.test(value) ? 'Chat ' + value : '@' + value.replace(/^@/, '');
        const chats = (ctx.get('tg.chats') || []).slice();
        if (chats.indexOf(name) < 0) chats.push(name);
        ctx.set({ 'tg.chats': chats, 'tg.addChat': '', 'tg.statusNote': 'Selected ' + name + '. Search in BitChord → Addons.' });
      },
      tgClearCache(ctx) {
        ctx.set('tg.statusNote', 'Telegram audio cache cleared.');
      },
      'bc.notDrawnToggle'(ctx) { ctx.toast(NOT_DRAWN); }
    }
  });

  // Our dialogs. "Music chats" (the folder list) is a plain platform dialog; the checkbox list
  // and the confirmations are tuned to the page's colours.
  G.screen({
    id: 'tg.chatFolders',
    kind: 'dialog',
    title: 'Music chats',
    className: 'b-pdlg-ov',
    render() {
      return '<div class="b-pdlg">' +
        '<h2 class="b-pdlg-title">Music chats</h2>' +
        '<div class="b-pdlg-items">' +
          CHAT_FOLDERS.map((f) => '<button type="button" class="b-pdlg-item" data-key="f-' + esc(f.value) + '" data-action="tgFolder" data-folder="' + esc(f.value) + '"' +
            (f.tg ? ' data-tg="' + f.tg + '"' : '') + '>' + esc(f.label) + '</button>').join('') +
        '</div>' +
        '<div class="b-pdlg-actions"><button type="button" class="b-pdlg-btn" data-close>Cancel</button></div>' +
      '</div>';
    },
    actions: {
      tgFolder(ctx, node) {
        ctx.set('tg.chatFolder', node.getAttribute('data-folder'));
        ctx.close('tg.chatFolders');
        ctx.open('tg.chatPicker');
      }
    }
  });

  let pickerBefore = null; // the ticks when the checkbox list opened, for Cancel
  function pickerChats(s) {
    const base = CHATS[s['tg.chatFolder']] || CHATS.all;
    const extra = (s['tg.chats'] || []).filter((c) => base.indexOf(c) < 0 && (s['tg.chatFolder'] || 'all') === 'all');
    return extra.concat(base);
  }

  G.screen({
    id: 'tg.chatPicker',
    kind: 'dialog',
    title: 'Music chats',
    className: 'b-tdlg-ov',
    render(ctx) {
      const s = ctx.s;
      const ticked = s['tg.chats'] || [];
      return '<div class="b-tdlg">' +
        '<h2 class="b-tdlg-title">Music chats</h2>' +
        '<div class="b-tdlg-list" role="group" aria-label="Music chats" data-tg="tg.chatPicker.list">' +
          pickerChats(s).map((c, i) => {
            const on = ticked.indexOf(c) >= 0;
            return '<button type="button" class="b-tdlg-check" role="checkbox" aria-checked="' + on + '" data-key="c-' + esc(c) + '" data-bind="tg.chats" data-item="' + esc(c) + '"' +
              (i === 0 ? ' data-tg="tg.chatPicker.first"' : '') + '>' +
              '<span class="b-tdlg-box" aria-hidden="true">' + (on ? icon('check', { size: 18, stroke: 3 }) : '') + '</span><span>' + esc(c) + '</span></button>';
          }).join('') +
        '</div>' +
        '<div class="b-tdlg-actions">' +
          '<button type="button" class="b-tdlg-btn" data-action="tgPickCancel">Cancel</button>' +
          '<button type="button" class="b-tdlg-btn" data-action="tgPickSave" data-tg="tg.chatPicker.saveButton">Save</button>' +
        '</div>' +
      '</div>';
    },
    mount(node, ctx) {
      pickerBefore = (ctx.get('tg.chats') || []).slice();
    },
    actions: {
      tgPickCancel(ctx) {
        if (pickerBefore) ctx.set('tg.chats', pickerBefore);
        ctx.close('tg.chatPicker');
      },
      tgPickSave(ctx) {
        const n = (ctx.get('tg.chats') || []).length;
        // The saved index starts reading newly chosen chats straight away.
        ctx.set({ 'tg.statusNote': 'Selected ' + n + ' music chats. Search in BitChord → Addons.', 'tg.indexing': !!(n && ctx.get('tg.savedIndex')) });
        pickerBefore = null;
        ctx.close('tg.chatPicker');
      }
    }
  });

  const PRIVACY = {
    privacy: { title: 'Privacy and your account', text: 'Your phone talks to Telegram directly. Your API details are encrypted on this phone and your Telegram session is stored privately. The session is a normal Telegram login, so it can reach your whole account, not just music. Audio BitChord has saved stays until you clear it, even after you sign out. You can end the session anytime in the official Telegram app under Settings → Devices.' },
    audio: { title: 'Your audio', text: 'You’re responsible for the audio you upload, share and play. This add-on doesn’t provide any music. Only use recordings you own or have permission to use, and follow Telegram’s terms. Protected or self-destructing media can’t be played here.' }
  };

  G.screen({
    id: 'tg.privacy',
    kind: 'dialog',
    title: 'Privacy and your account',
    className: 'b-tdlg-ov',
    render(ctx) {
      const p = PRIVACY[ctx.params.which] || PRIVACY.privacy;
      return '<div class="b-tdlg">' +
        '<h2 class="b-tdlg-title">' + esc(p.title) + '</h2>' +
        '<p class="b-tdlg-text">' + esc(p.text) + '</p>' +
        '<div class="b-tdlg-actions"><button type="button" class="b-tdlg-btn" data-close>Cancel</button>' +
          '<button type="button" class="b-tdlg-btn" data-close data-tg="tg.privacy.doneButton">Done</button></div>' +
      '</div>';
    }
  });

  G.screen({
    id: 'tg.signOut',
    kind: 'dialog',
    title: 'Sign out?',
    className: 'b-tdlg-ov',
    render() {
      return '<div class="b-tdlg">' +
        '<h2 class="b-tdlg-title">Sign out?</h2>' +
        '<p class="b-tdlg-text">Signing out removes your music chats, saved index, corrected names and main channel setup from this phone.</p>' +
        '<div class="b-tdlg-actions b-tdlg-actions-3">' +
          '<button type="button" class="b-tdlg-btn b-tdlg-neutral" data-action="tgSignOut">Sign out</button>' +
          '<button type="button" class="b-tdlg-btn" data-close>Cancel</button>' +
          '<button type="button" class="b-tdlg-btn" data-action="tgSignOut">Back up and sign out</button>' +
        '</div>' +
      '</div>';
    },
    actions: {
      tgSignOut(ctx) {
        ctx.set({ 'tg.stage': 'phone', 'tg.chats': [], 'tg.tab': 'basic', 'tg.statusNote': '', 'tg.phone': '', 'tg.code': '', 'tg.password': '' });
        ctx.close('tg.signOut');
      }
    }
  });

  // ---------------------------------------------------------------------------------------
  // Patches & tweaks (ExtrasActivity)
  // ---------------------------------------------------------------------------------------

  function batteryStatus(s) {
    let text = 'This phone’s audio chip can play: AAC, MP3, Opus.';
    if (s['tweaks.battery.on']) {
      let held = null;
      if (Number(s['tweaks.sing.voice']) > 0) held = 'Sing is on';
      else if (s['tweaks.headphones.profile']) held = 'Headphone correction is on';
      else if (s['tweaks.jamesdsp.effects']) held = 'JamesDSP has the audio';
      else if (s['tweaks.dj.on']) held = 'DJ mode is listening';
      if (held) text += ' Waiting: ' + held + '. Now decoding with c2.android.opus.decoder.';
      else text += ' No decoder in use now.';
    }
    return text;
  }

  function bluetoothStatus(s) {
    const out = output(s['bitchord.output']);
    if (!out.bt) return 'Playing through: ' + out.page + ' (' + out.kind + ')';
    return 'Playing through: ' + out.page + ' over Bluetooth (' + out.codec + ')';
  }

  // Equaliser code: version 1, mode, pad, focus, balance and seven gains (as OutputEq does).
  function eqCode(s) {
    const bytes = [s['bitchord.eq.mode'] === 'manual' ? 1 : 0, s['bitchord.eq.toneX'], s['bitchord.eq.toneY'], s['bitchord.eq.focused'] ? 1 : 0, 0, 0, 0, 0, 0, 0, 0, 0];
    const bin = bytes.map((b) => String.fromCharCode(b & 255)).join('');
    return 'BCEQ1.' + btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function readEqCode(text) {
    const t = String(text || '').trim();
    if (t.indexOf('BCEQ1.') !== 0) return null;
    try {
      const raw = atob(t.slice(6).replace(/-/g, '+').replace(/_/g, '/'));
      if (raw.length < 12) return null;
      const b = (i) => { const v = raw.charCodeAt(i); return v > 127 ? v - 256 : v; };
      return { manual: b(0) === 1, x: Math.max(-5, Math.min(5, b(1))), y: Math.max(-5, Math.min(5, b(2))), focused: b(3) === 1 };
    } catch (e) { return null; }
  }

  function savedEq(s, key) {
    if (key === s['bitchord.output']) return eqDescribe(s);
    return { headphones: 'Tone pad 2, -1', car: 'Sliders +3 +2 +0 +0 +0 +1 +2', speaker: 'Equaliser off', wired: 'Tone pad 0, 1', usb: 'Equaliser off' }[key] || 'Equaliser off';
  }

  function pastedSummary(s) {
    const text = String(s['tweaks.headphones.paste'] || '');
    const filters = (text.match(/filter\s*\d*\s*:\s*on/gi) || []).length || 10;
    const m = /preamp\s*:\s*(-?[0-9.]+)\s*db/i.exec(text);
    const pre = m ? Number(m[1]) : -6;
    return filters + ' filters, preamp ' + pre.toFixed(1).replace('-', '−') + ' dB';
  }

  const SECTIONS = {
    sing(s) {
      return twCard('Sing',
        twP('Turns the singer down so you can sing over the song. Works best on studio recordings with the voice in the middle.') +
        twSlider({ id: 'sing-voice', tg: 'tweaks.sing.voiceSlider', bind: 'tweaks.sing.voice', title: 'Voice', min: 0, max: 100, step: 10, text: (v) => v === 0 ? 'Off' : v + '% quieter' }) +
        twDiv() +
        twToggle({ id: 'sing-centre', bind: 'tweaks.sing.wholeCentre', tg: 'tweaks.sing.wholeCentreToggle', title: 'Whole centre',
          yes: 'Removes everything in the middle, drums included', no: 'Only the voice range, and only while someone sings', details: 'The cleaner choice for older recordings.' }) +
        twP('Sing is ported from Auris by OreoMuncher45, a fork of BitChord.', { cls: 'b-tw-p-top' }),
        { key: 'sing', tg: 'tweaks.sing.card' });
    },
    pitch() {
      return twCard('Pitch',
        twP('Changes the key without changing the speed. BitChord’s speed setting keeps the pitch you set here.') +
        twSlider({ id: 'pitch', tg: 'tweaks.pitch.slider', bind: 'tweaks.pitch', title: 'Pitch', min: -12, max: 12,
          text: (v) => v === 0 ? 'Original key' : signed(v) + (Math.abs(v) === 1 ? ' semitone' : ' semitones') }),
        { key: 'pitch', tg: 'tweaks.pitch.card' });
    },
    loudness() {
      return twCard('Loudness level',
        twP('How loud songs play when BitChord’s loudness normalisation is on. BitChord’s own level is -14 LUFS, as on YouTube and Spotify. Lower is quieter with more room for peaks; higher is louder.') +
        twSlider({ id: 'loudness', tg: 'tweaks.loudness.slider', bind: 'tweaks.loudness', title: 'Level', min: -23, max: -8,
          text: (v) => v === -14 ? '−14 LUFS (BitChord’s level)' : '−' + Math.abs(v) + ' LUFS' }),
        { key: 'loudness', tg: 'tweaks.loudness.card' });
    },
    headphones(s) {
      const out = output(s['bitchord.output']);
      const profile = s['tweaks.headphones.profile'];
      const q = String(s['tweaks.headphones.query'] || '').trim();
      const model = q || 'HD 650';
      const results = !!s['tweaks.headphones.results'];
      const sources = ['oratory1990', 'Rtings', 'crinacle'];
      const progress = s['tweaks.headphones.note'] === 'empty' ? 'Type part of the model name.' : results ? sources.length + ' results' : '';
      return twCard('Headphone correction',
        twP('Flattens a headphone’s own sound with a profile measured for that model. Each output keeps its own profile: set one with the headphones connected.') +
        twP('Playing through: ' + out.page + ' (' + out.kind + ')', { tg: 'tweaks.headphones.playingThrough' }) +
        (profile ?
          '<div class="b-tw-profile" data-key="profile"><span class="b-tw-profile-text"><span class="b-tw-profile-name">' + esc(profile) + '</span>' +
            '<span class="b-tw-profile-sub">' + esc(profile === 'Pasted profile' ? pastedSummary(s) : '10 filters, preamp −6.0 dB') + '</span></span>' +
            twSmall('Remove', { tg: 'tweaks.headphones.removeButton', set: { 'tweaks.headphones.profile': '' }, label: 'Remove ' + profile }) + '</div>' +
          twSlider({ id: 'hp-strength', tg: 'tweaks.headphones.strengthSlider', bind: 'tweaks.headphones.strength', title: 'Strength', min: 0, max: 100, step: 5, text: (v) => v + '%' }) :
          twP('No profile for this output yet.', { key: 'no-profile' })) +
        twDiv('hp-div') +
        twHeading('Find your headphones') +
        G.ui.field({ bind: 'tweaks.headphones.query', tg: 'tweaks.headphones.searchField', label: 'Find your headphones', placeholder: 'Model, such as “HD 650” or “AirPods Pro”', enter: 'twSearch', cls: 'b-tw-input' }) +
        '<div class="b-tw-buttons">' +
          twBtn('Search', { primary: true, action: 'twSearch', tg: 'tweaks.headphones.searchButton' }) +
          twBtn('Paste a profile', { open: 'tweaks.pasteProfile', tg: 'tweaks.headphones.pasteButton' }) +
        '</div>' +
        '<p class="b-tw-progress" aria-live="polite">' + esc(progress) + '</p>' +
        (results ? '<div class="b-tw-results" data-key="results">' + sources.map((src, i) =>
          '<button type="button" class="b-tw-result" data-key="res-' + src + '" data-action="twUseResult" data-name="' + esc(model) + '"' +
            (i === 0 ? ' data-tg="tweaks.headphones.resultRow"' : '') + '>' + esc(model + ' · ' + src) + '</button>').join('') + '</div>' : '') +
        twP('Profiles come from AutoEq by Jaakko Pasanen, which measures thousands of headphones. Any parametric EQ in Equalizer APO’s text form can be pasted too. The idea is from Prism by Yigder.', { cls: 'b-tw-p-top' }),
        { key: 'headphones', tg: 'tweaks.headphones.card' });
    },
    outputEq(s) {
      const out = output(s['bitchord.output']);
      const saved = s['tweaks.outputEq.saved'] || [];
      return twCard('Equaliser per output',
        twToggle({ id: 'oeq', bind: 'tweaks.outputEq.remember', tg: 'tweaks.outputEq.rememberToggle', title: 'Remember the equaliser per output',
          yes: 'Each output gets back the equaliser you set with it connected', no: 'One equaliser setting for every output' }) +
        twP('Now: ' + out.page + ' · ' + eqDescribe(s), { tg: 'tweaks.outputEq.now', cls: 'b-tw-p-top' }) +
        (saved.length ?
          '<div class="b-tw-saved" data-tg="tweaks.outputEq.savedList" data-key="saved">' + twDiv() + twHeading('Saved for') +
            saved.map((k) => '<div class="b-tw-savedrow" data-key="saved-' + esc(k) + '"><span class="b-tw-profile-text"><span class="b-tw-saved-name">' + esc(output(k).page) + '</span>' +
              '<span class="b-tw-saved-sub">' + esc(savedEq(s, k)) + '</span></span>' + twSmall('Forget', { action: 'twForget', key: 'forget-' + k, item: k, label: 'Forget ' + output(k).page }) + '</div>').join('') +
          '</div>' : '') +
        twDiv() +
        '<div class="b-tw-buttons">' +
          twBtn('Copy code', { action: 'twCopyCode', tg: 'tweaks.outputEq.copyCodeButton' }) +
          twBtn('Paste code', { open: 'tweaks.pasteCode', tg: 'tweaks.outputEq.pasteCodeButton' }) +
        '</div>' +
        twP('A code holds the equaliser’s sliders, tone pad and balance, so a setting can be shared. The idea is from Prism by Yigder.', { cls: 'b-tw-p-top' }),
        { key: 'outputEq', tg: 'tweaks.outputEq.card' });
    },
    djMode(s) {
      const status = s['tweaks.dj.on'] ? 'This song: A minor · 8A · 124 BPM · 37 songs known' : 'This song: not known yet · 0 songs known';
      return twCard('DJ mode',
        twToggle({ id: 'dj', bind: 'tweaks.dj.on', tg: 'tweaks.dj.toggle', title: 'DJ mode',
          yes: 'The next song is picked to mix well: a neighbouring key, a close tempo', no: 'The queue plays in its own order',
          details: 'Songs you queued yourself keep their place, and nothing moves in a Listen Together party. Each song’s key and tempo are learned as it plays; BitChord’s own Automix analyses are used too.' }) +
        twP(status, { tg: 'tweaks.dj.status', cls: 'b-tw-p-top', live: true }) +
        twP('The idea is from Prism by Yigder.'),
        { key: 'dj', tg: 'tweaks.dj.card' });
    },
    sleepFade() {
      return twCard('Sleep timer',
        twToggle({ id: 'fade', bind: 'tweaks.sleep.fade', tg: 'tweaks.sleep.fadeToggle', title: 'Fade out at the sleep timer',
          yes: 'The song fades out, then pauses', no: 'The song pauses straight away' }) +
        twDiv() +
        twSlider({ id: 'fade-length', tg: 'tweaks.sleep.lengthSlider', bind: 'tweaks.sleep.length', title: 'Fade length', min: 3, max: 60, text: (v) => v + ' s' }) +
        twDiv() +
        twToggle({ id: 'fade-ending', bind: 'tweaks.sleep.ending', tg: 'tweaks.sleep.endingToggle', title: 'Fade the end of the last song',
          yes: 'With “After this song”, its last seconds fade out', no: 'The last song plays to its end at full volume' }) +
        twP('A fade stands down as soon as you play, pause, seek or set the timer again, and the volume goes back up for the next time you play. The idea is from Prism by Yigder.', { cls: 'b-tw-p-top' }),
        { key: 'sleep', tg: 'tweaks.sleep.card' });
    },
    batterySaver(s) {
      return twCard('Battery saver',
        twToggle({ id: 'battery', bind: 'tweaks.battery.on', tg: 'tweaks.battery.toggle', title: 'Battery saver',
          yes: 'The phone’s audio chip plays songs it can decode', no: 'BitChord decodes every song', detailsTg: 'tweaks.battery.details',
          details: 'Saves battery on long listens. While a song plays this way, BitChord’s equaliser, loudness normalisation, Sing, spatial audio and crossfade don’t work on it. Android decides which songs and devices can; the rest play as before.' }) +
        twP(batteryStatus(s), { tg: 'tweaks.battery.status', cls: 'b-tw-p-top', live: true }),
        { key: 'battery', tg: 'tweaks.battery.card' });
    },
    losslessBluetooth(s) {
      const out = output(s['bitchord.output']);
      const on = s['tweaks.bluetooth.devices'] || [];
      return twCard('Lossless addons over Bluetooth',
        twP('Bluetooth can’t carry lossless audio on most phones, so BitChord normally skips lossless addons there. Allow them on the outputs you want anyway; the phone sends them with its best Bluetooth codec, such as LDAC or aptX. Starts with the next song.', { tg: 'tweaks.bluetooth.intro' }) +
        twP(bluetoothStatus(s), { tg: 'tweaks.bluetooth.status', live: true }) +
        twDiv() +
        twToggle({ id: 'bt-all', bind: 'tweaks.bluetooth.all', tg: 'tweaks.bluetooth.allToggle', title: 'All Bluetooth outputs',
          yes: 'Lossless addons are used over any Bluetooth output', no: 'Only the outputs allowed below' }) +
        twDiv() + twHeading('Bluetooth outputs seen') +
        BT_SEEN.map((name, i) => twToggle({ id: 'bt-dev-' + i, key: 'bt-dev-' + i, bind: 'tweaks.bluetooth.devices', item: name, on: on.indexOf(name) >= 0,
          tg: i === 0 ? 'tweaks.bluetooth.deviceToggle' : 'tweaks.bluetooth.deviceToggle' + (i + 1),
          title: name === out.page ? name + ' (connected)' : name, yes: 'Lossless addons allowed', no: 'Left to BitChord' })).join(''),
        { key: 'bluetooth', tg: 'tweaks.bluetooth.card' });
    },
    perDownload(s) {
      const last = QUALITY[s['tweaks.downloads.last']] || QUALITY.lossless;
      return twCard('Downloads',
        twToggle({ id: 'dl-ask', bind: 'tweaks.downloads.ask', tg: 'tweaks.downloads.askToggle', title: 'Ask the quality for each download',
          yes: 'Asked when you download a song; an album is asked about once', no: 'BitChord’s Download quality setting is used' }) +
        twP('Last chosen: ' + last.label + ' · The idea is from Stash by Rawnald Clark.', { cls: 'b-tw-p-top', tg: 'tweaks.downloads.lastChosen' }),
        { key: 'downloads', tg: 'tweaks.downloads.card' });
    },
    jamesdsp(s) {
      const inst = s['tweaks.jamesdsp.installed'];
      const line = inst === 'root' ? 'JamesDSP (root) is installed' : inst === 'rootless' ? 'JamesDSP (rootless) is installed' : 'JamesDSP isn’t installed';
      return twCard('JamesDSP',
        twP(line, { tg: 'tweaks.jamesdsp.installedLine' }) +
        twP('BitChord tells effect apps about its audio session when a song starts, so JamesDSP can work on the music. The rootless JamesDSP hears BitChord through Android’s audio capture, which BitChord allows.') +
        '<div class="b-tw-buttons">' +
          twBtn(inst === 'none' ? 'Get JamesDSP' : 'Open JamesDSP', { primary: true, action: 'twJames', tg: 'tweaks.jamesdsp.getButton' }) +
          twBtn('Announce the session again', { action: 'twAnnounce', tg: 'tweaks.jamesdsp.announceButton' }) +
        '</div>' +
        twDiv() +
        twToggle({ id: 'jdsp', action: 'twEffects', on: !!s['tweaks.jamesdsp.effects'], tg: 'tweaks.jamesdsp.effectsToggle', title: 'Let JamesDSP do the effects',
          yes: 'BitChord’s equaliser is turned off and Battery saver stands down', no: 'BitChord’s own effects run as usual',
          details: 'Keeps the sound from being equalised twice, and keeps songs off the audio chip, where JamesDSP couldn’t hear them.' }),
        { key: 'jamesdsp', tg: 'tweaks.jamesdsp.card' });
    }
  };
  const SECTION_ORDER = ['sing', 'pitch', 'loudness', 'headphones', 'outputEq', 'djMode', 'sleepFade', 'batterySaver', 'losslessBluetooth', 'perDownload', 'jamesdsp'];

  G.screen({
    id: 'tweaks.page',
    title: 'Bitchord+: Patches & tweaks',
    back: 'bitchord.settings',
    bars: { bg: '#000000', fg: '#ffffff' },
    render(ctx) {
      const s = ctx.s;
      const shown = SECTION_ORDER.filter((id) => s['morphe.patch.' + id]);
      return '<div class="b-our">' +
        ourTop('Patches & tweaks', 'tweaks.page.header') +
        (shown.length ? shown.map((id) => SECTIONS[id](s)).join('') :
          twCard('Nothing here yet', twP('Add one of the playback patches in Morphe and its settings show up here.'), { key: 'nothing', tg: 'tweaks.page.nothing' })) +
      '</div>';
    },
    actions: {
      twSearch(ctx) {
        if (!String(ctx.get('tweaks.headphones.query') || '').trim()) {
          ctx.set({ 'tweaks.headphones.note': 'empty', 'tweaks.headphones.results': false });
          return;
        }
        ctx.set({ 'tweaks.headphones.note': '', 'tweaks.headphones.results': true });
      },
      twUseResult(ctx, node) {
        const name = node.getAttribute('data-name') || 'HD 650';
        ctx.set({ 'tweaks.headphones.profile': name, 'tweaks.headphones.results': false, 'tweaks.headphones.note': '' });
        ctx.toast('Using ' + name);
      },
      twForget(ctx, node) {
        const key = node.getAttribute('data-item');
        ctx.set('tweaks.outputEq.saved', (ctx.get('tweaks.outputEq.saved') || []).filter((k) => k !== key));
      },
      twCopyCode(ctx) { ctx.toast('Copied ' + eqCode(ctx.s)); },
      twJames(ctx) {
        ctx.toast(ctx.get('tweaks.jamesdsp.installed') === 'none' ? 'This opens JamesDSP’s page on F-Droid.' : 'This opens JamesDSP.');
      },
      twAnnounce(ctx) {
        ctx.toast(ctx.get('bitchord.playing') ? 'Told effect apps about BitChord’s audio session.' : 'Play a song first.');
      },
      twEffects(ctx) {
        const on = !ctx.get('tweaks.jamesdsp.effects');
        const change = { 'tweaks.jamesdsp.effects': on };
        if (on) change['bitchord.equalizer'] = false;
        ctx.set(change);
      }
    }
  });

  // "Paste a profile" and "Paste code": plain platform dialogs, as in the app.
  G.screen({
    id: 'tweaks.pasteProfile',
    kind: 'dialog',
    title: 'Paste a profile',
    className: 'b-pdlg-ov',
    render() {
      return '<div class="b-pdlg">' +
        '<h2 class="b-pdlg-title">Paste a profile</h2>' +
        '<div class="b-pdlg-body">' + G.ui.textarea({ bind: 'tweaks.headphones.paste', tg: 'tweaks.pasteProfile.field', label: 'Profile', rows: 4,
          placeholder: 'Preamp: -6 dB\nFilter 1: ON PK Fc 105 Hz Gain -3.2 dB Q 0.70', cls: 'b-pdlg-field' }) + '</div>' +
        '<div class="b-pdlg-actions"><button type="button" class="b-pdlg-btn" data-close>Cancel</button>' +
          '<button type="button" class="b-pdlg-btn" data-action="twUseProfile" data-tg="tweaks.pasteProfile.useButton">Use it</button></div>' +
      '</div>';
    },
    actions: {
      twUseProfile(ctx) {
        const text = String(ctx.get('tweaks.headphones.paste') || '');
        ctx.close('tweaks.pasteProfile');
        if (!/filter\s*\d*\s*:\s*on/i.test(text)) { ctx.toast('No filters found in the text.'); return; }
        ctx.set({ 'tweaks.headphones.profile': 'Pasted profile', 'tweaks.headphones.results': false });
        ctx.toast('Using Pasted profile');
      }
    }
  });

  G.screen({
    id: 'tweaks.pasteCode',
    kind: 'dialog',
    title: 'Paste code',
    className: 'b-pdlg-ov',
    render() {
      return '<div class="b-pdlg">' +
        '<h2 class="b-pdlg-title">Paste code</h2>' +
        '<div class="b-pdlg-body">' + G.ui.field({ bind: 'tweaks.outputEq.paste', tg: 'tweaks.pasteCode.field', label: 'Code', placeholder: 'BCEQ1.…', cls: 'b-pdlg-field', enter: 'twUseCode' }) + '</div>' +
        '<div class="b-pdlg-actions"><button type="button" class="b-pdlg-btn" data-close>Cancel</button>' +
          '<button type="button" class="b-pdlg-btn" data-action="twUseCode" data-tg="tweaks.pasteCode.useButton">Use it</button></div>' +
      '</div>';
    },
    actions: {
      twUseCode(ctx) {
        const eq = readEqCode(ctx.get('tweaks.outputEq.paste'));
        ctx.close('tweaks.pasteCode');
        if (!eq) { ctx.toast('That isn’t an equaliser code.'); return; }
        ctx.set({ 'bitchord.equalizer': true, 'bitchord.eq.mode': eq.manual ? 'manual' : 'dynamic', 'bitchord.eq.toneX': eq.x, 'bitchord.eq.toneY': eq.y,
          'bitchord.eq.focused': eq.focused, 'tweaks.outputEq.paste': '' });
      }
    }
  });

  // ---------------------------------------------------------------------------------------
  // Per-download quality dialog (DownloadChoice)
  // ---------------------------------------------------------------------------------------

  G.screen({
    id: 'downloadChoice',
    kind: 'dialog',
    title: 'Download quality',
    className: 'b-dc-ov',
    render(ctx) {
      const last = ctx.s['tweaks.downloads.last'];
      const title = ctx.params.title || 'Download “' + song(ctx.s['bitchord.nowPlaying']).title + '”';
      return '<div class="b-dc">' +
        '<h2 class="b-dc-title" data-tg="downloadChoice.title">' + esc(title) + '</h2>' +
        '<p class="b-dc-sub">Pick the quality for this download.</p>' +
        '<div class="b-dc-options" data-tg="downloadChoice.options" role="group" aria-label="Quality">' +
          ['standard', 'high', 'lossless'].map((k) => '<button type="button" class="b-dc-opt' + (k === last ? ' b-dc-last' : '') + '" data-key="dc-' + k + '" data-action="dcPick" data-q="' + k + '"' +
            (k === 'lossless' ? ' data-tg="downloadChoice.lossless"' : '') + '>' +
            '<span class="b-dc-label">' + QUALITY[k].label + '</span><span class="b-dc-detail">' + esc(QUALITY[k].dialog) + '</span></button>').join('') +
        '</div>' +
        '<button type="button" class="b-dc-cancel" data-close data-tg="downloadChoice.cancel">Cancel</button>' +
      '</div>';
    },
    actions: {
      dcPick(ctx, node) {
        ctx.set('tweaks.downloads.last', node.getAttribute('data-q'));
        const id = ctx.params.song;
        if (id && SONGS[id]) queueDownload(id);
        ctx.close('downloadChoice');
      }
    }
  });
})();
