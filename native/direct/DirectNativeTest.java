package app.bitchord.soulseek;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** Exercises the actual host JNI library and QUIC, using a loopback-only STUN fixture. */
public final class DirectNativeTest {
    interface Action {
        void run() throws Exception;
    }

    static int checks;

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static void rejects(Action action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected IOException");
    }

    static void put16(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 8);
        data[at + 1] = (byte) value;
    }

    static void put32(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    public static void main(String[] arguments) throws Exception {
        try (final DatagramSocket stun =
                new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            Thread fixture =
                    new Thread(
                            () -> {
                                while (!stun.isClosed())
                                    try {
                                        DatagramPacket request =
                                                new DatagramPacket(new byte[2048], 2048);
                                        stun.receive(request);
                                        if (request.getLength() != 20 || request.getData()[1] != 1)
                                            continue;
                                        byte[] reply = new byte[32];
                                        put16(reply, 0, 0x0101);
                                        put16(reply, 2, 12);
                                        put32(reply, 4, 0x2112a442);
                                        System.arraycopy(request.getData(), 8, reply, 8, 12);
                                        put16(reply, 20, 0x20);
                                        put16(reply, 22, 8);
                                        reply[25] = 1;
                                        put16(reply, 26, request.getPort() ^ 0x2112);
                                        byte[] ip = request.getAddress().getAddress();
                                        for (int i = 0; i < 4; i++)
                                            reply[28 + i] =
                                                    (byte)
                                                            (ip[i]
                                                                    ^ new byte[] {
                                                                                0x21,
                                                                                0x12,
                                                                                (byte) 0xa4,
                                                                                0x42
                                                                            }
                                                                            [i]);
                                        stun.send(
                                                new DatagramPacket(
                                                        reply,
                                                        reply.length,
                                                        request.getAddress(),
                                                        request.getPort()));
                                    } catch (IOException ignored) {
                                    }
                            },
                            "mock-stun");
            fixture.setDaemon(true);
            fixture.start();
            String[] sources = {"stun:127.0.0.1:" + stun.getLocalPort()};
            long provider = DirectNative.start(sources, 1000),
                    client = DirectNative.start(sources, 1000);
            try {
                String descriptor = DirectNative.endpoint(provider);
                check(
                        descriptor.length() < 2048 && descriptor.contains("127.0.0.1:"),
                        "loopback STUN descriptor");
                check(DirectNative.accept(provider, 10) == 0, "accept timeout");
                long outgoing = DirectNative.connect(client, descriptor, 1000),
                        incoming = DirectNative.accept(provider, 1000);
                try {
                    byte[] payload = new byte[65536];
                    Arrays.fill(payload, (byte) 19);
                    DirectNative.write(outgoing, payload, 0, payload.length, 1000);
                    byte[] received = new byte[payload.length];
                    int at = 0;
                    while (at < received.length) {
                        int n =
                                DirectNative.read(
                                        incoming, received, at, received.length - at, 1000);
                        check(n > 0, "JNI readprogress");
                        at += n;
                    }
                    check(Arrays.equals(payload, received), "JNI reliable transfer");
                    rejects(() -> DirectNative.read(incoming, new byte[2], -1, 1, 100));
                    rejects(() -> DirectNative.write(outgoing, new byte[65537], 0, 65537, 100));
                    rejects(() -> DirectNative.read(incoming, new byte[2], 1, 2, 100));
                    DirectNative.finish(outgoing, 1000);
                    check(
                            DirectNative.read(incoming, new byte[1], 0, 1, 1000) == -1,
                            "JNI graceful EOF");
                    DirectNative.finish(incoming, 1000);
                    check(
                            DirectNative.read(outgoing, new byte[1], 0, 1, 1000) == -1,
                            "JNI bidirectional finish");
                } finally {
                    DirectNative.closeConnection(outgoing);
                    DirectNative.closeConnection(incoming);
                }
                rejects(
                        () ->
                                DirectNative.connect(
                                        client,
                                        "{\"v\":1,\"candidates\":[\"example.com:123\"],\"cert\":\"AA==\"}",
                                        100));
                rejects(() -> DirectNative.endpoint(-1));
                FutureTask<Boolean> waiting =
                        new FutureTask<Boolean>(
                                () -> {
                                    try {
                                        DirectNative.accept(provider, 10000);
                                        return false;
                                    } catch (IOException expected) {
                                        return true;
                                    }
                                });
                Thread thread = new Thread(waiting, "jni-accept");
                thread.start();
                Thread.sleep(20);
                DirectNative.closeNode(provider);
                check(waiting.get(1, TimeUnit.SECONDS), "JNI close cancels accept");
            } finally {
                DirectNative.closeNode(provider);
                DirectNative.closeNode(client);
            }
        }
        System.out.println(
                "Direct actual JNI/local QUIC fixtures passed: " + checks + " assertions");
    }
}
