# beta.11 validation

The final MPP was built locally from the beta.9 → beta.10 → beta.11 source history.
No GitHub push, release publication, external account login or device installation
was performed. Automated account/peer tests use controlled fixtures and loopback.

| Check | Result |
| --- | --- |
| Full host regression runner (`test.py`) | Pass |
| Python release/translation/metadata checks | 31 tests passed |
| Android/Robolectric suite | 146 tests passed |
| Selected Soulseek download runner | 365 assertions passed |
| Manual source-choice runner | 46 assertions passed |
| Navigation/addon boundary runner | 24 assertions passed |
| Production Telegram upload/read-back runner | 49 assertions passed |
| Native adapter unit + peer-wire tests | 28 + 9 tests passed |
| Bundled Soulseek library unit tests | 335 tests passed |
| All 15 patches on original 1.9 arm64, armv7 and x86_64 | Pass |
| Playback-only 1.9 arm64 | Pass |
| Standalone Soulseek-only 1.9 arm64 | Pass |
| Player/source reflection ABI checks in each combined APK | 141 assertions passed per ABI |
| Patch catalog exported from final MPP; checksum/metadata round trip | Pass |

The Android suite executes actual production native-view controllers, lifecycle
handling and archive state machines using service fixtures. The player-shell tests
use an AndroidView/Compose host ABI fixture; the actual optimized host descriptors
are independently checked in each patched APK. They are not on-device Compose
instrumentation. The native peer test confirms real socket browse responses before
and after public-share withdrawal. Library tests cover original-byte upload streams,
resume offsets and cancellation. Telegram tests cover pending acknowledgements,
receipt recovery/idempotency, independent read-back/cache eviction, hash mismatches,
missing messages, account changes and original-byte restoration.

Physical Android/Waydroid, live Telegram, real remote Soulseek peers, NAT reachability
and Android background-service limits remain device qualification tasks. They are
not reported as passed by these tests. New UI strings use English fallback entries
in the existing 20-language catalog.
