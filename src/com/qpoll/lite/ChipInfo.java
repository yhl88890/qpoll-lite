package com.qpoll.lite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从系统里读 NFC 芯片型号，用来判断「这台机器有没有可能支持 Q-Poll」。
 *
 * <p>依据是 NXP 自己的库配置文件（libnfc-nxp.conf 一类）里的 {@code NXP_FW_NAME}，
 * 例如 K90 上是 {@code NXP_FW_NAME="libsn220u_fw.so"} → SN220U。
 *
 * <p><b>这是「固件配置」，不是芯片 ID 实读</b>，只能当参考，不能当判决。
 * 真正的判决在「兼容性自检」里：发一条 NXP 私有指令，看控制器认不认。
 *
 * <p>路径特意列了六个：华为/荣耀的 NFC 配置常年不在 /odm/etc（有的在 /vendor/etc，
 * 有的在 /system/etc），只查两个路径会在华为机上直接漏判成「未知」。
 */
public final class ChipInfo {
    private static final String[] DIRS = {
            "/odm/etc", "/vendor/etc", "/system/etc", "/product/etc",
            "/odm/etc/nfc", "/vendor/etc/nfc"
    };
    private static final Pattern FW_NAME =
            Pattern.compile("^\\s*NXP_FW_NAME\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CHIP =
            Pattern.compile("lib([a-zA-Z0-9]+)_fw\\.so");

    private ChipInfo() { }

    public static String inspect() {
        for (String dir : DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".conf") || !name.contains("nfc")) continue;
                String firmware = firmwareOf(file);
                if (firmware == null) continue;
                // 固件名通常是完整路径（/vendor/firmware/libsn220u_fw.so），所以必须用 find
                // 而不是 matches —— 老版本这里用 matches 永远不中，只能把整条路径打出来。
                Matcher chip = CHIP.matcher(firmware);
                String model = chip.find() ? chip.group(1).toUpperCase(Locale.ROOT) : firmware;
                return "NXP " + model + "\n依据：" + file.getPath() + " / " + firmware
                        + "\n注：读的是系统固件配置，不是芯片 ID 实读";
            }
        }
        return "没找到 NXP 的 NFC 固件配置（/odm/etc、/vendor/etc、/system/etc 都查过了）。\n"
                + "这通常说明这台机器的 NFC 不是 NXP 的 → Q-Poll 大概率不支持。\n"
                + "但别只信这一条，最终结论请看「兼容性自检」。";
    }

    /** 有没有找到 NXP 固件配置。仅供自检报告措辞用，不当作判决。 */
    public static boolean looksLikeNxp() {
        return !inspect().startsWith("没找到");
    }

    private static String firmwareOf(File file) {
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) continue;
                Matcher match = FW_NAME.matcher(trimmed);
                if (match.find()) return match.group(1);
            }
        } catch (Exception ignored) { }
        return null;
    }
}
