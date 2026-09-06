#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -x "$BASE_DIR/runtime/bin/java" ]; then
  JAVA_EXE="$BASE_DIR/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE=$(command -v java || true)
fi

JAR=$(find "$BASE_DIR" -maxdepth 1 -type f -name 'litematic-gpu-agent-*-all.jar' -print -quit)
if [ -z "$JAR" ] && [ -d "$BASE_DIR/build/libs" ]; then
  JAR=$(find "$BASE_DIR/build/libs" -maxdepth 1 -type f -name 'litematic-gpu-agent-*-all.jar' -print -quit)
fi
if [ -z "$JAR" ] && [ -d "$BASE_DIR/build/distributions" ]; then
  JAR=$(find "$BASE_DIR/build/distributions" -maxdepth 1 -type f -name 'litematic-gpu-agent-*-all.jar' -print -quit)
fi

if [ -z "${JAVA_EXE:-}" ]; then
  echo '未找到 Java 25，请安装 Java 25 后重试。' >&2
  exit 1
fi
if [ -z "$JAR" ]; then
  echo '未找到 litematic-gpu-agent-*-all.jar。' >&2
  exit 1
fi

exec "$JAVA_EXE" -jar "$JAR" "$@"
