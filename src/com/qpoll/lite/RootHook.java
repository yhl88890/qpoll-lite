package com.qpoll.lite;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;

/**
 * 自动兼容层：给需要改写 HAL 的机型（如 Redmi Note 12T Pro / SN100U）准备 hook。
 *
 * <p>为什么需要它：有些机型的 NFC HAL 收到 Q-Poll 指令会返回 OK，但发出去的发现类型
 * 仍然是 {@code 00}（普通 NFC-A），而墨水屏只应答 {@code 71}。这种机型光靠 App 层
 * 无论给多大权限都没用，必须在 HAL 里把出站包的 {@code 00} 改成 {@code 71}。
 *
 * <p>原生支持 Q-Poll 的机型（如 K90 Pro Max / SN220U）完全不需要这一套，
 * 没有 root 也能刷，所以 {@link #ensureReady} 在没 root 时只是安静地返回 false。
 *
 * <p>安全设计沿用旧工程：注入器只接受 {@code /data/vendor/nfc/qpolllab/} 下的库、
 * 必须 root 所有且不允许组/其他用户写；hook 还要一个 6 分钟内新鲜的租约文件才生效，
 * 刷完就删。这样 hook 的生效窗口只有一次刷新的几十秒，不会长期干扰公交卡、门禁卡。
 */
public final class RootHook {
    private static final String DIR = "/data/vendor/nfc/qpolllab";
    private static final String LIB_NAME = "libqpollhook.so";
    private static final String INJECT_NAME = "qpoll-inject";
    /** 这两个是「本轮要用的那个文件」的绝对路径，名字里带内容哈希，随版本变化。 */
    private static String LIB = DIR + "/" + LIB_NAME;
    private static String INJECT = DIR + "/" + INJECT_NAME;
    private static final String LEASE = DIR + "/enable";
    private static final String ASSET_DIR = "native/arm64-v8a/";

    public interface Sink { void report(String line); }

    private RootHook() { }

    /** 有没有 root：能拿到 uid=0 才算。首次调用可能弹授权框。 */
    public static boolean hasRoot() {
        return rootProbe().contains("uid=0");
    }

    /** 探一次 su，把它的原样输出交出来——失败时这串就是唯一线索。 */
    public static String rootProbe() {
        return exec("id").trim();
    }

    /**
     * 部署兼容层、注入 HAL、打开租约。整段要跑在后台线程（su 可能弹框、注入要一两秒）。
     *
     * @return true = 兼容层真的就绪；false = 没 root 或这台机器不需要/不支持
     */
    public static boolean ensureReady(Context context, Sink sink, boolean explainNoRoot) {
        String probe = rootProbe();
        if (!probe.contains("uid=0")) {
            // 两种情况要分开说，不然用户根本不知道下一步该干嘛：
            //   ENOENT = root 管理器的「隐藏 root」在起作用，未授权的 App 连 su 文件都看不见
            //   其它   = su 存在但被拒绝，或者 root 已经没了
            // 给用户看的文案里不提 su、也不写死管理器名字——各家叫法都不一样，
            // 统一说「root 管理器」，用户自己就知道是哪个 App。
            if (!explainNoRoot) {
                // 刷图时没 root 不是错：K90 这类原生支持 Q-Poll 的机器本来就不用 root。
                // 这种情况安静地按「原生支持」往下走，别吓用户。
                sink.report("没有 root 权限，按「原生支持」模式直接试（很多机器本来就不需要 root）");
                return false;
            }
            if (probe.contains("No such file") || probe.isEmpty())
                sink.report("这台手机要刷图需要 root 权限，现在还没授权。请打开你手机里的"
                        + "「root 管理器」（平时管 root 权限的那个 App）→ 进「超级用户」或"
                        + "「授权管理」→ 把「墨小七」勾成允许；列表里没有就点角落的「+」手动添加。"
                        + "授权完回来再点一次发送就行。");
            else
                sink.report("root 权限被拒绝了。请打开手机里的「root 管理器」→"
                        + "「超级用户」→ 把「墨小七」改成允许，然后重试。");
            return false;
        }
        if (!deploy(context, sink)) return false;

        // 先看库是不是已经在 HAL 里了。已经在，就只续租约——重复注入既慢，
        // 又会让 HAL 里多挂一份同样的库。
        // 两个路径都要查：早期版本用的是不带哈希的文件名，那时候起就一直映射着，
        // 只查新名字会误判成「没挂」而重复注入。
        String mapped = null;
        for (String candidate : new String[]{LIB, DIR + "/" + LIB_NAME}) {
            if (exec(INJECT + " " + candidate + " --status").contains("stable=")) {
                mapped = candidate;
                break;
            }
        }
        if (mapped == null) {
            String injected = exec(INJECT + " " + LIB);
            if (!injected.contains("HAL adapter verified")) {
                // 找不到目标 HAL 是正常的（原生支持机型、或非 NXP 芯片），不算错误。
                sink.report("兼容层：这次没注入进去（" + firstLine(injected) + "）。"
                        + "如果反复失败，关一次 NFC 再开，然后重试。");
                return false;
            }
        } else {
            LIB = mapped;   // 后面续租约、查状态都以真正挂着的那份为准
        }

        if (!renewLease()) {
            sink.report("兼容层：生效开关打不开，请重启一次 NFC 再试");
            return false;
        }
        sink.report("兼容层：就绪（" + (mapped != null ? "HAL 里已挂着，直接复用" : "刚刚注入 HAL") + "）");
        return true;
    }

