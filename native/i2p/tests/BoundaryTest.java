package app.bitchord.soulseek;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes production JNI, substituting only the core's transport with memory. */
public final class BoundaryTest {
    interface Call {
        void run() throws Exception;
    }

    static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    static void rejects(Call call) throws Exception {
        try {
            call.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
        }
    }

    public static void main(String[] arguments) throws Exception {
        File home = new File(arguments[0]);
        rejects(() -> I2pNative.start("relative"));
        rejects(() -> I2pNative.start(home.getAbsolutePath()));
        check(new File(home, "certificates/reseed").mkdirs());
        long router = I2pNative.start(home.getAbsolutePath());
        check(router != 0 && I2pNative.status(router).equals("ready"));
        String address = I2pNative.localDestination(router);
        check(address.equals(new String(new char[52]).replace('\0', 'a') + ".b32.i2p"));
        check(Files.size(new File(home, "destination.keys").toPath()) == 64);
        rejects(() -> I2pNative.start(home.getAbsolutePath()));
        rejects(() -> I2pNative.connect(router, "http://example.org", 100));
        rejects(() -> I2pNative.connect(router, address.substring(0, 51) + "b.b32.i2p", 100));
        rejects(() -> I2pNative.accept(router, 0));
        check(I2pNative.accept(router, 10) == 0);
        long sender = I2pNative.connect(router, address, 1000);
        long receiver = I2pNative.accept(router, 1000);
        check(sender != 0 && receiver != 0 && sender != receiver);
        byte[] message = new byte[65536];
        for (int i = 0; i < message.length; i++) message[i] = (byte) (i * 37);
        check(I2pNative.write(sender, message, 0, message.length, 1000) == message.length);
        byte[] received = new byte[65536];
        check(I2pNative.read(receiver, received, 0, received.length, 1000) == received.length);
        check(Arrays.equals(message, received));
        rejects(() -> I2pNative.read(receiver, received, -1, 1, 100));
        rejects(() -> I2pNative.write(sender, new byte[65537], 0, 65537, 100));
        check(I2pNative.read(receiver, received, 0, 1, 15) == 0);
        rejects(() -> I2pNative.read(receiver, received, 0, 1, 100));
        I2pNative.closeStream(receiver);
        I2pNative.closeStream(receiver);
        check(I2pNative.read(sender, received, 0, 1, 1000) == -1);
        I2pNative.closeStream(sender);
        rejects(() -> I2pNative.connect(router, "b" + address.substring(1), 15));
        long waitingSender = I2pNative.connect(router, address, 1000);
        long waitingReceiver = I2pNative.accept(router, 1000);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(2);
        Thread accept =
                new Thread(
                        () -> {
                            entered.countDown();
                            try {
                                I2pNative.accept(router, 120000);
                                unexpected.set(new AssertionError("accept not cancelled"));
                            } catch (IOException expected) {
                            }
                        });
        Thread read =
                new Thread(
                        () -> {
                            entered.countDown();
                            try {
                                I2pNative.read(waitingReceiver, new byte[1], 0, 1, 120000);
                            } catch (IOException expected) {
                            } catch (Throwable error) {
                                unexpected.set(error);
                            }
                        });
        accept.start();
        read.start();
        check(entered.await(1, TimeUnit.SECONDS));
        I2pNative.stop(router);
        accept.join(2000);
        read.join(2000);
        check(!accept.isAlive() && !read.isAlive() && unexpected.get() == null);
        check(I2pNative.status(router).equals("stopped"));
        I2pNative.stop(router);
        I2pNative.closeStream(waitingSender);
        rejects(() -> I2pNative.connect(router, address, 100));
        rejects(() -> I2pNative.start(home.getAbsolutePath()));
        System.out.println("I2P production JNI memory-backend boundary tests passed");
    }
}
