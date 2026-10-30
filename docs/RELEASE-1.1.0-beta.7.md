# 1.1.0-beta.7

This testing release targets **BitChord 1.8.1** on Android 8 or newer and includes
the beta.6 download tools, format ordering and Patches & tweaks interface.

Five additional patches are available, each off by default:

- **Per-download quality:** choose BitChord's Standard, High or Lossless quality
  for a download, with one choice for an album queued together.
- **Headphone correction:** choose a measured AutoEq profile or paste a parametric
  EQ profile, retain a profile per output and adjust its strength.
- **Equaliser per output:** retain BitChord's equaliser settings for each output
  and copy or paste settings codes.
- **DJ mode:** choose upcoming tracks using neighbouring musical keys and similar
  tempos, using learned analysis and available BitChord Automix analysis.
- **JamesDSP:** open the separately installed effect app, reannounce BitChord's
  audio session and hand over compatible effects.

Sleep timer fade-out now offers a 3–60 second fade and handles the timer's
After this song option. Playback or timer changes cancel an active fade and
restore the volume. Battery saver shows decoder/format information and stands
down when enabled processing needs decoded audio. Lossless addons over Bluetooth
can apply to all Bluetooth outputs or only selected devices, with output and
codec information where Android makes it available.

Each feature on Patches & tweaks has an enable switch. Six additional languages
are included: Polish, Hebrew, Italian, Thai, Vietnamese and Traditional Chinese.
The beta.6 Soulseek, peer and debrid download labels remain in English in these
six languages. Telegram's audio cache is trimmed at most every five minutes as
songs start; changing the cache limit still applies immediately.

The compatibility watcher distinguishes BitChord's own build/download failures
from patch-hook failures and retries failed upstream builds on a later run.
BitChord 1.9 support is not added by this release.

These are local-build and fixture-tested changes. Physical-phone audio output
switching, decoder/offload behavior, fade interruptions, DJ queue changes,
JamesDSP operation and live provider/public-network behavior still need device
validation. See the [known limitations](KNOWN_LIMITATIONS.md).

The per-download quality chooser applies to catalogue downloads. Explicitly selected
Telegram and Soulseek files retain their original encoding and format choices.