    /**
     * 把库和注入器放到 HAL 目录下。
     *
     * <p><b>文件名必须带内容哈希</b>：HAL 一旦把 .so 映射进去，直接覆盖同一个文件
     * 会写坏它的内存映射，HAL 当场崩溃重启、hook 全丢——这正是「第一次成、第二次不成」
     * 的原因。改用「哈希-原名」，内容变了就是新文件，旧文件原地不动。
     */
    private static boolean deploy(Context context, Sink sink) {
        File staging = new File(context.getCacheDir(), "hook");
        if (!staging.isDirectory() && !staging.mkdirs()) {
            sink.report("兼容层：临时目录创建失败");
            return false;
        }
        String libName = stage(context, "libqpollhook.so", staging);
        String injectName = stage(context, "qpoll-inject", staging);
        if (libName == null || injectName == null) {
            sink.report("兼容层：从安装包里解出文件失败");
            return false;
        }
        LIB = DIR + "/" + libName;
        INJECT = DIR + "/" + injectName;

        String libTmp = new File(staging, libName).getAbsolutePath();
        String injectTmp = new File(staging, injectName).getAbsolutePath();
        // 一次 su 搞定：不存在才拷（原子 mv 落位），然后修属主/权限/SELinux 标签。
        // 标签必须是 vendor_file，否则从应用这边执行会被系统拦下。
        String cmd = "mkdir -p " + DIR
                + " ; chcon u:object_r:vendor_file:s0 " + DIR + " 2>/dev/null"
                + " ; if [ ! -f " + LIB + " ] ; then cp " + libTmp + " " + LIB + " && chown root:root " + LIB
                + " && chmod 0755 " + LIB + " && chcon u:object_r:vendor_file:s0 " + LIB + " 2>/dev/null ; fi"
                + " ; if [ ! -f " + INJECT + " ] ; then cp " + injectTmp + " " + INJECT + " && chown root:root " + INJECT
                + " && chmod 0755 " + INJECT + " && chcon u:object_r:vendor_file:s0 " + INJECT + " 2>/dev/null ; fi"
                + " ; ls " + LIB + " " + INJECT;
        String out = exec(cmd);
        if (out.contains("denied") || out.contains("No such file")) {
            sink.report("兼容层：放不进系统目录，权限被拒绝了。请确认 root 授权是真的生效了。");
            return false;
        }
        return true;
    }

    /** 把 asset 解到缓存目录，返回「哈希-原名」；失败返回 null。 */
    private static String stage(Context context, String asset, File staging) {
        try (InputStream in = context.getAssets().open(ASSET_DIR + asset)) {
            byte[] data = readAll(in);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(data)) hex.append(String.format("%02x", b));
            String name = hex + "-" + asset;
            File target = new File(staging, name);
            if (!target.exists() || target.length() != data.length) {
                try (FileOutputStream out = new FileOutputStream(target)) { out.write(data); }
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** 续租约。hook 只在租约文件 6 分钟内新鲜时生效，所以每轮刷新前都要续一次。 */
    public static boolean renewLease() {
        String out = exec("touch " + LEASE + " ; chown root:root " + LEASE + " ; chmod 0644 " + LEASE
                + " ; test -f " + LEASE + " && echo LEASE_OK");
        return out.contains("LEASE_OK");
    }

    /** 刷完就撤租约，让 NFC 立刻恢复正常（不然 hook 会一直把 NFC-A 改成 Q-Poll）。 */
    public static void releaseLease() {
        exec("rm -f " + LEASE);
    }

    private static String firstLine(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "无输出";
        int end = trimmed.indexOf('\n');
        return end < 0 ? trimmed : trimmed.substring(0, end);
    }

    /** 各家 root 方案的 su 位置不一样，逐个试；未授权时这些路径会统一表现为「文件不存在」。 */
    private static final String[] SU_BINARIES = {
            "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su", "su"
    };

    /** 给其他模块的 root 命令入口（走同一条 su 探测链）。返回里带 "No such" 开头 = 没有可用 su。 */
    public static String runAsRoot(String command) { return exec(command); }

    private static String exec(String command) {
        boolean ranAny = false;
        for (String binary : SU_BINARIES) {
            String out = execOnce(binary, command);
            if (out == null) continue;      // 这个路径起不来，试下一个
            ranAny = true;
            if (!out.trim().isEmpty()) return out;
            // 有输出才算数；空输出继续试下一个路径
        }
        return ranAny ? "" : "No such file or directory（su 对所有路径都不可见）";
    }

    /** @return null 表示进程没能启动（路径不存在/无权限），非 null 是它的输出 */
    private static String execOnce(String binary, String command) {
        StringBuilder out = new StringBuilder();
        Process process = null;
        try {
            process = new ProcessBuilder(binary, "-c", command)
                    .redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) out.append(line).append('\n');
            }
            process.waitFor();
            return out.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }
}
