# Beetswap 0.5.0 local mobile policy patch

Source: https://crates.io/crates/beetswap/0.5.0
Upstream: https://github.com/eigerco/beetswap
License: Apache-2.0, preserved in `beetswap/LICENSE`.

Changed `beetswap/src/lib.rs`, `server.rs`, `client.rs` and `wantlist.rs`:

1. Add `ServerAuthorization` and `Behaviour::set_server_authorization`, a
   per-PeerID/per-CID policy hook. Apply it before reading server blocks and
   before dispatching a response to each waiting peer. The adapter installs
   a default-deny publication/grant policy.
2. Limit full and incremental server wantlists to eight CIDs per peer, server
   read tasks to eight, queued outgoing blocks to 32, and inbound Bitswap
   streams to two per connection.

3. Reset client request state for a fresh query and retire completed/denied
   server wants, so cache eviction or a later grant cannot permanently suppress
   a legitimate re-fetch of the same CID.

No protocol IDs, framing, protobuf messages, crypto, CID checks or wire formats
were replaced. The adapter also gives Beetswap a read-only publication store,
so automatic received-block insertion cannot implicitly announce/forward it.
The private cache is committed separately after the query result is verified.
