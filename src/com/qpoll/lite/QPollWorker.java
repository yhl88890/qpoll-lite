package com.qpoll.lite;

import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真正干活的 NFC 进程。由 Shizuku 后端用 {@code app_process} 拉起，身份固定 UID 2000 + shell 域。
 *
 * <p>流程：开 QTag（让控制器接受 0x71 Q-Poll 发现类型）→ 开 ReaderMode 收标签 →
 * 收到标签后走 ISO-DEP 写图 → 释放 ReaderMode、关 QTag。
 *
 * <p>它不是常驻服务，也不是 root 后门：{@link #init()} 那两道门禁（UID 必须是 2000、
 * SELinux 域必须是 shell）就是为了保证这一点。
 */
public final class QPollWorker {
    private static Object service, callback;
    private static Class<?> adapterType;
    private static final Binder token = new Binder();
    private static final AtomicBoolean stopping = new AtomicBoolean();
    private static final AtomicBoolean cleaned = new AtomicBoolean();
    private static final AtomicBoolean tagSeen = new AtomicBoolean();
    private static volatile boolean readerTouched, qtagTouched, readerReleased, qtagDisabled;
    private static int discoveredCode, lostCode = -1;
    /** qtag(1) 的返回码：0 = 这台机器的 NFC 栈接受了 NXP 私有指令；非 0 = 不认 Q-Poll。 */
    private static int qtagStatus = -1;
    private static Process logcat;
    private static volatile Tag detectedTag;
    private static volatile String qtagRejection;
    private static volatile boolean qDiscoverySent, qDiscoveryAccepted;
    private static String mode;
    private static final Binder callbackBinder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString("android.nfc.IAppCallback");
                return true;
            }
            if (code != discoveredCode && code != lostCode) return super.onTransact(code, data, reply, flags);
            data.enforceInterface("android.nfc.IAppCallback");
            Tag tag = data.readInt() == 0 ? null : Tag.CREATOR.createFromParcel(data);
            if (code == discoveredCode && tag != null && tagSeen.compareAndSet(false, true)) {
                detectedTag = tag;
                emit("TAG", "收到标签 UID=" + hex(tag.getId()) + "；技术=" + Arrays.toString(tag.getTechList()));
                NfcA a = NfcA.get(tag);
                if (a != null) emit("META", "ATQA=" + hex(a.getAtqa()) + "；SAK=" + String.format("%04X", a.getSak() & 0xffff));
                stopping.set(true);
            } else if (code == lostCode) {
                emit("LOST", "收到标签离场通知（不代表已读到目标屏）");
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private static synchronized void emit(String kind, String message) {
        System.out.println("QP|" + kind + "|" + message.replace('\n', ' '));
        System.out.flush();
    }
    private static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02X", b & 255));
        return s.toString();
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        try { return adapterType.getMethod(name, types).invoke(service, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    private static void reader(int flags) throws Exception {
        Bundle extras = new Bundle();
        // ⚠️ 5000 是原版（org.nfcresearch.qpolllab 2.11.1）A 路验证过的值，别改大。
        // 2026-10-26 21:19 教训：一度照 B 路（FrontSession 走 NfcAdapter 的 Java 接口）的
        // 90000 抄到这里，结果 INfcAdapter.setReaderMode 这个底层 binder 接口对 presence 有
        // 上限校验，超限时 HyperOS 不抛异常、静默不注册 reader mode —— 表现就是
        // 「接口返回成功、日志一路往下跑、RF 层有系统自己的扫描帧、但永远收不到 TAG 回调」。
        // B 路是前台进程走 Java 接口，90000 没问题；A 路是 shell 进程走底层接口，只能 5000。
        if ("refresh".equals(mode) || "identity".equals(mode)) extras.putInt("presence", 5000);
        Class<?> callbackType = Class.forName("android.nfc.IAppCallback");
        try {
            // Android 15+: setReaderMode(IBinder, IAppCallback, int, Bundle, String packageName)
            invoke("setReaderMode", new Class<?>[]{IBinder.class, callbackType,
                    int.class, Bundle.class, String.class}, token, callback, flags, extras, "com.android.shell");
        } catch (NoSuchMethodException e) {
            // Android 14 及更早的 ROM（含部分 HyperOS）：没有调用方包名参数
            invoke("setReaderMode", new Class<?>[]{IBinder.class, callbackType,
                    int.class, Bundle.class}, token, callback, flags, extras);
        }
    }
    private static int qtag(int action) throws Exception {
        return (Integer) invoke("sendVendorNciMessage", new Class<?>[]{int.class, int.class, int.class, byte[].class},
                1, 15, 112, new byte[]{0x31, (byte) action});
    }
    private static void init() throws Exception {
        if (android.os.Process.myUid() != 2000)
            throw new SecurityException("NFC worker 必须以 UID 2000 运行；身份切换失败，未调用 NFC");
        String domain = new String(java.nio.file.Files.readAllBytes(
                new File("/proc/self/attr/current").toPath()), "UTF-8").replace("\u0000", "").trim();
        if (!domain.equals("u:r:shell:s0"))
            throw new SecurityException("NFC worker SELinux 域不是 shell：" + domain);
        emit("WORKER_IDENTITY", "UID=2000；SELinux=" + domain);
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "nfc");
        if (binder == null) throw new IllegalStateException("未找到 NFC 服务");
        adapterType = Class.forName("android.nfc.INfcAdapter");
        service = Class.forName("android.nfc.INfcAdapter$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        int state = (Integer) invoke("getState", new Class<?>[0]);
        emit("STATE", "执行身份 UID=" + android.os.Process.myUid() + "；NFC 状态=" + state + (state == 3 ? "（已开启）" : "（未开启）"));
        if (state != 3) throw new IllegalStateException("请先在系统设置中开启 NFC");
    }
    private static void setupCallback() throws Exception {
        Class<?> type = Class.forName("android.nfc.IAppCallback");
        Class<?> stub = Class.forName("android.nfc.IAppCallback$Stub");
        Field field = stub.getDeclaredField("TRANSACTION_onTagDiscovered");
        field.setAccessible(true);
        discoveredCode = field.getInt(null);
        try {
            field = stub.getDeclaredField("TRANSACTION_onTagLost");
            field.setAccessible(true);
            lostCode = field.getInt(null);
        } catch (NoSuchFieldException ignored) { }
        callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("asBinder")) return callbackBinder;
            if (method.getName().equals("toString")) return "QPollLiteCallback";
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("equals")) return object == args[0];
            return null;
        });
    }
    /**
     * 旁路观察 NCI 收发（只为日志和「控制器是否接受 0x71」的判定，不参与流程）。
     *
     * <p>有的 ROM（HyperOS/SN100U）不给 piped 子进程流式输出，但 {@code logcat -d} 正常，
     * 所以这里用快照轮询而不是长连接。
     */
    private static void startLogcat() {
        try {
            java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
            Thread monitor = new Thread(() -> {
                while (!cleaned.get()) {
                    try {
                        java.lang.Process snap = new ProcessBuilder("/system/bin/logcat", "-d", "-v", "threadtime",
                                "-s", "NxpNciX:D", "NxpNciR:D").redirectErrorStream(true).start();
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(snap.getInputStream()))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                if (line.startsWith("---------")) continue;
                                if (!seen.add(line)) continue;
                                int marker = line.indexOf("> ");
                                if (marker < 0) continue;
                                String packet = line.substring(marker + 2).trim().replace(" ", "");
                                if (!cleaned.get() && line.contains("NxpNciX") && NciDiscovery.hasQPoll(packet)) {
                                    qDiscoverySent = true;
                                    emit("CAPABILITY", "已发出 0x71 Q-Poll 寻卡，等待控制器确认。");
                                }
                                if (!cleaned.get() && qDiscoverySent && line.contains("NxpNciR")
                                        && packet.startsWith("410301") && packet.length() >= 8) {
                                    if (packet.substring(6, 8).equals("00")) {
                                        qDiscoveryAccepted = true;
                                        emit("CAPABILITY", "已确认控制器接受 Q-Poll 寻卡（0x71）。");
                                    } else {
                                        emit("CAPABILITY", "Q-Poll 寻卡被拒绝，响应状态=" + packet.substring(6, 8));
                                    }
                                }
                                if (line.contains("NxpNciR") && packet.toUpperCase(java.util.Locale.ROOT).startsWith("4F7001")
                                        && packet.length() >= 8 && !cleaned.get()) {
                                    String status = packet.substring(6, 8);
                                    if (!status.equals("00")) {
                                        qtagRejection = "系统拒绝 QTag，响应状态=" + status + "；当前系统未成功开启 Q-Poll。";
                                        stopping.set(true);
                                        emit("QTAG_REJECTED", qtagRejection);
                                    }
                                }
                                if (line.contains("NxpNciX") && packet.startsWith("2103")) emit("RF_TX", packet);
                                if (line.contains("NxpNciR") && (packet.startsWith("4103") || packet.startsWith("6103")
                                        || packet.startsWith("6105"))) emit("RF_RX", packet);
                            }
                        }
                        snap.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                        snap.destroy();
                    } catch (Exception e) { emit("SNAP_ERROR", String.valueOf(e)); }
                    SystemClock.sleep(400);
                }
            }, "nci-monitor");
            monitor.setDaemon(true);
            monitor.start();
        } catch (Exception e) { emit("LOG_UNAVAILABLE", "无法读取 HAL 日志；仍可观察标签回调：" + e); }
    }
    private static synchronized void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        if (readerTouched) {
            try { reader(0); readerReleased = true; emit("READER_OFF", "ReaderMode 已释放"); }
            catch (Exception e) { emit("CLEANUP_ERROR", "释放 ReaderMode 失败：" + e); }
        }
        if (qtagTouched) {
            try {
                int status = qtag(0);
                qtagDisabled = status == 0;
                emit(status == 0 ? "QTAG_OFF" : "CLEANUP_ERROR", "关闭 QTag，API 状态=" + status);
            } catch (Exception e) { emit("CLEANUP_ERROR", "关闭 QTag 失败：" + e); }
        }
        SystemClock.sleep(150);
        if (logcat != null) logcat.destroy();
    }
    public static void main(String[] args) {
        int exit = 0;
        boolean sessionStarted = false;
        try {
            if (args.length != 5 || !(args[0].equals("identity") || args[0].equals("refresh")
                    || args[0].equals("probe")))
                throw new IllegalArgumentException("Invalid mode or argument count");
            mode = args[0];
            BackendPolicy.validate(android.os.Process.myUid(), mode);
            // probe 是兼容性自检：要开 QTag、要寻卡，但不发任何 APDU，所以不算「屏幕操作」。
            boolean probe = mode.equals("probe");
            boolean panelOperation = mode.equals("identity") || mode.equals("refresh");
            int seconds = Integer.parseInt(args[1]);
            if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("时长超出允许范围");
            File stopFile = new File(args[2]);
            if (stopFile.exists()) { emit("END", "已取消，未启动寻卡"); return; }
            // 后端先建好 shell 名下的锁；worker 活得比后端久时它还管用
            try (RandomAccessFile lockFile = new RandomAccessFile(args[3], "rw");
                 FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("另一轮刷新正在运行");
                try {
                    init();
                    if (stopFile.exists()) { emit("END", "已取消，未启动寻卡"); return; }
                    setupCallback();
                    Runtime.getRuntime().addShutdownHook(new Thread(QPollWorker::cleanup));
                    startLogcat();
                    // 三种模式都要开 QTag：probe 也靠它判断这台机器认不认 NXP 私有指令。
                    qtagTouched = true;
                    qtagStatus = qtag(1);
                    emit("QTAG_ON", "启用 QTag，API 状态=" + qtagStatus
                            + (qtagStatus == 0 ? "（接受）" : "（被拒：这台机器不认 NXP Q-Poll）"));
                    if (qtagStatus != 0) throw new IllegalStateException("QTag 启用失败：API 状态=" + qtagStatus);
                    readerTouched = true;
                    reader(0x81); // NFC-A + 跳过 NDEF 检查
                    sessionStarted = true;
                    emit("START", args[0] + ":" + seconds);
                    long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                    while (!stopping.get() && !stopFile.exists() && SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(100);
                    if (qtagRejection != null) throw new IllegalStateException(qtagRejection);
                    if (panelOperation && !qDiscoveryAccepted)
                        emit("CAPABILITY", "本轮寻卡窗口结束；未获得 0x71 指令及成功响应的完整证据，请查看日志。");
                    if (tagSeen.get() && panelOperation) {
                        final int scanSeconds = seconds;
                        final File panelJob = new File(args[4]);
                        final byte[] targetUid = detectedTag.getId().clone();
                        final int[] connectionAttempt = {0};
                        ConnectionRetry.run(() -> {
                            if (stopFile.exists()) throw new InterruptedException("已停止，未开始协议通信");
                            if (!Arrays.equals(targetUid, detectedTag.getId()))
                                throw new java.io.IOException("重新寻到不同 UID 的标签，停止；尚未发送 APDU");
                            emit("CONNECT_ATTEMPT", (++connectionAttempt[0]) + " / 3");
                            PanelSession.run(detectedTag, mode, panelJob, stopFile, QPollWorker::emit);
                        }, () -> {
                            if (stopFile.exists()) throw new InterruptedException("已停止重新寻卡");
                            emit("RECONNECT", "尚未发送 APDU；重新寻卡以获取新标签，最多三次连接尝试");
                            reader(0);
                            SystemClock.sleep(200);
                            detectedTag = null;
                            tagSeen.set(false);
                            stopping.set(false);
                            if (stopFile.exists()) throw new InterruptedException("已停止重新寻卡");
                            reader(0x81);
                            emit("START", mode + ":" + scanSeconds);
                            long retryDeadline = SystemClock.elapsedRealtime() + scanSeconds * 1000L;
                            while (!stopping.get() && !stopFile.exists() && SystemClock.elapsedRealtime() < retryDeadline)
                                SystemClock.sleep(20);
                            if (stopFile.exists()) throw new InterruptedException("已停止重新寻卡");
                            if (qtagRejection != null) throw new IllegalStateException(qtagRejection);
                            if (!tagSeen.get())
                                throw new java.io.IOException("连接失败后重新寻卡超时；尚未发送 APDU");
                        });
                        if (mode.equals("identity")) emit("RESULT", "D1 屏幕规格校验通过，未写图像");
                    } else if (probe) {
                        // 自检的结论要能让人一眼看懂「卡在哪一级」，所以分三种说法。
                        if (tagSeen.get())
                            emit("RESULT", "自检通过：NFC 正常 · shell 身份正常 · NXP 私有指令返回 " + qtagStatus
                                    + " · 找到墨水屏。这台机器能刷。");
                        else if (stopFile.exists())
                            emit("RESULT", "自检中止：用户停止；没等到标签，结论不完整。");
                        else
                            emit("RESULT", "自检部分通过：NFC 正常 · shell 身份正常 · NXP 私有指令返回 " + qtagStatus
                                    + "，但 " + seconds + " 秒内没收到标签。要么手机没贴到线圈上，要么这机器认指令却耦合不到这块屏。");
                    } else if (tagSeen.get()) emit("RESULT", "已收到标签；只显示元信息，未发送 APDU");
                    else if (stopFile.exists()) emit("RESULT", "用户停止；未收到标签");
                    else emit("RESULT", "本轮超时，未收到标签；请把手机贴稳在屏幕线圈上再试");
                } finally { cleanup(); }
            }
        } catch (InterruptedException e) {
            emit("CANCELLED", e.getMessage());
            exit = 3;
        } catch (Throwable e) {
            emit("ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            exit = 1;
            java.io.StringWriter trace = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(trace));
            for (String line : trace.toString().split("\n")) emit("TRACE", line.trim());
        } finally {
            cleanup();
            if (readerTouched || qtagTouched) {
                boolean ok = (!readerTouched || readerReleased) && (!qtagTouched || qtagDisabled);
                emit(ok ? "RESTORED" : "RESTORE_FAILED", ok ? "已恢复普通寻卡" : "恢复未确认，请重启 NFC 开关后再试");
                if (!ok) exit = 2;
            }
            if (sessionStarted || exit != 0) emit("END", "本轮结束");
        }
        System.exit(exit);
    }
}
