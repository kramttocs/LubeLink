#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v kotlinc >/dev/null 2>&1 || ! command -v kotlin >/dev/null 2>&1; then
  echo 'Install Kotlin command-line compiler to run these offline checks.' >&2
  exit 1
fi
# The networking harness compiles the production clients as a second module.
# Kotlin's 256 MiB CLI default can GC-thrash during this compile.
if ! kotlinc -version 2>&1 | grep -q 'kotlinc-jvm 2.1.20'; then
  if [[ "${LUBELINK_ALLOW_UNPINNED_KOTLIN:-0}" == "1" ]]; then
    echo "WARNING: diagnostic only; non-pinned Kotlin compiler, not the v1.2 pinned compile gate" >&2
  else
  echo "ERROR: Offline checks require Kotlin 2.1.20; installed: $(kotlinc -version 2>&1)" >&2
    exit 2
  fi
fi
export JAVA_OPTS="${JAVA_OPTS:--Xmx640m -Xms64m}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/com/kramttocs/lubelink/core
kotlinc "$src/Domain.kt" "$src/EditorRules.kt" "$src/CopyRecordRules.kt" \
  "$src/Reports.kt" "$src/NotificationRules.kt" "$src/WriteSuccessNotice.kt" "$src/FormSessionRules.kt" \
  "$src/StationBounds.kt" "$src/StationAreaCachePolicy.kt" "$src/RecordFieldRules.kt" "$src/OdometerChart.kt" \
  tests/DomainChecks.kt -d "$out"
kotlin -cp "$out" com.kramttocs.lubelink.core.DomainChecksKt

# Synthetic localhost-only HTTPS probe of JDK transport behavior. The separate
# scripts/run-network-tests.sh uses LubeLink's actual write clients.
if command -v javac >/dev/null 2>&1 && command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair -alias synthetic -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=localhost' -ext SAN=dns:localhost,ip:127.0.0.1 \
    -storetype PKCS12 -keystore "$out/local-mock.p12" -storepass changeit -keypass changeit \
    -noprompt >/dev/null 2>&1
  javac --release 17 -d "$out" tests/StreamingReplayProbe.java
  java -cp "$out" StreamingReplayProbe "$out/local-mock.p12"
else
  echo 'NOT RUN: javac or keytool missing; HTTP probe is a required gate.' >&2
  exit 2
fi
