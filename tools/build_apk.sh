#!/usr/bin/env bash
set -euo pipefail
# 切到工程根目录（脚本位于 tools/ 下）
cd "$(dirname "$0")/.."

# 优先使用环境变量指定的工具链，否则自动查找
BT=${BT:-""}
JAR=${JAR:-""}
if [ -z "$BT" ] || [ -z "$JAR" ]; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/build-tools/34.0.0/aapt2" ]; then
        BT="$ANDROID_HOME/build-tools/34.0.0"
        JAR="$ANDROID_HOME/platforms/android-34/android.jar"
    fi
fi

if [ -z "$BT" ] || [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "错误：找不到 Android 构建工具链。"
    echo "请设置 ANDROID_HOME，或手动指定 BT 和 JAR 环境变量。"
    echo "示例："
    echo "  BT=/path/to/build-tools/34.0.0 JAR=/path/to/android.jar bash tools/build_apk.sh"
    exit 1
fi

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

mkdir -p build/gen build/obj build/dex dist
rm -rf build/*.apk

# 1. 编译资源
"$AAPT2" compile --dir app/src/main/res -o build/res.zip

# 2. 链接生成基础 APK + R.java
"$AAPT2" link -o build/app-unsigned.apk \
    -I "$JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --java build/gen \
    --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0.0 \
    build/res.zip

# 3. 编译 Java
find app/src/main/java build/gen -name "*.java" > build/sources.txt
javac -source 1.8 -target 1.8 -Xlint:-options -bootclasspath "$JAR" -d build/obj @build/sources.txt

# 4. 转 DEX（注意：输出目录必须以 / 结尾）
find build/obj -name "*.class" > build/classes.txt
"$D8" --release --lib "$JAR" --min-api 21 \
    --output build/dex/ $(cat build/classes.txt)

# 5. 把 classes.dex 打入 APK
cp build/app-unsigned.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)

# 6. 对齐 + 签名
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f tools/keystore.jks ]; then
    keytool -genkeypair -keystore tools/keystore.jks -alias callhelper \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass callhelper2024 -keypass callhelper2024 \
        -dname "CN=Family Call Helper, OU=Family, O=Home, L=Beijing, ST=Beijing, C=CN"
fi
"$APKSIGNER" sign --ks tools/keystore.jks --ks-pass pass:callhelper2024 \
    --key-pass pass:callhelper2024 --out "dist/亲情接听助手-v1.0.apk" build/aligned.apk

# 7. 验证
"$APKSIGNER" verify --print-certs "dist/亲情接听助手-v1.0.apk"
"$AAPT2" dump badging "dist/亲情接听助手-v1.0.apk" | head -8

echo ""
echo "✅ 构建完成: dist/亲情接听助手-v1.0.apk"
