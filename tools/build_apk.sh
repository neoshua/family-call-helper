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

# 版本号：由 CI 通过环境变量传入（VERSION_NAME / VERSION_CODE），本地未传时用默认值。
# 关键点：App 内部显示的版本号（versionName）与产出的 APK 文件名用同一个值，
# 避免出现「安装后显示 1.1.0、下载的包却叫 v1.5」这种对不上的情况。
VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.1.0}
APK_OUT="dist/亲情接听助手-v${VERSION_NAME}.apk"
echo "构建版本：versionName=${VERSION_NAME} versionCode=${VERSION_CODE}"

mkdir -p build/gen build/obj build/dex dist
rm -rf build/*.apk
# 【重要】清空上一次的编译中间产物（*.class / R.java）。
# 已删除的类如果残留在 build/obj 里，会被 d8 一并打进 classes.dex：
# 虽然不会被调用，但等于把废弃代码（连带被删掉的界面）又塞回了安装包。
rm -rf build/obj build/gen build/dex
mkdir -p build/gen build/obj build/dex
# 【重要】清空 dist 里的旧包。
# 否则 dist/ 里残留的历史 APK 会和本次产物一起被 CI 扫到，
# 而发布步骤若用「取第一个 apk」的方式，就会把旧包当成新包发出去。
rm -f dist/*.apk dist/*.idsig

# 1. 编译资源
"$AAPT2" compile --dir app/src/main/res -o build/res.zip

# 2.【重要】把版本号注入清单副本。
# 直接用 aapt2 的 --version-code/--version-name 是无效的：当清单里已经写了
# android:versionCode / versionName 时，清单属性的优先级更高，会把命令行参数覆盖掉，
# 导致「包名叫 v1.6，装完却显示 1.1.0」。所以这里改清单副本，保证两边一致。
MANIFEST=build/AndroidManifest.xml
sed -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"${VERSION_CODE}\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"${VERSION_NAME}\"/" \
    app/src/main/AndroidManifest.xml > "$MANIFEST"
echo "清单版本号：$(grep -o 'android:versionName="[^"]*"' "$MANIFEST")"

# 3. 链接生成基础 APK + R.java
"$AAPT2" link -o build/app-unsigned.apk \
    -I "$JAR" \
    --manifest "$MANIFEST" \
    --java build/gen \
    --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    build/res.zip

# 4. 编译 Java
# 说明：用 -classpath 而不是 -bootclasspath。JDK 9 以后 javac 已忽略 -bootclasspath，
# 若在部分 JDK 上生效还会把 java.lang.String 这类核心类一起屏蔽掉，导致满屏
# "cannot find symbol / class file for java.lang.String not found"。
find app/src/main/java build/gen -name "*.java" > build/sources.txt
javac -source 1.8 -target 1.8 -Xlint:-options -nowarn -classpath "$JAR" -d build/obj @build/sources.txt

# 5. 转 DEX（注意：输出目录必须以 / 结尾）
find build/obj -name "*.class" > build/classes.txt
"$D8" --release --lib "$JAR" --min-api 21 \
    --output build/dex/ $(cat build/classes.txt)

# 6. 把 classes.dex 打入 APK
cp build/app-unsigned.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)

# 7. 对齐 + 签名
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f tools/keystore.jks ]; then
    keytool -genkeypair -keystore tools/keystore.jks -alias callhelper \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass callhelper2024 -keypass callhelper2024 \
        -dname "CN=Family Call Helper, OU=Family, O=Home, L=Beijing, ST=Beijing, C=CN"
fi
"$APKSIGNER" sign --ks tools/keystore.jks --ks-pass pass:callhelper2024 \
    --key-pass pass:callhelper2024 --out "$APK_OUT" build/aligned.apk

# 8. 验证：必须打印出与文件名一致的版本号
"$APKSIGNER" verify --print-certs "$APK_OUT"
# 注意：这里先把输出收进变量再 head。直接 `aapt2 dump badging ... | head -8` 时，
# head 读够 8 行就退出，aapt2 继续往管道写会收到 SIGPIPE（退出码 141），
# 在 set -o pipefail 下整个脚本会被判为失败——明明包已经构建好了。
BADGING=$("$AAPT2" dump badging "$APK_OUT")
echo "$BADGING" | head -8
echo ""
echo "✅ 构建完成: $APK_OUT"
echo "   文件名版本: v${VERSION_NAME} / versionCode ${VERSION_CODE}"
echo "   （App 内「设置」页底部会显示同一个版本号）"
