package com.qpoll.lite;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台直连会话：不起独立进程、不经过 Shizuku，整条链路都在 App 自己的进程里跑。
 *
 * <p><b>为什么必须在前台</b>：部分 ROM（实测 Redmi Note 12T Pro 的 HyperOS）会对
 * 「非前台调用方」发出的 reader-mode 请求<b>静默拒绝</b>——不报错、不抛异常，
 * 就是永远收不到标签。旧工程把这套做法叫「前台直连模式」，原版能刷靠的就是它。
 * K90 不挑这个，所以之前用独立 worker 进程也能收到，把我误导了很久。
 *
 * <p>职责很窄：开 Q-Poll → 开 ReaderMode 等标签 → 把 Tag 交给 {@link PanelSession}
 * → 收尾关场。所有 APDU 逻辑都在 PanelSession 里，这里不碰协议。
 */
public final class FrontSession {
    /**
     * 设备画像：这台机器的屏刷完之后「装死」吗（需要断场才能复位/充电）？
     *
     * <p>1.4.8 定稿的双路分支——12T（Android 15 / SN100U）的屏刷完活着，收尾保持
     * 场就行；K90（Android 17 / SN220U）的屏刷完装死，必须每轮收尾把场整个断掉，
     * 给墨水屏壳（无电池、靠场充电）留出回血窗口，否则第二张起 D4 波形断供
     * 「只闪一下」。两台各自的行为都有完整实测背书（见下面两路注释），从此
     * 一台的动作不再牵连另一台。
     *
     * <p>分支依据用 SDK_INT（35=12T 一族活屏，36+ 一族装死屏）：以后新机型插上
     * 自动归路，不需要按型号改代码。若未来出现例外机型，再把判定换成实测探针。
     */
    private static final boolean DEAD_SCREEN_AFTER_REFRESH =
            android.os.Build.VERSION.SDK_INT >= 36;

    /**
     * 在场探测延时（毫秒）——两台统一 90000，都有台账铁证：
     *
     * <ul>
     *   <li>12T：1.4.2-1.4.4 全程 90000，连刷 6+ 张完整波形。</li>
     *   <li>K90：1.3.3 于 18:37 用 90000「整屏连续闪，刷成了」（台账 18:37 行，
     *       与 1.2.6 的 null 同图同 SHA 对照）。21:19 的「90000 静默不注册」只发生
     *       在 worker 的底层 shell 接口上，不适用于前台公开 API——1.4.6 把它错误
     *       推广到 K90 前台路，从 1.4.6 起 K90 的 presence 一直是错的。</li>
     * </ul>
     */
    private static final int PRESENCE_DELAY_MS = 90000;

    public interface Events { void emit(String kind, String message); }

    private final Activity activity;
    private final NfcAdapter adapter;
    private final String mode;
    private final File jobFile;
    private final File stopFile;
    private final Events events;
    private final Runnable onDone;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile boolean readerOn;
    private volatile Thread runner;
    /** 最近一次有动静的时间与阶段，静默看门狗靠它判断是不是卡死了。 */
    private volatile long lastSignal = System.currentTimeMillis();
    private volatile String lastKind = "";

    private FrontSession(Activity activity, NfcAdapter adapter, String mode,
                         File jobFile, File stopFile, Events events, Runnable onDone) {
        this.activity = activity;
        this.adapter = adapter;
        this.mode = mode;
        this.jobFile = jobFile;
        this.stopFile = stopFile;
        this.events = events;
        this.onDone = onDone;
    }

