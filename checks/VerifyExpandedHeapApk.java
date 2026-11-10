import org.w3c.dom.Element;

import java.io.File;

/** Checks the packaged host application, not a source/library manifest. */
public final class VerifyExpandedHeapApk {
    public static void main(String[] args) throws Exception {
        var document = VerifyApk.manifest(new File(args[0]));
        var applications = document.getElementsByTagName("application");
        if (applications.getLength() != 1) throw new AssertionError("Host application not unique");
        String value = ((Element) applications.item(0)).getAttributeNS(VerifyApk.NS, "largeHeap");
        boolean expected = Boolean.parseBoolean(args[1]);
        if ("true".equals(value) != expected) throw new AssertionError("largeHeap=" + value);
        System.out.println("PASS: final host application android:largeHeap=" + value);
    }
}
