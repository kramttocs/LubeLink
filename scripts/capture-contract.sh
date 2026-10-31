#!/usr/bin/env bash
# Captures LubeLogger's responses to every call LubeLink makes, from a DISPOSABLE container with synthetic
# data (scripts/lubelogger-fixture.sh), as API-contract evidence (docs/API-CONTRACT.md) and as the fake
# server's replay data for the contract tests. Never run against a real server.
#
# Usage: scripts/capture-contract.sh <out-dir>     e.g. tests/contract/lubelogger-1.7.4
# Each call is saved as <name>.json (body) and listed in <out-dir>/INDEX.tsv with method, path, status
# and content type. The API keys are never written.
set -euo pipefail
cd "$(dirname "$0")/.."
out=${1:?usage: scripts/capture-contract.sh <out-dir>}
work=$(mktemp -d)
trap './scripts/lubelogger-fixture.sh down; rm -rf "$work"' EXIT
./scripts/lubelogger-fixture.sh up "$work" >/dev/null
# shellcheck disable=SC1091
. "$work/fixture.env"
mkdir -p "$out"
rm -f "$out"/*.json "$out/INDEX.tsv"
printf 'name\tmethod\tpath\tkey\tstatus\tcontent_type\n' > "$out/INDEX.tsv"

# call <name> <key: none|viewer|editor|manager|wrong> <method> <path> [json body]
call() {
  local name=$1 who=$2 method=$3 path=$4 body=${5:-} key=""
  case $who in
    viewer) key=$LUBELOGGER_VIEWER_KEY ;; editor) key=$LUBELOGGER_EDITOR_KEY ;;
    manager) key=$LUBELOGGER_MANAGER_KEY ;; wrong) key=synthetic-wrong-key ;; none) key="" ;;
  esac
  local args=(-s --noproxy '*' -X "$method" -H 'culture-invariant: true' -o "$out/$name.json"
              -w '%{http_code}\t%{content_type}')
  [[ -n $key ]] && args+=(-H "x-api-key: $key")
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  local meta
  meta=$(curl "${args[@]}" "$LUBELOGGER_BASE$path")
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$method" "$path" "$who" "$meta" >> "$out/INDEX.tsv"
  # Pretty-print JSON bodies for review; leave anything else as received.
  python3 -c 'import json,sys
p=sys.argv[1]
try:
    d=json.load(open(p))
except Exception:
    sys.exit(0)
json.dump(d, open(p,"w"), indent=2, ensure_ascii=False); open(p,"a").write("\n")' "$out/$name.json"
}

# Synthetic content the app reads back: one reminder, and one record of each kind with notes and tags.
call write-reminder-add editor POST '/api/vehicle/reminders/add?vehicleId=1' \
  '{"description":"Rotate tires","metric":"Date","dueDate":"2026-11-01","notes":"Synthetic reminder","tags":""}'
call write-service-add editor POST '/api/vehicle/servicerecords/add?vehicleId=1' \
  '{"Date":"2026-10-01","Odometer":"1200","Description":"Oil change","Cost":45.5,"Notes":"Synthetic note","Tags":"synthetic"}'
call write-repair-add editor POST '/api/vehicle/repairrecords/add?vehicleId=1' \
  '{"Date":"2026-10-02","Odometer":"1250","Description":"Brake pads","Cost":120,"Notes":"","Tags":""}'
call write-upgrade-add editor POST '/api/vehicle/upgraderecords/add?vehicleId=3' \
  '{"Date":"2026-10-03","Odometer":"340","Description":"Roof rack","Cost":89.99,"Notes":"","Tags":""}'
call write-fuel-add editor POST '/api/vehicle/gasrecords/add?vehicleId=1' \
  '{"Date":"2026-10-04","Odometer":"1300","Cost":52.1,"Notes":"","Tags":"","FuelConsumed":12.5,"IsFillToFull":"true","MissedFuelUp":"false","ExtraFields":[{"name":"LubeLink Gas Station","value":"Synthetic Fuel Stop","isRequired":false,"fieldType":0},{"name":"LubeLink Station Address","value":"1 Test Way, Testville","isRequired":false,"fieldType":0}]}'

# Every read the app makes (LubeLoggerApi, LubeLoggerEditorApi, LubeLoggerManagerApi).
call whoami-editor editor GET /api/whoami
call whoami-manager manager GET /api/whoami
call vehicles editor GET /api/vehicles
call vehicle-info editor GET /api/vehicle/info
call reminders-all editor GET /api/vehicle/reminders/all
call extrafields editor GET /api/extrafields
for kind in servicerecords repairrecords upgraderecords; do
  call "$kind-vehicle1" editor GET "/api/vehicle/$kind?vehicleId=1"
  call "$kind-all" editor GET "/api/vehicle/$kind/all"
done
call gasrecords-vehicle1 editor GET '/api/vehicle/gasrecords?vehicleId=1&useMPG=true&useUKMPG=false'
call gasrecords-all editor GET '/api/vehicle/gasrecords/all?useMPG=true&useUKMPG=false'
call servicerecords-by-id editor GET '/api/vehicle/servicerecords?vehicleId=1&id=1'
call gasrecords-by-id editor GET '/api/vehicle/gasrecords?vehicleId=1&id=1&useMPG=true&useUKMPG=false'

# Writes the app makes, and their answers.
call write-service-update editor PUT /api/vehicle/servicerecords/update \
  '{"id":"1","vehicleId":"1","date":"2026-10-01","odometer":"1200","description":"Oil and filter","cost":46,"notes":"Synthetic note","tags":"synthetic","files":[],"extraFields":[]}'
call write-repair-delete manager DELETE '/api/vehicle/repairrecords/delete?id=1'

# Error forms the app distinguishes.
call error-no-key none GET /api/vehicles
call error-wrong-key wrong GET /api/vehicles
call error-whoami-wrong-key wrong GET /api/whoami
call error-editor-delete editor DELETE '/api/vehicle/servicerecords/delete?id=1'
call error-record-missing editor GET '/api/vehicle/servicerecords?vehicleId=1&id=999'
call error-delete-missing manager DELETE '/api/vehicle/repairrecords/delete?id=999'
call error-add-unknown-vehicle editor POST '/api/vehicle/servicerecords/add?vehicleId=999' \
  '{"Date":"2026-10-05","Odometer":"1","Description":"x","Cost":1,"Notes":"","Tags":""}'

image=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["imageLocation"])' "$out/vehicles.json")
curl -s --noproxy '*' -H "x-api-key: $LUBELOGGER_EDITOR_KEY" -o /dev/null \
  -w "vehicle-image\tGET\t$image\teditor\t%{http_code}\t%{content_type}\n" "$LUBELOGGER_BASE$image" >> "$out/INDEX.tsv"

# Nothing secret may be kept.
if grep -rqF -e "$LUBELOGGER_EDITOR_KEY" -e "$LUBELOGGER_MANAGER_KEY" -e "$LUBELOGGER_VIEWER_KEY" "$out"; then
  echo "ERROR: an API key appears in the capture" >&2; exit 1
fi
echo "Captured $(($(wc -l < "$out/INDEX.tsv") - 1)) calls from $(docker inspect -f '{{.Config.Image}}' lubelink-it-lubelogger) into $out"
