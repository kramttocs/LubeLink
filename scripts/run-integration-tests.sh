#!/usr/bin/env bash
# Real-server integration tests: production clients against a DISPOSABLE LubeLogger container
# started by scripts/lubelogger-fixture.sh. Needs Docker, JDK 17+ and the pinned kotlinc 2.1.20.
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in docker javac keytool kotlinc kotlin; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing $tool" >&2; exit 1; }
done
if ! kotlinc -version 2>&1 | grep -q 'kotlinc-jvm 2.1.20'; then
  echo "ERROR: integration tests require the pinned Kotlin 2.1.20; installed: $(kotlinc -version 2>&1)" >&2
  exit 2
fi
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms128m}"
out="$(mktemp -d)"
trap './scripts/lubelogger-fixture.sh down; rm -rf "$out"' EXIT
./scripts/lubelogger-fixture.sh up "$out/fixture"
# shellcheck disable=SC1091
. "$out/fixture/fixture.env"
keytool -genkeypair -alias synthetic -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=localhost' -ext SAN=dns:localhost,ip:127.0.0.1 \
  -storetype PKCS12 -keystore "$out/local.p12" -storepass changeit -keypass changeit \
  -noprompt >/dev/null 2>&1
javac --release 17 -d "$out" tests/integration/TlsProxy.java
src=app/src/main/java/com/kramttocs/lubelink/core
# Same test-only JSON/coroutine/Android-image shims as the synthetic network harness.
kotlinc -cp "$out" "$src/Domain.kt" "$src/EditorRules.kt" "$src/Reports.kt" \
  "$src/RecordFieldRules.kt" "$src/ExtraFieldValues.kt" "$src/VehicleDetails.kt" "$src/ImageDiskCache.kt" "$src/LubeLoggerEditorApi.kt" \
  "$src/LubeLoggerManagerApi.kt" "$src/LubeLoggerApi.kt" "$src/ApiResponse.kt" \
  tests/jvm_harness/CoroutineFixture.kt tests/jvm_harness/JsonFixture.kt \
  tests/jvm_harness/AndroidReadFixture.kt tests/jvm_harness/ReadCacheFixture.kt \
  tests/integration/RealServerChecks.kt -d "$out"
port=${LUBELOGGER_BASE##*:}
kotlin -cp "$out" com.kramttocs.lubelink.core.RealServerChecksKt \
  "$out/local.p12" "$port" "$LUBELOGGER_VIEWER_KEY" "$LUBELOGGER_EDITOR_KEY" "$LUBELOGGER_MANAGER_KEY"
