#!/usr/bin/env bash
# Run on the BE/Nginx edge, after the voice service is ready on the media host.
set -Eeuo pipefail
[[ $EUID == 0 ]] || { echo 'Run as root.' >&2; exit 2; }
media_ip=${1:?usage: deploy/ec2/media-edge.sh MEDIA_PRIVATE_IPV4 MEDIA_DOMAIN}
domain=${2:?expected the existing public media domain}
[[ $domain =~ ^[a-z0-9][a-z0-9.-]+[a-z0-9]$ ]] || exit 2
python3 - "$media_ip" <<'PYCODE'
from ipaddress import ip_address, ip_network
import sys
ip = ip_address(sys.argv[1])
assert any(ip in ip_network(cidr) for cidr in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
PYCODE

edge=/etc/nginx/conf.d/mar-media-wss.conf
include_dir=/etc/nginx/mar-voice-ai
routes=$include_dir/routes.conf
include="    include $include_dir/*.conf;"
grep -qx '# MAR_MANAGED_MEDIA_EDGE' "$edge"
grep -qxF "    server_name $domain;" "$edge"
if [[ -e $routes ]]; then grep -qx '# MAR_MANAGED_VOICE_AI' "$routes"; fi
curl -fsS --max-time 5 "http://$media_ip:7861/health" |
  python3 -c 'import json,sys; assert json.load(sys.stdin)["state"]=="READY"'

temp=$(mktemp -d /run/mar-voice-edge.XXXXXX)
cp -- "$edge" "$temp/old-edge"
if [[ -e $routes ]]; then cp -- "$routes" "$temp/old-routes"; fi
changed=false
cleanup() {
  status=$?
  if (( status != 0 )) && $changed; then
    install -m 0644 "$temp/old-edge" "$edge"
    if [[ -f $temp/old-routes ]]; then install -m 0644 "$temp/old-routes" "$routes"; else rm -f -- "$routes"; fi
    nginx -t && systemctl reload nginx
  fi
  rm -rf -- "$temp"
  return "$status"
}
trap cleanup EXIT

python3 - "$temp/old-edge" "$temp/edge" "$include" <<'PYCODE'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text()
line = sys.argv[3]
anchor = '    location / { return 404; }'
assert text.count(anchor) == 1
if line not in text.splitlines():
    text = text.replace(anchor, line + '\n' + anchor)
Path(sys.argv[2]).write_text(text)
PYCODE

cat > "$temp/routes" <<EOF
# MAR_MANAGED_VOICE_AI
location = /health {
    proxy_pass http://$media_ip:7861;
    proxy_connect_timeout 3s;
    proxy_read_timeout 5s;
}
location ~ ^/api/robot/(wake|chat)\$ {
    client_max_body_size 256k;
    proxy_pass http://$media_ip:7861;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 3s;
    proxy_read_timeout 30s;
}
EOF
install -d -m 0755 "$include_dir"
changed=true
install -m 0644 "$temp/routes" "$routes"
install -m 0644 "$temp/edge" "$edge"
nginx -t
systemctl reload nginx

curl -fsS --retry 3 --retry-connrefused --max-time 4 --resolve "$domain:443:127.0.0.1" "https://$domain/" | grep -qx 'MAR Media'
https_ready() {
  curl -fsS --max-time 4 --resolve "$domain:443:127.0.0.1" "https://$domain/health" 2>/dev/null |
    python3 -c 'import json,sys; assert json.load(sys.stdin)["state"]=="READY"' 2>/dev/null
}
for attempt in $(seq 1 10); do
  if https_ready; then break; fi
  sleep 1
done
https_ready
code=$(curl -sS --max-time 4 --resolve "$domain:443:127.0.0.1" -o /dev/null -w '%{http_code}' --data-binary '' "https://$domain/api/robot/wake")
[[ $code == 401 ]]
echo "VOICE_HTTPS_READY https://$domain"
