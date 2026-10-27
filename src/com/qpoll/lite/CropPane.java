package com.qpoll.lite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 裁切页：选图之后、出图之前，让用户自己定构图。
 *
 * <p><b>1.4.19 交互定稿（阿亮 2026-10-27 拍板）——「框焊死、图拖缩」</b>：
 * 取景框固定在屏幕中央（552:768，跟屏同比例），图片在框底下单指拖动、双指捏合缩放。
 * <ul>
 *   <li><b>打开第一眼 = 自动铺满框</b>（与旧版自动行为一致，比例接近的图感知不到差异）；</li>
 *   <li>往大捏：放大铺满整框，多余部分被框裁掉（= 旧的「自动铺满」构图，还能放大看细节）；</li>
 *   <li>往小捏：下限 = 图片完整放进框——方形图会变成「宽贴满、上下露白」的方块构图，
 *       露白位置由用户拖动决定（占上半屏还是下半屏自己选）；</li>
 *   <li><b>白边是真白边，会进画面</b>：输出 = 白底 552×768 + 图片按当前摆放画上去，
 *       框里看到啥样刷出来就啥样。放大裁切和留白构图之间连续可调。</li>
 * </ul>
 *
 * <p><b>墨小佳的坑</b>：它的确认 ✓ 顶在状态栏里点不到。这一版确认按钮放屏幕底部悬浮，
 * 并按导航栏高度留出安全距离。
 */
public final class CropPane extends Activity {
    public static final int REQUEST = 42;

    /** MainActivity 塞进来的原图（用完置 null 防泄漏）。 */
    public static Bitmap incoming;
    /** 确认后的裁切结果，MainActivity 取走后置 null。 */
    public static Bitmap result;

