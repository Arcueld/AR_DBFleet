#!/bin/bash
# 编译并打包 AR_DBFleet DBeaver 插件。
# 依赖：本机 DBeaver 23.3.0 安装目录（默认 E:/dbEaver）+ JDK 17 编译器。
#
# 两处 Windows 踩坑，改脚本时别动：
#  1) 脚本内的 find 可能解析到 C:\Windows\system32\find.exe，所以只用 shell glob 收源文件。
#  2) 把全部 500+ 个 plugin jar 拼进 -cp 会超过命令行长度上限（os error 206 / Argument list too long），
#     所以只列编译真正需要的 jar。
set -e

cd "$(dirname "$0")/.."
DBEAVER=${DBEAVER_HOME:-E:/dbEaver}
JDK_HOME=${JDK_HOME:-C:/Program Files/Java/jdk-18.0.2.1}
JAVAC="$JDK_HOME/bin/javac.exe"
JAR="$JDK_HOME/bin/jar.exe"

if [ ! -d "$DBEAVER/plugins" ]; then
  echo "找不到 DBeaver 安装目录: $DBEAVER  (用 DBEAVER_HOME 覆盖)" >&2
  exit 1
fi

rm -rf build/classes
mkdir -p build/classes dist

# 收集源文件
: > build/sources.txt
count=0
for f in src/main/java/com/arsenal/dbfleet/*/*.java; do
  [ -f "$f" ] || continue
  printf '%s\n' "$f" >> build/sources.txt
  count=$((count + 1))
done
if [ "$count" -eq 0 ]; then
  echo "没有找到源文件，请在本工程根目录下执行" >&2
  exit 1
fi

# 只挑编译需要的 jar，避免命令行过长
CP=""
for n in \
  org.jkiss.dbeaver.model_ \
  org.jkiss.dbeaver.registry_ \
  org.jkiss.dbeaver.ui_ \
  org.jkiss.dbeaver.ui.navigator_ \
  org.jkiss.dbeaver.ui.editors.connection_ \
  org.jkiss.utils_ \
  org.eclipse.core.runtime_ \
  org.eclipse.core.jobs_ \
  org.eclipse.core.commands_ \
  org.eclipse.equinox.common_ \
  org.eclipse.equinox.registry_ \
  org.eclipse.jface_ \
  org.eclipse.swt_ \
  org.eclipse.swt.win32.win32.x86_64_ \
  org.eclipse.ui_ \
  org.eclipse.ui.workbench_ \
  org.eclipse.e4.ui.workbench_
do
  for f in "$DBEAVER"/plugins/${n}*.jar; do
    [ -f "$f" ] && CP="$CP$f;"
  done
done
CP=${CP%;}

if [ -z "$CP" ]; then
  echo "没有在 $DBEAVER/plugins 下找到需要的依赖 jar" >&2
  exit 1
fi

echo "编译中（$count 个源文件）..."
"$JAVAC" --release 17 -encoding UTF-8 -nowarn -Xlint:none \
  -cp "$CP" -d build/classes @build/sources.txt

cp -r src/main/resources/. build/classes/

echo "打包中..."
"$JAR" cfM dist/com.arsenal.dbfleet_1.0.0.jar -C build/classes .

echo "生成: dist/com.arsenal.dbfleet_1.0.0.jar"
ls -l dist/com.arsenal.dbfleet_1.0.0.jar
