# Automix Editor

Select **Source-independent AutoMix**, enable Automix in BitChord's Playback settings, and queue two tracks with known durations. Tap the waveform icon below the stats at the foot of the artwork to open **Automix Editor** for the playing track and the next queued track. The icon stays available when stats are hidden and works independently of the Audio and video switch patch. The editor is also available in Patches & tweaks.

The native screen shows outgoing track, stacked waveforms and volume curves, an independent Preview button, overlap selector, incoming track, Auto-apply/Auto-save, thinner presets, and Volume / EQ / Effects / Tools. The first four presets are Custom, Auto, Fade and Rise; scroll horizontally for Blend, Wave, Melt and Slam. Apply and the current listening track remain visible at the bottom. Opening a control tab or scrolling down compacts the waveforms and track names into a pinned header. Scrolling back to the top or using its expand control restores the full timeline. Landscape uses side-by-side deck and control panes. Light/dark themes, larger text, system bars and cutouts are supported.

## Preview and listening

Preview pauses the current listening song and captures its position. Two private, single-track players audition a copy of the draft using the host's selected-source playback pipeline. They have independent processors and a shared transient audio-focus request. They do not drive the real queue, create a MediaSession, or send the main player's listening-history events. The audition stops at the end of the blend and cannot continue into the next song. The original song stays paused between previews and while editing.

**Apply** commits the transition to the database and resumes the song from the editor's paused position, while keeping the editor open. **Close/Back** also stops preview and resumes the editor-paused song. A song that was already paused before Preview stays paused. No restoration seeks the real player or rewrites its playback speed, pitch or volume. If the real queue changes or playback resumes externally, preview cleanup does not overwrite that new listening state. Preview is unavailable while a live native transition is already in progress.

Repeat preview loops only completed private auditions. Stop, edits, backgrounding, source failure, changed queues or lost audio focus stop the private decks. Rotation preserves the original pause snapshot and the draft. Audio focus loss and ducking stop both audition decks together.

Preview reports loading and playing states. Setup or source failures appear in the player’s Copy log without source URLs.


## Track analysis

The **Track analysis** row remains visible in both the expanded and compact editor. Tap it for both tracks' tempo, beat confidence, first beat, intro/outro intervals, musical key with confidence, estimated vocal likelihood and a timestamped relative-energy plot. Opening the editor requests missing results for the selected audio even while listening is paused. The panel updates while analysis runs; **Retry** restarts failed requests without altering the draft or remeasuring an existing ready result.

The panel distinguishes active analysis, memory/thermal waiting and unavailable results. Empty results do not become zero-valued measurements. A beat grid requires at least 50% native beat confidence; a displayed header key requires at least 60% native key confidence. These thresholds are display/use gates, not calibrated accuracy guarantees. Low-confidence keys remain labelled in the detail panel. Vocal likelihood is an estimate, not confidence in a vocal detector; it is unavailable without a real vocal activity mask. In particular, bounded DSP supplies no model vocal evidence and does not label a song instrumental from its default zero probability. Energy is shown relative to that track's measured curve, not as a universal loudness or quality score.

## Drafts and applied transitions

**Auto-save**, on by default, stores an editable draft after a brief debounce. It does not activate that draft in normal listening. With Auto-save off, **Save draft** stores it explicitly; closing an unsaved draft asks whether to discard it. Closing with Auto-save on flushes the draft before exiting.

**Auto-apply**, off by default, commits edited settings after editing pauses. If an audition is running, application waits until it ends. **Apply** is always available for a deliberate commit. Both forms of application save the transition for later playback; only committed settings affect the real transition planner. Successful application happens before the paused listening song resumes. A storage failure is reported without claiming the change was saved.

The app-private SQLite database stores drafts separately from applied transitions. Both are keyed by the ordered media pair and exact selected rendition/duration identity. Reversing the pair or choosing another recording does not reuse its timing. Recreated queue-entry identifiers alone do not invalidate an unchanged recording. Old bound preference-based overrides migrate once, without the previous 200-pair eviction limit. Invalid/unbound legacy records are left untouched. This database is separate from the host's Room schema.

Selecting **Auto** and applying removes the manual override for that recording pair. Its draft can still be remembered independently. Undo/Redo restore draft settings without committing them.

## Waveforms and controls

Waveforms use actual streaming PCM of the selected rendition. The DJM-Rec per-channel Butterworth bands preserve stereo peak information without full-track PCM allocation. Missing extraction remains visibly unavailable; the display does not substitute another recording’s waveform. Frequency colours do not represent isolated stems. Display scaling changes visibility, not audio gain.

A bounded memory and disk cache stores only waveform peaks, keyed by recording/version, duration and waveform format. Cover images load separately from waveform work. The visible player's current pair is prefetched in the background once its saved settings are known. Nearby cue changes reuse covering windows and keep the previous real peaks visible until fresh data arrives. First-time remote resolution and decoding can still be slower; cache improvements do not guarantee a particular cold-load time on a phone.

Volume provides independent outgoing/incoming levels and curves, overlap, outgoing trim, incoming cue and four editable curve points. The advanced curve options include Mixxx's constant-power crossfader with adjustable sharpness. Drag waveforms horizontally for timing, or use exact numeric controls. Beats snaps to measured grids without changing speed. Bar lengths require a measured tempo and enough audio; each bar is four outgoing beats, and unequal tempos remain unequal.

The yellow curve is the outgoing volume envelope; the blue curve is the incoming envelope. Moving a point changes the time/level of that fade, independently of the waveform's audio peaks. The curves and overlap do not speed either song up or slow it down.

EQ is independent low/mid/high processing per deck, with headroom for boosts. Effects provide low-pass and high-pass sweeps. Tools offers editable phrase/intro suggestions, four-beat cue jumps, grid offsets, half/double grid tempo, four cue bookmarks per deck, repeated preview and stereo peak/RMS readings before faders. A suggestion requires adequate measured beat confidence. Grid corrections affect cues and painting rather than playback speed. Meters are not a master limiter, true-peak meter or recorder.

Playable source, duration, network, decoder and output restrictions still apply. Exclusive bit-perfect output and Listen Together have playback restrictions. See the [component credits](../THIRD_PARTY_NOTICES.md).
