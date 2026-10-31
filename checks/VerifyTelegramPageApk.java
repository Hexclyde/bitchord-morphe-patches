import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import org.w3c.dom.Element;

import java.io.File;
import java.util.*;

/** Inspect the packaged opening path, protected credential boundary and diagnostic redaction. */
public final class VerifyTelegramPageApk {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static void guarded(Method method, String callName) {
        int offset = 0, call = -1;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction r
                    && r.getReference() instanceof MethodReference m
                    && m.getName().equals(callName)) call = offset;
            offset += instruction.getCodeUnits();
        }
        check(call >= 0, "Packaged UI call present: " + callName);
        Set<String> handled = new HashSet<>();
        for (var block : method.getImplementation().getTryBlocks())
            if (block.getStartCodeAddress() <= call
                    && call < block.getStartCodeAddress() + block.getCodeUnitCount())
                for (var handler : block.getExceptionHandlers())
                    handled.add(handler.getExceptionType());
        check(
                handled.contains("Ljava/lang/RuntimeException;"),
                "Runtime errors contained at " + callName);
        check(
                handled.contains("Ljava/lang/LinkageError;"),
                "Linkage errors contained at " + callName);
    }

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        Map<String, ClassDef> types = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : dex.getDexEntryNames())
            for (ClassDef c : dex.getEntry(entry).getDexFile().getClasses())
                types.put(c.getType(), c);
        ClassDef activity = types.get("Lapp/bitchord/telegram/TelegramActivity;");
        check(activity != null, "Telegram page included");
        Set<String> found = new HashSet<>();
        for (Method m : activity.getMethods()) {
            found.add(m.getName());
            if (m.getName().equals("onCreate")) guarded(m, "openPage");
            if (m.getName().equals("render")) guarded(m, "renderPage");
            if (m.getName().equals("failureDetails"))
                for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof ReferenceInstruction r
                            && r.getReference() instanceof MethodReference target)
                        check(
                                !Set.of("getMessage", "getLocalizedMessage", "printStackTrace")
                                        .contains(target.getName()),
                                "Diagnostics omit exception messages");
        }
        check(
                found.containsAll(List.of("pageFailure", "failureDetails", "secureWindow")),
                "Recovery and protection methods retained");
        var manifest = VerifyApk.manifest(apk);
        int declared = 0;
        var nodes = manifest.getElementsByTagName("activity");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (!e.getAttributeNS(VerifyApk.NS, "name")
                    .equals("app.bitchord.telegram.TelegramActivity")) continue;
            declared++;
            check(
                    e.getAttributeNS(VerifyApk.NS, "exported").equals("false"),
                    "Telegram page not exported");
        }
        check(declared == 1, "One Telegram setup page");
        boolean permission = false;
        nodes = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < nodes.getLength(); i++)
            if (((Element) nodes.item(i))
                    .getAttributeNS(VerifyApk.NS, "name")
                    .equals("android.permission.HIDE_OVERLAY_WINDOWS")) permission = true;
        check(permission, "Overlay protection permission retained");
        if (args.length > 1 && args[1].equals("standalone"))
            check(
                    !types.containsKey("Lapp/bitchord/soulseek/SoulseekBridge;"),
                    "Telegram needs no Soulseek runtime");
        System.out.println(
                "PASS: " + checks + " Telegram page startup, protection and diagnostic APK checks");
    }
}
