# Patches & tweaks

Open **Settings → Patches & tweaks** after applying the patches you want in Morphe.
The native page lists controls whose installed markers are present. Its counts
come from those controls and their saved states, rather than a fixed bundle size.
Optional Telegram and download implementations are not required by audio-only
installs.

Search matches feature names, descriptions and categories. **All controls** and
**Enabled** combine with the Sources, Playback, Audio and Library filters. The
list changes in place, preserving the search field and keyboard. Returning to a
previous filter restores its scroll position where the remaining content allows.

Switching a feature on leaves its controls collapsed. An enabled feature with
configuration has a gear; open that gear to adjust its settings, then choose
**Done** to collapse it. Binary switches have no gear. Disabling a feature leaves its configuration unchanged. Telegram account/channels, Soulseek setup, debrid keys, peer
setup, playlist tools and the transition editor keep their existing native flows.

Slider values use the current saved preference. Minus/plus buttons adjust one
supported unit at a time; **Reset** is an explicit action using the native default:

| Control | Range | Reset |
| --- | --- | --- |
| Sing voice reduction | 0–100% | 50% |
| Pitch | −12 to +12 semitones | 0 |
| Loudness target | −23 to −8 LUFS | −14 LUFS |
| Sleep fade | 3–60 seconds | 10 seconds |
| Headphone correction strength | 0–100% | 100% |

Opening the page or a configuration does not apply a slider value. Enabling
Source-independent AutoMix uses BitChord's existing Automix setting; it does not
change playback speed or the saved pitch.

## Undo and dependencies

A successful feature-toggle change offers **Undo** for eight seconds, or the
longer timeout requested by Android accessibility settings. A subsequent toggle
replaces the offer. Leaving or recreating the page expires it.

Undo changes only the relevant enable preference. Slider values and unrelated edits remain unchanged. JamesDSP's native equalizer dependency is restored only while
that dependency remains unchanged; an intervening change, including an off/on
cycle, invalidates that Undo offer. Streamseek still requires Soulseek downloads;
the page does not enable Soulseek on its behalf.

Download-gate changes run on a worker. Undo may reopen a gate, but it does not
restore deleted credentials/files, restart cancelled transfers or resume an
interrupted upload. A runtime that is still stopping may reject re-enabling until
its cleanup finishes.

Each feature has a distinct native line icon. Sleep fade combines a moon and fading speaker; battery saver uses a battery with a bolt, DJ mode a turntable, AutoMix crossing audio paths, and the player switch music/video symbols. Icons are decorative; the labelled controls provide TalkBack names.

The controls preserve BitChord's green 64 × 28 dp switch track, white 40 × 24 dp
thumb and 48 dp activation target. Theme and text size follow BitChord/Android.
Selectors and switches respect the system animation setting. Search, category
selectors, switches, gears and action feedback expose native accessibility roles.
Core search/filter/Undo labels are translated in all 20 supported languages; new
explanatory messages use English fallback where a translation is not supplied.
