# Integrated Downloads — beta.11

The original app remains BitChord 1.9. Install the **1.1.0-beta.11 Morphe bundle** and
apply Unofficial Soulseek audio (plus Unofficial Telegram music for Telegram features).
Beta.11 is based on beta.10, which includes every unpublished beta.9 change.

## Player integration

Downloads is the fifth tab, between Explore and Library, in both native navigation
styles. It uses BitChord's original Downloads glyph. The hub, Explorer and batch
review render in the player's content area through its retained AndroidView API.
BitChord owns the mini-player, playback controls, top bar, bottom bar and their
measured padding. Back first returns from a download detail to the hub, then to the
previous player tab. The Compose back handler allows the expanded player and dialogs
to retain their normal precedence. Existing setup/advanced transfer screens remain
available as separate settings activities. The older experimental 1.8.1 target uses
standalone pages; the new player slot and batch hook are qualified for 1.9.

## Find and choose sources

A manual Download on a YouTube catalogue track opens Explorer. It queries the host's
active-for-download addon registry and optional Soulseek account. Disabled and
streaming-only sources are excluded. Addon rows retain their source IDs, reported
quality and duration; Soulseek rows identify a particular peer and file, format and
size. The explorer bounds each search to 20 addons, 12 rows per addon and 20 displayed
Soulseek results, with a 25-second addon wait. Slow sources do not block admission.

Choose a result, then **Download now** or **Add to download queue**. Now takes the
next available slot; active files and remote peer queues are not interrupted.
Explicit addon selections cannot silently fall back to another addon or YouTube.
BitChord's automatic selection is an independently confirmed single-track action.
Its captured callback expires after two minutes; expiry never starts a download.

A manual album, playlist or artist download opens one batch review (up to 200
YouTube catalogue tracks). Choose source and reported quality for each row. The
"Find matches" buttons search an addon or Soulseek across the batch and select only
unique normalized title-and-artist matches. Multiple matches and missing tracks
remain unselected. Review all selections before submitting the selected rows now
or to the queue. Already submitted rows are excluded from retries; failed rows can
be reselected. Batch plans expire after 30 minutes or process death without starting
anything. Saved Soulseek metadata is retained with the choice so a later search
cannot replace its peer/file. There are up to 256 waiting selected Soulseek files.

Reported quality is not proof of provenance, a bit-perfect recording match or an
independently enumerated rendition. Host addon downloads still use BitChord's
quality setting. Soulseek validates the selected completed audio container before
publishing original bytes; it never silently substitutes another file.

## Downloads and public sharing

The hub shows actual host active/queued/saved rows, named Soulseek progress, phone
and Telegram publication receipts, paired app shares, public Soulseek uploads and
archive jobs. Saved files expose Open, Offload to Telegram and Share publicly on
Soulseek. The All/Active/Queue/On device/Sharing/Telegram filters refer to these
actual records, rather than illustrative counters.

Sharing publicly is a separate opt-in. Add an individual saved file to the public
inventory, then enable sharing. Only verified copies in the app-private inventory
are indexed. No phone folder is automatically scanned or advertised. Public peers
can search, browse and request those files through the bundled Soulseek protocol
library. Set 1–4 upload slots, inspect peer/progress/speed/queue state, cancel active
uploads, pause all sharing, remove a sharing copy or rescan. Pausing or changing the
inventory withdraws pending upload grants and cancels active streams. Copies already
received by someone else cannot be recalled.

The inventory survives restart but advertising pauses until explicitly enabled
again. It is limited to 200 files, 256 MiB per file and 2 GiB total. Copies use extra
phone storage. Remove an inventory entry to reclaim its sharing copy; removing the
original download does not delete the separate sharing copy. Peer reachability,
NAT and the remote user's availability still affect public uploads.

## Telegram archive and offload

Use Offload to Telegram on an existing saved file. It captures the configured
account and private main channel, uploads the original as a document and records a
durable receipt. A retry reuses that receipt rather than blindly posting duplicates.
After delivery, the bridge evicts the TDLib cache, downloads the remote document
again, hashes it and checks the live message and account/channel. The initial
archive operation keeps the phone copy.

In the Telegram view, Archive actions offers retry, Remove local copy and Restore
original to phone folder. Removal requires another independent remote read-back,
a matching receipt and a fresh comparison of the local file's size/hash. Changed
or unavailable originals, failed read-backs, deleted messages and changed accounts
keep the local file. Removal is explicitly confirmed, updates the archive journal
and forgets the host saved row only when its URI still matches. No success is
inferred from a temporary SendMessage acknowledgement.

Restore obtains and verifies the original bytes into private staging, then hands
them to the existing journaled phone publisher, which checks the completed SAF
file. Follow its progress in Downloads. Choose a phone destination first. Archive
jobs and receipts survive restart; interrupted operations require an explicit
retry. Files are limited to 256 MiB and upload/read-back needs temporary free space.
The optional Telegram patch must be present. A remote copy is only available while
the message/channel/account remains accessible.

## Background work and qualification

Explicit sharing and archive actions start the existing data-sync foreground
service. Its notification opens Downloads. Stop and Android's background timeout
pause archive work, cancel upload grants and retain journals/originals for review.
The service is not started to advertise files at boot. The host's initial queues
and unsubmitted source plans remain process-local; destination and archive journals
are durable and explicitly resumed.

Automated coverage includes Android view/controller and lifecycle checks, partial
batch submission, retained Soulseek choices, real loopback peer browse/withdrawal,
upload receipt/read-back failures, local-file preservation and original-byte restore.
Each patched 1.9 ABI is checked for the exact host method/field descriptors. These
checks do not replace installation on a connected Android/Waydroid device or actual
account/remote-peer/NAT tests. New phrases currently use English fallback entries
in the existing 20-language catalogue.
