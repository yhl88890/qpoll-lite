# 墨小七（QPoll Lite）

用手机 NFC 直刷四色墨水屏手机壳的安卓 App。从 [QPollLab](https://github.com/yhl88890/huawei-nfc-eink-myron) 改造分支演进而来，本仓库是独立精简版：前台直连、零特权优先、设备画像双路适配。

## 来源与致谢（代码出处）

本项目**不是从零写的**，代码站在下面这些项目的肩膀上，按血缘从远到近：

| 来源 | 拿了什么 | 许可 |
|---|---|---|
| [kur0neko/huawei-epd-pn532](https://github.com/kur0neko/huawei-epd-pn532) | NFC 协议层与工厂扫描打包（`PanelProtocol` 的 D1/D3/D4 指令序列、四色扫描方向、PIN 流程）——**最早的代码起点** | PolyForm Noncommercial 1.0.0（见 `assets/licenses/`） |
| 墨小佳（Reink Case，商业 App） | 四档出图算法（鎏金/质感/色阶/水墨：色彩矩阵 + Atkinson 抖动），从其 Flutter debug 构建提取学习，仅作非商业研究与互操作用途 | 算法归原作者，本项目不包含其代码与资源 |
| [wiwiwei/ink-nfc-lab](https://github.com/wiwiwei/ink-nfc-lab) | NFC 寻卡-传图-刷新流程参考（姊妹仓库 [Ink NFC Lab myron 分支](https://github.com/yhl88890/huawei-nfc-eink-myron) 的上游） | 见其仓库 |
| QPollLab（`org.nfcresearch.qpolllab`） | 工程基底：UI 骨架、PanelSession 会话结构由此改造而来 | 见其仓库 |
| [yhl88890/huawei-nfc-eink-myron](https://github.com/yhl88890/huawei-nfc-eink-myron) | **本仓库的直接前身**——QPollLab 改造分支（Q-Poll 兼容层 + 前台直连，SN100U/SN220 双机验证），墨小七是在其基础上删减重整的精简版 | PolyForm Noncommercial 1.0.0 |

一句话版本：**kur0neko 的协议 + 墨小佳的算法 + QPollLab 的壳 → myron 改造分支 → 墨小七（本仓库）**。

## 功能

- **选图 → 手动裁切 → 出图 → NFC 刷屏** 全链路
- 裁切页：取景框固定 552:768（与屏同比例），图片单指拖动、双指捏合缩放；默认铺满（与自动裁切一致），缩小可做留白构图，白边会真实进入画面（所见即所得）
- 四档出图风格，移植自墨小佳（Reink Case）官方算法：鎏金 / 质感 / 色阶 / 水墨
- 预览从打包字节回读，所见即所发
- 相册选图（ACTION_PICK），两机统一大缩略图体验

## 双机验证

| 机型 | 芯片 | Android | 权限路径 |
|---|---|---|---|
| Redmi Note 12T Pro | NXP SN100U | 15 | 前台 + 反射隐藏接口（**零特权**：无 root / 无 Shizuku / 无 adb 授权） |
| Redmi K90 Pro Max | NXP SN220U | 17 | 前台 + 公开 vendor NCI API（WRITE_SECURE_SETTINGS，adb 授予） |

设备画像按 `SDK_INT` 分路（36+ 为「装死屏」路：收尾全断场 + D4 后干睡保场；35 为「活屏」路：踢遗留 + 保场 + DE 喂场）。两路的供能特性相反——DE 喂场是小线圈的活命开关，却是大线圈的波形杀手，详见源码内注释与实验记录。

## 构建

```
ANDROID_HOME=<sdk> JAVA_HOME=<jdk17> python build-win.py
```

产物在 `dist/`。构建脚本只依赖 Android SDK / JDK17 / Shizuku aar（`libs/`），无 Gradle。

## 许可

- 本项目：PolyForm Noncommercial 1.0.0（非商业使用）
- `assets/licenses/` 内含第三方许可：kur0neko/huawei-epd-pn532（协议与工厂扫描打包）、Shizuku
