#!/usr/bin/env bash
# Compile production transport sources once and run eight grouped contract scenarios once.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
for cmd in java keytool kotlinc; do command -v "$cmd" >/dev/null || { echo "Missing tool: $cmd" >&2; exit 1; }; done
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
keytool -genkeypair -alias lan -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -dname 'CN=Gestor LAN test only' -validity 2 -storetype PKCS12 \
  -keystore "$WORK/test.p12" -storepass test-only-fixture -noprompt > "$WORK/keytool.log" 2>&1
SOURCES="$ROOT/app/src/main/java/com/frosst/gestordomestico/link"
kotlinc "$SOURCES/LinkProtocol.kt" "$SOURCES/PairingGate.kt" "$SOURCES/LanTransport.kt" \
  "$ROOT/tools/LinkContract.kt" -jvm-target 1.8 -include-runtime -d "$WORK/link-contract.jar"
java -jar "$WORK/link-contract.jar" "$WORK/test.p12" "$WORK"
