#!/bin/sh
# Ubuntu/WSL test broker; packages are extracted locally, never installed as a system service.
set -eu
dest=${1:?workspace target directory required}
mkdir -p "$dest"
cd "$dest"
apt-get download mosquitto libwrap0 libdlt2 libwebsockets16 libcap2 libev4
for package in ./*.deb; do dpkg-deb -x "$package" extracted; done
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=MAR MQTT test CA' -keyout ca.key -out ca.pem
openssl req -newkey rsa:2048 -nodes -subj '/CN=localhost' -keyout server.key -out server.csr
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -extfile server.ext -out server.pem
printf 'CA ready; import ca.pem into a PKCS12 trust store with keytool.\n'
