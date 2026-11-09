#!/usr/bin/env bash
# Run on the media EC2 after staging the root-only environment and wake assets.
set -Eeuo pipefail
[[ $EUID == 0 ]] || { echo 'Run as root.' >&2; exit 2; }
release=${1:?usage: deploy/ec2/install.sh RELEASE_SHA}
[[ $release =~ ^[a-f0-9]{40}$ ]] || { echo 'Expected a 40-character release SHA.' >&2; exit 2; }
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$source_root"

root=/opt/mar-voice
state=/var/lib/mar-voice
service=mar-voice-ai.service
unit=/etc/systemd/system/$service
environment=/etc/mediassistrover/voice-ai.env
[[ -s $environment && $(stat -c '%u:%a' "$environment") == 0:600 ]] || {
  echo 'Install the root-owned voice-ai.env (mode 600) before deploying.' >&2; exit 2;
}
for file in encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt; do
  [[ -s $state/wake-model/$file ]] || { echo "Missing wake model file $file." >&2; exit 2; }
done

exec 9>/run/lock/mar-voice-deploy.lock
flock -n 9 || { echo 'Another voice deploy is running.' >&2; exit 1; }
getent passwd marvoice >/dev/null || useradd --system --user-group --home-dir "$state" --shell /usr/sbin/nologin marvoice
install -d -m 0755 "$root/releases" "$root/venvs" "$state/data"
for file in faqs.json hospital_vector_store.json; do
  if [[ ! -e $state/data/$file ]]; then
    install -m 0644 "data/$file" "$state/data/$file"
  fi
done

if ! python3 -c 'import ensurepip' 2>/dev/null; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3-venv
fi
requirements_hash=$( { sha256sum backend/requirements.voice.txt deploy/ec2/requirements.txt; python3 -V; } | sha256sum | cut -c1-40 )
venv=$root/venvs/$requirements_hash
if [[ ! -f $venv/.ready ]]; then
  python3 -m venv "$venv"
  nice -n 10 "$venv/bin/python" -m pip install --disable-pip-version-check --only-binary=:all: -r deploy/ec2/requirements.txt
  touch "$venv/.ready"
fi

ready() {
  curl -fsS --max-time 2 http://127.0.0.1:7861/health 2>/dev/null |
    python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("state")=="READY" and d["wake"]["enabled"] and d["wake"]["loaded"]' 2>/dev/null
}
release_dir=$root/releases/$release
previous=$(readlink "$root/current" 2>/dev/null || true)
if [[ -n $previous && ( $previous != "$root/releases/"* || ! -d $previous ) ]]; then
  echo 'Unexpected current release path.' >&2; exit 2
fi
if [[ $previous == "$release_dir" ]] && cmp -s deploy/ec2/mar-voice-ai.service "$unit" && ready; then
  echo "VOICE_READY release=$release"
  exit 0
fi
stage=$(mktemp -d "$root/.staged.XXXXXX")
switched=false
if [[ -f $unit ]]; then cp -- "$unit" "$stage/previous.service"; fi
cleanup() {
  status=$?
  if (( status != 0 )) && $switched; then
    if [[ -f $stage/previous.service ]]; then
      install -m 0644 "$stage/previous.service" "$unit"
    else
      rm -f -- "$unit"
    fi
    if [[ -n $previous ]]; then
      ln -sfn "$previous" "$stage/restore"
      mv -Tf "$stage/restore" "$root/current"
      systemctl daemon-reload
      systemctl restart "$service" || true
    else
      systemctl stop "$service" || true
      rm -f -- "$root/current"
      systemctl daemon-reload
    fi
  fi
  rm -rf -- "$stage"
  return "$status"
}
trap cleanup EXIT

if [[ ! -d $release_dir ]]; then
  install -d -m 0755 "$stage/release"
  cp -r -- src "$stage/release/src"
  ln -s "$venv" "$stage/release/.venv"
  mv -T "$stage/release" "$release_dir"
fi
ln -s "$release_dir" "$stage/current"
switched=true
install -m 0644 deploy/ec2/mar-voice-ai.service "$unit"
mv -Tf "$stage/current" "$root/current"
systemctl daemon-reload
systemctl restart "$service"

for attempt in $(seq 1 30); do
  if ready; then
    systemctl enable "$service" >/dev/null
    echo "VOICE_READY release=$release"
    exit 0
  fi
  sleep 1
done
echo 'New voice release failed the readiness check. Restoring the previous release.' >&2
exit 1
