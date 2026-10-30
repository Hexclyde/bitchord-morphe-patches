package app.bitchord.soulseek;

import java.io.IOException;

/** Explicitly paired IPFS Bitswap downloads. All blocking calls belong on a worker. */
public final class IpfsNative {
    private IpfsNative() {}

    private static final class Loader {
        static {
            System.loadLibrary("bitchord_ipfs");
        }

        static void loaded() {}
    }

    public static void load() {
        Loader.loaded();
    }

    /** home must be an app-private absolute directory; peers is a JSON PeerID string array. */
    public static native long create(String home, String allowedPeerIdsJson) throws IOException;

    /** Includes persisted PeerID, bound IP/TCP listen addresses, and honest capability flags. */
    public static native String info(long handle) throws IOException;

    /** Atomically replaces the paired allowlist, closing removed peers' connections. */
    public static native void setPeers(long handle, String allowedPeerIdsJson) throws IOException;

    /** Explicit IP/TCP multiaddresses from an authenticated Nostr offer, 100..120000 ms. */
    public static native void connect(
            long handle, String peerId, String addressesJson, int timeoutMs) throws IOException;

    /** Stages a selected file <=256 MiB with no recipients. Returns manifest + canonical bytes. */
    public static native String publish(long handle, String absoluteSourcePath) throws IOException;

    /** Replaces this publication's authorized recipients after verifying Nostr file grants. */
    public static native void grantPublication(
            long handle, String manifestCid, String allowedPeerIdsJson) throws IOException;

    /** Stops serving that publication; cached bytes stay private. Leases end on process exit. */
    public static native void unpublish(long handle, String manifestCid) throws IOException;

    /** Removes only unpublished cached blocks. Returns bytes freed; active publications survive. */
    public static native long clearPrivate(long handle) throws IOException;

    /** One query per runtime. Returns a verified raw SHA256 block <=256 KiB, kept private. */
    public static native byte[] fetchBlock(long handle, String peerId, String cid, int timeoutMs)
            throws IOException;

    public static native void cancel(long handle) throws IOException;

    public static native void close(long handle) throws IOException;
}
