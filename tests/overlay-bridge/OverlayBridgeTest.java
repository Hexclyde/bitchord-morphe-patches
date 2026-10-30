package app.bitchord.soulseek;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class OverlayBridgeTest {
    private static int assertions;

    public static void main(String[] arguments) throws Exception {
        Looper.markMain(true);
        OverlayClient main = new OverlayClient(new Context(), OverlayClient.Kind.I2P);
        fails(() -> main.start(500), "main thread cannot start blocking RPC");
        main.close();
        Looper.markMain(false);

        OverlayClient client = new OverlayClient(new Context(), OverlayClient.Kind.I2P);
        client.start(2000);
        check(client.endpoint(1000).equals("fixture.b32.i2p"), "runtime endpoint crosses IPC");
        OverlayClient unused = new OverlayClient(new Context(), OverlayClient.Kind.I2P);
        unused.close();
        check(
                client.endpoint(1000).equals("fixture.b32.i2p"),
                "closing unused client cannot stop owner service");
        OverlayClient other = new OverlayClient(new Context(), OverlayClient.Kind.I2P);
        fails(() -> other.start(1000), "second session cannot take over router");
        check(
                client.endpoint(1000).equals("fixture.b32.i2p"),
                "rejected session cannot stop owner service");
        client.status();
        until(() -> "ready".equals(client.status()), "status refresh completes off IPC worker");
        OverlayClient.Connection first = client.connect("fixture.b32.i2p", 1000);
        byte[] payload = new byte[32768];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) index;
        first.output.write(payload);
        byte[] received = new byte[payload.length];
        int offset = 0;
        while (offset < received.length)
            offset += first.input.read(received, offset, received.length - offset);
        check(Arrays.equals(payload, received), "socket-pair pumps preserve every byte");
        check(I2pService.closed.get() == 0, "successful RPC does not cancel its returned stream");

        OverlayClient.Connection second = client.connect("fixture.b32.i2p", 1000);
        check(
                client.accept(200) == null,
                "busy provider accept returns null without terminating listener");
        fails(() -> client.connect("fixture.b32.i2p", 1000), "two active stream limit enforced");
        int beforeClose = I2pService.closed.get();
        first.close();
        first.close();
        until(
                () -> I2pService.closed.get() == beforeClose + 1,
                "connection close stops both pumps exactly once");
        OverlayClient.Connection replacement = client.connect("fixture.b32.i2p", 1000);
        replacement.close();
        second.close();
        until(
                () -> I2pService.closed.get() == I2pService.opened.get(),
                "all native streams closed");
        check(client.accept(250) == null, "accept deadline returns null");

        I2pService.discardWrites = true;
        I2pService.writeGate = new CountDownLatch(1);
        I2pService.writeEntered = new CountDownLatch(1);
        OverlayClient.Connection graceful = client.connect("fixture.b32.i2p", 1000);
        int beforeDrain = I2pService.bytesWritten.get();
        graceful.output.write(new byte[] {1, 2, 3, 4});
        check(
                I2pService.writeEntered.await(1, TimeUnit.SECONDS),
                "native final ACK write is pending");
        FutureTask<Void> drain =
                new FutureTask<>(
                        () -> {
                            graceful.finish();
                            return null;
                        });
        new Thread(drain, "graceful-finish-fixture").start();
        Thread.sleep(30);
        check(!drain.isDone(), "normal finish waits for queued FD bytes to reach native writer");
        I2pService.writeGate.countDown();
        drain.get(2, TimeUnit.SECONDS);
        check(
                I2pService.bytesWritten.get() == beforeDrain + 4,
                "normal finish preserves final protocol ACK");
        graceful.finish();
        graceful.close();
        I2pService.writeGate = null;
        I2pService.discardWrites = false;

        I2pService.gate = new CountDownLatch(1);
        I2pService.entered = new CountDownLatch(1);
        try {
            client.connect("fixture.b32.i2p", 150);
            throw new AssertionError("timeout missing");
        } catch (SocketTimeoutException expected) {
            assertions++;
        }
        check(I2pService.entered.await(1, TimeUnit.SECONDS), "late native operation started");
        I2pService.gate.countDown();
        I2pService.gate = null;
        until(
                () -> I2pService.closed.get() == I2pService.opened.get(),
                "late completion closes its native stream");
        until(
                () -> requestCount() == 0,
                "cancelled queued/active requests leave no registry entries");

        OverlayClient.Connection deathStream = client.connect("fixture.b32.i2p", 1000);
        Field replies = OverlayClient.class.getDeclaredField("replies");
        replies.setAccessible(true);
        ((Messenger.LocalBinder) ((Messenger) replies.get(client)).getBinder()).die();
        until(() -> I2pService.stopped.get() == 1, "Binder death stops router");
        until(() -> android.os.Process.kills == 1, "only private process is terminated");
        deathStream.close();
        client.close();
        until(() -> ParcelFileDescriptor.liveCount() == 0, "all Binder duplicate FDs released");

        OverlayClient timed = new OverlayClient(new Context(), OverlayClient.Kind.I2P);
        timed.start(1000);
        OverlayClient.Connection timedStream = timed.connect("fixture.b32.i2p", 1000);
        android.app.Service timedService = Context.active;
        long timeoutBegan = System.nanoTime();
        Looper.markMain(true);
        try {
            timedService.onTimeout(1, 1);
        } finally {
            Looper.markMain(false);
        }
        check(
                timedService.foregroundStopped && timedService.selfStopped,
                "dataSync timeout removes foreground status immediately");
        check(
                System.nanoTime() - timeoutBegan < TimeUnit.MILLISECONDS.toNanos(100),
                "timeout never waits for native shutdown on main thread");
        until(
                () -> I2pService.stopped.get() == 2 && android.os.Process.kills == 2,
                "timeout closes router in private process");
        timedStream.close();
        timed.close();
        until(() -> ParcelFileDescriptor.liveCount() == 0, "timeout releases every stream FD");

        int starts = I2pService.opened.get();
        Application.processName = "fixture";
        OverlayClient unsafe = new OverlayClient(new Context(), OverlayClient.Kind.I2P);
        fails(() -> unsafe.start(500), "router cannot start in player process");
        unsafe.close();
        Thread.sleep(50);
        check(I2pService.opened.get() == starts, "guard refuses main-process transport");
        check(android.os.Process.kills == 2, "main player process is never killed");
        check(ParcelFileDescriptor.liveCount() == 0, "guard failure leaks no FDs");
        System.out.println("Overlay bridge: " + assertions + " assertions passed");
    }

    private static int requestCount() {
        try {
            Field requests = OverlayService.class.getDeclaredField("requests");
            requests.setAccessible(true);
            return ((Map<?, ?>) requests.get(Context.active)).size();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void until(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.getAsBoolean(), message);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void fails(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            assertions++;
        }
    }

    interface Action {
        void run() throws Exception;
    }
}
