#!/usr/bin/env python3
"""极简版墨水屏刷新的打包脚本：用本机 SDK/JDK 直接出包，无 Gradle、无构建期下载。

跟旧工程那套的区别：不带 native 层（不再有 HAL 兼容层），所以没有 build.sha256 校验，
输入就是 src/**/*.java + res + assets + libs 里的四个 Shizuku AAR。

用法：ANDROID_HOME=... JAVA_HOME=... python build-win.py
"""
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
sdk = Path(os.environ["ANDROID_HOME"])
jdk = Path(os.environ["JAVA_HOME"])
bt = sdk / "build-tools/34.0.0"
android = sdk / "platforms/android-34/android.jar"
build_root = root / "build"
build_root.mkdir(parents=True, exist_ok=True)
build = Path(tempfile.mkdtemp(prefix="lite-", dir=build_root))
dist = root / "dist"
for directory in (build / "classes", build / "dex", dist):
    directory.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(jdk), PATH=str(jdk / "bin") + os.pathsep + os.environ.get("PATH", ""))

libraries = []
for aar in sorted((root / "libs").glob("*-13.1.5.aar")):
    jar = build / (aar.stem + ".jar")
    with zipfile.ZipFile(aar) as archive:
        jar.write_bytes(archive.read("classes.jar"))
    libraries.append(jar)
if len(libraries) != 4:
    raise RuntimeError("libs/ 需要固定的 Shizuku api/provider/aidl/shared 13.1.5 四个 AAR")


def run(*args, cwd=root):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)


run(jdk / "bin/javac", "-encoding", "UTF-8", "--release", "8",
    "-classpath", os.pathsep.join(str(p) for p in [android, *libraries]),
    "-d", build / "classes", *sorted((root / "src").rglob("*.java")))
run(jdk / "bin/jar", "cf", build / "classes.jar", "-C", build / "classes", ".")
run(bt / "d8.bat", "--min-api", "26", "--lib", android, "--output", build / "dex",
    build / "classes.jar", *libraries)
run(bt / "aapt.exe", "package", "-f", "-M", root / "AndroidManifest.xml", "-S", root / "res",
    "-A", root / "assets", "-I", android, "-F", build / "unsigned.apk")
with zipfile.ZipFile(build / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(build / "dex/classes.dex", "classes.dex")
run(bt / "zipalign.exe", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
key = build_root / "debug.keystore"
if not key.exists():
    run(jdk / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
        "-alias", "qpolllab", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=QPoll Lite", "-noprompt")
version = ET.parse(root / "AndroidManifest.xml").getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"]
apk = dist / ("QPollLite-" + version + ".apk")
run(bt / "apksigner.bat", "sign", "--ks", key, "--ks-key-alias", "qpolllab", "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--out", apk, build / "aligned.apk")
run(bt / "apksigner.bat", "verify", "--verbose", apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(dist / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n", encoding="utf-8")
print("APK:", apk)
print("SHA256:", digest)
