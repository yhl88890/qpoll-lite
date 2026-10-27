package com.qpoll.lite;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Shizuku 用户服务：跑在 shell（或 root）身份下，负责起 NFC worker 并把它的输出搬回 App。
 *
 * <p>接口用裸 Binder 手写，只有四条：
 * <ol>
 *   <li>1 = 开始一轮会话（mode / 秒数 / job JSON / 图片字节 / 用于取消的回调 Binder）</li>
 *   <li>2 = 取增量日志（App 每 100ms 拉一次，顺带知道还在不在跑）</li>
 *   <li>3 = 取消</li>
 *   <li>4 = 报自己的 UID（App 用它判断是 Shell 还是 Root 权限）</li>
 * </ol>
 */
public final class ShellBridge extends Binder {
    private static final String INTERFACE = "com.qpoll.lite.NfcSession";
    private static final String PKG = "com.qpoll.lite";
    private final int appUid;
    private final Object lock = new Object();
    private final StringBuilder logs = new StringBuilder();
    private volatile boolean active, cancelled;
    private volatile File stop;

    public ShellBridge() throws Exception {
        if (android.os.Process.myUid() != 0 && android.os.Process.myUid() != 2000)
            throw new SecurityException("Shell/root required");
        appUid = applicationInfo().uid;
    }

    private static ApplicationInfo applicationInfo() throws Exception {
        Object pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        ApplicationInfo info = (ApplicationInfo) Class.forName("android.content.pm.IPackageManager")
                .getMethod("getApplicationInfo", String.class, long.class, int.class)
                .invoke(pm, PKG, 0L, 0);
        if (info == null) throw new IllegalStateException("App missing");
        return info;
    }

    /**
     * APK 路径必须每次现查，<b>绝对不能缓存</b>。
     *
     * <p>Shizuku 的后端进程是常驻的，跨 App 重装活着。如果构造时把 {@code sourceDir}
     * 记下来，App 一更新这个路径就被系统删了，worker 再拿它当 CLASSPATH 就是
     * {@code ClassNotFoundException: com.qpoll.lite.QPollWorker}，而 ART 是以
     * SIGABRT（退出码 134）直接崩掉的——现象是「一直寻不到卡」，看不出任何头绪。
     */
    private static String apkPath() throws Exception {
        return applicationInfo().sourceDir;
    }

    private void log(String line) {
        synchronized (lock) {
            logs.append(line).append('\n');
            if (logs.length() > 50000) logs.delete(0, logs.indexOf("\n", logs.length() - 40000) + 1);
        }
    }
    private void cancel() {
        cancelled = true;
        File file = stop;
        if (file != null) try { file.createNewFile(); } catch (Exception ignored) { }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == 16777115) { // Shizuku 的销毁通知
            cancel();
            new Thread(() -> {
                long deadline = SystemClock.elapsedRealtime() + 330000;
                while (active && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                System.exit(0);
            }, "nfc-destroy").start();
            return true;
        }
        if (code < 1 || code > 5) return super.onTransact(code, data, reply, flags);
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Wrong caller");
        data.enforceInterface(INTERFACE);
        if (code == 4) { reply.writeNoException(); reply.writeInt(android.os.Process.myUid()); return true; }
        if (code == 5) {
            // 只做一件事：在 shell 身份下把 Q-Poll 开关发出去。
            // 为什么必须借 shell：Android 16+ 把这条厂商指令锁成 WRITE_SECURE_SETTINGS，
            // 普通 App 没有；而 shell 天然有，且这里不受「非 SDK 接口限制」管。
            int action = data.readInt();
            reply.writeNoException();
            try { reply.writeInt(qtag(action)); }
            catch (Throwable e) { log("QP|QTAG_SHELL_ERROR|" + e); reply.writeInt(-1); }
            return true;
        }
        if (code == 1) {
            String mode = data.readString();
            int seconds = data.readInt();
            String json = data.readString();
            byte[] image = data.createByteArray();
            IBinder owner = data.readStrongBinder();
            if (mode == null || !mode.matches("identity|refresh") || seconds < 1 || seconds > 60
                    || json == null || json.length() > 8192 || image == null
                    || image.length > PanelProtocol.IMAGE_BYTES || owner == null)
                throw new IllegalArgumentException("Invalid NFC session");
            synchronized (lock) {
                if (active) throw new IllegalStateException("另一轮刷新尚未结束");
                active = true;
                cancelled = false;
                logs.setLength(0);
            }
            IBinder.DeathRecipient death = this::cancel;
            try { owner.linkToDeath(death, 0); } catch (RemoteException e) { active = false; throw e; }
            new Thread(() -> {
                try { run(mode, seconds, json, image); }
                catch (Exception e) { log("QP|ERROR|" + e); }
                finally {
                    owner.unlinkToDeath(death, 0);
                    synchronized (lock) { logs.append("QP|BRIDGE_END|后端会话结束\n"); active = false; }
                }
            }, "nfc-session").start();
            reply.writeNoException();
            return true;
        }
        if (code == 2) {
            synchronized (lock) {
                reply.writeNoException();
                reply.writeString(logs.toString());
                logs.setLength(0);
                reply.writeInt(active ? 1 : 0);
            }
            return true;
        }
        cancel();
        reply.writeNoException();
        return true;
    }

