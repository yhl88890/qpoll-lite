package com.qpoll.lite;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 零权限探测：完全不用 Shizuku、不用 root，就在 App 自己的进程里试。
 *
 * <p>要回答的问题只有一个：<b>普通 App 身份到底能不能发 NXP 的私有寻卡指令（Q-Poll）？</b>
 *
 * <p>如果某台机器（比如华为/荣耀）答案是「能」，那它上面连 Shizuku 都不用装，
 * 装上就能刷；如果是「不能」（比如小米），就必须借 shell 身份。
 *
 * <p>探测分四级，任一级断了就说明卡在哪：
 * <ol>
 *   <li>有没有 NFC 硬件、开没开</li>
 *   <li>能不能从 ServiceManager 拿到 nfc 服务的 binder（SELinux 会挡）</li>
 *   <li>能不能调 getState（binder 权限检查会挡）</li>
 *   <li>能不能调 sendVendorNciMessage（服务端的签名/权限检查会挡）</li>
 * </ol>
 *
 * <p>第 4 级只发「开 Q-Poll」和「关 Q-Poll」各一条，不开寻卡、不发任何 APDU，
 * 对屏上画面没有任何影响。
 */
public final class PlainProbe {
    /** 逐条报告，跑在调用线程上。 */
    public interface Sink { void report(String line); }

    private PlainProbe() { }

    public static void run(Activity activity, Sink sink) {
        // ── 第一级：硬件与开关 ─────────────────────────────
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null) {
            sink.report("① 没有 NFC 硬件 —— 到此为止，换手机");
            return;
        }
        sink.report("① NFC 硬件：有 · 系统开关：" + (adapter.isEnabled() ? "已开" : "关着（先去打开）"));

        // ── 第二级：拿得到 NFC 服务的 binder 吗 ──────────────
        Object service;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) {
                sink.report("② 取 NFC 服务：返回 null（服务没起来）");
                return;
            }
            Class<?> stub = Class.forName("android.nfc.INfcAdapter$Stub");
            service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            sink.report("② 取 NFC 服务：拿到了（普通 App 在这一步没被挡）");
        } catch (Throwable e) {
            sink.report("② 取 NFC 服务：被挡 —— " + explain(e));
            sink.report("→ 这台机器普通 App 够不着 NFC 服务，必须借 shell 身份（Shizuku）。");
            return;
        }

        // ── 第三级：能不能读状态（binder 权限检查在这里） ────
        try {
            Object state = call(service, "getState");
            sink.report("③ 读 NFC 状态：通，状态=" + state);
        } catch (Throwable e) {
            sink.report("③ 读 NFC 状态：被拒 —— " + explain(e));
            sink.report("→ binder 权限检查没过，必须借 shell 身份（Shizuku）。");
            return;
        }

        // ── 第四级：能不能发 NXP 私有指令（关键） ────────────
        try {
            Object code = call(service, "sendVendorNciMessage",
                    1, 15, 112, new byte[]{0x31, 0x01});
            boolean ok = (code instanceof Integer) && ((Integer) code) == 0;
            sink.report("④ 发 NXP 私有寻卡指令：返回 " + code
                    + (ok ? "（接受）" : "（不为 0，说明控制器不认）"));
            // 不管成没成，立刻撤回 Q-Poll 模式，别让 NFC 停在异常状态
            try { call(service, "sendVendorNciMessage", 1, 15, 112, new byte[]{0x31, 0x00}); }
            catch (Throwable ignored) { }
            if (ok) {
                sink.report("→ 零权限可行：这台机器上不用装 Shizuku，普通 App 就能发起寻卡。");
            } else {
                sink.report("→ 指令被拒：这台机器的 NFC 栈不支持 Q-Poll，软件层没有绕法。");
            }
        } catch (Throwable e) {
            sink.report("④ 发 NXP 私有寻卡指令：被拒 —— " + explain(e));
            sink.report("→ 这台机器上必须借 shell 身份（Shizuku）才能发这条指令。");
        }
    }

    /**
     * 最后一级，也是唯一有决定性的一级：普通 App 身份下，标准 ReaderMode 能不能真的收到这块屏。
     *
     * <p>前四级只证明「指令发出去了」，这一级才证明「屏认这台手机」。
     * 必须跑在主线程（{@code enableReaderMode} 绑 Activity）。内部自己开关 Q-Poll，
     * 结束一定回调 {@code done}，不会把 NFC 停在 Q-Poll 状态。
     */
    public static void scan(final Activity activity, final int seconds, final Sink sink, final Runnable done) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null) { sink.report("⑤ 没有 NFC 硬件"); done.run(); return; }
        if (!qtag(activity, 0x01)) {
            sink.report("⑤ 开 Q-Poll 失败，没法寻卡");
            done.run();
            return;
        }
        final AtomicBoolean found = new AtomicBoolean();
        try {
            adapter.enableReaderMode(activity, tag -> {
                if (!found.compareAndSet(false, true)) return;
                StringBuilder hex = new StringBuilder();
                for (byte b : tag.getId()) hex.append(String.format("%02X", b & 255));
                sink.report("⑥ 收到标签：UID=" + hex + " —— 零权限整条链路能收到这块屏，这台机器装上就能用");
            }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
        } catch (Throwable e) {
            sink.report("⑤ 开标准 ReaderMode 失败：" + explain(e));
            qtag(activity, 0x00);
            done.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { adapter.disableReaderMode(activity); } catch (Throwable ignored) { }
            qtag(activity, 0x00);
            if (!found.get())
                sink.report("⑥ " + seconds + " 秒内没收到标签。要么屏不在这台手机旁边，"
                        + "要么普通 App 收不到这块屏（那就还得借 shell 身份）。");
            done.run();
        }, seconds * 1000L);
    }

    /** 开关 Q-Poll，用普通 App 身份，失败返回 false。 */
    private static boolean qtag(Activity activity, int action) {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) return false;
            Class<?> stub = Class.forName("android.nfc.INfcAdapter$Stub");
            Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Object code = call(service, "sendVendorNciMessage", 1, 15, 112, new byte[]{0x31, (byte) action});
            return (code instanceof Integer) && ((Integer) code) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    private static Object call(Object service, String name, Object... args) throws Exception {
        Method target = null;
        for (Method method : service.getClass().getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (method.getParameterTypes().length != args.length) continue;
            target = method;
            break;
        }
        if (target == null) throw new NoSuchMethodException(name + "/" + args.length);
        target.setAccessible(true);
        try {
            return target.invoke(service, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    /** 把反射里常见的几种拒绝翻译成人话。 */
    private static String explain(Throwable e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        String name = cause.getClass().getSimpleName();
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        if (cause instanceof SecurityException || name.contains("Security"))
            return "SecurityException（权限不够）" + (message.isEmpty() ? "" : "：" + message);
        if (message.contains("not permitted") || message.contains("denied"))
            return "SELinux 拒绝（" + message + "）";
        return name + (message.isEmpty() ? "" : "：" + message);
    }
}
