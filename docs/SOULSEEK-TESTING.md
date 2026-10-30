# Testing the download patch

Testing version: **1.1.0-beta.9**, based on the completed beta.8 release.
Target: original **BitChord 1.9**, Android 8 or newer, on
arm64-v8a, armeabi-v7a or x86_64. This is an experimental testing bundle.

## Implemented behavior

- Optional **Unofficial Soulseek audio** Morphe patch with on-phone native runtimes.
- Unified **Patches & tweaks** controls, compact off states and one expanded
  enabled feature at a time.
- BitChord-style format switches and drag ordering, with accessible move actions.
- Guided Soulseek setup, dedicated manual search, bounded short-lived query
  caching and downloads bound to the exact selected peer result.
- Explicit Soulseek/original-flow choice for eligible manual single-track
  downloads; no automatic original download after a cancelled or failed search.
- Opt-in public compatible-peer discovery and bounded encrypted PEX referrals;
  found keys remain separate from trusted peers.
- First setup for Phone, Telegram main channel or Both, followed by enabled audio
  formats and their preference order. Encoding is checked before saving.
- Selected-source interception before the host's filename-based reuse, private
  complete-file staging and byte-preserving destination publication.
- Paired local-file sharing with encrypted Nostr offers, recipient consent and
  source grants, including IPFS identity and direct endpoint bindings.
- Direct STUN/UDP punching and certificate-pinned QUIC, with I2P, Tor and paired
  IPFS Bitswap fallback for compatible advertised files.
- Durable per-item bulk journals, verified chunks, pause/cancel/resume and separate
  phone/Telegram copy status. Telegram uploads require the Telegram patch.
- Explicit relay benchmarking using the phone's measured network path.

Soulseek starts disabled and file sharing requires explicit selection. There is
no live playback from these download transports or automatic YouTube replacement.
Ordinary Soulseek peers do not gain overlay routes. Collection-level album and
playlist integration, automatic pairing and recording matching remain pending;
the sharing picker currently queues multiple explicitly selected files.

Optional Real-Debrid, AllDebrid, Premiumize and TorBox link downloads are included
in this same build. Each service starts off and requires its
own key. See [debrid setup](DEBRID.md); it does not turn debrid into a music index
or change the paired-file fallback chain.

## Install and first setup

1. Import the development `.mpp` as a Local source in Morphe and disable duplicate
   versions of this patch source for that patching run.
2. Select the original BitChord **1.9 APK** for the phone's architecture.
3. Select **Unofficial Soulseek audio**. Add **Unofficial Telegram music** if
   Telegram saving is wanted. Patch and install with the existing Morphe signing
   key when updating an already patched installation.
4. Open **Settings → Patches & tweaks**, enable **Soulseek downloads**, then choose
   **Account & download settings**. In first setup choose
   **Phone**, **Telegram main channel** or **Both**. Phone requires a writable
   folder; Telegram requires a connected account and its owned private main channel.
5. Under **Formats, best first**, switch on accepted formats and drag their
   handles into preference order, then save. Accessible move actions can also
   rearrange a row. At least one format must stay enabled.
   Cancelling an incomplete setup does not permit a download to bypass it.
6. Use **Account settings** to sign in to Soulseek. The guided overview should
   report **Ready to search** only after the feature is enabled, the account is
   signed in and destination/format setup is complete. Open **Find music on
   Soulseek**, enter an artist and title and press **Search Soulseek**. Then
   review the peer's advertised details and choose **Download this file**.
   Use **View downloads** for that file's capture and destination-copy progress.
   **Search → Addons** remains an alternative manual entry point. Remote
   Soulseek results remain download-only.
7. Confirm that **More download options** keeps peer/debrid details collapsed
   until opened, that the setup summary updates after returning from each row,
   and that resuming the overview does not unexpectedly open another setup page.

## Manual single-track download checks

1. With Soulseek downloads enabled, use Download on an eligible ordinary YouTube
   song from the normal player. Check that the source-choice screen offers
   **Search Soulseek** and **Continue with BitChord download**, with the artist
   and title prefilled for a search.