    private Object nfcService;
    private Class<?> nfcType;

    /** 在 shell 身份下调用 NXP 私有的 Q-Poll 开关；返回 NFC 服务的原始返回值，0 = 接受。 */
    private int qtag(int action) throws Exception {
        if (nfcService == null) {
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) throw new IllegalStateException("没有 NFC 服务");
            nfcType = Class.forName("android.nfc.INfcAdapter");
            nfcService = Class.forName("android.nfc.INfcAdapter$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        }
        java.lang.reflect.Method method = nfcType.getMethod("sendVendorNciMessage",
                int.class, int.class, int.class, byte[].class);
        Object result = method.invoke(nfcService, 1, 15, 112, new byte[]{0x31, (byte) action});
        return result instanceof Integer ? (Integer) result : -1;
    }

    private void run(String mode, int seconds, String json, byte[] bytes) throws Exception {
        File lease = new File("/data/local/tmp/qpoll-lite-session.lock");
        if (!lease.getCanonicalFile().equals(lease)) throw new IOException("Invalid session lock path");
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lease.toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (android.os.Process.myUid() == 0) {
                checked("chown", "2000:2000", lease.toString());
                checked("chcon", "u:object_r:shell_data_file:s0", lease.toString());
            }
            checked("chmod", "600", lease.toString());
            try (java.nio.channels.FileLock session = channel.tryLock()) {
                if (session == null) throw new IOException("另一轮刷新尚未结束");
                File workerLease = new File("/data/local/tmp/qpoll-lite-worker.lock");
                if (!workerLease.getCanonicalFile().equals(workerLease)) throw new IOException("Invalid worker lock path");
                try (java.nio.channels.FileChannel workerChannel = java.nio.channels.FileChannel.open(workerLease.toPath(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (android.os.Process.myUid() == 0) {
                        checked("chown", "2000:2000", workerLease.toString());
                        checked("chcon", "u:object_r:shell_data_file:s0", workerLease.toString());
                    }
                    checked("chmod", "600", workerLease.toString());
                    // worker 可能比后端活得久，绝不能在这时候再起第二个
                    try (java.nio.channels.FileLock orphan = workerChannel.tryLock()) {
                        if (orphan == null) throw new IOException("上次的 NFC 进程还在跑，请等它结束");
                    }
                    runLocked(mode, seconds, json, bytes);
                }
            }
        }
    }

    private void runLocked(String mode, int seconds, String json, byte[] bytes) throws Exception {
        final int uid = android.os.Process.myUid();
        JSONObject settings = new JSONObject(json);
        BackendPolicy.validate(uid, mode);
        log("QP|BRIDGE_STATE|Shizuku UID=" + uid + "；worker UID=2000");
        File dir = new File("/data/local/tmp/qpoll-lite-" + android.os.Process.myPid() + "-" + System.nanoTime());
        if (!dir.mkdir()) throw new IOException("Cannot create session directory");
        File mark = new File(dir, "stop"), job = new File(dir, "job.json"), image = new File(dir, "image.bin");
        stop = mark;
        java.lang.Process child = null;
        try {
            checked("chmod", "700", dir.toString());
            if (cancelled) { log("QP|CANCELLED|已停止，未启动 NFC 进程"); return; }
            if (bytes.length > 0) settings.put("imageFile", image.toString());
            try (FileOutputStream out = new FileOutputStream(image)) { out.write(bytes); }
            try (FileOutputStream out = new FileOutputStream(job)) { out.write(settings.toString().getBytes("UTF-8")); }
            if (cancelled) { log("QP|CANCELLED|已停止，未启动 NFC 进程"); return; }
            if (uid == 0) {
                if (!new File("/system/bin/runcon").canExecute())
                    throw new IOException("Root 后端需要 runcon 切换到 shell SELinux 域");
                checked("chown", "-R", "2000:2000", dir.toString());
                checked("chcon", "-R", "u:object_r:shell_data_file:s0", dir.toString());
            }
            java.util.List<String> command = BackendPolicy.workerCommand(uid, apkPath(), mode, seconds,
                    mark.toString(), "/data/local/tmp/qpoll-lite-worker.lock", job.toString());
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("CLASSPATH", apkPath());
            child = builder.start();
            final java.lang.Process running = child;
            Thread deadline = new Thread(() -> {
                try {
                    if (!running.waitFor(320, TimeUnit.SECONDS)) {
                        mark.createNewFile();
                        if (!running.waitFor(10, TimeUnit.SECONDS)) running.destroy();
                    }
                } catch (Exception ignored) { }
            });
            deadline.setDaemon(true);
            deadline.start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = in.readLine()) != null) log(line);
            }
            int exit = child.waitFor();
            if (exit != 0) log("QP|PROCESS_EXIT|" + exit);
        } finally {
            try {
                mark.createNewFile();
                if (child != null && !child.waitFor(10, TimeUnit.SECONDS)) {
                    child.destroy();
                    if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
                    log("QP|RESTORE_FAILED|NFC 进程未正常结束，请关闭再开启 NFC");
                }
            } finally {
                stop = null;
                job.delete();
                image.delete();
                mark.delete();
                dir.delete();
            }
        }
    }

    private static void checked(String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("权限准备超时：" + command[0]);
        }
        if (process.exitValue() != 0) throw new IOException("权限准备失败：" + command[0]);
    }
}
