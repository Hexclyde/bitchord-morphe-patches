# Compatible-peer discovery

Beta.9 can find recent public profiles from other compatible app clients. It
combines an explicit Nostr relay scan with small peer-exchange referrals from
phones you have already paired. Both use the original signed public profile.
A result proves control of that app key, not the person's identity, current
connectivity, upload slots or possession of a recording.

The feature starts off. It neither finds ordinary Soulseek users nor searches
BitTorrent swarms. Soulseek's manual music search remains a separate path.

## Setup

1. Open **Settings → Patches & tweaks** and enable **Peer downloads**.
2. Open its **Connection setup**, save the app identity and configured relays,
   then open **Peer discovery**. An existing trusted peer is not required for a
   relay scan.
3. Enable **Discover compatible peers**. Its details expand, following the
   page's switch and settings-gear behavior. Enabling does not start a scan.
4. Leave **Discoverable while this page is open** off if you only want to read
   public profiles. To publish your own app profile, enable that separate switch.
5. Press **Find peers**. The app reads fresh public profiles for ten seconds.
   Previously cached profiles are reverified before reuse. The list shows recent
   keys, advertised transports and expiry; it does not label a key online.
6. Use **Copy key for pairing**, compare the complete key through a trusted
   channel, then explicitly add it to **Trusted peers** in Connection setup.
   Pairing and selected-file offers retain their existing consent checks.

The list initially shows up to 50 rows. **Show more** reveals another 50, up to
the 512-profile cache limit. A profile is valid for no more than five minutes;
expired and invalid profiles are removed from results. A relay may reject this
application protocol or retain no recent profiles, so an empty scan does not
mean that all relays are offline.

**Stop discovery** ends local reading/publication. Leaving the screen also stops
it, and returning does not restart it. **Clear found peers** stops discovery and
removes the local cache. Turning off discovery or its parent Peer downloads
feature prevents new discovery work. These actions do not remove trusted peers,
cancel independent authorized downloads or erase data already held by a relay.

## What is public

An advertised profile contains the app public key and signature, protocol
version, supported transport names, creation time and short expiry. It contains
no filenames, audio, music catalogue, private contact list, Telegram identifiers,
API keys, network endpoints or file-access grants. Downloading or saving a file
does not automatically advertise or seed it.

Publishing continues only during the explicitly started session while the
discovery screen is open. Stopping discovery or closing the page stops profile
renewals immediately; it does not retract an already signed profile. That
profile can remain discoverable on relays and in peer caches until its signed
expiry, at most five minutes after creation. Relays may retain stored copies
longer, but this app rejects expired profiles. See [privacy](../PRIVACY.md).

## How peer exchange helps

A relay scan supplies the starting public profiles even with zero paired peers.
If paired coordination is running and discovery is active, compatible trusted
peers can negotiate the app's PEX capability and exchange bounded batches of
public profiles they have already verified. This can widen the candidate list
without a separate relay lookup for each referral. Older paired builds retain
normal file-transfer behavior without receiving unsupported PEX messages.

Each response carries at most eight signed profiles within an 8 KiB payload.
Per-peer rate limits, expiry checks, request nonces, duplicate handling and cache
limits bound the work. The original author's signature is verified again rather
than trusting the referring phone's claim. No referral automatically opens a
connection, grants file access or enters Trusted peers.

PEX complements bootstrap discovery. It cannot create peers, available files,
upload slots, bandwidth or a route through every NAT. This app uses a custom
`app.bitchord.pex/1` protocol over its paired encrypted channel, not BitTorrent's
`ut_pex`. Its private IPFS adapter remains paired-only without public DHT or
automatic CID publication. Real-Debrid, AllDebrid, Premiumize and TorBox continue
to resolve and download through the explicitly selected provider; this PEX layer
does not change their provider-side swarms.

## What remains manual

After finding a key, users must compare and pair it. A sender must explicitly
choose complete local files, and the recipient must approve each offer. The
existing Direct → I2P → Tor → IPFS route selection applies only to routes present
in the authenticated offer. Discovery does not match profiles to requested songs,
create file sessions automatically or combine chunks from several providers.

See [Nostr signaling](NOSTR-SIGNALING.md), [download behavior](SOULSEEK.md) and
[the testing checklist](SOULSEEK-TESTING.md). Public-relay acceptance, NAT paths,
Android background behavior and physical-device UI still require phone testing.
