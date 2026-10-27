package com.qpoll.lite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 权限决策：谁能跑 NFC worker，以及用什么身份跑。
 *
 * <p>这里是整个「免 root」方案的关键。Shizuku 用无线调试（ADB）启动时自己就是 shell，
 * 它拉起来的后端 UID=2000；后端再用 {@code app_process} 把 worker 也拉成 UID=2000 +
 * {@code u:r:shell:s0}。系统只认 shell 身份能发 Q-Poll 寻卡（{@code sendVendorNciMessage}
 * 是系统签名级接口，普通 App 够不着），所以<b>只要 Shizuku 走 ADB，root 就可以完全不装</b>。
 *
 * <p>唯一不能省的是「身份必须是 shell」：worker 里有一段硬门禁，UID 或 SELinux 域不对就直接拒绝，
 * 宁可不干活也不拿 root 身份去碰 NFC——root 身份反而会被 NFC 服务拒。
 */
public final class BackendPolicy {
    private BackendPolicy() {}

    /** 允许的会话类型：写图 / 只读检查 / 兼容性自检。 */
    private static final List<String> MODES = Arrays.asList("identity", "refresh", "probe");

    public static void validate(int uid, String mode) {
        if (uid != 0 && uid != 2000) throw new SecurityException("请先连接 Shizuku（以无线调试或 Root 启动）");
        if (!MODES.contains(mode)) throw new IllegalArgumentException("未知的会话类型：" + mode);
    }

    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    /**
     * 拼出 worker 的启动命令行。
     *
     * <p>UID 2000 时直接用 app_process；UID 0 时（Shizuku 以 root 启动）必须同时降 UID 和切
     * SELinux 域，两件事都不做就等于用 root 去碰 NFC，会被服务端挡回来。
     */
    public static List<String> workerCommand(int uid, String apk, String mode, int seconds,
            String stop, String lock, String job) {
        validate(uid, mode);
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("时长超出允许范围");
        List<String> args = new ArrayList<>(Arrays.asList("/system/bin/app_process", "/system/bin",
                "com.qpoll.lite.QPollWorker", mode, "" + seconds, stop, lock, job));
        if (uid == 2000) return args;
        StringBuilder script = new StringBuilder("export CLASSPATH=").append(quote(apk)).append("; exec");
        for (String arg : args) script.append(' ').append(quote(arg));
        // 身份和 SELinux 域都得换。任何情况下都不回退到 UID 0。
        return Arrays.asList("su", "2000", "-c",
                "exec /system/bin/runcon u:r:shell:s0 /system/bin/sh -c " + quote(script.toString()));
    }
}
