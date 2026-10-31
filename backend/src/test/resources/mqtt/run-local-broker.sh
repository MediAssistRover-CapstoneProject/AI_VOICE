#!/bin/sh
set -eu
base=${MQTT_TEST_BROKER_HOME:?test broker extraction directory required}
action=${1:?start or stop}
port=${2:?port}
case "$port" in *[!0-9]*|'') exit 2;; esac
run="$base/run-$port"
if [ "$action" = stop ]; then
    if [ -f "$run/pid" ]; then
        pid=$(cat "$run/pid")
        if [ -r "/proc/$pid/cmdline" ]; then
            cmd=$(tr '\000' ' ' < "/proc/$pid/cmdline")
            case "$cmd" in "$base/extracted/usr/sbin/mosquitto"*) kill "$pid";; esac
        fi
    fi
    exit 0
fi
robot=${3:?robot UUID}
mkdir -p "$run"
export LD_LIBRARY_PATH="$base/extracted/lib/x86_64-linux-gnu:$base/extracted/usr/lib/x86_64-linux-gnu"
"$base/extracted/usr/bin/mosquitto_passwd" -b -c "$run/passwords" be-test test-password
"$base/extracted/usr/bin/mosquitto_passwd" -b "$run/passwords" "$robot" test-password
cat > "$run/acl" <<'ACL'
user be-test
topic read mar/robots/+/events
topic read mar/robots/+/heartbeat
topic read mar/robots/+/presence
topic read mar/robots/+/map-preview
topic write mar/robots/+/commands
topic write mar/robots/+/event-acks
pattern write mar/robots/%u/events
pattern write mar/robots/%u/heartbeat
pattern write mar/robots/%u/presence
pattern write mar/robots/%u/map-preview
pattern read mar/robots/%u/commands
pattern read mar/robots/%u/event-acks
ACL
cat > "$run/broker.conf" <<CONFIG
listener $port 127.0.0.1
user $(id -un)
allow_anonymous false
password_file $run/passwords
acl_file $run/acl
cafile $base/ca.pem
certfile $base/server.pem
keyfile $base/server.key
tls_version tlsv1.2
log_type all
CONFIG
printf '%s\n' $$ > "$run/pid"
exec "$base/extracted/usr/sbin/mosquitto" -c "$run/broker.conf"