    /**
     * 启动一轮。必须从主线程调用。
     *
     * @param scanSeconds 等标签的秒数，超时即结束
     */
    public static void start(Activity activity, String mode, File jobFile, File stopFile,
                             int scanSeconds, Events events, Runnable onDone) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null) {
            events.emit("ERROR", "这台机器没有 NFC 硬件");
            if (onDone != null) onDone.run();
            return;
        }
        FrontSession s = new FrontSession(activity, adapter, mode, jobFile, stopFile, events, onDone);
        // 2026-10-26 22:07 定稿：不再做「NFC 开关重配」——svc 开关实测救不了第二张
        //（22:05 root 手动开关后照样寻不到，假设已推翻）。真正的规律在 RF 层日志里：
        //   发现配置 = 单项 71 → 必成；= 6 项混合（71+常规轮询）→ 必败。
        // 挪屏之所以有效，是它触发了把发现收窄成单项 71 的重配。
        // App 侧主动做这件事：enableReaderMode 之后调 updateDiscoveryTechnology(0x71)。
        s.main.post(() -> s.begin(scanSeconds));
    }

    /**
     * 把系统的发现配置收窄成「单项 0x71」——治「第二轮寻不到、必须把屏挪一下」。
     *
     * <p>2026-10-26 22:07 从 RF 层日志钉死的规律：
     * <pre>
     *   发现 = 2103 03 01 71（单项 Q-Poll）        → TAG 85ms 内就到，必成
     *   发现 = 2103 0D 06 71 01 01 02 03 80 06（6 项混合轮询）→ 屏跟不上，必败
     * </pre>
     * 挪屏之所以救场，是它恰好触发了把发现收窄成单项 71 的重配；不挪屏时系统保持
     * 6 项混合，Q-Poll 的场窗太稀，刚刷完、储能见底的屏根本等不到自己那一片。
     *
     * <p>收窄的正规入口是 {@code INfcAdapter.updateDiscoveryTechnology(IBinder, 0x71, ...)}
     * （原版探针分支用的同一个接口）。token 拿不到 reader mode 里那枚，先传自有 Binder 试；
     * 被系统拒绝就把异常原样打出来，别静默——这一天最贵的教训就是静默失败。
     */
    private void narrowDiscoveryToQPoll() {
        java.lang.reflect.Method upd = null;
        Object service;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) { signal("DISCOVERY_SKIP", "NFC 服务不在"); return; }
            service = Class.forName("android.nfc.INfcAdapter$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            for (java.lang.reflect.Method m : service.getClass().getMethods()) {
                if (m.getName().equals("updateDiscoveryTechnology")) { upd = m; break; }
            }
            if (upd == null) {
                signal("DISCOVERY_SKIP", "系统没有 updateDiscoveryTechnology 接口，保持默认发现");
                return;
            }
        } catch (Throwable e) {
            signal("DISCOVERY_SKIP", "拿不到发现配置接口：" + e);
            return;
        }
        try {
            IBinder token = new android.os.Binder();
            int n = upd.getParameterCount();
            Object result;
            if (n >= 3) result = upd.invoke(service,
                    java.util.Arrays.copyOfRange(new Object[]{token, 0x71, 0, null}, 0, n));
            else result = upd.invoke(service, token, 0x71);
            signal("DISCOVERY", "发现已请求收窄到单项 Q-Poll（0x71），返回=" + result);
        } catch (Throwable e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            signal("DISCOVERY_SKIP", "收窄发现被系统拒绝：" + cause + "（按默认配置继续）");
        }
    }

    private void begin(int scanSeconds) {
        if (!adapter.isEnabled()) {
            signal("ERROR", "NFC 关着，请先在系统设置里打开");
            finish("没开始");
            return;
        }
        signal("START", mode + ":" + scanSeconds);
        readerOn = true;
        if (DEAD_SCREEN_AFTER_REFRESH) {
            /*
             * 1.4.11 定案：装死屏（K90 大线圈）D4 之后必须「干睡」——不能发 DE 喂场。
             * 1.3.3（干睡 20 秒、DE 发 0 次）能刷，1.4.5~1.4.10（喂 DE ~40 次）全部
             * 「只闪一下」；装回 1.3.3 立即复活 + dex 反编译坐实。屏跑刷新波形时被
             * DE 一次次打断，波形跑不完整。12T 小线圈相反，必须喂——见 PanelProtocol。
             */
            PanelProtocol.holdKeepAlive = false;
            /*
             * 【装死屏路】K90（Android 16+）——完全复刻 1.3.x worker 的成功顺序：
             *   qtag(1) 先把 Q-Poll 场起起来（此时 reader mode 是关闭的——上一轮
             *   finish 已全断），然后才 enableReaderMode 收屏。
             *
             * ⚠️ 顺序是 K90 的命门（1.4.4-1.4.8 五版日志交叉钉死）：reader 开着的
             * 状态下发 qtag(1)，场起不来——发现层直接失效（1.4.8 冷启动 10 秒零发现，
             * 而当时 QTag 恰好被 qtag(0) 关过、需要「关着 reader 重新开」）。
             * worker 时代永远「先场后 reader」，从没踩过这个坑。
             */
            int code = qtag(0x01);
            signal("QTAG_ON", "Q-Poll 开关已发出，返回 " + code
                    + (code == 0 ? "（接受）" : "（被拒，本轮可能找不到屏）"));
            try {
                adapter.enableReaderMode(activity, this::onTag,
                        READER_FLAGS, readerOptions());
                signal("READER_ON", "前台寻卡已开启（Q-Poll 场先行），等 " + scanSeconds + " 秒");
            } catch (Throwable e) {
                signal("ERROR", "开前台寻卡失败：" + e);
                finish("没开始");
                return;
            }
        } else {
            /*
             * 【活屏路】12T（Android 15）——1.4.2-1.4.6 验证过的完整循环：
             *   踢遗留（disable+enable 同步连发，绕开 connected 防抖）→ qtag →
             *   发现收窄（narrowDiscovery 成功，Q-Poll 配置落地双保险）。
             */
            try {
                adapter.disableReaderMode(activity);
                adapter.enableReaderMode(activity, this::onTag,
                        READER_FLAGS, readerOptions());
                signal("READER_ON", "前台寻卡已开启（踢遗留 + 即时重开），等 " + scanSeconds + " 秒");
            } catch (Throwable e) {
                signal("ERROR", "开前台寻卡失败：" + e);
                finish("没开始");
                return;
            }
            int code = qtag(0x01);
            signal("QTAG_ON", "Q-Poll 开关已发出，返回 " + code
                    + (code == 0 ? "（接受）" : "（被拒，本轮可能找不到屏）"));
            narrowDiscoveryToQPoll();
        }
        main.postDelayed(() -> {
            if (finished.get()) return;
            signal("RESULT", scanSeconds + " 秒内没收到标签。要么没贴到线圈上，"
                    + "要么这台机器还得靠 Shizuku（不用 root）或 root 兼容层才发得出 Q-Poll。");
            finish("没找到屏幕");
        }, scanSeconds * 1000L);
    }

    /** enableReaderMode 的公共参数：只听 Nfc-A、跳过 NDEF、在场探测延时 90 秒。 */
    private android.os.Bundle readerOptions() {
        android.os.Bundle ro = new android.os.Bundle();
        ro.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_DELAY_MS);
        return ro;
    }

    /** ReaderMode 回调，跑在主线程——只做搬运，绝不在这里跑几十秒的 APDU。 */
    private void onTag(Tag tag) {
        // 1.4.2：任何时刻收到的标签都记下来（含空闲期）——下一轮开刷的「踢一脚」
        // 本来就会让屏重新被发现，缓存只作诊断与快路径备料，不参与决策。
        lastTag = tag;
        lastTagAt = System.currentTimeMillis();
        if (finished.get() || runner != null) {
            idleTags++;   // 空闲期屏贴着被反复发现：只计数、静默丢弃，防「tag connected」
            return;
        }
        // 关键：收到标签就把「等标签超时」那个定时器撤掉。
        // 否则它会在传图中途（十几秒）触发，把整轮会话连 reader mode 一起关掉，
        // 现象就是 D3 传到一半 transceive failed。
        main.removeCallbacksAndMessages(null);
        StringBuilder uid = new StringBuilder();
        for (byte b : tag.getId()) uid.append(String.format("%02X", b & 255));
        signal("TAG", "收到标签 UID=" + uid);
        runner = new Thread(() -> {
            try {
                // PanelSession 跑在后台线程，但它发事件时会直接去改界面控件。
                // 必须转回主线程，否则抛 CalledFromWrongThreadException（实测会打断传图）。
                PanelSession.run(tag, mode, jobFile, stopFile,
                        (kind, message) -> main.post(() -> signal(kind, message)));
                if (mode.equals("identity")) main.post(() -> signal("RESULT", "D1 屏幕规格校验通过，未写图像"));
            } catch (Throwable e) {
                main.post(() -> signal("ERROR", e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                finish("本轮结束");
            }
        }, "front-panel");
        runner.start();
        // 看门狗必须在 removeCallbacksAndMessages 之后再挂，否则会被一起撤掉。
        main.postDelayed(watchdog, 3000);
    }

    /**
     * 静默看门狗。
     *
     * <p><b>为什么必须有</b>：NFC 的 {@code transceive} 一旦底层会话没了（比如场被关掉、
     * 标签离开），它不会抛异常，而是<b>永远阻塞</b>——实测表现为进度停在某个百分比不动，
     * 界面像死机，用户只能强杀。唯一能把它拉出来的办法是主动关 reader mode，
     * 让那次调用以失败返回。所以这里盯着「多久没动静」，超了就强制收场。
     */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (finished.get()) return;
            long quiet = System.currentTimeMillis() - lastSignal;
            // 传图/保场阶段每秒都有事件，静默 12 秒肯定是卡住了。
            // D4 上电刷新本身可能合法地静默二十多秒，所以阈值放宽。
            boolean transferring = "PROGRESS".equals(lastKind) || "HOLD".equals(lastKind)
                    || "STAGE".equals(lastKind);
            long limit = transferring ? 12000 : 40000;
            if (quiet > limit) {
                signal("ERROR", "通信卡住了（" + (quiet / 1000) + " 秒没有任何回应）。"
                        + "通常是贴合松了或者屏被挪动。已强制结束本轮，重新贴稳再刷一次就行。");
                finish("通信超时");
                return;
            }
            main.postDelayed(this, 2000);
        }
    };

    private void signal(String kind, String message) {
        lastSignal = System.currentTimeMillis();
        lastKind = kind;
        events.emit(kind, message);
    }

    /**
     * 收尾。⚠️ 1.4.3 定稿：收尾「软踢」——reader mode 全程不关，只断标签连接。
     *
     * <p>演变史（全是实测学费）：
     * <ul>
     *   <li>1.3.x：收尾关 reader → 屏被系统普通派发抓走（叮咚）→ tag connected 死锁
     *       → 第二张必须挪屏。</li>
     *   <li>1.4.0：什么都不关 → 连接挂着 → 控制器彻底退出轮询 → 挪屏都没人理。</li>
     *   <li>1.4.1/1.4.2：双跳（disable→10ms→enable）→ 功能上通了（下一张 begin 的
     *       踢会清场），但 62ms 空窗里屏稳定被普通派发抢一把 → 每张刷完叮一声。</li>
     *   <li>1.4.3：对当前标签 connect+close（软踢）——连接断了、轮询恢复、reader
     *       路由不动 → 屏的重发现走 reader 回调（系统对 reader 回调不出提示音），
     *       叮咚消失。与 begin 的软踢是同一招。</li>
     * </ul>
     */
    private void finish(String reason) {
        if (!finished.compareAndSet(false, true)) return;
        main.removeCallbacksAndMessages(null);
        // 收尾可能从 PanelSession 的后台线程进来，UI 动作必须切回主线程。
        main.post(() -> {
            signal("END", reason);
            if (onDone != null) onDone.run();
            /*
             * 1.4.8 收尾：按设备画像走两路（两路行为都有完整实测背书）。
             *
             * 【活屏路】12T（Android 15）：踢遗留 + 同步重开，场保持——12T 的屏刷完
             *   活着，保持轮询就没有 1.3.x 的「tag connected」死锁（1.4.2-1.4.6 验证）。
             * 【装死屏路】K90（Android 16+）：全部断掉（reader + QTag），场整个落下
             *   ——墨水屏壳没有电池、全靠场充电；场一直开着屏就回不了血，第二张起
             *   D4 波形断供「只闪一下」。1.3.x 每轮全断 + 用户点下一张的间隙回血，
             *   从没坏过。下一张 begin 会把一切都重新抬起来。
             */
            try {
                adapter.disableReaderMode(activity);
                if (DEAD_SCREEN_AFTER_REFRESH) {
                    qtag(0);
                    signal("READER_OFF", "场已全部断开（装死屏充电窗口），点下一张自动重启");
                } else {
                    adapter.enableReaderMode(activity, this::onTag, READER_FLAGS, readerOptions());
                    signal("READER_KEEP_ON", "本轮结束，寻卡保持中，屏贴上即可识别下一张");
                }
            } catch (Throwable e) {
                signal("ERROR", "收尾重开寻卡失败：" + e);
            }
        });
    }

    /** 空闲期（不在刷图）屏被 reader 轮询发现的次数，只作诊断计数，不打印。 */
    private static volatile int idleTags;

    /** 1.4.2：最近一次发现的标签缓存（static 跨轮共享，只作诊断，不参与决策）。 */
    private static volatile Tag lastTag;
    private static volatile long lastTagAt;

    /** enableReaderMode 的公共 flags：只听 Nfc-A、跳过 NDEF 检测。 */
    private static final int READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    /** 页面退出时的完整清理：关 reader mode、关 QTag。只该从主线程调一次。 */
    public static void teardown(Activity activity) {
        try {
            NfcAdapter.getDefaultAdapter(activity).disableReaderMode(activity);
        } catch (Throwable ignored) { }
        // 关 Q-Poll 开关：sendVendorNciMessage(1,15,112,{0x31,0})
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            Object service = Class.forName("android.nfc.INfcAdapter$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            for (java.lang.reflect.Method m : service.getClass().getMethods()) {
                if (m.getName().equals("sendVendorNciMessage") && m.getParameterTypes().length == 4) {
                    m.invoke(service, 1, 15, 112, new byte[]{0x31, 0});
                    break;
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 用户点「停止」：只写停止标记，让 PanelSession 自己收尾，不粗暴断场。 */
    public void requestStop() {
        try { if (stopFile != null) stopFile.createNewFile(); } catch (Exception ignored) { }
    }

    // ─────────────────────────── Q-Poll 开关 ───────────────────────────

    /**
     * 发 Q-Poll 开关。三条路，按「代价从小到大」试：
     *
     * <ol>
     *   <li><b>App 自己的身份</b>，走 Android 16+ 公开的 {@code NfcAdapter.sendVendorNciMessage}
     *       ——需要 WRITE_SECURE_SETTINGS，只有被 adb 授过才有</li>
     *   <li><b>App 自己的身份</b>，反射隐藏的 {@code INfcAdapter}——Android 15 及以下走这条，
     *       不需要任何权限（12T Pro 就是靠它）</li>
     *   <li><b>借 Shizuku 的 shell 身份</b>——shell 天生有那条权限，也不受隐藏接口限制，
     *       所以 Android 16/17 上这条最稳（K90 实测）</li>
     * </ol>
     *
     * @return NFC 服务的返回值，0 = 接受；负数 = 这条指令没发成功
     */
    private int qtag(int action) {
        int self = qtagAsSelf(action);
        if (self == 0) return 0;
        int shell = ShizukuQtag.send(action);
        if (shell != ShizukuQtag.UNAVAILABLE) {
            if (shell == 0) signal("QTAG_PATH", "走的是 Shizuku（shell 身份）");
            if (shell != 0 && action == 1)
                signal("QTAG_REJECTED", "借 Shizuku 发 Q-Poll 也失败了，返回 " + shell);
            return shell;
        }
        return self;
    }

    /** 用 App 自己的身份发；不成功返回负数，并把原因抛给界面。 */
    private int qtagAsSelf(int action) {
        // 先试公开方法：Android 16+ 有，但通常要有 WRITE_SECURE_SETTINGS 才放行。
        try {
            Method open = NfcAdapter.class.getMethod(
                    "sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
            Object code = open.invoke(adapter, 1, 15, 112, new byte[]{0x31, (byte) action});
            if (code instanceof Integer && (Integer) code == 0) {
                signal("QTAG_PATH", "走的是公开 API（WRITE_SECURE_SETTINGS 已授权）");
                return 0;
            }
        } catch (NoSuchMethodException ignored) {
            // 老系统没有这个公开方法，正常，往下走
        } catch (Throwable e) {
            // 权限被拒是 Android 16+ 最常见的情况，交给外面用 Shizuku 补，不在这里报错
        }
        // 再试反射隐藏接口：Android 15 及以下靠这条，不需要任何权限。
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) return -1;
            Class<?> stub = Class.forName("android.nfc.INfcAdapter$Stub");
            Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Method target = null;
            for (Method m : service.getClass().getMethods()) {
                if (m.getName().equals("sendVendorNciMessage") && m.getParameterTypes().length == 4) {
                    target = m;
                    break;
                }
            }
            if (target == null) return -1;
            target.setAccessible(true);
            Object code = target.invoke(service, 1, 15, 112, new byte[]{0x31, (byte) action});
            int result = code instanceof Integer ? (Integer) code : -1;
            if (result == 0) signal("QTAG_PATH", "走的是隐藏接口反射（系统没堵这个后门）");
            return result;
        } catch (Throwable e) {
            // 新系统的隐藏接口限制会抛 NoSuchMethodException，静默交给 Shizuku
            return -1;
        }
    }
}
