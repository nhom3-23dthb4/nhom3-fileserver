#!/bin/sh
# Tao keystore tu ky cho moi truong dev. KHONG commit file .p12.
# Dung: FS_KEYSTORE_PASSWORD=changeit ./certs/gen-keystore.sh
set -e
PW="${FS_KEYSTORE_PASSWORD:?Hay dat bien moi truong FS_KEYSTORE_PASSWORD}"
keytool -genkeypair -alias fs -keyalg RSA -keysize 2048 -validity 365 \
  -keystore certs/server.p12 -storetype PKCS12 \
  -storepass "$PW" -keypass "$PW" -dname "CN=localhost" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"
echo "Da tao certs/server.p12"
