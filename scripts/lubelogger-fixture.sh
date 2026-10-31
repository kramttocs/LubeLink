#!/usr/bin/env bash
# Start a DISPOSABLE LubeLogger container with synthetic data for integration tests.
# It creates a root account, API keys, vehicles and extra fields, so it must never be pointed at a
# real server: it only ever talks to the container it starts on 127.0.0.1.
#
# Usage: scripts/lubelogger-fixture.sh up <out-dir>   writes <out-dir>/fixture.env
#        scripts/lubelogger-fixture.sh down
set -euo pipefail
image=${LUBELOGGER_IMAGE:-hargata/lubelogger:v1.7.4}
name=${LUBELOGGER_CONTAINER:-lubelink-it-lubelogger}
port=${LUBELOGGER_PORT:-18080}
locale=${LUBELOGGER_LOCALE:-en_US.UTF-8}   # server culture, e.g. de_DE.UTF-8 for comma decimals
base="http://127.0.0.1:$port"

wait_ready() {
  for _ in $(seq 1 90); do
    curl -fsS -o /dev/null "$base/" 2>/dev/null && return 0
    sleep 2
  done
  echo "LubeLogger did not start" >&2; docker logs "$name" >&2 || true; exit 1
}

if [[ ${1:-} == down ]]; then docker rm -f "$name" >/dev/null 2>&1 || true; exit 0; fi
[[ ${1:-} == up ]] || { echo "usage: $0 up <out-dir> | down" >&2; exit 2; }
out=${2:?out dir}
mkdir -p "$out"
jar="$out/cookies"; rm -f "$jar"

docker rm -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" -p "127.0.0.1:$port:8080" -e "LC_ALL=$locale" -e "LANG=$locale" "$image" >/dev/null
wait_ready

post() { curl -fsS -b "$jar" -c "$jar" -X POST "$base$1" "${@:2}"; }
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

# Auth is off on a fresh container, so keys and fixtures are created first (as root); the root
# account is created last, because LubeLogger starts enforcing auth once it reloads that config.
key() { post /Home/CreateAPIKeyForUser --data-urlencode "keyName=$1" "${@:2}" | json '["additionalData"]["apiKey"]'; }
viewer=$(key it-viewer)
editor=$(key it-editor --data-urlencode 'permissions[]=Edit')
manager=$(key it-manager --data-urlencode 'permissions[]=Edit' --data-urlencode 'permissions[]=Delete')

vehicle() {  # year make model plate isElectric useHours [more form fields]
  post /Vehicle/SaveVehicle --data-urlencode id=0 --data-urlencode "year=$1" --data-urlencode "make=$2" \
    --data-urlencode "model=$3" --data-urlencode "licensePlate=$4" --data-urlencode "isElectric=$5" \
    --data-urlencode "useHours=$6" --data-urlencode odometerOptional=false \
    --data-urlencode hasOdometerAdjustment=false --data-urlencode odometerMultiplier=1 \
    --data-urlencode odometerDifference=0 --data-urlencode vehicleIdentifier=LicensePlate "${@:7}" \
    | json '["success"]' >/dev/null
}
# The Wagon carries details the vehicle-details dialog shows (OD-49): a purchase, tags, diesel. ISO dates and
# whole prices read the same in every server culture. It stays unsold, so the other scenarios see a vehicle in use.
vehicle 2015 Synthetic Wagon TEST-001 false false --data-urlencode isDiesel=true \
  --data-urlencode purchaseDate=2019-03-14 --data-urlencode purchasePrice=27450 \
  --data-urlencode 'tags[]=synthetic-family' --data-urlencode 'tags[]=synthetic-commuter'
vehicle 2021 Synthetic Volt TEST-EV1 true false --data-urlencode isDiesel=false
vehicle 2010 Synthetic Tractor TEST-HRS false true --data-urlencode isDiesel=false

# The two optional Text fields LubeLink stores station details in (GasRecord = import mode 2).
post /Home/UpdateExtraFields --data-urlencode id=2 \
  --data-urlencode 'extraFields[0][name]=LubeLink Gas Station' --data-urlencode 'extraFields[0][isRequired]=false' \
  --data-urlencode 'extraFields[0][fieldType]=0' \
  --data-urlencode 'extraFields[1][name]=LubeLink Station Address' --data-urlencode 'extraFields[1][isRequired]=false' \
  --data-urlencode 'extraFields[1][fieldType]=0' >/dev/null

post /Login/CreateLoginCreds --data-urlencode 'credentials[userName]=synthetic-root' \
  --data-urlencode 'credentials[password]=synthetic-pass-1' >/dev/null
docker restart "$name" >/dev/null
wait_ready
status=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/vehicles")
[[ $status == 401 ]] || { echo "Auth not enforced after restart (GET without key -> $status)" >&2; exit 1; }

cat > "$out/fixture.env" <<EOF
LUBELOGGER_BASE=$base
LUBELOGGER_VIEWER_KEY=$viewer
LUBELOGGER_EDITOR_KEY=$editor
LUBELOGGER_MANAGER_KEY=$manager
EOF
echo "Disposable LubeLogger ready at $base ($image, $locale); keys in $out/fixture.env"
