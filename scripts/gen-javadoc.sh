#!/usr/bin/env bash
# 生成可浏览的 Javadoc HTML（含 package-info 的代码地图）。
#
# 为什么不用 `mvn javadoc:javadoc`：maven-javadoc-plugin 本地仓库里没有，
# 离线环境下载不到。这里改成两条都能离线跑的命令：
#   1) maven-dependency-plugin 导出依赖 classpath（本地仓库已有）
#   2) 直接用 JDK 自带的 javadoc 工具生成
#
# 用法：bash scripts/gen-javadoc.sh
# 产出：target/site/apidocs/index.html  —— 用浏览器打开
#
# ⚠️ 三个编码参数（-encoding/-docencoding/-charset）不能省，否则中文网页乱码。
#    命令行里 javadoc 自身的提示信息仍可能是 GBK（Windows 中文 locale），
#    属控制台问题，不影响生成的 HTML。

set -euo pipefail
cd "$(dirname "$0")/.."

OUT=target/site/apidocs
CP_FILE=target/javadoc-classpath.txt

echo "==> 导出依赖 classpath（离线）"
mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

echo "==> 清理旧输出"
rm -rf "$OUT"

echo "==> 生成 Javadoc"
javadoc \
  -J-Dstdout.encoding=UTF-8 -J-Dstderr.encoding=UTF-8 \
  -d "$OUT" \
  -classpath "$(cat "$CP_FILE")" \
  -sourcepath src/main/java \
  -subpackages org.agent \
  -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 \
  -Xdoclint:none \
  --release 21 \
  -quiet

echo
echo "==> 完成：$OUT/index.html"
echo "    浏览器打开，或：start $OUT/index.html"
