#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v javac >/dev/null; then
    compiler=(javac --release 17)
else
    # Some managed images retain the compiler module but omit the javac launcher/ct.sym.
    # This fallback checks Java 17 language/bytecode; CI's full JDK also checks Java 17 APIs.
    compiler=(java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17)
fi
mkdir -p build/protocol-tests
find protocol/src -name '*.java' -print > build/protocol-tests/sources.txt
"${compiler[@]}" -Xlint:all,-options -Werror -d build/protocol-tests @build/protocol-tests/sources.txt
java -ea -cp build/protocol-tests org.openbridge.protocol.ProtocolTest
