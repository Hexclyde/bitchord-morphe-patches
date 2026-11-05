# Automix Editor

Select **Automix Editor**, enable Automix in BitChord's Playback settings, and queue two tracks with known durations. Tap the waveform icon below the stats at the foot of the artwork to open **Automix Editor** for the playing track and the next queued track. The icon stays available when stats are hidden and works independently of the Audio and video switch patch. The editor is also available in Patches & tweaks.

The native screen shows outgoing track, stacked waveforms and volume curves, an independent Preview button, overlap selector, incoming track, Auto-apply/Auto-save, thinner presets, and Volume / EQ / Effects / Tools. The first four presets are Custom, Auto, Fade and Rise; scroll horizontally for Blend, Wave, Melt and Slam. Apply and the current listening track remain visible at the bottom. Opening a control tab or scrolling down compacts the waveforms and track names into a pinned header. Scrolling back to the top or using its expand control restores the full timeline. Landscape uses side-by-side deck and control panes. Light/dark themes, larger text, system bars and cutouts are supported.

## Preview and listening

Preview pauses the current listening song and captures its position. Two private, single-track players audition a copy of the draft using the host's selected-source playback pipeline. They have independent processors and a shared transient audio-focus request. They do not drive the real queue, create a MediaSession, or send the main player's listening-history events. The audition stops at the end of the blend and cannot continue into the next song. The original song stays paused between previews and while editing.

**Apply** commits the transition to the database and resumes the song from the editor's paused position, while keeping the editor open. **Close/Back** also stops preview and resumes the editor-paused song. A song that was already paused before Preview stays paused. No restoration seeks the real player or rewrites its playback speed, pitch or volume. If the real queue changes or playback resumes externally, preview cleanup does not overwrite that new listening state. Preview is unavailable while a live native transition is already in progress.

Repeat preview loops only completed private auditions. Stop, edits, backgrounding, source failure, changed queues or lost audio focus stop the private decks. Rotation preserves the original pause snapshot and the draft. Audio focus loss and ducking stop both audition decks together.

I can compare **Before**, the captured native automatic transition, with the current draft through Preview. Before remains unavailable when the native plan is missing or uses effects the audition cannot reproduce. Long transitions use a short window around the blend's middle. Private audition decks apply BitChord's blend headroom law.

Preview reports loading and playing states. Setup or source failures appear in the player’s Copy log without source URLs.


## Audio files and pair order

**Load outgoing** and **Load incoming** choose a device file for either side through Android's file picker. The file's title, artist and duration come from its metadata. **Reverse** changes the preview order. These actions leave the listening queue and playback untouched.

**Preview** holds the original listening pair while auditioning the selection. **Use this pair** explicitly replaces the current and actual next queue entries; other entries stay in place. A different outgoing file starts at its beginning. The editor waits for the native duration before applying or closing. If the queue or listening settings change externally, the operation stops without overwriting that new state. Use is unavailable in Listen Together or external player shuffle.

Draft saving keeps read access only when a chosen file can remain available. This includes successful Auto-save writes when that mode is enabled. An unreadable or non-persistent provider can still prevent a file from being saved or queued. **Apply** affects the actual queued pair; a detached selection must first be used in the queue. Reopening a saved draft and using a pair do not activate the draft by themselves.

## Track analysis

The **Track analysis** row remains visible in both the expanded and compact editor. Tap it for both tracks' tempo, beat confidence, first beat, intro/outro intervals, musical key with confidence, estimated vocal likelihood and a timestamped relative-energy plot. Opening the editor requests missing results for the selected audio even while listening is paused. The panel updates while analysis runs; **Retry** restarts failed requests without altering the draft or remeasuring an existing ready result.

I reuse ready results from BitChord's native analyzer and request missing measurements for the selected recording. The panel distinguishes active analysis, memory/thermal waiting and unavailable results. Repeated full-track memory rejections stop with an explicit Retry action rather than waiting indefinitely. Empty results do not become zero-valued measurements. I show measured key and tempo above each deck, with an **Estimated** label and a spoken **Low confidence** label when confidence is too low for mixing. A usable beat grid still requires at least 50% native beat confidence; automatic key-dependent decisions require at least 60% native key confidence. These gates do not measure calibrated accuracy. Missing measurements remain unavailable. Vocal likelihood is an estimate, not confidence in a vocal detector; it is unavailable without a real vocal activity mask. In particular, bounded DSP supplies no model vocal evidence and does not label a song instrumental from its default zero probability. Energy is shown relative to that track's measured curve, not as a universal loudness or quality score.

## Drafts and applied transitions

