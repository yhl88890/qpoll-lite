package com.qpoll.lite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/**
 * 单页界面：预览 → 点风格 → 发送。没有页签、没有算法下拉、没有测试图。
 *
 * <p>权限模型是「Shizuku + shell 身份」：Shizuku 只要用无线调试（ADB）启动就够了，
 * 不需要 root。App 本身碰不到 NFC 的私有接口（{@code sendVendorNciMessage} 是系统签名级），
 * 所以真正发寻卡的永远是 Shizuku 拉起来的 worker，身份 UID 2000 / u:r:shell:s0。
 */
public final class MainActivity extends Activity {
    /** 寻卡窗口（秒）。实测 10 秒够把屏贴上去。 */
    private static final int SCAN_SECONDS = 10;
    private static final int[] POWER_TIMES = {500, 1000, 2000, 3000, 4000, 5000, 8000, 12000};
    /**
     * 上电等待默认档：2000 ms。
     *
     * <p>不是墨小佳官方那个 500 ms——实测 500 ms 时 K90 上 D4 连启动都不肯，直接回 68CA；
     * 2000 ms 才过。屏刷新前要先升压，给的时间太短屏直接拒绝。
     */
    private static final int DEFAULT_POWER = 2;
    private static final int[] POLL_TIMES = {0, 10, 20, 50, 100, 200, 300, 400, 500};
    private static final String PREFS = "lite-settings";
    private static final String INTERFACE = "com.qpoll.lite.NfcSession";

    private final Handler main = new Handler();
    private final StringBuilder transcript = new StringBuilder();

    private TextView state, detail;
    private ProgressBar progress;
    private PixelPreview preview;
    private Button send, stopButton;
    private AlertDialog settingsDialog;
    private final ImageView[] thumbs = new ImageView[PanelImage.STYLES.length];
    private final LinearLayout[] styleCells = new LinearLayout[PanelImage.STYLES.length];
    private android.app.Dialog previewDialog;

    /** 已经缩放到 552×768 的像素，换风格时直接复用它，不再重新缩放。 */
    private int[] pixels;
    private Bitmap source;
    private String imageName = "未选择图片";
    private int rotation;
    private int mode;
    private boolean cover = true;
    private boolean letterbox = false;

    private PanelImage.Prepared prepared;
    private volatile boolean busy, preparing, resumed, destroyed;
    private volatile File stopFile;
    private int revision;
    private long scanDeadline;

    private volatile IBinder bridgeBinder;
    private int bridgeUid = -1;
    private boolean binding;

    // ── 本轮会话的事实，用来在结束时给出「能不能刷 / 要不要 root」的结论 ──
    /** 本轮是不是检测（自检），结束时要不要出结论。 */
    private volatile boolean checkSession;
    /** 兼容层这次是真的就绪了没。 */
    private volatile boolean hookReady;
    /** 收到标签了吗。收到 = 屏应答了 Q-Poll。 */
    private volatile boolean tagSeen;
    /** D1 读规格成功了吗。成功 = 链路和屏都是好的。 */
    private volatile boolean identityOk;
    /** 本轮有没有已经给出结论（刷完/失败/中止）。没有就在结束时补一条"中途断了"。 */
    private volatile boolean terminalSeen;
    /** Q-Poll 指令是不是被系统的权限检查拒了（Android 16+ 才有这道坎）。 */
    private volatile boolean qtagDenied;

    /**
     * Shizuku 用户服务的绑定参数。
     *
     * <p><b>{@code version()} 必须跟 versionCode 一起往上加。</b>Shizuku 拿它判断要不要重启
     * 已存在的那份后端进程：版本号不变就复用旧的。而复用的旧进程里可能还揣着上一版
     * （已被卸载的）APK 路径，加载 worker 类时直接 SIGABRT。改一次代码就得抬一次版本号，
     * 这是这套机制唯一的安全阀。
     */
    private final Shizuku.UserServiceArgs bridgeArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.qpoll.lite", "com.qpoll.lite.ShellBridge"))
            .tag("nfc-lite").version(61).daemon(false).processNameSuffix("nfc_shell");

