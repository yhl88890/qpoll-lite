package com.qpoll.lite;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.InputStream;

/**
 * 图片落到 552×768 四色屏的全过程：解码 → 缩放 → 选风格出图 → 按屏型打包 → 回读成预览。
 *
 * <p>这里只做四件事：{@link #load}、{@link #fit}、{@link #prepare}、{@link #thumbnails}。
 * 旧版那些二值化阈值、色块简化、抖动开关、网点版画全部下架——实测真正出片的只有墨小佳那四档。
 */
public final class PanelImage {
    /** 风格名，界面按钮和出图都从这里取，不会再对不上。 */
    public static final String[] STYLES = ReinkRaster.MODES;

    /** 缩略图用的尺寸：半分辨率，够看清风格差异，出图快四倍。 */
    private static final int THUMB_WIDTH = PanelProtocol.WIDTH / 2;
    private static final int THUMB_HEIGHT = PanelProtocol.HEIGHT / 2;

    public static final class Prepared {
        public final Bitmap preview;
        public final byte[] packed;
        public final String settings;
        public final String panelType;
        Prepared(Bitmap preview, byte[] packed, String settings, String panelType) {
            this.preview = preview;
            this.packed = packed;
            this.settings = settings;
            this.panelType = panelType;
        }
    }

    /** 解码相册里的图片：先读尺寸定采样率，再按 EXIF 摆正。 */
    public static Bitmap load(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IllegalArgumentException("无法识别图片");
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) { decoded = BitmapFactory.decodeStream(in, null, options); }
        if (decoded == null) throw new IllegalArgumentException("图片解码失败");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (Exception ignored) { }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setScale(1, -1); break;
            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case 8: matrix.setRotate(270); break;
            default: break;
        }
        return matrix.isIdentity() ? decoded
                : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
    }

    public static Bitmap rotated(Bitmap source, int rotation) {
        if (rotation == 0) return source;
        Matrix rotate = new Matrix();
        rotate.setRotate(rotation);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), rotate, true);
    }

    /**
     * 把原图落到目标尺寸。缩小走面积平均，放大才交给 Canvas 双线性。
     *
     * <p><b>为什么不用 Canvas 缩放</b>：手机照片动辄 1728×2304，到屏上是 3.1 倍降采样。
     * {@code FILTER_BITMAP_FLAG} 在这个比例下只做 2×2 双线性，等于隔点采样——
     * 照片里的传感器噪声被原样搬下来，再经过抖动就变成满脸色点，
     * 五官边缘也因为采样点乱跳而毛刺。面积平均是把每个目标像素盖住的源像素全部按面积加权，
     * 噪声被平均掉、边缘被正确低通，同一套抖动算法出来脸立刻干净一档（2026-10-25 实测）。
     *
     * <p><b>这是清晰度的第二个关键，别改回去。</b>
     */
    static int[] fit(Bitmap input, int width, int height, boolean cover) {
        int sourceWidth = input.getWidth(), sourceHeight = input.getHeight();
        float scale = cover ? Math.max(width / (float) sourceWidth, height / (float) sourceHeight)
                            : Math.min(width / (float) sourceWidth, height / (float) sourceHeight);
        if (scale >= 1) {
            Bitmap fitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fitted);
            canvas.drawColor(Color.WHITE);
            float w = sourceWidth * scale, h = sourceHeight * scale;
            canvas.drawBitmap(input, null, new RectF((width - w) / 2, (height - h) / 2,
                    (width + w) / 2, (height + h) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] pixels = new int[width * height];
            fitted.getPixels(pixels, 0, width, 0, 0, width, height);
            return pixels;
        }
        int[] source = new int[sourceWidth * sourceHeight];
        input.getPixels(source, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight);
        int[] out = new int[width * height];
        java.util.Arrays.fill(out, 0xffffffff);
        float drawnWidth = sourceWidth * scale, drawnHeight = sourceHeight * scale;
        float originX = (width - drawnWidth) / 2f, originY = (height - drawnHeight) / 2f;
        for (int y = 0; y < height; y++) {
            double top = (y - originY) / scale, bottom = (y + 1 - originY) / scale;
            if (bottom <= 0 || top >= sourceHeight) continue;
            top = Math.max(0, top); bottom = Math.min(sourceHeight, bottom);
            int firstRow = (int) Math.floor(top), lastRow = (int) Math.ceil(bottom);
            for (int x = 0; x < width; x++) {
                double left = (x - originX) / scale, right = (x + 1 - originX) / scale;
                if (right <= 0 || left >= sourceWidth) continue;
                left = Math.max(0, left); right = Math.min(sourceWidth, right);
                int firstColumn = (int) Math.floor(left), lastColumn = (int) Math.ceil(right);
                double sumRed = 0, sumGreen = 0, sumBlue = 0, weight = 0;
                for (int sy = firstRow; sy < lastRow; sy++) {
                    double overlapY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    if (overlapY <= 0) continue;
                    for (int sx = firstColumn; sx < lastColumn; sx++) {
                        double overlapX = Math.min(right, sx + 1) - Math.max(left, sx);
                        if (overlapX <= 0) continue;
                        double area = overlapX * overlapY;
                        int pixel = source[sy * sourceWidth + sx];
                        // 透明像素按白底合成，跟界面预览的口径保持一致
                        double alpha = ((pixel >>> 24) / 255.0) * area, blank = area - alpha;
                        sumRed += (((pixel >>> 16) & 255) * alpha + 255 * blank);
                        sumGreen += (((pixel >>> 8) & 255) * alpha + 255 * blank);
                        sumBlue += ((pixel & 255) * alpha + 255 * blank);
                        weight += area;
                    }
                }
                if (weight <= 0) continue;
                out[y * width + x] = 0xff000000 | ((int) Math.round(sumRed / weight) << 16)
                        | ((int) Math.round(sumGreen / weight) << 8) | (int) Math.round(sumBlue / weight);
            }
        }
        return out;
    }

    /**
     * 第一步：把原图落到 552×768 的像素数组。这一步只做一次，四档风格和缩略图全部复用它。
     *
     * @param cover true = 填满屏幕（裁掉多余），false = 完整显示（留白）
     */
    public static int[] fittedPixels(Bitmap source, int rotation, boolean cover) {
        return fit(rotated(source, rotation), PanelProtocol.WIDTH, PanelProtocol.HEIGHT, cover);
    }

    /**
     * 第二步：用已经缩好的像素出图。换风格只需要重跑这一步，不用再缩放。
     *
     * @param mode 0–3，见 {@link ReinkRaster#MODES}
     */
    public static Prepared prepare(int[] pixels, String panelType, int mode) {
        if (pixels == null || pixels.length != PanelProtocol.WIDTH * PanelProtocol.HEIGHT)
            throw new IllegalArgumentException("像素尺寸错误");
        byte[] codes = ReinkRaster.quantize(pixels, PanelProtocol.WIDTH, PanelProtocol.HEIGHT, mode);
        PanelType type = panelType.isEmpty() ? PanelType.A1 : PanelType.parse(panelType);
        byte[] packed = type.pack(codes);
        // 预览从打包后的字节回读，所见即所发（A0 的转置也包含在内）
        Bitmap image = Bitmap.createBitmap(type.preview(packed), PanelProtocol.WIDTH,
                PanelProtocol.HEIGHT, Bitmap.Config.ARGB_8888);
        String settings = ReinkRaster.MODES[mode] + " · 非白占比 "
                + density(codes, PanelProtocol.WIDTH, PanelProtocol.HEIGHT) + "% · 屏型 " + type;
        return new Prepared(image, packed, settings, panelType);
    }

    /**
     * 四档风格各出一张小图，给风格按钮当缩略图。
     *
     * <p>返回顺序与 {@link #STYLES} 一致。用全图像素隔点取样到 276×384 再出图：
     * 缩略图只有几十 dp 宽，细节看不出来，但四个风格的色调差异一眼就分得清，
     * 而计算量只有全图四分之一。<b>缩略图只为选风格，不是清晰度判据</b>——
     * 判清晰度必须看 1:1 像素的放大图。
     */
    public static Bitmap[] thumbnails(int[] fullPixels) {
        if (fullPixels == null || fullPixels.length != PanelProtocol.WIDTH * PanelProtocol.HEIGHT)
            throw new IllegalArgumentException("像素尺寸错误");
        int[] pixels = new int[THUMB_WIDTH * THUMB_HEIGHT];
        for (int row = 0; row < THUMB_HEIGHT; row++)
            for (int column = 0; column < THUMB_WIDTH; column++)
                pixels[row * THUMB_WIDTH + column] = fullPixels[(row * 2) * PanelProtocol.WIDTH + column * 2];
        Bitmap[] result = new Bitmap[ReinkRaster.MODES.length];
        for (int mode = 0; mode < result.length; mode++) {
            byte[] codes = ReinkRaster.quantize(pixels, THUMB_WIDTH, THUMB_HEIGHT, mode);
            // 跟全图一个口径：先打包再回读，缩略图才和真机刷出来长得一样
            byte[] packed = PanelProtocol.packCodes(codes, THUMB_WIDTH, THUMB_HEIGHT);
            int[] argb = ImageRaster.preview(packed, THUMB_WIDTH, THUMB_HEIGHT);
            result[mode] = Bitmap.createBitmap(argb, THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        return result;
    }

    /**
     * 非白占比（%）：黑 / 黄 / 红都算「要上电、要翻转的像素」，只有纯白不计。
     *
     * <p>旧版只数纯黑（code 0），把黄（2）红（3）全漏了，还挂着「翻转密度」的名字——
     * 数值自然跟肉眼对不上。改成非白占比后，主体越白这个值越低，跟「轻不轻」一致。
     */
    static int density(byte[] codes, int width, int height) {
        int lit = 0;
        for (int row = 0; row < height; row++)
            for (int column = 0; column < width; column++)
                if ((codes[row * width + column] & 3) != 1) lit++;
        return Math.round(100f * lit / (width * (float) height));
    }

}
