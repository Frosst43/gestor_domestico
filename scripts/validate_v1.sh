#!/usr/bin/env bash
# One build invocation. No installs, clean, device changes, commits or retries.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -f app/build.gradle.kts && -f settings.gradle.kts ]] || fail 'No es la raiz del proyecto Android.'
command -v git >/dev/null || fail 'Falta git.'
command -v python3 >/dev/null || fail 'Falta python3 para leer los resultados XML.'
command -v sha256sum >/dev/null || fail 'Falta sha256sum.'
[[ "$(git rev-parse --show-toplevel)" == "$ROOT" ]] || fail 'Directorio Git inesperado.'
[[ "$(git branch --show-current)" == 'develop/android-v1-foundation' ]] || fail 'Rama distinta de develop/android-v1-foundation. No se cambia automaticamente.'
export JAVA_HOME="$ROOT/.local-tools/jdk17"
export ANDROID_HOME="$ROOT/.local-tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$ROOT/.local-tools/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE="$ROOT/.local-tools/gradle-8.14/bin/gradle"
for path in "$JAVA_HOME/bin/java" "$JAVA_HOME/bin/javac" "$GRADLE" "$ANDROID_HOME/build-tools/35.0.0/apksigner"; do
    [[ -x "$path" ]] || fail "No existe la herramienta local: $path"
done
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || fail 'Falta Android API 35.'
[[ -f local.properties ]] || fail 'Falta local.properties; no se crea ni sobrescribe.'
grep -Fxq "sdk.dir=$ANDROID_HOME" local.properties || fail 'sdk.dir no coincide con el SDK local esperado.'
git check-ignore -q .local-tools/ || fail '.local-tools/ debe estar ignorado por Git.'
git check-ignore -q local.properties || fail 'local.properties debe estar ignorado por Git.'

RUN="$(mktemp -d "$ROOT/.local-tools/validation-v1-$(date +%Y%m%d-%H%M%S)-XXXXXX")"
printf 'INCOMPLETE\n' > "$RUN/result.txt"
trap 'rc=$?; if ((rc != 0)); then printf "FAIL (exit=%s). Consultar los logs; sin reintento automatico.\n" "$rc" > "$RUN/result.txt"; fi; printf "Informes: %s\n" "$RUN"' EXIT
{
    git rev-parse HEAD
    git status --short
    printf 'JAVA_HOME=%s\nANDROID_HOME=%s\nGRADLE_USER_HOME=%s\n' "$JAVA_HOME" "$ANDROID_HOME" "$GRADLE_USER_HOME"
} > "$RUN/environment.txt"
git diff --binary HEAD -- . ':!.gitignore' > "$RUN/local-changes.patch"

# A single invocation compiles both tests and the debug APK.
set +e
"$GRADLE" --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug 2>&1 | tee "$RUN/build.log"
PIPE_CODES=("${PIPESTATUS[@]}")
set -e
printf 'GRADLE_EXIT_CODE=%s\nTEE_EXIT_CODE=%s\n' "${PIPE_CODES[0]}" "${PIPE_CODES[1]}" > "$RUN/exit-code.txt"
(( PIPE_CODES[0] == 0 )) || fail 'Gradle fallo. No se ejecutara otra compilacion.'
(( PIPE_CODES[1] == 0 )) || fail 'No se pudo guardar el log completo.'

python3 - "$ROOT" "$RUN" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

root, run = map(pathlib.Path, sys.argv[1:])
xml_dir = root / "app/build/test-results/testDebugUnitTest"
reports = sorted(xml_dir.glob("TEST-*.xml"))
if not reports:
    raise SystemExit("ERROR: Gradle termino, pero faltan resultados XML de pruebas.")
required = {
    "com.frosst.gestordomestico.ShoppingRulesTest",
    "com.frosst.gestordomestico.BackupCodecTest",
}
totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
seen = set()
lines = []
for path in reports:
    suite = ET.parse(path).getroot()
    seen.add(suite.get("name", ""))
    counts = {key: int(suite.get(key, "0")) for key in totals}
    for key, value in counts.items():
        totals[key] += value
    lines.append(f"{suite.get('name')}: {counts}")
    (run / path.name).write_bytes(path.read_bytes())
lines.append(f"TOTAL: {totals}")
summary = "\n".join(lines) + "\n"
(run / "tests.txt").write_text(summary, encoding="utf-8")
print(summary, end="")
if not required.issubset(seen) or totals["tests"] < 8:
    raise SystemExit("ERROR: Falta alguna de las suites esperadas.")
if any(totals[key] for key in ("failures", "errors", "skipped")):
    raise SystemExit("ERROR: Hay pruebas fallidas u omitidas.")
PY

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || fail 'No se genero un APK debug valido.'
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$APK" > "$RUN/apk-signature.txt" 2>&1
sha256sum "$APK" | tee "$RUN/apk-sha256.txt"
printf 'BUILD_AND_UNIT_TESTS=PASS\nDEVICE_CHECK=PENDING\nRELEASE=PENDING\nAPK=%s\n' "$APK" | tee "$RUN/result.txt"