**Auto-save**, on by default, stores an editable draft after a brief debounce. It does not activate that draft in normal listening. With Auto-save off, **Save draft** stores it explicitly; closing an unsaved draft asks whether to discard it. Closing with Auto-save on flushes the draft before exiting.

**Auto-apply**, off by default, commits edited settings after editing pauses. If an audition is running, application waits until it ends. With Auto-apply off, **Apply** commits the actual queued pair explicitly. Both forms of application save the transition for later playback; only committed settings affect the real transition planner. Successful application happens before the paused listening song resumes. A storage failure is reported without claiming the change was saved.

The app-private SQLite database stores drafts separately from applied transitions. Both are keyed by the ordered media pair and exact selected rendition/duration identity. Reversing the pair or choosing another recording does not reuse its timing. Recreated queue-entry identifiers alone do not invalidate an unchanged recording. Old bound preference-based overrides migrate once, without the previous 200-pair eviction limit. Invalid/unbound legacy records are left untouched. This database is separate from the host's Room schema.

I use BitChord's actual current and next queue entries. The editor follows them when playback advances. Auto-save finishes writing the previous draft before switching pairs. With Auto-save off, unsaved edits stay open until **Follow current pair** is chosen. A separately loaded or reversed preview pair stays selected. Selecting **Auto** uses the native planner's captured timing for that recording pair; an unavailable plan does not supply an automatic preview. A late result updates an untouched automatic draft while preserving explicit edits. Explicit track tempos and overlap rates can still be saved with Auto; without those values, applying Auto removes the pair's manual override. Its draft is stored separately. Undo/Redo change the draft; with Auto-apply enabled, those changes are also applied after adjustment ends.

## Track tempo

**Track BPM** uses the measured tempo when available or an explicit manual value from 30 to 300 BPM. **Overlap tempo** sets each deck's multiplier from 0.5× to 2× while keeping pitch. **Match A to B** and **Match B to A** copy the other deck's effective overlap tempo; both tempos must be known and the resulting multiplier must fit that range. Matching does not guess a half-time or double-time interpretation. **Original tempo** resets both multipliers to 1× without deleting manual BPM values.

Waveforms and cue positions follow each track's source clock. The overlap duration follows the listener's base speed, so a track at 2× covers twice as much source audio during the same blend. Beat-grid half/double controls change grid interpretation separately from playback tempo.

## Waveforms and controls

Waveforms use actual streaming PCM of the selected rendition. The DJM-Rec per-channel Butterworth bands preserve stereo peak information without full-track PCM allocation. Missing extraction remains visibly unavailable; the display does not substitute another recording’s waveform. Frequency colours do not represent isolated stems. Display scaling changes visibility, not audio gain.

A bounded memory and disk cache stores only waveform peaks, keyed by recording/version, duration and waveform format. Cover images load separately from waveform work. The visible player's current pair is prefetched in the background once its saved settings are known. Fresh waveform work starts with the outgoing track; cached incoming peaks can appear immediately. **Retry** also retries failed waveform requests. Nearby cue changes reuse covering windows and keep the previous real peaks visible until fresh data arrives. First-time remote resolution and decoding can still be slower; cache improvements do not guarantee a particular cold-load time on a phone.

Volume provides independent outgoing/incoming levels and curves, overlap, outgoing trim, incoming cue and four editable curve points. The advanced curve options include Mixxx's constant-power crossfader with adjustable sharpness. Drag waveforms horizontally for timing, or use exact numeric controls. Beats snaps to measured grids without changing speed. Bar lengths require a measured or manually entered outgoing tempo and enough audio; each bar is four outgoing beats at the chosen overlap rate. Manual tempo alone does not supply a measured beat position for snapping.

The yellow curve is the outgoing volume envelope; the blue curve is the incoming envelope. Moving a point changes the time/level of that fade, independently of the waveform's audio peaks. Curve edits do not change playback speed; the separate overlap-tempo controls set that explicitly.

EQ is independent low/mid/high processing per deck, with headroom for boosts. Effects provide low-pass and high-pass sweeps. **Filter depth** controls how far the selected filters sweep, from bypass at 0% to their selected endpoints at 100%. Tools offers editable phrase/intro suggestions, four-beat cue jumps, grid offsets, half/double grid tempo, four cue bookmarks per deck, repeated preview and stereo peak/RMS readings before faders. A suggestion requires adequate measured beat confidence. Grid corrections affect cues and painting rather than playback speed. Meters are not a master limiter, true-peak meter or recorder.

Playable source, duration, network, decoder and output restrictions still apply. Exclusive bit-perfect output and Listen Together have playback restrictions. See the [component credits](../THIRD_PARTY_NOTICES.md).
