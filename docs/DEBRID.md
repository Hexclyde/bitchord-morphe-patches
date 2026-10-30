# Optional debrid downloads

Build **1.1.0-beta.6** includes optional Real-Debrid, AllDebrid, Premiumize and
TorBox downloads. These adapters use the provider routes described below. Live
accounts and every provider-supported host have not been qualified.

Each provider has its own enable switch and API key. All four start **off**.
Choose the provider for each link explicitly; a failure does not automatically
submit the link through another paid service. Provider account access, quotas,
supported hosts and transfer availability still apply. This feature resolves
links and cloud transfers; it is not a music search index.

## Setup

1. Install the testing bundle with **Unofficial Soulseek audio**, which contains
   these download settings. Add **Unofficial Telegram music** if Telegram saving
   is wanted. A Soulseek account is not needed for the debrid path.
2. Open **Settings → Patches & tweaks**, enable **Debrid downloads**, then
   choose **Services & API keys** from its expanded controls. Its settings
   gear reopens those controls while enabled.
3. Open the provider's account page and obtain your own API key. Enter it only in
   that provider's masked field in the app, then save it. Saved keys are not shown
   again; a blank field keeps an existing key.
4. Use **Test API key** to check the entered or saved key, then enable that service.
   Testing a new key does not save it automatically.
5. Complete **Where copies go** and **Formats, best first**. Destinations are a
   writable phone folder, the configured private Telegram main channel, or Both.
   Telegram saving requires the Telegram integration and a connected account.

No Soulseek login or Nostr pairing is needed for a debrid link download. The
integration makes HTTPS requests from the phone to the selected service; it does
not require a custom server, bot or companion app.

## Resolve and save a link

1. Open **Debrid link downloads**, choose an enabled provider, and paste a
   supported HTTPS link or magnet.
2. Select **Resolve link**. This can create a transfer in the provider account.
   If it is still preparing, use **Refresh provider transfer** to obtain its
   current state and file list.
3. Select the audio entries to save. Filename hints filter the list against
   enabled formats; a container extension does not prove its codec or source
   quality. Non-audio entries are not downloaded through this workflow.
4. Select **Download selected audio**. The job records the provider, chosen file,
   destination and format choices. It stages the complete original privately and
   checks its encoding before handing it to the same copy publisher used by
   Soulseek and paired downloads.
5. Open the copy queue to inspect Phone and Telegram results. With Both, a
   completed copy is retained while an unfinished copy is retried.

Some services require file selection before they begin preparing the transfer.
If a queued item shows that it is waiting for the provider, refresh that transfer
and then resume the item. Refresh follows the existing remote job rather than
silently adding the same source again.

Depending on the service, the cloud may prepare the whole transfer even though
the phone saves only the selected audio files. The phone's 256 MiB file limit
does not set the provider's cloud quota or transfer size.

Files are saved as original bytes without transcoding or tag rewriting. Each
selected file is limited to **256 MiB**; temporary phone space is needed even for
Telegram-only saving. Provider responses, supported link types and queue states
vary by service and account. A provider's website offering a capability does not
mean this adapter implements every route to it.

## Provider routes

| Provider | Adapter workflow |
| --- | --- |
| Real-Debrid | Resolves supported HTTPS links. A magnet creates a metadata job; selecting files submits those choices to the same remote job. Refresh follows that job rather than adding the magnet again. |
| AllDebrid | Unlocks supported HTTPS links, including delayed responses. A magnet upload creates a provider transfer; refresh retrieves its state and file tree for audio selection. |
| Premiumize | Tries supported direct HTTPS resolution, with a provider transfer when preparation is required. Magnets create transfers. Completed file or folder results are traversed within bounded limits. |
| TorBox | Creates a web download for an HTTPS link or a torrent transfer for a magnet. HTTPS paths ending in `.nzb` use its Usenet transfer route; ambiguous NZB links are not automatically detected. Completed files remain subject to account support and the audio filter. |

Usenet support is not a shared capability of all four adapters. This is not a
general torrent or Usenet client, and the phone does not download an archive just
to extract arbitrary files from it. The UI presents available audio entries from
the selected provider's response.

## Keys, cancellation and transfer routes

API keys are encrypted with Android Keystore and kept in no-backup app storage.
They are not included in Nostr offers, channel metadata, ordinary backups or the
source package. The **Debrid downloads** master switch pauses local debrid work without deleting
saved keys. Each provider also retains its own switch. Remove a provider's key to disable it; rotating or disabling a
provider invalidates work using the older credential revision.

Pause and cancel control the phone's fetching. A cloud transfer already created
in the provider account may continue there; use that provider's account page to
manage it. The app does not promise to undo a provider-side request after it has
been accepted.

Resume restarts incomplete debrid file capture rather than combining ranges from
different expiring URLs. Prepared links and source-fetch state are kept in memory;
process death before the verified handoff requires preparing the source again.
Check the provider dashboard first because its earlier cloud transfer may still
exist. Once handed to the copy queue, the completed original uses the publisher's
durable destination journal and receipts.

The debrid path is separate from paired-phone transport fallback. It does not
route provider authentication or cloud downloads over Nostr, I2P, Tor or IPFS,
and it does not silently switch between providers. Nostr coordination and the
direct/I2P/Tor/IPFS fallback chain remain available for compatible paired-file
offers independently of debrid settings.

## Qualification

Local fixtures exercise provider response handling and the app's credential,
selection, cancellation and publication boundaries without genuine account keys.
Live account authorization, paid/account-specific limits, provider-side queue
behavior and Android background operation still require device testing. Codec
checks identify encoding; they do not prove that a lossless file was never made
from a lossy source.

Focused local fixtures can be run without account keys:

```bash
python3 tests/debrid-settings/run.py
python3 tests/debrid-rdad/run.py
python3 tests/debrid-pmtb/run.py
python3 tests/debrid-tls/run.py
```
