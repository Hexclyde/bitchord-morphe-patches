/* The tours of the Bitchord+ install guide, registered with Guide.tour(). */
(function () {
  'use strict';
  const G = window.Guide;

  const REPO = 'github.com/Hexclyde/bitchord-morphe-patches';
  const SITE = 'browser.morpheSite';
  const RELEASES = 'browser.bitchordRelease';

  G.tour({
    id: 'start',
    group: 'start',
    order: 10,
    title: 'Get Morphe and add my source',
    summary: 'Download Morphe and BitChord, install Morphe, then add my patches to it from GitHub.',
    next: ['patch'],
    state: { 'android.morpheInstalled': false },
    steps: [
      {
        title: 'Open your browser',
        body: 'Everything starts in your phone’s web browser. Open it.',
        screen: 'android.home',
        target: 'android.home.browser',
        tap: true
      },
      {
        title: 'Go to morphe.software',
        body: 'Type <code>morphe.software</code> in the address bar. This is Morphe’s own site, where you get <strong>Morphe Manager</strong>, the app that adds my patches to BitChord.',
        screen: SITE,
        target: 'browser.address'
      },
      {
        title: 'Download Morphe',
        body: 'Tap <strong>Download</strong>. The Morphe Manager APK goes to your Downloads. You’ll install it in a moment.',
        screen: SITE,
        target: 'browser.morpheSite.downloadButton',
        tap: true
      },
      {
        title: 'Open another tab',
        body: 'Next you need BitChord itself. Tap the tab button to open another tab.',
        screen: SITE,
        state: { 'browser.morpheDownloaded': true },
        target: 'browser.tabsButton',
        tap: true
      },
      {
        title: 'Tap New tab',
        body: 'Your morphe.software tab stays open. Tap <strong>New tab</strong>.',
        screen: 'browser.tabs',
        target: 'browser.tabs.newTab',
        tap: true
      },
      {
        title: 'Go to BitChord’s releases',
        body: 'Type <code>github.com/kushagrasinghx/BitChord/releases</code>. That’s BitChord’s own release page, by kushagra.',
        screen: 'browser.newTab',
        target: 'browser.newTab.bitchordRelease',
        tap: true
      },
      {
        title: 'Pick the APK for your phone',
        body: '<table><tr><th>Your phone</th><th>File</th></tr>' +
          '<tr><td>Most phones</td><td><code>…_arm64-v8a.apk</code></td></tr>' +
          '<tr><td>Older 32-bit phones</td><td><code>…_armeabi-v7a.apk</code></td></tr>' +
          '<tr><td>x86-64 devices</td><td><code>…_x86_64.apk</code></td></tr></table>' +
          'Use the release marked <strong>Latest</strong>. The <strong>Source code</strong> files won’t work.',
        screen: RELEASES,
        state: { 'browser.tabs': [SITE, RELEASES] },
        target: 'browser.bitchordRelease.assetList'
      },
      {
        title: 'Download it',
        body: 'Tap your file. Most phones need <code>BitChord-v1.9_arm64-v8a.apk</code>. You don’t install this one: Morphe asks for it when it patches.',
        screen: RELEASES,
        target: 'browser.bitchordRelease.arm64Asset',
        tap: true
      },
      {
        title: 'Open the Morphe download',
        body: 'Now install Morphe. Open <strong>Files</strong>, or tap the download notification, and tap <code>morphe-manager-2.2.0.apk</code>.',
        screen: 'files.downloads',
        state: { 'browser.bitchordDownloaded': true },
        target: 'files.downloads.morpheApk',
        tap: true
      },
      {
        title: 'Install Morphe',
        body: 'Tap <strong>Install</strong>. If Android first asks, allow the app you opened it from to install apps, then come back.',
        screen: 'files.downloads',
        overlay: 'android.installPrompt',
        overlayParams: { app: 'morphe' },
        target: 'android.installPrompt.installButton',
        tap: true
      },
      {
        title: 'Open my repository',
        body: 'Back in your browser, open another tab and go to <code>' + REPO + '</code>. That’s where my patches live.',
        screen: 'browser.patchesRepo',
        state: { 'android.morpheInstalled': true, 'browser.tabs': [SITE, RELEASES, 'browser.patchesRepo'] },
        target: 'browser.address'
      },
      {
        title: 'Scroll to the README',
        body: 'Scroll down past the files to the <strong>README</strong>. It explains what the patches do and has the link you need.',
        screen: 'browser.patchesRepo',
        target: 'browser.patchesRepo.readme'
      },
      {
        title: 'Tap Add this source to Morphe',
        body: 'Tap <strong>Add this source to Morphe</strong>. You don’t need to download anything from my releases page.',
        screen: 'browser.patchesRepo',
        target: 'browser.patchesRepo.addSourceLink',
        tap: true
      },
      {
        title: 'Tap Open in Morphe',
        body: 'Morphe’s site shows the source it’s about to add: <strong>BitChord Morphe Patches</strong>, for the app <code>com.music.bitchord</code>. Tap <strong>Open in Morphe</strong>.',
        screen: 'browser.addSourcePage',
        state: { 'browser.tabs': [SITE, RELEASES, 'browser.patchesRepo', 'browser.addSourcePage'] },
        target: 'browser.addSourcePage.openButton',
        tap: true
      },
      {
        title: 'Morphe asks first',
        body: 'Morphe opens and asks whether to add the source. Check that it says <strong>github.com/Hexclyde</strong> and <strong>com.music.bitchord</strong>. Only add sources you trust.',
        screen: 'morphe.apps',
        overlay: 'morphe.addSource',
        target: 'morphe.addSource.header'
      },
      {
        title: 'Tap Add',
        body: 'Tap <strong>Add</strong>.',
        screen: 'morphe.apps',
        overlay: 'morphe.addSource',
        target: 'morphe.addSource.addButton',
        tap: true
      },
      {
        title: 'My source is in Morphe',
        body: 'It’s on Morphe’s <strong>Sources</strong> screen as <strong>BitChord Morphe Patches</strong>. Morphe keeps it up to date from GitHub, so you never handle a patch file yourself.',
        screen: 'morphe.sources',
        state: { 'morphe.sourceAdded': true },
        target: 'morphe.sources.bitchordCard'
      }
    ]
  });

  G.tour({
    id: 'patch',
    group: 'start',
    order: 20,
    title: 'Patch BitChord and connect Telegram',
    summary: 'Patch BitChord with Morphe, install Bitchord+, then sign in to Telegram and choose your music chats.',
    state: {
      'android.morpheInstalled': true,
      'browser.morpheDownloaded': true,
      'browser.bitchordDownloaded': true,
      'morphe.sourceAdded': true,
      'morphe.prerelease': true,
      'morphe.experimental': true,
      'morphe.expertMode': true,
      'morphe.set.customPicker': true
    },
    steps: [
      {
        title: 'Tap BitChord',
        body: 'With my source added, BitChord is on Morphe’s <strong>Apps</strong> screen. Tap it.',
        screen: 'morphe.apps',
        target: 'morphe.apps.bitchordCard',
        tap: true
      },
      {
        title: 'Version 1.9 is selected',
        body: 'Morphe lists the BitChord versions my patches support. <strong>1.9</strong> is the one to patch, and it’s already selected.',
        screen: 'morphe.apps',
        overlay: 'morphe.apkHelp',
        target: 'morphe.apkHelp.version'
      },
      {
        title: 'Tap No, I already have an APK',
        body: 'You downloaded BitChord’s APK earlier, so tap <strong>No, I already have an APK</strong>.',
        screen: 'morphe.apps',
        overlay: 'morphe.apkHelp',
        target: 'morphe.apkHelp.alreadyHaveButton',
        tap: true
      },
      {
        title: 'Tap Open APK file',
        body: 'Tap <strong>Open APK file</strong>.',
        screen: 'morphe.apps',
        overlay: 'morphe.selectApk',
        target: 'morphe.selectApk.openFileButton',
        tap: true
      },
      {
        title: 'Find your Downloads',
        body: 'Morphe’s file browser opens. Go to <strong>Internal storage</strong>, then <strong>Download</strong>. If you see Android’s own picker instead, open <strong>Downloads</strong> there.',
        screen: 'morphe.apps',
        overlay: ['morphe.selectApk', 'android.filePicker'],
        overlayParams: [{}, { mode: 'apk' }],
        target: 'android.filePicker.path'
      },
      {
        title: 'Pick the BitChord APK',
        body: 'Tap <code>BitChord-v1.9_arm64-v8a.apk</code>, or the one you downloaded for your phone.',
        screen: 'morphe.apps',
        overlay: ['morphe.selectApk', 'android.filePicker'],
        overlayParams: [{}, { mode: 'apk' }],
        target: 'android.filePicker.bitchordApk',
        tap: true
      },
      {
        title: 'Tap Proceed anyway',
        body: 'My patches mark BitChord 1.9 as experimental, so Morphe asks first. Tap <strong>Proceed anyway</strong>.',
        screen: 'morphe.apps',
        state: { 'morphe.apkPicked': true },
        overlay: 'morphe.experimentDialog',
        target: 'morphe.experimentDialog.proceedButton',
        tap: true
      },
      {
        title: 'Let Morphe keep patching',
        body: 'The first time, Morphe asks to run without battery limits, so patching doesn’t stop halfway. Tap <strong>Allow</strong>.',
        screen: 'morphe.apps',
        overlay: 'morphe.bgProtection',
        target: 'morphe.bgProtection.allowButton',
        tap: true
      },
      {
        title: 'Two sources of patches',
        body: 'The tabs are the two sources: my <strong>BitChord Morphe Patches</strong> and Morphe’s own <strong>Morphe Patches</strong>. Leave Morphe’s all off.',
        screen: 'morphe.apps',
        state: { 'morphe.bgProtectionAllowed': true, 'morphe.bgProtectionAsked': true },
        overlay: 'morphe.patchList',
        target: 'morphe.patchList.bitchordTab'
      },
      {
        title: 'Keep Unofficial Telegram music',
        body: '<strong>Unofficial Telegram music</strong> is the main patch, and it’s ticked by default. If it isn’t, tap the card to tick it.',
        screen: 'morphe.apps',
        overlay: 'morphe.patchList',
        target: 'morphe.patchList.patch.telegram'
      },
      {
        title: 'Keep Install as Bitchord+',
        body: '<strong>Install as Bitchord+</strong> is ticked too. It installs the patched app as <strong>Bitchord+</strong>, next to your regular BitChord instead of over it. Keep it ticked.',
        screen: 'morphe.apps',
        overlay: 'morphe.patchList',
        target: 'morphe.patchList.patch.bitchordPlus'
      },
      {
        title: 'Rename it if you like',
        optional: true,
        body: 'Its gear sets the name on your home screen. Tap it to see.',
        screen: 'morphe.apps',
        overlay: 'morphe.patchList',
        target: 'morphe.patchList.installAsGear',
        tap: true
      },
      {
        title: 'Leave the name blank',
        optional: true,
        body: 'Leave <strong>Installation name</strong> blank to get <strong>Bitchord+</strong>, or type your own, up to 80 characters. Then tap <strong>Close</strong>.',
        screen: 'morphe.apps',
        overlay: ['morphe.patchList', 'morphe.patchOptions'],
        overlayParams: [{}, { patch: 'bitchordPlus' }],
        target: 'morphe.patchOptions.installationNameField',
        tap: 'morphe.patchOptions.closeButton'
      },
      {
        title: 'Untick Sign in with MicroG',
        body: 'For now, test with just these two. Tap <strong>Sign in with MicroG</strong> to untick it. Leave the other patches off. You can add them later by patching again.',
        screen: 'morphe.apps',
        overlay: 'morphe.patchList',
        target: 'morphe.patchList.patch.microg',
        tap: true
      },
      {
        title: 'Tap Patch',
        body: 'Two patches are ticked: <strong>Unofficial Telegram music</strong> and <strong>Install as Bitchord+</strong>. Tap <strong>Patch</strong>.',
        state: { 'morphe.patch.microg': false },
        screen: 'morphe.apps',
        overlay: 'morphe.patchList',
        target: 'morphe.patchList.patchButton',
        tap: true
      },
      {
        title: 'Wait for patching',
        body: 'Morphe builds Bitchord+ on your phone. It takes a few minutes. Keep Morphe open until it’s done.',
        screen: 'morphe.patching',
        target: 'morphe.patching.progress'
      },
      {
        title: 'Tap Install',
        body: 'Patching is done. Tap <strong>Install</strong>.',
        screen: 'morphe.result',
        target: 'morphe.result.installButton',
        tap: true
      },
      {
        title: 'Confirm the install',
        body: 'Android asks if you want to install Bitchord+. Tap <strong>Install</strong>. It installs next to your regular BitChord.',
        screen: 'morphe.result',
        overlay: 'android.installPrompt',
        overlayParams: { app: 'bitchordPlus' },
        target: 'android.installPrompt.installButton',
        tap: true
      },
      {
        title: 'Open Bitchord+',
        body: 'Installed. Tap <strong>Open</strong>.',
        screen: 'morphe.result',
        state: { 'morphe.installed': true },
        target: 'morphe.result.openButton',
        tap: true
      },
      {
        title: 'Open your account menu',
        body: 'This is Bitchord+. Tap the account button at the top.',
        screen: 'bitchord.home',
        target: 'bitchord.topbar.accountButton',
        tap: true
      },
      {
        title: 'Tap Settings',
        body: 'Tap <strong>Settings</strong>.',
        screen: 'bitchord.home',
        overlay: 'bitchord.accountSheet',
        target: 'bitchord.accountSheet.settingsRow',
        tap: true
      },
      {
        title: 'Tap Account & integrations',
        body: 'Tap <strong>Account &amp; integrations</strong>.',
        screen: 'bitchord.settings',
        target: 'bitchord.settings.accountIntegrationsRow',
        tap: true
      },
      {
        title: 'Tap Unofficial Telegram',
        body: 'Tap <strong>Unofficial Telegram</strong>. That’s my patch’s settings page.',
        screen: 'bitchord.accountIntegrations',
        target: 'bitchord.accountIntegrations.unofficialTelegramRow',
        tap: true
      },
      {
        title: 'Get your API ID and hash',
        body: 'Telegram needs an API ID and API hash of your own. Sign in at <code>my.telegram.org</code>, open <strong>API development tools</strong> and create an app. Telegram then shows your <strong>App api_id</strong> and <strong>App api_hash</strong>.',
        screen: 'tg.page',
        target: 'tg.setup.createCredentialsLink'
      },
      {
        title: 'Enter them and save',
        body: 'Type the <strong>App API ID</strong> and <strong>App API hash</strong>, then tap <strong>Save and continue</strong>. They stay on your phone, encrypted.',
        screen: 'tg.page',
        state: { 'tg.apiId': '1234567', 'tg.apiHash': '0123456789abcdef0123456789abcdef' },
        target: 'tg.setup.apiIdField',
        tap: 'tg.setup.saveButton'
      },
      {
        title: 'Enter your phone number',
        body: 'Type your phone number with its country code, such as +44, and tap <strong>Continue</strong>. You need an existing Telegram account.',
        screen: 'tg.page',
        state: { 'tg.stage': 'phone', 'tg.phone': '+44 7700 900123' },
        target: 'tg.account.phoneField',
        tap: 'tg.account.continueButton'
      },
      {
        title: 'Enter the code',
        body: 'Telegram sends a code to your Telegram app. Type it and tap <strong>Verify and continue</strong>.',
        screen: 'tg.page',
        state: { 'tg.stage': 'code', 'tg.code': '12345' },
        target: 'tg.account.codeField',
        tap: 'tg.account.verifyButton'
      },
      {
        title: 'Telegram is connected',
        body: 'That’s it: Bitchord+ is signed in to your Telegram account, on your phone only.',
        screen: 'tg.page',
        state: { 'tg.stage': 'connected' },
        target: 'tg.account.connectedRow'
      },
      {
        title: 'Choose your music chats',
        body: 'Under <strong>Music library</strong>, tap <strong>Choose</strong> next to <strong>Music chats</strong>.',
        screen: 'tg.page',
        target: 'tg.library.musicChatsChoose',
        tap: true
      },
      {
        title: 'Tap All chats',
        body: 'Tap <strong>All chats</strong>, or a chat folder of yours.',
        screen: 'tg.page',
        overlay: 'tg.chatFolders',
        target: 'tg.chatFolders.allChats',
        tap: true
      },
      {
        title: 'Tick the chats with music',
        body: 'Tick the channels and chats that have music files, here <strong>Lossless Library</strong> and <strong>Jazz Vinyl Rips</strong>. Bitchord+ only searches the ones you tick.',
        screen: 'tg.page',
        state: { 'tg.chatFolder': 'all', 'tg.chats': ['Lossless Library', 'Jazz Vinyl Rips'] },
        overlay: 'tg.chatPicker',
        target: 'tg.chatPicker.list',
        interactive: true
      },
      {
        title: 'Tap Save',
        body: 'Tap <strong>Save</strong>.',
        screen: 'tg.page',
        overlay: 'tg.chatPicker',
        target: 'tg.chatPicker.saveButton',
        tap: true
      },
      {
        title: 'Indexing starts at once',
        body: '<strong>Saved index</strong> is on, so Bitchord+ starts listing the music in your chats straight away, in the background. It keeps the list on your phone and tops it up every few hours.',
        screen: 'tg.page',
        state: { 'tg.indexing': true },
        target: 'tg.library.savedIndex'
      },
      {
        title: 'Telegram is a source',
        body: 'In BitChord’s <strong>Sources</strong>, <strong>Unofficial Telegram</strong> is on. Search in BitChord’s <strong>Addons</strong> tab to play from your chats.',
        screen: 'bitchord.sources',
        target: 'bitchord.sources.telegramRow'
      }
    ]
  });
})();
