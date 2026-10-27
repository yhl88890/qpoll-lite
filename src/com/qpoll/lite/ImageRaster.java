package com.qpoll.lite;

/** 屏的四种颜色码和「打包字节 → 像素」的回读。出图算法全在 {@link ReinkRaster}。 */
public final class ImageRaster {
    private static final int[] COLORS = {0xff000000, 0xffffffff, 0xfff5cd00, 0xffc80000};

    public static int color(int code) {
        if (code < 0 || code > 3) throw new IllegalArgumentException("颜色码错误");
        return COLORS[code];
    }

    /**
     * 把屏上真实的打包字节还原成一张 552×768 的位图。
     *
     * <p>打包是「每字节四个竖向像素、从右到左、从下到上、高位在前」（见
     * {@link PanelProtocol#packCodes}），所以回读要反着来——这也是预览和实际屏面
     * 方向一致的原因。别在这儿加滤镜或缩放，所见即所发。
     */
    public static int[] preview(byte[] packed, int width, int height) {
        if (width <= 0 || height <= 0 || height % 4 != 0 || (long) width * height / 4 != packed.length)
            throw new IllegalArgumentException("预览尺寸错误");
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int fromBottom = height - 1 - y, index = (width - 1 - x) * (height / 4) + fromBottom / 4;
                pixels[y * width + x] = COLORS[((packed[index] & 255) >> (6 - 2 * (fromBottom % 4))) & 3];
            }
        return pixels;
    }
}