2. Choose Soulseek and run the search. Check that no original YouTube download
   starts in parallel. Review and select one exact result, then check its named
   progress, selected format and saved destination. The original YouTube row
   must not be marked saved merely because a different Soulseek file was imported.
3. Cancel the source choice, cancel the search, search for no matching result and
   force a search failure. None may silently resume the original download.
   Select **Continue with BitChord download** explicitly and check that the host
   resumes its existing source selection and download settings only once.
4. Let the source-choice lease expire after two minutes. Check that an expired
   button cannot start old work and that returning to the song permits a new
   choice. Recreate the activity and leave/resume during a search; old callbacks
   must not repopulate stale results or enqueue a file.
5. Check a manual album/playlist batch, a managed retry and a cached-folder export.
   Those paths must retain their existing behavior without entering this choice.
   Explicit Telegram/Soulseek selections keep their original source. With the
   Soulseek feature off, the ordinary Download action keeps the host's behavior.

Changed accounts, disabled sources and expired results must fail without a
new title search or an unrelated filename being adopted. Process death during
the initial Soulseek capture requires a fresh selection; completed imports and
paired bulk jobs have their own publication/transfer journals.

## Paired download checks

Enable **Peer downloads** under **Settings → Patches & tweaks** on each phone.

1. On both phones open **Download connection setup**, exchange and compare the
   full app public keys, save each other as trusted peers and retain at least one
   common working relay. Start and run **Test trusted peer**.
2. Open **Peer downloads** and start download connections. On the sender enter
   the saved recipient key and explicitly choose local audio files. Cold I2P/Tor
   startup can delay preparation; unavailable runtimes are omitted from offers.
3. On the receiver approve an incoming offer within two minutes. Check that the
   saved destination and format choices remain fixed if settings change afterward.
4. Observe Direct first, then only the fallback routes included in that same
   authenticated file offer. Compare the final original's SHA-256 at both ends.
5. Test interruption, Resume, cancellation, app restart, sharing revocation, a
   changed default network, insufficient space and one unavailable destination.
   With Both, the successful copy must remain complete while the other is retried.

IPFS is paired Noise/TCP Bitswap without public DHT, relay or DCUtR. A provider
behind restrictive NAT may be unreachable by both direct UDP and IPFS; I2P/Tor
must be reachable for those fallback routes to help. Direct connections disclose
IP addresses to the paired phone. File names and codec probes do not prove an
original lossless recording.

## Beta.9 discovery checks

1. Follow [the discovery setup](PEER-DISCOVERY.md) with two compatible builds. A
   fresh installation must show discovery off, its gear disabled and details hidden.
   Enabling it expands the controls without starting a network scan. Opening or
   resuming the page must not start discovery automatically.
2. Leave own-profile publication off and press **Find peers**. Verify that the
   scan stops reading after ten seconds and publishes no profile. No paired phone
   is required to read public profiles from a configured compatible relay.
3. On the second phone enable **Discoverable while this page is open** and start
   a scan. It publishes only its signed app profile. Scan on the first phone and
   compare/copy the full key before explicitly saving it as a trusted peer.
4. Check that expired, malformed, incorrectly signed or incompatible profiles
   are absent. A recent result must not be labelled online or claim it has a
   particular recording. The list initially shows at most 50 rows, with **Show
   more** paging through at most 512 cached profiles.
5. With both phones explicitly paired and coordination running, start discovery
   on each. Compatible peers can exchange only signed public profiles after a
   capability handshake. An older paired build must keep normal file transfers
   without receiving unsupported PEX message types.
6. Stop discovery, leave the screen, disable Peer downloads and reset the app
   identity during a queued scan. Check that delayed callbacks do not restart it.
   **Clear found peers** stops discovery and removes the local cache. A previously
   published profile may remain on relays until expiry or longer in their storage.
7. Repeat in light/dark themes, large text and an RTL language. Record screenshots
   and screen video of the page, expanded controls and errors for UI review.

