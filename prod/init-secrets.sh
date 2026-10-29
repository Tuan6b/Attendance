#!/usr/bin/env bash
# Sinh secret production vào <thư mục>: .env, face-ai.env, mobile-backend.env, secrets/ (JWT keystore, CA riêng).
#   prod/init-secrets.sh <thư mục> <tên miền hoặc IP> [email ACME]
# package.sh gọi với <gói>/deploy. Bản beta chạy thẳng từ repo (docs/DEPLOY.md mục 8): prod/init-secrets.sh prod <host> <email>
# Không ghi đè secret có sẵn: secret mới không khớp DB (mật khẩu nằm trong volume) và APK đã cài (tin CA cũ).
# Cần: openssl, keytool (JDK/JRE).
set -euo pipefail
umask 077   # chỉ chủ sở hữu đọc được secret

[ $# -ge 2 ] || { echo "Cách dùng: $0 <thư mục> <tên miền hoặc IP> [email ACME]"; exit 2; }
DIR=$1 HOST=$2 ACME_EMAIL=${3:-} NAME=${NAME:-local}
HERE=$(cd "$(dirname "$0")" && pwd)
[[ $HOST =~ ^[0-9.]+$ ]] && [ -n "$ACME_EMAIL" ] && { echo "Email ACME cần tên miền, không dùng với IP"; exit 2; }
[ -e "$DIR/.env" ] && { echo "$DIR/.env đã có: không ghi đè secret"; exit 2; }
mkdir -p "$DIR/secrets/ca"
TLS_MODE=${ACME_EMAIL:-internal}
GATEWAY_URL=https://$HOST:8443

rand() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
FACE_AI_API_KEY=$(rand 48) JWT_KEYSTORE_PASSWORD=$(rand 32) ADMIN_INITIAL_PASSWORD="$(rand 10)-$(rand 10)Aa9"
cat > "$DIR/.env" <<EOF
PACKAGE_TAG=$NAME
PUBLIC_HOST=$HOST
TLS_MODE=$TLS_MODE
POSTGRES_PASSWORD=$(rand 32)
GATEWAY_DB_PASSWORD=$(rand 32)
FACE_AI_API_KEY=$FACE_AI_API_KEY
FACE_ID_API_KEY=$(rand 48)
ADMIN_INITIAL_PASSWORD=$ADMIN_INITIAL_PASSWORD
# Máy chủ SMTP gửi OTP (tài khoản/mật khẩu email nhập trong web: Cài đặt hệ thống)
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_STARTTLS=true
EOF
. "$DIR/.env"
sed -e "s|^SECRET_KEY=.*|SECRET_KEY=$(rand 48)|" -e "s|^DEFAULT_ADMIN_PASSWORD=.*|DEFAULT_ADMIN_PASSWORD=$(rand 24)|" \
    -e "s|^INTERNAL_API_KEY=.*|INTERNAL_API_KEY=$FACE_AI_API_KEY|" "$HERE/../face-ai/.env.example" > "$DIR/face-ai.env"
cat > "$DIR/mobile-backend.env" <<EOF
PORT=8080
DB_USERNAME=mobile_gateway
DB_PASSWORD=$GATEWAY_DB_PASSWORD
OAUTH2_ISSUER=$GATEWAY_URL
JWT_KEYSTORE_PATH=/app/secrets/jwt.p12
JWT_KEYSTORE_PASSWORD=$JWT_KEYSTORE_PASSWORD
JWT_KEY_ALIAS=jwt
SERVER_CLIENT_ID=attendance-server
SERVER_CLIENT_SECRET=$(rand 48)
MOBILE_CLIENT_ID=mobile-app
LOGIN_OTP_ENABLED=false
REFRESH_REUSE_GRACE=5m
EOF
keytool -genkeypair -alias jwt -keyalg RSA -keysize 2048 -storetype PKCS12 -validity 3650 -dname "CN=attendance-gateway" \
    -keystore "$DIR/secrets/jwt.p12" -storepass "$JWT_KEYSTORE_PASSWORD" -keypass "$JWT_KEYSTORE_PASSWORD" 2>/dev/null
# Gateway chạy user không phải root nên phải đọc được; keystore đã có mật khẩu (trong mobile-backend.env, 600).
chmod 644 "$DIR/secrets/jwt.p12"
# CA riêng cho "tls internal" (Caddyfile luôn nạp nên sinh cả khi dùng Let's Encrypt). MSYS2_ARG_CONV_EXCL: Git Bash
# không đổi "/CN=..." thành đường dẫn Windows (vẫn đổi đường dẫn file).
MSYS2_ARG_CONV_EXCL=/CN= openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
    -subj "/CN=Attendance $NAME Root CA" -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$DIR/secrets/ca/root.key" -out "$DIR/secrets/ca/root.crt" 2>/dev/null

if [ "$(cd "$DIR" && pwd)" = "$HERE" ]; then
    # Chạy thẳng từ repo: build từ mã nguồn của repo (mobileBackend clone cạnh Attendance)
    printf '# Mã nguồn để build, tính từ prod/\nATTENDANCE_SRC=..\nMOBILE_SRC=../../mobileBackend\n' >> "$DIR/.env"
    echo "Đã sinh secret trong $DIR. Mật khẩu admin ban đầu: ADMIN_INITIAL_PASSWORD trong $DIR/.env"
fi
