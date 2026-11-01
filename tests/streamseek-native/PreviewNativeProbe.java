import dalvik.system.DexClassLoader;

public final class PreviewNativeProbe {
    public static void main(String[] args) throws Exception {
        ClassLoader loader =
                new DexClassLoader(
                        args[0] + ":" + args[2],
                        "/data/local/tmp",
                        args[1],
                        ClassLoader.getSystemClassLoader().getParent());
        try {
            loader.loadClass("PreviewNativeCases")
                    .getMethod("run", String.class)
                    .invoke(null, args[3]);
        } catch (Throwable failed) {
            failed.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
