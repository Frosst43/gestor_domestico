#!/usr/bin/env bash
# One focused contract run; no network, Gradle, Android device, package installs or retries.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
for tool in kotlinc java keytool python3 sha256sum; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
OUT="${1:-$ROOT/.local-tools/exchange-contract-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd -P)"
LAB="$(mktemp -d)"
trap 'rm -rf -- "$LAB"' EXIT
SRC="$ROOT/app/src/main/java/com/frosst/gestordomestico"
FILES=("$SRC/SyncSql.kt" "$SRC/exchange/ExchangeProtocol.kt" "$SRC/exchange/ExchangeSql.kt" "$SRC/exchange/ShoppingExchange.kt"
       "$SRC/link/LinkProtocol.kt" "$SRC/link/PairingGate.kt" "$SRC/link/LanTransport.kt" "$ROOT/tools/ExchangeContract.kt")
{
    java -version 2>&1
    kotlinc -version 2>&1
    python3 -c 'import sqlite3; print("SQLite", sqlite3.sqlite_version)'
    sha256sum "${FILES[@]}" "$ROOT/tools/exchange_sqlite_bridge.py"
} > "$OUT/environment.txt"
PASS="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
keytool -genkeypair -alias test -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 2 \
    -dname CN=gestor-exchange-laboratory -storetype PKCS12 -keystore "$LAB/test.p12" \
    -storepass "$PASS" -keypass "$PASS" > "$OUT/certificate-setup.log" 2>&1
kotlinc "${FILES[@]}" -jvm-target 17 -include-runtime -d "$LAB/exchange.jar" 2>&1 | tee "$OUT/compile.log"
mkdir "$LAB/databases"
java -jar "$LAB/exchange.jar" "$LAB/databases" "$ROOT/tools/exchange_sqlite_bridge.py" "$LAB/test.p12" "$PASS" \
    2>&1 | tee "$OUT/shopping-exchange-contract.log"
printf '%s\n' 'CONTRACT=PASS' 'ANDROID_BUILD=PENDING' 'TWO_PHONES=PENDING' > "$OUT/result.txt"
echo "Evidence: $OUT"