    private final ServiceConnection bridgeConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(INTERFACE);
                if (!binder.transact(4, request, reply, 0)) throw new java.io.IOException("请重新连接新版后端");
                reply.readException();
                bridgeUid = reply.readInt();
                BackendPolicy.validate(bridgeUid, "refresh");
                bridgeBinder = binder;
                ShizukuQtag.attach(binder);   // 前台会话靠它借 shell 身份发 Q-Poll
                binding = false;
                detail.setText("授权就绪 · Shizuku " + (bridgeUid == 0 ? "Root" : "Shell")
                        + " UID=" + bridgeUid + " · 通信使用 Shell 身份");
                updateSendLabel();
            } catch (Exception e) {
                bridgeBinder = null; bridgeUid = -1; binding = false;
                detail.setText("后端连接失败，请重启 Shizuku 后重试：" + e.getMessage());
                updateSendLabel();
            } finally { request.recycle(); reply.recycle(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bridgeBinder = null; bridgeUid = -1; binding = false;
            ShizukuQtag.detach();
            detail.setText("Shizuku 后端已断开");
            updateSendLabel();
            // Shizuku 已不参与刷图链路，它断开不该影响正在跑的会话。
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> {
        if (code != 28) return;
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) bindBridge();
        else detail.setText("Shizuku 授权被拒绝，可在 Shizuku 应用里重新授权");
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // 授权结果回来要立刻把后端连上，否则那条 Q-Poll 通道一直是断的。
        Shizuku.addRequestPermissionResultListener(permissionResult);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff4f7f5);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("墨小七", 22, 0xff142f2a);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button logs = button("日志");
        logs.setOnClickListener(v -> showLogs());
        header.addView(logs);
        Button gear = button("设置");
        gear.setOnClickListener(v -> showSettings());
        header.addView(gear);
        root.addView(header);

        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        preview = new PixelPreview(this);
        preview.setContentDescription("最终写入墨水屏的画面");
        preview.setOnClickListener(v -> showPixels());
        host.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(476);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-1, dp(5));
        progressParams.gravity = Gravity.BOTTOM;
        host.addView(progress, progressParams);

        state = text("先把手机贴到墨水屏的线圈上", 15, 0xff176b61);
        state.setTypeface(null, Typeface.BOLD);
        root.addView(state);
        detail = text("点「换一张图」，选风格，再点发送。", 12, 0xff52635d);
        root.addView(detail);

        LinearLayout styles = new LinearLayout(this);
        styles.setPadding(0, dp(6), 0, dp(4));
        root.addView(styles);
        for (int i = 0; i < styleCells.length; i++) {
            final int index = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(3), dp(4), dp(3), dp(4));
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(image, new LinearLayout.LayoutParams(-1, dp(84)));
            TextView label = text(PanelImage.STYLES[i], 12, 0xff263e35);
            label.setGravity(Gravity.CENTER);
            cell.addView(label);
            cell.setOnClickListener(v -> selectStyle(index));
            styles.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            thumbs[i] = image;
            styleCells[i] = cell;
        }

        LinearLayout actions = new LinearLayout(this);
        Button pick = action("换一张图", v -> pickImage());
        actions.addView(pick, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button rotate = action("转 90°", v -> {
            rotation = (rotation + 90) % 360;
            refit();
        });
        actions.addView(rotate, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(actions);

        send = button("发送到墨水屏");
        send.setTextSize(16);
        send.setTextColor(Color.WHITE);
        send.setBackgroundTintList(ColorStateList.valueOf(0xff176b61));
        send.setOnClickListener(v -> onSend());
        root.addView(send, new LinearLayout.LayoutParams(-1, dp(54)));

        stopButton = button("停止并恢复 NFC");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> requestStop("用户停止"));
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, dp(40)));

        // 上电等待默认 2000 ms；升级时把老安装里存的值一次性重置过来，
        // 免得升级后还踩到 500 ms 那种过短的参数。
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getInt("settingsVersion", 0) < 3) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("power", DEFAULT_POWER).putInt("settingsVersion", 3).apply();
        }
        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("mode", 1);
        letterbox = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("letterbox", false);
        cover = !letterbox;
        updateSendLabel();
        highlightStyle();
        append("就绪 · 四档风格：鎏金 / 质感 / 色阶 / 水墨（墨小佳官方算法）");
        restoreLastImage();
        autoConnect();
    }

    // ───────────────────────── 权限 ─────────────────────────

    private boolean authorized() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.isPreV11()) return false;
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            return bridgeBinder != null && bridgeBinder.isBinderAlive();
        } catch (Exception e) { return false; }
    }

    /** 启动时悄悄试一次：已经授权过就直接连上，用户什么都不用点。 */
    private void autoConnect() {
        try {
            // Shizuku 不是每台机器都需要（Android 15 及以下 App 自己就能发 Q-Poll），
            // 所以这里只做「能连就悄悄连上」，连不到也不打扰人。
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) bindBridge();
            else Shizuku.requestPermission(28);   // 授权过一次以后就不再弹
        } catch (Throwable ignored) { }
    }

    private void connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                detail.setText("没检测到 Shizuku。请先打开 Shizuku，用无线调试（ADB）启动服务——不需要 root。");
                Toast.makeText(this, "请先启动 Shizuku 服务", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.isPreV11()) { detail.setText("Shizuku 版本太旧，请升级"); return; }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(28);
                return;
            }
            bindBridge();
        } catch (Exception e) { detail.setText("连接 Shizuku 失败：" + e); }
    }

    /**
     * 确保「借 shell 身份发 Q-Poll」那条通道是通的。
     *
     * <p>只在 Shizuku 正在跑、且已经授权时才做；没装/没开 Shizuku 的机器直接跳过
     * （Android 15 及以下 App 自己能发，不需要它）。
     */
    private void ensureQtagChannel() {
        try {
            if (ShizukuQtag.available()) return;
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            main.post(this::bindBridge);
            for (int i = 0; i < 40 && !ShizukuQtag.available(); i++) Thread.sleep(100);
            if (!ShizukuQtag.available()) main.post(() -> append("借 shell 身份发指令的通道没连上，改用 App 自己发"));
        } catch (Throwable ignored) { }
    }

    private void bindBridge() {
        if (binding || (bridgeBinder != null && bridgeBinder.isBinderAlive())) return;
        binding = true;
        detail.setText("正在连接 Shizuku 后端…");
        try { Shizuku.bindUserService(bridgeArgs, bridgeConnection); }
        catch (Exception e) { binding = false; detail.setText("绑定后端失败：" + e); }
    }

    private void updateSendLabel() {
        if (send == null) return;
        // 只有「Shizuku 正在跑、但还没给本应用授权」这种情况才提示去授权。
        // 12T Pro 那种不装 Shizuku 的机器，按钮照旧是「发送到墨水屏」。
        boolean needGrant = false;
        try {
            needGrant = Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) { }
        send.setText(needGrant ? "连接 Shizuku 授权" : "发送到墨水屏");
    }

    // ───────────────────────── 出图 ─────────────────────────

    private void pickImage() {
        if (busy || preparing) return;
        /*
         * 1.4.14：换相册选择器（ACTION_PICK）。之前用 ACTION_OPEN_DOCUMENT（系统文档
         * 选择器），展示样式各 ROM 自己定——12T 出大缩略图、K90 出文件列表（小图+文件名），
         * 阿亮反馈不好选。ACTION_PICK 直接进相册 App 的选图界面，两台机都是
         * 大缩略图 + 相册分类，体验一致。个别 ROM 没有相册选择器时退回文档选择器兜底。
         */
        try {
            Intent pick = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(pick, 41);
        } catch (Throwable e) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 41);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 42) {
            // 裁切页回来：取走裁切结果。取消（RESULT_CANCELED / result==null）就不动现有图。
            if (result != RESULT_OK || CropPane.result == null) { CropPane.result = null; return; }
            Bitmap cropped = CropPane.result;
            CropPane.result = null;
            source = cropped;
            imageName = "自选图片";
            rotation = 0;
            saveLastImage(cropped);   // 存裁切后的构图，下次恢复所见即所得
            refit();
            return;
        }
        if (request != 41 || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        preparing = true;
        detail.setText("正在读取图片…");
        new Thread(() -> {
            try {
                final Bitmap bitmap = PanelImage.load(getContentResolver(), uri);
                main.post(() -> {
                    if (destroyed) return;
                    preparing = false;
                    // 1.4.13：选完图先进裁切页，用户自己定构图，不再自动铺满裁切。
                    CropPane.incoming = bitmap;
                    startActivityForResult(new Intent(this, CropPane.class), CropPane.REQUEST);
                });
            } catch (Throwable e) {
                main.post(() -> {
                    if (destroyed) return;
                    preparing = false;
                    detail.setText("图片读取失败：" + e.getMessage());
                });
            }
        }, "image-load").start();
    }

    /** 重新缩放 + 重出四张缩略图 + 重出大预览。只在换图 / 旋转 / letterbox切换时调。 */
    private void refit() {
        if (source == null || destroyed) return;
        final int token = ++revision;
        final Bitmap input = source;
        final int angle = rotation;
        final boolean fill = cover;
        preparing = true;
        prepared = null;
        updateSendLabel();
        detail.setText("正在出图…");
        new Thread(() -> {
            try {
                final int[] fitted = PanelImage.fittedPixels(input, angle, fill);
                final Bitmap[] small = PanelImage.thumbnails(fitted);
                main.post(() -> {
                    if (token != revision || destroyed) return;
                    pixels = fitted;
                    for (int i = 0; i < thumbs.length; i++) if (small[i] != null) thumbs[i].setImageBitmap(small[i]);
                    preparing = false;
                    render();
                });
            } catch (Throwable e) {
                main.post(() -> {
                    if (token != revision || destroyed) return;
                    preparing = false;
                    detail.setText("出图失败：" + e.getMessage());
                });
            }
        }, "fit").start();
    }

    /** 换风格只重跑量化，不动缩放。 */
    private void render() {
        if (pixels == null || destroyed) return;
        final int token = ++revision;
        final int[] fitted = pixels;
        final int selected = mode;
        final String panelType = panelType();
        preparing = true;
        detail.setText("正在出图…");
        new Thread(() -> {
            try {
                final PanelImage.Prepared result = PanelImage.prepare(fitted, panelType, selected);
                main.post(() -> {
                    if (token != revision || destroyed) return;
                    prepared = result;
                    preview.setImage(result.preview);
                    preparing = false;
                    state.setText(PanelImage.STYLES[selected] + " · " + imageName);
                    detail.setText(result.settings + " · 点画面看 1:1 像素");
                    updateSendLabel();
                });
            } catch (Throwable e) {
                main.post(() -> {
                    if (token != revision || destroyed) return;
                    preparing = false;
                    detail.setText("出图失败：" + e.getMessage());
                });
            }
        }, "render").start();
    }

    private void selectStyle(int index) {
        if (busy || preparing) return;
        if (mode == index) return;
        mode = index;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("mode", index).apply();
        highlightStyle();
        if (pixels == null) { refit(); return; }
        render();
    }

    private void highlightStyle() {
        for (int i = 0; i < styleCells.length; i++) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(i == mode ? 0xffe6f2ee : Color.WHITE);
            background.setCornerRadius(dp(10));
            background.setStroke(dp(i == mode ? 2 : 1), i == mode ? 0xff176b61 : 0xffdbe4df);
            styleCells[i].setBackground(background);
        }
    }

    private String panelType() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("panelType", "A1");
    }

    /**
     * 记住上次那张图，下次打开直接能刷。
     *
     * <p>写两份：内部目录是正常的家，外部 {@code Android/data/com.qpoll.lite/files/}
     * 方便把图直接丢进去调试（不用点选图器）。
     */
    private void saveLastImage(final Bitmap bitmap) {
        for (File target : lastImageFiles()) {
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) { }
        }
    }

    private File[] lastImageFiles() {
        File external = getExternalFilesDir(null);
        return external == null
                ? new File[]{new File(getFilesDir(), "last.png")}
                : new File[]{new File(getFilesDir(), "last.png"), new File(external, "last.png")};
    }

    /**
     * 裁切后的构图单独存（1.4.13）：恢复时优先它，保证「重开 App 看到的就是上次裁好的」；
     * 没有裁切文件（老版本残留 / 裁切前退出）才退回原图。
     */
    private File[] lastCropFiles() {
        File external = getExternalFilesDir(null);
        return external == null
                ? new File[]{new File(getFilesDir(), "last-crop.png")}
                : new File[]{new File(getFilesDir(), "last-crop.png"), new File(external, "last-crop.png")};
    }

    private void restoreLastImage() {
        File found = null;
        for (File candidate : lastCropFiles()) if (candidate.exists()) found = candidate;
        if (found == null) {
            for (File candidate : lastImageFiles()) if (candidate.exists()) found = candidate;
        }
        if (found == null) { detail.setText("点「换一张图」开始。"); return; }
        final File file = found;
        final boolean cropped = file.getName().contains("last-crop");
        new Thread(() -> {
            try {
                Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) return;
                main.post(() -> {
                    if (destroyed) return;
                    source = bitmap;
                    imageName = cropped ? "上次那张图（已裁切）" : "上次那张图";
                    refit();
                });
            } catch (Exception ignored) { }
        }, "image-restore").start();
    }

    private void showPixels() {
        if (prepared == null || busy || preparing) return;
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        previewDialog = dialog;
        dialog.setOnDismissListener(d -> previewDialog = null);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xfff4f7f5);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.addView(text("实际发送的像素 · 552 × 768", 18, 0xff142f2a));
        content.addView(text("双指缩放看细节 · 这才是判断清晰度的依据", 12, 0xff52635d));
        ZoomPreview viewer = new ZoomPreview(this, prepared.preview);
        content.addView(viewer, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        Button fit = button("适应屏幕"), actual = button("1:1 像素"), close = button("关闭");
        fit.setOnClickListener(v -> viewer.fit());
        actual.setOnClickListener(v -> viewer.actual());
        close.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(fit, new LinearLayout.LayoutParams(0, dp(44), 1));
        buttons.addView(actual, new LinearLayout.LayoutParams(0, dp(44), 1));
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1));
        content.addView(buttons);
        dialog.setContentView(content);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }

    // ───────────────────────── 发送 ─────────────────────────

    private void onSend() {
        if (busy || preparing) return;
        // 发送前只处理一种情况：Shizuku 开着但没授权。
        // 没装/没开 Shizuku 的机器直接往下走（Android 15 及以下 App 自己能发 Q-Poll）。
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                connectShizuku();
                return;
            }
        } catch (Throwable ignored) { }
        if (prepared == null) { Toast.makeText(this, "还没有图，先选一张", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
                .setTitle("写入这块墨水屏？")
                .setMessage("会替换屏上当前的画面。\n\n风格：" + prepared.settings
                        + "\n上电等待：" + powerMs() + " ms"
                        + "\n\n贴稳别动，全程保持本页在前台，直到提示结束。中途断开可能留下半张图。")
                .setNegativeButton("再看看", null)
                .setPositiveButton("开始刷新", (d, w) -> startRefresh()).show();
    }

    private void startRefresh() {
        if (busy || preparing || !resumed || prepared == null) return;
        final String id = Long.toString(SystemClock.elapsedRealtime());
        final File sessionStop = new File(getFilesDir(), "stop-" + id);
        final File jobFile = new File(getFilesDir(), "job-" + id + ".json");
        final File imageFile = new File(getFilesDir(), "image-" + id + ".bin");
        try {
            byte[] image = prepared.packed.clone();
            try (FileOutputStream out = new FileOutputStream(imageFile)) { out.write(image); }
            String sha = PanelProtocol.toHex(MessageDigest.getInstance("SHA-256").digest(image));
            JSONObject job = new JSONObject();
            job.put("panelType", prepared.panelType);
            job.put("imageFile", imageFile.getAbsolutePath());
            job.put("sha256", sha);
            job.put("powerMs", powerMs());
            job.put("pollMs", pollMs());
            try (FileOutputStream out = new FileOutputStream(jobFile)) { out.write(job.toString().getBytes("UTF-8")); }
            append("图片参数：" + prepared.settings);
        } catch (Exception e) {
            append("准备失败：" + e);
            imageFile.delete(); jobFile.delete();
            return;
        }
        stopFile = sessionStop;
        checkSession = false;
        terminalSeen = false;
        qtagDenied = false;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        send.setEnabled(false);
        stopButton.setVisibility(View.VISIBLE);
        stopButton.setEnabled(true);
        stopButton.setText("停止并恢复 NFC");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state.setText("正在准备…");
        detail.setText("检查这台机器需不需要兼容层");
        append("──────── 开始刷新 · 寻卡 " + SCAN_SECONDS + " 秒 ────────");
        // 一轮刷新的收尾。
        final Runnable done = () -> {
            sessionStop.delete(); jobFile.delete(); imageFile.delete();
            busy = false;
            new Thread(RootHook::releaseLease, "hook-release").start();
            if (!destroyed) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                progress.setVisibility(View.GONE);
                send.setEnabled(true);
                stopButton.setVisibility(View.GONE);
                updateSendLabel();
            }
        };

        // ───────── 后端选择：只走前台直连 + HAL 兼容层 ─────────
        // 2026-10-26 21:28 实机 NfcService 日志坐实：12T 的 HyperOS 对「非前台调用方」的
        // setReaderMode 一律拒绝（E/NfcService: Caller is not in foreground and is not
        // system process），所以 shell/worker 后端在这台机器上连第一张都寻不到，
        // 1.3.5/1.3.6 的「Shizuku 后端优先」是把唯一能刷的路绕开了，撤掉。
        // runBridge 代码保留在文件里，等将来有绕过前台检查的合法手段再评估。
        new Thread(() -> {
            main.post(() -> {
                if (destroyed) return;
                append("寻卡后端：前台直连 + HAL 兼容层");
                RootHook.ensureReady(this, line -> main.post(() -> append(line)), false);
                state.setText("正在寻卡…");
                detail.setText("把手机贴到线圈上，保持不动");
                FrontSession.start(this, "refresh", jobFile, sessionStop, SCAN_SECONDS,
                        (kind, message) -> event("QP|" + kind + "|" + message), done);
            });
        }, "session-prepare").start();
    }

    private void runBridge(String mode, File job, File image, File stop) throws Exception {
        IBinder binder = bridgeBinder;
        if (binder == null || !binder.isBinderAlive()) throw new java.io.IOException("请先连接 Shizuku 授权");
        String json = "{}";
        if (job.exists()) json = new String(readFile(job), "UTF-8");
        byte[] bytes = image.exists() ? readFile(image) : new byte[0];
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        final android.os.Binder owner = new android.os.Binder();
        try {
            request.writeInterfaceToken(INTERFACE);
            request.writeString(mode);
            request.writeInt(SCAN_SECONDS);
            request.writeString(json);
            request.writeByteArray(bytes);
            request.writeStrongBinder(owner);
            if (!binder.transact(1, request, reply, 0)) throw new java.io.IOException("后端不兼容");
            reply.readException();
        } finally { request.recycle(); reply.recycle(); }
        boolean cancelSent = false, ended = false;
        try {
            boolean running = true;
            long deadline = SystemClock.elapsedRealtime() + 335000;
            while (running) {
                if (stop.exists() && !cancelSent) { cancelBridge(); cancelSent = true; }
                request = Parcel.obtain(); reply = Parcel.obtain();
                try {
                    request.writeInterfaceToken(INTERFACE);
                    binder.transact(2, request, reply, 0);
                    reply.readException();
                    String logs = reply.readString();
                    running = reply.readInt() != 0;
                    if (logs != null) for (String line : logs.split("\n")) {
                        final String value = line;
                        main.post(() -> { if (!destroyed) event(value); });
                    }
                } finally { request.recycle(); reply.recycle(); }
                if (SystemClock.elapsedRealtime() > deadline) { cancelBridge(); throw new java.io.IOException("后端超时，已请求停止"); }
                if (running) SystemClock.sleep(100);
            }
            ended = true;
        } finally { if (!ended) try { cancelBridge(); } catch (Exception ignored) { } }
    }

    private void cancelBridge() throws Exception {
        IBinder binder = bridgeBinder;
        if (binder == null) return;
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(INTERFACE);
            binder.transact(3, request, reply, 0);
            reply.readException();
        } finally { request.recycle(); reply.recycle(); }
    }

    private void requestStop(String reason) {
        if (!busy) return;
        try {
            if (stopFile != null) stopFile.createNewFile();
            append(reason + "；等当前通信返回后恢复 NFC，不重发写入。");
            stopButton.setEnabled(false);
        } catch (Exception e) { append("停止标记失败：" + e); }
    }

    private static byte[] readFile(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { in.readFully(bytes); }
        return bytes;
    }

    // ───────────────────────── 事件 ─────────────────────────

    private void event(String line) {
        if (!line.startsWith("QP|")) { if (!line.trim().isEmpty()) append(line); return; }
        String[] parts = line.split("\\|", 3);
        if (parts.length != 3) return;
        String kind = parts[1], message = parts[2];
        if (kind.equals("START")) {
            scanDeadline = SystemClock.elapsedRealtime() + SCAN_SECONDS * 1000L;
            state.setText("正在寻卡…");
            main.post(tick);
        }
        if (kind.equals("TAG")) {
            tagSeen = true;
            main.removeCallbacks(tick);
            state.setText("找到屏幕了");
            detail.setText("正在握手、传图，别动");
        }
        if (kind.equals("IDENTITY")) identityOk = true;
        if (kind.equals("QTAG_REJECTED")) {
            String why = message;
            if (why.contains("WRITE_SECURE_SETTINGS") || why.contains("SecurityException")) {
                qtagDenied = true;
                state.setText("差一条权限");
                detail.setText("这台是 Android 16 或更高，系统要求一条特殊权限才允许发 Q-Poll 指令。"
                        + "用数据线连电脑执行一次即可（只做一次，以后不用再插线）：\n"
                        + "adb shell pm grant com.qpoll.lite android.permission.WRITE_SECURE_SETTINGS");
            }
        }
        if (kind.equals("APDU_FAILED")) {
            terminalSeen = true;
            state.setText("中途断了");
            detail.setText("跟屏幕的连接断了——通常是屏被挪动、或者贴合松了。这张图没写进去，"
                    + "重新贴稳再刷一次就行（不用重启 NFC）。");
        }
        if (kind.equals("REFRESH_DONE") || kind.equals("REFRESH_UNCONFIRMED")
                || kind.equals("ERROR") || kind.equals("RESULT") || kind.equals("CANCELLED")) {
            terminalSeen = true;
        }
        if (kind.equals("END") && !terminalSeen && busy && !checkSession) {
            state.setText("没刷成 · 中途断了");
            detail.setText("这一轮没有走到刷新那一步就结束了（" + message + "）。"
                    + "多半是屏被挪动或者贴合松了。贴稳重来一次就行。");
        }
        if (kind.equals("END") && checkSession) {
            checkSession = false;
            showVerdict();
            append(kind + " · " + message);
            return;
        }
        if (kind.equals("STAGE")) {
            main.removeCallbacks(tick);
            String[] stage = message.split("\\|", 2);
            state.setText(stage.length == 2 ? stage[1] : message);
            detail.setText("保持贴合，别切换应用");
        }
        if (kind.equals("PROGRESS")) {
            String[] fields = message.split("\\|", 3);
            try {
                int done = Integer.parseInt(fields[0]), total = Integer.parseInt(fields[1]);
                progress.setMax(total);
                progress.setProgress(done);
                detail.setText("已传 " + done + " / " + total + " 包 · " + (100 * done / Math.max(1, total)) + "%");
            } catch (Exception ignored) { }
            return;
        }
        if (kind.equals("HOLD")) {
            main.removeCallbacks(tick);
            state.setText("正在刷新屏幕");
            detail.setText(message);
            // 保场这十几秒一旦被「停止」打断，NFC 场消失，屏掉电，本次刷新直接作废。
            // 所以这段时间把停止按钮锁死，视觉上也说清楚。
            if (stopButton != null) {
                stopButton.setEnabled(false);
                stopButton.setText("刷新供能中，请勿停止");
            }
        }
        if (kind.equals("REFRESH_DONE") || kind.equals("REFRESH_UNCONFIRMED")) {
            boolean done = kind.equals("REFRESH_DONE");
            state.setText(done ? "刷完了，看屏幕" : "刷新没有启动");
            detail.setText(message);
        }
        if (kind.equals("RESULT")) {
            if (message.startsWith("自检通过")) state.setText("这台机器能刷");
            else if (message.startsWith("自检部分通过")) state.setText("自检只过了一半");
            else if (message.startsWith("自检中止")) state.setText("自检被中止");
            else state.setText(message.startsWith("D1") ? "屏幕校验通过" : message.startsWith("已收到") ? "找到屏幕了" : "没找到屏幕");
            detail.setText(message);
        }
        if (kind.equals("CAPABILITY")) detail.setText(message);
        if (kind.equals("ERROR") || kind.equals("QTAG_REJECTED") || kind.equals("RESTORE_FAILED")) {
            state.setText("出问题了");
            detail.setText(hint(kind, message));
        }
        if (kind.equals("CANCELLED")) {
            state.setText("已停止 · 请看一眼屏幕");
            detail.setText(message);
        }
        append(kind + " · " + message);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!busy) return;
            detail.setText("寻找墨水屏 · 最长还剩 "
                    + Math.max(0, (scanDeadline - SystemClock.elapsedRealtime() + 999) / 1000) + " 秒 · 贴稳别动");
            main.postDelayed(this, 250);
        }
    };

    /**
     * 日志写两份：界面里留最近一份可看可复制，文件留在外部目录
     * （{@code Android/data/com.qpoll.lite/files/latest.log}），出问题时能直接拉出来看，
     * 不用让人截图。
     */
    /**
     * 把协议层的错误翻译成人话。
     *
     * <p>{@code SW=68CA} 是这块屏最常见的失败：图已经全传完了，最后一步上电刷新时功率不够。
     * 绝大多数情况是手机没对准线圈中心、或者中间隔了壳/桌布。这一条必须给人看明白，
     * 否则用户以为是软件坏了。
     */
    private String hint(String kind, String message) {
        if (message.contains("QTag 启用失败") || message.contains("API 状态="))
            return "这台手机不支持：系统拒绝了 NXP 的私有寻卡指令（就是 Q-Poll）。\n"
                    + "说明它的 NFC 栈不是 NXP 的、或者厂商把这个接口裁掉了——换手机，软件层面没有绕法。";
        if (message.contains("68CA"))
            return "图已经传完了，最后上电刷新时供能不足（SW=68CA）。\n把手机重新对准屏幕背面线圈的正中、贴紧别垫东西，再刷一次。";
        if (message.contains("未收到标签"))
            return "没找到屏幕。确认手机 NFC 天线位置正对屏幕线圈，贴稳后重试。";
        if (kind.equals("RESTORE_FAILED"))
            return message + "\n（关掉再打开一次 NFC 开关即可恢复）";
        return message;
    }

    /**
     * 检测结论：只用三档说话，别让人猜。
     *
     * <ol>
     *   <li>能刷、不用 root —— 手机 NFC 原生支持 Q-Poll</li>
     *   <li>能刷、但要 root —— 固件没开 Q-Poll，靠 compat 层补</li>
     *   <li>要 root 才能刷（还没授权）／屏幕没应答</li>
     * </ol>
     * 判决依据只有两个：屏有没有应答（tagSeen）、兼容层是不是真就绪（hookReady）。
     */
    private void showVerdict() {
        boolean nxp = ChipInfo.looksLikeNxp();
        if (tagSeen && identityOk) {
            state.setText("这台能刷");
            detail.setText(hookReady
                    ? "结论：能刷，但这台要 root。它的 NFC 固件没开放 Q-Poll，"
                      + "刚才是靠 root 装兼容层把寻卡指令改出来的。以后每次刷图 App 会自动装一遍，你不用管。"
                    : "结论：能刷，而且不用 root。这台手机的 NFC 原生支持 Q-Poll 寻卡。");
            return;
        }
        if (tagSeen) {
            state.setText("屏有应答，读规格没成");
            detail.setText("Q-Poll 是通的（收到标签了），但 D1 读屏幕规格没成功。"
                    + "多半是贴合中途松了，贴稳再自检一次。");
            return;
        }
        if (qtagDenied) {
            state.setText("差一条权限（不用 root）");
            detail.setText("这台手机的 Q-Poll 指令被系统的权限检查挡住了，不是硬件不支持。"
                    + "用数据线连电脑执行一次就好，不需要 root：\n"
                    + "adb shell pm grant com.qpoll.lite android.permission.WRITE_SECURE_SETTINGS\n"
                    + "授权后回到这里再点一次自检，就能确认。");
            return;
        }
        if (!hookReady) {
            state.setText("这台要 Shizuku 或 root");
            detail.setText("结论：App 自己发不出 Q-Poll，得借一个更高身份。"
                    + "推荐 Shizuku（不用 root）：打开 Shizuku → 用无线调试启动服务 → 回这里点「连接 / 重连 Shizuku」授权，再自检一次。"
                    + "有 root 的话走兼容层也行。\n"
                    + "另外：需要 root 权限才能刷。"
                    + (nxp ? "这台是 NXP 的 NFC 芯片，但系统固件没开放 Q-Poll，必须让 App 拿到 root 装兼容层。"
                           : "系统里没找到 NXP 的 NFC 固件配置，很可能不是 NXP 芯片，那样连 root 也救不了——但先把 root 给了再试一次最稳。")
                    + "\n做法：打开手机里的「root 管理器」→ 超级用户 → 把「墨小七」勾成允许，"
                    + "然后回来再点一次自检。");
            return;
        }
        state.setText("没找到屏幕");
        detail.setText("兼容层已经装好了，但屏没应答。确认墨水屏贴在线圈正中、贴稳别垫东西，再自检一次。");
    }

    private void append(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + "  " + line + "\n";
        // 同时打到 logcat：出问题时能 adb 直接抓，不用让人截图。
        android.util.Log.i("QPollLite", line);
        transcript.append(stamped);
        if (transcript.length() > 60000) transcript.delete(0, transcript.indexOf("\n", transcript.length() - 50000) + 1);
        File log = logFile();
        if (log != null) {
            try (FileWriter out = new FileWriter(log, true)) { out.write(stamped); }
            catch (Exception ignored) { }
        }
    }

    private File logFile() {
        File external = getExternalFilesDir(null);
        return external == null ? new File(getFilesDir(), "latest.log") : new File(external, "latest.log");
    }

    private void showLogs() {
        TextView view = text(transcript.length() == 0 ? "（还没有日志）" : transcript.toString(), 11, 0xff233c31);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(scroll)
                .setNeutralButton("复制", (d, w) -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("墨水屏日志", transcript.toString()));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("关闭", null).show();
    }

    // ───────────────────────── 设置 ─────────────────────────

    private int powerMs() {
        return POWER_TIMES[Math.max(0, Math.min(POWER_TIMES.length - 1,
                getSharedPreferences(PREFS, MODE_PRIVATE).getInt("power", DEFAULT_POWER)))];
    }
    private int pollMs() {
        return POLL_TIMES[Math.max(0, Math.min(POLL_TIMES.length - 1,
                getSharedPreferences(PREFS, MODE_PRIVATE).getInt("poll", POLL_TIMES.length - 1)))];
    }

    private void showSettings() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));

        body.addView(text("屏幕类型（排线顺序）", 13, 0xff52635d));
        final Spinner panel = new Spinner(this);
        panel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"A1 · 常见（默认）", "A0 · 少见"}));
        panel.setSelection("A0".equals(panelType()) ? 1 : 0);
        panel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("panelType", pos == 1 ? "A0" : "A1").apply();
                if (pixels != null && !busy && !preparing) render();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        body.addView(panel, new LinearLayout.LayoutParams(-1, dp(44)));
        body.addView(text("改错了会刷出横向错位的图，A0 换 A1 重刷即可恢复。", 11, 0xff68766e));

        body.addView(text("上电等待", 13, 0xff52635d));
        final Spinner power = new Spinner(this);
        String[] powerLabels = new String[POWER_TIMES.length];
        for (int i = 0; i < POWER_TIMES.length; i++)
            powerLabels[i] = POWER_TIMES[i] + " ms" + (i == DEFAULT_POWER ? "（默认 · 官方值）" : i == POWER_TIMES.length - 1 ? "（协议上限 12700）" : "");
        power.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, powerLabels));
        int savedPower = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("power", DEFAULT_POWER), selPower = DEFAULT_POWER;
        for (int i = 0; i < POWER_TIMES.length; i++) if (i == savedPower) selPower = i;
        power.setSelection(selPower);
        power.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("power", pos).apply();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        body.addView(power, new LinearLayout.LayoutParams(-1, dp(44)));

        body.addView(text("状态查询间隔", 13, 0xff52635d));
        final Spinner poll = new Spinner(this);
        String[] pollLabels = new String[POLL_TIMES.length];
        for (int i = 0; i < POLL_TIMES.length; i++)
            pollLabels[i] = POLL_TIMES[i] == 0 ? "0 ms · 连续查询" : POLL_TIMES[i] + " ms" + (i == POLL_TIMES.length - 1 ? "（默认）" : "");
        poll.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, pollLabels));
        int savedPoll = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("poll", POLL_TIMES.length - 1), selPoll = POLL_TIMES.length - 1;
        for (int i = 0; i < POLL_TIMES.length; i++) if (i == savedPoll) selPoll = i;
        poll.setSelection(selPoll);
        poll.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("poll", pos).apply();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        body.addView(poll, new LinearLayout.LayoutParams(-1, dp(44)));

        final android.widget.CheckBox letterboxBox = new android.widget.CheckBox(this);
        letterboxBox.setText("完整显示（留白，不裁掉边缘）");
        letterboxBox.setChecked(letterbox);
        letterboxBox.setOnCheckedChangeListener((b, checked) -> {
            letterbox = checked;
            cover = !checked;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("letterbox", checked).apply();
            if (source != null && !busy && !preparing) refit();
        });
        body.addView(letterboxBox);

        body.addView(text("本机 NFC 芯片：" + ChipInfo.inspect(), 11, 0xff68766e));
        body.addView(text(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " · Android " + android.os.Build.VERSION.RELEASE, 11, 0xff68766e));

        LinearLayout row = new LinearLayout(this);
        Button check = button("只读检查屏幕");
        check.setOnClickListener(v -> startCheck("identity"));
        row.addView(check, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button about = button("开源许可");
        about.setOnClickListener(v -> showLicense());
        row.addView(about, new LinearLayout.LayoutParams(0, dp(44), 1));
        body.addView(row);

        Button reconnect = button("连接 / 重连 Shizuku");
        reconnect.setOnClickListener(v -> connectShizuku());
        body.addView(reconnect, new LinearLayout.LayoutParams(-1, dp(44)));

        // 换手机、换屏、怀疑「这台到底行不行」时先点这个。
        // 它只发一条 NXP 私有指令 + 开一次寻卡，绝不发任何 APDU，不会动屏上的画面。
        Button probe = button("兼容性自检（不动画面）");
        probe.setOnClickListener(v -> {
            if (settingsDialog != null) settingsDialog.dismiss();
            startCheck("probe");
        });
        body.addView(probe, new LinearLayout.LayoutParams(-1, dp(44)));

        // 这一项完全不碰 Shizuku：就在 App 自己的身份下试，用来回答
        // 「这台机器是不是连授权都不需要」——华为/荣耀机型上很可能就是这条路。
        Button plain = button("零权限检测（不用 root）");
        plain.setOnClickListener(v -> {
            if (settingsDialog != null) settingsDialog.dismiss();
            runPlainProbe();
        });
        body.addView(plain, new LinearLayout.LayoutParams(-1, dp(44)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        settingsDialog = new AlertDialog.Builder(this).setTitle("设置").setView(scroll)
                .setPositiveButton("好", null).create();
        settingsDialog.show();
    }

    /**
     * 只读检查（identity）和兼容性自检（probe）共用同一条会话链路，只有 mode 和文案不同。
     * 两者都不发任何写图 APDU，屏上的画面不会变。
     */
    private void startCheck(String mode) {
        if (busy || preparing || !resumed) return;
        final boolean probe = "probe".equals(mode);
        final String id = Long.toString(SystemClock.elapsedRealtime());
        final File sessionStop = new File(getFilesDir(), "stop-" + id);
        final File jobFile = new File(getFilesDir(), "job-" + id + ".json");
        final File imageFile = new File(getFilesDir(), "image-" + id + ".bin");
        try (FileOutputStream out = new FileOutputStream(jobFile)) { out.write("{}".getBytes("UTF-8")); }
        catch (Exception e) { append("准备失败：" + e); return; }
        stopFile = sessionStop;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        send.setEnabled(false);
        stopButton.setVisibility(View.VISIBLE);
        stopButton.setEnabled(true);
        stopButton.setText("停止并恢复 NFC");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state.setText("正在准备…");
        detail.setText(probe ? "检查兼容层，再发一条指令 + 寻卡" : "检查兼容层，再读屏幕规格");
        append(probe ? "──────── 兼容性自检 ────────" : "──────── 只读检查 ────────");
        if (probe) append("本机芯片：" + ChipInfo.inspect().split("\n")[0]
                + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " · Android " + android.os.Build.VERSION.RELEASE);
        checkSession = probe;
        hookReady = false;
        tagSeen = false;
        identityOk = false;
        qtagDenied = false;
        new Thread(() -> {
            boolean ready = RootHook.ensureReady(this, line -> main.post(() -> append(line)), true);
            main.post(() -> {
                hookReady = ready;
                if (destroyed) return;
                state.setText("正在寻卡…");
                detail.setText(probe ? "只发一条指令 + 寻卡，不改画面" : "只读取屏幕规格，不会改动画面");
                FrontSession.start(this, mode, jobFile, sessionStop, SCAN_SECONDS,
                        (kind, message) -> event("QP|" + kind + "|" + message), () -> {
                    sessionStop.delete(); jobFile.delete(); imageFile.delete();
                    busy = false;
                    new Thread(RootHook::releaseLease, "hook-release").start();
                    if (!destroyed) {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        progress.setVisibility(View.GONE);
                        send.setEnabled(true);
                        stopButton.setVisibility(View.GONE);
                        updateSendLabel();
                    }
                });
            });
        }, "hook-prepare").start();
    }

    /**
     * 零权限检测：全程不碰 Shizuku，就用 App 自己的身份试。
     * 前四级同步跑；第四级过了就接着做第五级（真开寻卡 10 秒，看能不能收到屏）。
     */
    private void runPlainProbe() {
        final StringBuilder out = new StringBuilder();
        append("──────── 零权限检测（普通 App 身份，不用 Shizuku）────────");
        new Thread(() -> {
            try { PlainProbe.run(this, line -> out.append(line).append('\n')); }
            catch (Throwable e) { out.append("检测本身出错：").append(e).append('\n'); }
            main.post(() -> {
                if (destroyed) return;
                if (!out.toString().contains("零权限可行")) { showProbeResult(out.toString()); return; }
                out.append("⑤ 下面开 10 秒真寻卡，把手机贴到屏的线圈上别动……\n");
                state.setText("零权限寻卡中");
                detail.setText("贴住线圈，别动，10 秒");
                PlainProbe.scan(this, 10,
                        line -> out.append(line).append('\n'),
                        () -> {
                            state.setText("零权限检测完成");
                            detail.setText("结果见弹窗，也写进日志了");
                            showProbeResult(out.toString());
                        });
            });
        }, "plain-probe").start();
    }

    private void showProbeResult(String text) {
        for (String line : text.split("\n")) if (!line.isEmpty()) append(line);
        if (destroyed) return;
        new AlertDialog.Builder(this)
                .setTitle("零权限检测结果")
                .setMessage(text.isEmpty() ? "没有任何输出" : text)
                .setPositiveButton("好", null)
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("零权限检测", text));
                })
                .show();
    }

    private void showLicense() {
        StringBuilder body = new StringBuilder("协议实现参考 https://gitee.com/kur0neko/huawei-epd-pn532\n"
                + "固定提交 5d3acf730a988815b637262fd4862c1794ab079e\nRequired Notice: Copyright (c) 2026 kur0neko\n\n");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                getAssets().open("licenses/kur0neko-LICENSE.txt"), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) body.append(line).append('\n');
        } catch (Exception e) { body.append(e); }
        TextView view = text(body.toString(), 12, 0xff263e35);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("PolyForm Noncommercial 1.0.0")
                .setView(scroll).setPositiveButton("关闭", null).show();
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(44));
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }
    private Button action(String label, View.OnClickListener listener) {
        Button b = button(label);
        b.setOnClickListener(listener);
        return b;
    }
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onStop() {
        resumed = false;
        if (busy) requestStop("页面已离开，自动停止");
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        revision++;
        if (previewDialog != null) previewDialog.dismiss();
        if (busy) requestStop("页面关闭，自动停止");
        // 2026-10-26 22:20：刷图会话收尾不再关 reader/QTag（防「tag connected」死锁），
        // 页面真正退出时在这里做一次完整清理，把 NFC 还给系统。
        try { FrontSession.teardown(this); } catch (Throwable ignored) { }
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        try { Shizuku.unbindUserService(bridgeArgs, bridgeConnection, false); } catch (Exception ignored) { }
        main.removeCallbacks(tick);
        super.onDestroy();
    }
}
