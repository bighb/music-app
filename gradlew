#!/bin/sh
# Wrapper script for macOS / Linux
GRADLE_BIN="/Users/huangben/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle"
if [ -x "$GRADLE_BIN" ]; then
    exec "$GRADLE_BIN" "$@"
else
    exec gradle "$@"
fi
