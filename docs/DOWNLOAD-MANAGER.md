# Integrated Downloads — beta.10 foundation

This is the first implementation of the Downloads concept, targeting the original
BitChord 1.9 APK. The application remains version 1.9; the Morphe bundle is
1.1.0-beta.10. Existing native runtimes are reused without modification.

## Included

- Downloads is inserted between Explore and Library in both host bottom-bar styles.
  It uses BitChord's own download-arrow glyph. The existing four host routes keep
  their original route numbers, including during Compose restart/recomposition.
- An eligible manual Download on a single YouTube catalogue track opens Download
  Explorer, including when the Soulseek account module is disabled. The optional
  Soulseek module still requires its existing explicit setup and enablement.
- Explorer queries the host's active-for-download source registry, using the
  original addon search interface. Sources answer independently. Streaming-only,
  disabled and otherwise host-ineligible sources are excluded by the host policy.
  The initial search is bounded to 20 configured sources, 12 rows per addon and
  20 Soulseek rows. Source searches have a 25-second wait bound.
- Addon results display their own title, artist, duration and reported quality
  when supplied. Soulseek results also display the selected filename's format,
  size and peer. These are reported properties, not proof of audio provenance.
- Download now takes the next available application slot; Add to download queue
  preserves waiting order. Neither operation interrupts an active file or
  overrides a remote peer's queue. Source selection and queue admission run
  separately from search work, so slow source searches do not block admission.
- Explicit addon selections are bound to their source-specific row. The binding
  is saved before enqueue. A source that becomes unavailable or disabled fails
  instead of entering the host's search through other providers/YouTube.
- BitChord automatic source selection is a separate, clearly labelled action.
  It retains the original host source and quality rules and requires confirmation.
  The original manual callback expires after two minutes; expiry/cancellation
  never triggers an automatic download.
- Downloads shows host active/queued/saved entries, named Soulseek progress,
  confirmed phone and Telegram destination receipts, and existing paired-peer
  shares. Cancellation uses the relevant transfer owner. Saved audio opens through
  Android's audio handler. Existing advanced transfer/destination pages remain
  accessible from the hub.
- Account credentials, files and native-runtime policies retain their existing
  boundaries. This release does not perform an archive-and-delete operation.

## Current boundaries

The hub and explorer are native Android pages opened from the host navigation.
They use the existing patch theme, but do not yet embed the host's persistent
mini-player/navigation into those pages; playback continues in the host service.
The concept image remains the target for that subsequent visual integration.

Album, artist and playlist menu interception still follows the original host
flow. Batch selection and per-track source comparison are pending. Addon quality
is its advertised row tier; enumerating every resolved codec/bitrate variant and
freezing a chosen rendition are also pending. BitChord's existing download
quality setting still applies to host-addon downloads.

The first-stage host/source queues are process-local. Existing destination
journals survive restart and retain explicit resume/receipt checks, but this
release does not claim durable restart recovery for an interrupted Soulseek
search/staging task. New preview labels use English fallbacks in the existing
20-language catalog until their translation pass is completed.

Sharing currently means selected-file sharing with paired app peers. Public
Soulseek uploads, remote peer upload queues and seeding-style statistics require
additional engine work and must not be shown as operational controls.

Telegram view lists confirmed copies from the destination journal. Existing
Phone/Telegram/Both download destinations remain available. Bulk offloading of
existing local music, remote read-back/hash verification, restore actions and
safe local/cache cleanup are subsequent work. A successful upload alone must
never trigger deletion under an integrity-verified label.

## Remaining accepted scope

1. Embed Downloads/Explorer in the host player shell; badge counts; per-source
   progress and retry; source/format filters; recording/version match confidence;
   resolved size/format comparison; explicit rendition selection.
2. Album/playlist/artist batch selection; missing-tracks-only; storage estimates;
   saved presets; duplicate checks; verified replacement/upgrade policy.
3. Durable job admission/recovery; queue reordering and bulk actions; pause/resume
   where supported; scheduling; Wi-Fi/charging/battery/roaming/data controls;
   concurrency and speed limits; retry/backoff and expired-link refresh.
4. File organisation, supported external storage, filename templates, tags/artwork,
   integrity/decoding checks, optional conversion, history, notifications and
   diagnostics. Audio-quality claims remain separate from container integrity.
5. Public Soulseek sharing only after upload support is implemented and verified;
   distinguish availability, waiting peers and active uploads; retain explicit
   file/folder choices and upload policies.
6. Telegram archive destination picker, backup versus offload, original-file
   upload, message confirmation, optional full read-back/hash comparison, active
   upload coordination, explicit deletion policy, restore and cache accounting.
7. Supported-link/share imports, queue/index backup, accessibility and complete
   translation. Advanced connection/segmentation controls are exposed only for
   providers and protocols that actually support them.

## Qualification

Run `python3 build.py`, `python3 test.py`, the translation/Python checks, and the
original-APK validation tools. `VerifyDownloadIntegrationApk` checks both bar
hooks, the source-fallback guard, the exact host registry/search boundary and
the retained download glyph. `tests/download-explorer/run.py` exercises the
production navigation/source binding with local fixtures; the selected-download
suite verifies real queue ordering and cancellation against local transfer fixtures.

Automated tests and patched-APK inspection do not establish physical-device UI,
live addon accounts, public-network reachability or Android lifecycle behavior.
Record the exact final bundle hash and the checks actually run in the delivery
verification record. Test against the original APK and retain the current Morphe
signing key when updating an existing installation.