    private CropView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        result = null;   // Back 键/手势退出也走「取消」语义，别把上一轮的结果漏给主页面
        getWindow().setNavigationBarColor(0xE6101412);
        getWindow().setStatusBarColor(0xE6101412);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101412);
        view = new CropView();
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(controls(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        setContentView(root);
    }

    /** 底部悬浮按钮条：取消（左） + 确认（右），避开手势条。 */
    private View controls() {
        int navInset = 0;
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        if (insets != null) navInset = insets.getSystemWindowInsetBottom();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(16), dp(10), dp(16), navInset + dp(10));
        bar.setBackgroundColor(0xB3000000);
        Button cancel = button("取消", 0xFF2A302C, 0xFFC9D4CE);
        cancel.setOnClickListener(v -> { result = null; finish(); });
        Button ok = button("✓ 用这张", 0xFF176B61, 0xFFFFFFFF);
        ok.setOnClickListener(v -> {
            result = view.crop();
            if (result == null) { finish(); return; }
            setResult(RESULT_OK);
            finish();
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(52), 1f);
        left.rightMargin = dp(8);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(52), 1f);
        right.leftMargin = dp(8);
        bar.addView(cancel, left);
        bar.addView(ok, right);
        return bar;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(foreground);
        b.setBackgroundColor(background);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * 取景画布：框固定，图片拖缩。
     *
     * <p>图片状态只有一个「显示矩形 {@link #view}」（屏幕坐标），拖/缩都在改它，
     * {@link #clampView()} 负责界限：缩放不小于「完整放进框」（contain），不超过
     * 铺满后再放大 4 倍；平移不许把图整体拖出框。
     */
    private final class CropView extends View {
        private final Bitmap image;
        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint whitePaint = new Paint();
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** 输出窗口：屏幕坐标，固定不动。 */
        private final RectF frame = new RectF();
        /** 图片当前显示区（屏幕坐标），拖/缩改这个。 */
        private final RectF view = new RectF();
        private final RectF gestureStartView = new RectF();
        private boolean ready;
        private float startDist, startX, startY;

        CropView() {
            super(CropPane.this);
            image = incoming;
            whitePaint.setColor(0xFFFFFFFF);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(dp(2));
            framePaint.setColor(0xFFFFFFFF);
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(dp(4));
            cornerPaint.setColor(0xFFFFFFFF);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(dp(1));
            gridPaint.setColor(0x55FFFFFF);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            layout();
        }

        /** 框 = 可用区（避开顶部与按钮条）内接的 552:768，居中；图片初始自动铺满框。 */
        private void layout() {
            if (image == null || getWidth() < 1 || getHeight() < 1) return;
            float barTop = dp(8), barBottom = dp(96);   // 给按钮条留底
            float boxTop = barTop, boxBottom = getHeight() - barBottom;
            float ratio = PanelProtocol.HEIGHT / (float) PanelProtocol.WIDTH;
            float fw = getWidth(), fh = fw * ratio;
            if (fh > boxBottom - boxTop) { fh = boxBottom - boxTop; fw = fh / ratio; }
            frame.set((getWidth() - fw) / 2f, boxTop + (boxBottom - boxTop - fh) / 2f,
                    (getWidth() + fw) / 2f, boxTop + (boxBottom - boxTop - fh) / 2f + fh);
            // 初始构图 = 铺满框（与旧版自动行为一致），用户再自由调整
            float scale = Math.max(fw / image.getWidth(), fh / image.getHeight());
            float w = image.getWidth() * scale, h = image.getHeight() * scale;
            view.set(frame.centerX() - w / 2f, frame.centerY() - h / 2f,
                    frame.centerX() + w / 2f, frame.centerY() + h / 2f);
            clampView(frame.centerX(), frame.centerY());
            ready = true;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF101412);
            if (!ready) return;
            // 框内：白底 + 图片（图片没盖住的地方就是白边，所见即所得）
            canvas.save();
            canvas.clipRect(frame);
            canvas.drawRect(frame, whitePaint);
            canvas.drawBitmap(image, null, view, imagePaint);
            canvas.restore();
            // 三分线 + 框 + 四角
            float thirdW = frame.width() / 3f, thirdH = frame.height() / 3f;
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(frame.left + thirdW * i, frame.top, frame.left + thirdW * i, frame.bottom, gridPaint);
                canvas.drawLine(frame.left, frame.top + thirdH * i, frame.right, frame.top + thirdH * i, gridPaint);
            }
            canvas.drawRect(frame, framePaint);
            drawCorners(canvas);
        }

        private void drawCorners(Canvas canvas) {
            float len = dp(22);
            canvas.drawLine(frame.left, frame.top, frame.left + len, frame.top, cornerPaint);
            canvas.drawLine(frame.left, frame.top, frame.left, frame.top + len, cornerPaint);
            canvas.drawLine(frame.right - len, frame.top, frame.right, frame.top, cornerPaint);
            canvas.drawLine(frame.right, frame.top, frame.right, frame.top + len, cornerPaint);
            canvas.drawLine(frame.right - len, frame.bottom, frame.right, frame.bottom, cornerPaint);
            canvas.drawLine(frame.right, frame.bottom - len, frame.right, frame.bottom, cornerPaint);
            canvas.drawLine(frame.left, frame.bottom, frame.left + len, frame.bottom, cornerPaint);
            canvas.drawLine(frame.left, frame.bottom - len, frame.left, frame.bottom, cornerPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!ready) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    startX = e.getX(); startY = e.getY();
                    gestureStartView.set(view);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (e.getPointerCount() >= 2) {
                        startDist = pinchDist(e);
                        startX = pinchCenterX(e); startY = pinchCenterY(e);
                        gestureStartView.set(view);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (e.getPointerCount() >= 2 && startDist > 0) {
                        // 以捏合中心为焦点缩放：显示区围绕焦点等比放大/缩小
                        float factor = pinchDist(e) / startDist;
                        float cx = pinchCenterX(e), cy = pinchCenterY(e);
                        float w = gestureStartView.width() * factor;
                        float h = gestureStartView.height() * factor;
                        float relX = (cx - gestureStartView.centerX()) / gestureStartView.width();
                        float relY = (cy - gestureStartView.centerY()) / gestureStartView.height();
                        view.set(cx - relX * w, cy - relY * h, cx - relX * w + w, cy - relY * h + h);
                    } else {
                        view.set(gestureStartView);
                        view.offset(e.getX() - startX, e.getY() - startY);
                    }
                    clampView(e.getPointerCount() >= 2 ? pinchCenterX(e) : frame.centerX(),
                              e.getPointerCount() >= 2 ? pinchCenterY(e) : frame.centerY());
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: return true;
                default: return true;
            }
        }

        private float pinchDist(MotionEvent e) {
            float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private float pinchCenterX(MotionEvent e) { return (e.getX(0) + e.getX(1)) / 2f; }
        private float pinchCenterY(MotionEvent e) { return (e.getY(0) + e.getY(1)) / 2f; }

        /**
         * 界限：
         * <ul>
         *   <li>缩放下限 = contain（完整放进框：方形图在竖框里就是宽贴满、上下露白）；</li>
         *   <li>缩放上限 = cover × 4（铺满之后还能放大看细节）；</li>
         *   <li>平移：图比框大的轴卡两头（不许露边），图比框小的轴图中心不出框（露白但不出局）。</li>
         * </ul>
         */
        private void clampView(float focusX, float focusY) {
            float containScale = Math.min(frame.width() / image.getWidth(), frame.height() / image.getHeight());
            float coverScale = Math.max(frame.width() / image.getWidth(), frame.height() / image.getHeight());
            float scale = view.width() / (float) image.getWidth();
            if (scale < containScale || scale > coverScale * 4) {
                float clamped = Math.max(containScale, Math.min(scale, coverScale * 4));
                float w = image.getWidth() * clamped, h = image.getHeight() * clamped;
                view.set(focusX - w / 2f, focusY - h / 2f, focusX + w / 2f, focusY + h / 2f);
            }
            // 平移：每根轴独立钳
            float left, top;
            if (view.width() >= frame.width()) {
                left = Math.max(frame.right - view.width(), Math.min(view.left, frame.left));
            } else {
                left = Math.max(frame.left - view.width() / 2f, Math.min(view.left, frame.right - view.width() / 2f));
            }
            if (view.height() >= frame.height()) {
                top = Math.max(frame.bottom - view.height(), Math.min(view.top, frame.top));
            } else {
                top = Math.max(frame.top - view.height() / 2f, Math.min(view.top, frame.bottom - view.height() / 2f));
            }
            view.offsetTo(left, top);
        }

        /**
         * 输出：白底 552×768，把「屏幕上框区域的内容」按比例映射进去。
         * 框外图片内容天然被画布边界裁掉；图片没盖住的地方就是白边（构图的一部分）。
         */
        Bitmap crop() {
            if (!ready || image == null) return null;
            Bitmap out = Bitmap.createBitmap(PanelProtocol.WIDTH, PanelProtocol.HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.WHITE);
            canvas.save();
            canvas.scale(PanelProtocol.WIDTH / frame.width(), PanelProtocol.HEIGHT / frame.height());
            canvas.translate(-frame.left, -frame.top);
            canvas.drawBitmap(image, null, view, imagePaint);
            canvas.restore();
            return out;
        }
    }
}