For Soulseek search, compare a preferred-format response with no preferred result.
The first can settle early; the second should retain the full search window.
Repeat within 30 seconds, reorder or disable formats, sign out/reconnect and
confirm that stale session or format results cannot be downloaded. A queue/speed
hint must not replace recording identity or codec verification.

## Relay checks

**Find best relays** sends small signed encrypted messages to this app identity.
It tests a rotating bounded subset of the 1,746 candidates, with two workers,
then ranks successful three-sample round trips. It uses no GPS or geolocation
service. Results expire and are bound to identity and active network.

A connected socket is insufficient: the relay must accept and return probes.
Applying a recommendation is explicit and preserves healthy configured relays.
Both phones still need a common working relay. Rankings measure coordination
latency, not file-transfer speed.


## Settings and format UI checks

1. Open **Settings → Patches & tweaks** with each selected patch combination.
   Only installed download and playback-tweak features should appear. The
   Soulseek setup row should be absent from Sources; Unofficial Telegram and
   unrelated sources keep their existing controls and behavior.
2. Check that an off feature hides its controls and disables its settings gear.
   Turn it on: the controls expand. Open another feature: the first stays on,
   but its details collapse. Reopen it with the gear and confirm values remain.
3. Switch a feature off while it is active. Check that its effect or new network
   work stops and that saved settings survive reenabling it. Existing completed
   downloads and their independent publication receipts must remain intact.
4. Drag the first format to the bottom and the last to the top, including while
   the list scrolls. Save and reopen; order and enabled states should match.
   Cancel a drag and rotate during an unsaved edit; no format should disappear
   or be duplicated. The last enabled format cannot be switched off.
5. Repeat with TalkBack/accessible move actions, large text, light and dark
   themes, and the phone's selected app language. Check touch targets, readable
   disabled states and back navigation.

Local fixtures exercise state and pointer handling in a simulated Android
runtime. These checks still need execution on physical phones; they do not
establish device rendering, touch behavior or accessibility-service behavior.

## Local validation and remaining device checks

Local fixtures cover Soulseek reader/lifecycle behavior, exact selected-route
binding, the player guard, Nostr signatures and pairing, offer/request/grant
authorization, destination/format snapshots, chunk corruption/resume, stream
cancellation, private IPFS cache policy, Tor control/SOCKS and isolated service
IPC. Native libraries are built and inspected for all three ABIs. Public peers
and personal accounts are not used by these fixtures.

Phone qualification is still required for public STUN/NAT behavior, I2P reseed
and tunnel bootstrap, onion reachability, public relay acceptance, live Telegram
send confirmation, Android Keystore, foreground services, process death and
background restrictions. A successful build or loopback transfer does not verify
those conditions. Selected files are capped at 256 MiB; application and IPFS
stores have separate quotas and can require additional publication space.
Debrid fixtures use synthetic responses and no genuine provider keys; live
account access, cloud queue behavior and provider limits need separate testing.

```bash
python3 tools/build_soulseek_native.py
python3 tools/build_i2p_native.py
python3 tools/prepare_tor_runtime.py
python3 tools/build_ipfs_native.py
python3 tools/build_direct_native.py
python3 build.py
python3 test.py
python3 tests/soulseek/run.py
python3 tests/coordination/run.py
python3 tests/download-policy/run.py
python3 tests/download-route/run.py
python3 tests/selected-downloads/run.py
python3 tests/bulk-coordination/run.py
python3 tests/bulk-downloads/run.py
python3 tests/overlay-bridge/run.py
python3 tests/relay-policy/run.py
python3 tests/relay-discovery/run.py
python3 tests/peer-discovery/run.py
python3 tests/soulseek-wire/run.py
python3 native/tor/test_runtime.py
python3 native/direct/test_runtime.py
python3 tools/validate_soulseek_apks.py
```

Native Rust tests use each crate's locked manifest under `native/soulseek`,
`native/ipfs` and `native/direct`. I2P source/artifact and JNI boundary checks are
under `native/i2p/tests`. Builds require the pinned local toolchain/dependency
inputs described by their build scripts.
