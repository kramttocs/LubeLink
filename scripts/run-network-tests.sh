#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in javac keytool kotlinc kotlin; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing $tool" >&2; exit 1; }
done
if ! kotlinc -version 2>&1 | grep -q 'kotlinc-jvm 2.1.20'; then
  if [[ "${LUBELINK_ALLOW_UNPINNED_KOTLIN:-0}" == "1" ]]; then
    echo "WARNING: diagnostic only; non-pinned Kotlin compiler, not the v1.2 pinned compile gate" >&2
  else
  echo "ERROR: Network tests require the pinned Kotlin 2.1.20; installed: $(kotlinc -version 2>&1)" >&2
    exit 2
  fi
fi
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms128m}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/com/kramttocs/lubelink/core
keytool -genkeypair -alias synthetic -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=localhost' -ext SAN=dns:localhost,ip:127.0.0.1 \
  -storetype PKCS12 -keystore "$out/local-mock.p12" -storepass changeit -keypass changeit \
  -noprompt >/dev/null 2>&1
javac --release 17 -d "$out" tests/jvm_harness/SyntheticServer.java tests/jvm_harness/ContractReplayServer.java
# Test-only JSON and coroutine shims; real production Editor/Manager Kotlin sources.
kotlinc -cp "$out" "$src/Domain.kt" "$src/EditorRules.kt" "$src/Reports.kt" \
  "$src/RecordFieldRules.kt" "$src/ExtraFieldValues.kt" "$src/VehicleDetails.kt" "$src/ImageDiskCache.kt" "$src/LubeLoggerEditorApi.kt" \
  "$src/LubeLoggerManagerApi.kt" "$src/LubeLoggerApi.kt" "$src/ApiResponse.kt" \
  tests/jvm_harness/CoroutineFixture.kt tests/jvm_harness/JsonFixture.kt \
  tests/jvm_harness/AndroidReadFixture.kt tests/jvm_harness/ReadCacheFixture.kt \
  tests/jvm_harness/ProductionClientNetworkChecks.kt tests/jvm_harness/ContractReplayChecks.kt -d "$out"
kotlin -cp "$out" com.kramttocs.lubelink.core.ProductionClientNetworkChecksKt \
  "$out/local-mock.p12"
# The captured LubeLogger responses, replayed through the same production clients.
for capture in tests/contract/lubelogger-*/; do
  kotlin -cp "$out" com.kramttocs.lubelink.core.ContractReplayChecksKt "$out/local-mock.p12" "$capture"
done
