#!/usr/bin/env bash
# Đóng gói triển khai production (xem docs/DEPLOY.md).
#
#   prod/package.sh --host <tên miền hoặc IP server> [--acme-email <email>] [--tag v1.2.0 | --no-tag]
#   prod/package.sh --secrets-from <deploy/ của gói đang chạy> --tag v1.3.0 ...   # bản cập nhật: dùng lại secret, CA
#                   [--out <thư mục>] [--images] [--allow-dirty] [--no-apk]
#                   [--apk-keystore <file .jks/.p12>] [--apk-key-alias <alias>]   # mật khẩu: biến APK_KEYSTORE_PASSWORD
#                   [--mobile-dir <repo mobileBackend>] [--android-dir <repo DxLabAttendance>]
#
# --acme-email: có tên miền trỏ về server → Caddy lấy chứng chỉ Let's Encrypt. Không có → "tls internal": chứng chỉ
# ký bằng CA riêng sinh ở đây (deploy/secrets/ca), APK nhúng sẵn CA đó; máy tính dùng web phải cài ca.crt.
# Gói gồm: mã nguồn attendance + mobileBackend tại tag (git archive: không lịch sử, không .env/DB/ảnh), deploy/ với
# secret sinh mới, APK release, (tuỳ chọn) image docker. Gói chứa secret production: chỉ chép lên server qua kênh an toàn.
# Cập nhật mà sinh secret mới thì DB (mật khẩu nằm trong volume) và APK đã cài (tin CA cũ) sẽ không khớp → luôn
# dùng --secrets-from khi đóng gói bản cập nhật cho server đang chạy.
# Cần: git, openssl, JDK (keytool), Android SDK (khi build APK), docker (khi --images).
set -euo pipefail
umask 077   # gói chứa secret: mọi file/thư mục tạo ra chỉ chủ sở hữu đọc được

HOST="" ACME_EMAIL="" TAG="" NO_TAG=false OUT="" IMAGES=false ALLOW_DIRTY=false NO_APK=false APK_KS="" APK_ALIAS="" SECRETS_FROM=""
ROOT=$(git rev-parse --show-toplevel)
MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # checkout chính (kể cả khi chạy trong worktree)
MOBILE_DIR="$MAIN/../mobileBackend"
ANDROID_DIR="$MAIN/../../../DxLabAttendance"

while [ $# -gt 0 ]; do
    case "$1" in
        --host) HOST=$2; shift 2 ;;
        --acme-email) ACME_EMAIL=$2; shift 2 ;;
        --tag) TAG=$2; shift 2 ;;
        --no-tag) NO_TAG=true; shift ;;
        --out) OUT=$2; shift 2 ;;
        --images) IMAGES=true; shift ;;
        --allow-dirty) ALLOW_DIRTY=true; shift ;;
        --no-apk) NO_APK=true; shift ;;
        --apk-keystore) APK_KS=$(cd "$(dirname "$2")" && pwd)/$(basename "$2"); shift 2 ;;
        --apk-key-alias) APK_ALIAS=$2; shift 2 ;;
        --secrets-from) SECRETS_FROM=$(cd "$2" && pwd); shift 2 ;;
        --mobile-dir) MOBILE_DIR=$2; shift 2 ;;
        --android-dir) ANDROID_DIR=$2; shift 2 ;;
        *) echo "Tham số không hợp lệ: $1"; exit 2 ;;
    esac
done
if [ -n "$SECRETS_FROM" ]; then
    [ -f "$SECRETS_FROM/secrets/ca/root.crt" ] || { echo "$SECRETS_FROM không phải thư mục deploy/ của gói production"; exit 2; }
    HOST=$(sed -n 's/^PUBLIC_HOST=//p' "$SECRETS_FROM/.env")
    ACME_EMAIL=$(sed -n 's/^TLS_MODE=//p' "$SECRETS_FROM/.env"); [ "$ACME_EMAIL" = internal ] && ACME_EMAIL=""
fi
[ -n "$HOST" ] || { echo "Thiếu --host <tên miền hoặc IP server>"; exit 2; }
IS_IP=false; [[ $HOST =~ ^[0-9.]+$ ]] && IS_IP=true
$IS_IP && [ -n "$ACME_EMAIL" ] && { echo "--acme-email cần tên miền, không dùng với IP"; exit 2; }
$NO_TAG || [ -n "$TAG" ] || { echo "Thiếu --tag <phiên bản> (hoặc --no-tag để đóng gói HEAD, chỉ dùng thử)"; exit 2; }
[ -z "$APK_KS" ] || [ -n "${APK_KEYSTORE_PASSWORD:-}" ] || { echo "--apk-keystore cần biến APK_KEYSTORE_PASSWORD"; exit 2; }
REF=${TAG:-HEAD}
NAME=${TAG:-prod-$(date +%Y%m%d-%H%M)}
OUT=${OUT:-$MAIN/../prod-dist/$NAME}
[ -e "$OUT" ] && { echo "Đã tồn tại: $OUT"; exit 2; }
TLS_MODE=${ACME_EMAIL:-internal}
GATEWAY_URL=https://$HOST:8443

REPOS=("attendance|$ROOT" "mobileBackend|$MOBILE_DIR")
$NO_APK || REPOS+=("DxLabAttendance|$ANDROID_DIR")

echo "== 1. Kiểm tra repo"
for entry in "${REPOS[@]}"; do
    name=${entry%%|*} dir=${entry#*|}
    git -C "$dir" rev-parse --git-dir >/dev/null
    dirty=$(git -C "$dir" status --porcelain -- . ':(exclude).idea')
    if [ -n "$dirty" ]; then
        echo "  $name có thay đổi chưa commit (sẽ KHÔNG nằm trong gói):"; echo "$dirty" | sed 's/^/    /'
        $ALLOW_DIRTY || { echo "  → commit trước, hoặc thêm --allow-dirty"; exit 1; }
    fi
    if [ -n "$TAG" ] && ! git -C "$dir" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
        git -C "$dir" tag -a "$TAG" -m "Phát hành production $TAG"
        echo "  $name: tạo tag $TAG (chỉ local — push nếu cần: git push origin $TAG)"
    fi
done

mkdir -p "$OUT"/{src,deploy/secrets/ca}
OUT=$(cd "$OUT" && pwd)
BUILD=$(mktemp -d)   # mã nguồn app Android: chỉ để build APK, không nằm trong gói
trap 'rm -rf "$BUILD"' EXIT

echo "== 2. Xuất mã nguồn tại $REF (không lịch sử, không secret/dữ liệu)"
{
    echo "Gói: $NAME   Tạo lúc: $(date '+%Y-%m-%d %H:%M %z')   Host: $HOST   TLS: $TLS_MODE"
    for entry in "${REPOS[@]}"; do
        name=${entry%%|*} dir=${entry#*|}
        dest=$OUT/src; [ "$name" = DxLabAttendance ] && dest=$BUILD
        git -C "$dir" archive --format=tar --prefix="$name/" "$REF" -- . \
            ':(exclude).idea' ':(exclude,glob)**/.env' ':(exclude)face-ai/data/*.db' ':(exclude)uploads' \
            | tar -x -C "$dest"
        printf '%-16s %s  (nhánh %s)\n' "$name" "$(git -C "$dir" rev-parse "$REF^{commit}")" "$(git -C "$dir" branch --show-current)"
    done
} > "$OUT/VERSIONS.txt"
cat "$OUT/VERSIONS.txt"
# git archive phải xuất model thật qua Git LFS, không phải file con trỏ ~130 byte (liveness sẽ hỏng mà build vẫn qua)
for f in "$OUT"/src/attendance/face-ai/models/anti_spoofing/*.onnx; do
    [ "$(wc -c < "$f")" -gt 10000 ] || { echo "  $f là con trỏ Git LFS: chạy git lfs install && git lfs pull"; exit 1; }
done

rand() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
if [ -n "$SECRETS_FROM" ]; then
echo "== 3. Dùng lại secret của $SECRETS_FROM"
cp -R "$SECRETS_FROM"/{.env,face-ai.env,mobile-backend.env,secrets} "$OUT/deploy/"
sed -i "s|^PACKAGE_TAG=.*|PACKAGE_TAG=$NAME|" "$OUT/deploy/.env"
else
echo "== 3. Sinh secret production"
FACE_AI_API_KEY=$(rand 48) JWT_KEYSTORE_PASSWORD=$(rand 32) ADMIN_INITIAL_PASSWORD="$(rand 10)-$(rand 10)Aa9"
cat > "$OUT/deploy/.env" <<EOF
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
. "$OUT/deploy/.env"
sed -e "s|^SECRET_KEY=.*|SECRET_KEY=$(rand 48)|" -e "s|^DEFAULT_ADMIN_PASSWORD=.*|DEFAULT_ADMIN_PASSWORD=$(rand 24)|" \
    -e "s|^INTERNAL_API_KEY=.*|INTERNAL_API_KEY=$FACE_AI_API_KEY|" "$ROOT/face-ai/.env.example" > "$OUT/deploy/face-ai.env"
cat > "$OUT/deploy/mobile-backend.env" <<EOF
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
    -keystore "$OUT/deploy/secrets/jwt.p12" -storepass "$JWT_KEYSTORE_PASSWORD" -keypass "$JWT_KEYSTORE_PASSWORD" 2>/dev/null
# CA riêng cho "tls internal" (Caddyfile luôn nạp nên sinh cả khi dùng Let's Encrypt). MSYS2_ARG_CONV_EXCL: Git Bash
# không đổi "/CN=..." thành đường dẫn Windows (vẫn đổi đường dẫn file).
MSYS2_ARG_CONV_EXCL=/CN= openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
    -subj "/CN=Attendance $NAME Root CA" -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$OUT/deploy/secrets/ca/root.key" -out "$OUT/deploy/secrets/ca/root.crt" 2>/dev/null
fi
cp "$ROOT"/prod/{docker-compose.yml,Caddyfile,init-db.sql,start.sh,backup.sh} "$OUT/deploy/"
cp "$OUT/src/mobileBackend/src/main/resources/schema-mobile-auth.sql" "$OUT/deploy/"
chmod +x "$OUT/deploy/start.sh" "$OUT/deploy/backup.sh"
# umask 077 → mọi file 600; Caddy, postgres (root trong container) vẫn đọc được. Riêng jwt.p12: gateway chạy user
# không phải root nên phải đọc được; keystore đã có mật khẩu (trong mobile-backend.env, 600).
chmod 644 "$OUT/deploy/secrets/jwt.p12"

if [ -z "$ACME_EMAIL" ]; then
    cp "$OUT/deploy/secrets/ca/root.crt" "$OUT/ca.crt"
    CA_NOTE="Chứng chỉ do CA riêng ký: cài ca.crt (gốc gói) làm CA tin cậy trên máy tính dùng web. APK đã nhúng sẵn."
else
    CA_NOTE="Chứng chỉ Let's Encrypt (tên miền $HOST phải trỏ về server, mở cổng 80/443/8443)."
fi
[ -n "$SECRETS_FROM" ] || cat > "$OUT/CREDENTIALS.txt" <<EOF
Production $NAME — lưu vào password manager rồi XOÁ file này khỏi server.
Web Attendance   https://$HOST
  admin (ADMIN)  $ADMIN_INITIAL_PASSWORD   ← đổi ngay lần đăng nhập đầu
Gateway app máy quét  $GATEWAY_URL
$CA_NOTE
Các secret khác: deploy/.env, deploy/*.env, deploy/secrets/ (cần cho mọi lần khởi động/cập nhật — giữ nguyên, sao lưu).
EOF

APK=""
if ! $NO_APK; then
    echo "== 4. APK release (gateway $GATEWAY_URL)"
    APP="$BUILD/DxLabAttendance"
    cp "$ANDROID_DIR/local.properties" "$APP/"
    if [ -z "$ACME_EMAIL" ]; then
        # SECURITY-AUDIT H2 cách B: app tin thêm CA riêng của server
        mkdir -p "$APP/app/src/main/res/raw"
        cp "$OUT/deploy/secrets/ca/root.crt" "$APP/app/src/main/res/raw/gateway_ca.crt"
        cat > "$APP/app/src/main/res/xml/network_security_config.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Sinh bởi prod/package.sh: chỉ HTTPS, tin thêm CA riêng của server (Caddy tls internal) -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/gateway_ca" />
        </trust-anchors>
    </base-config>
</network-security-config>
EOF
    fi
    (cd "$APP" && sh ./gradlew -q --console=plain --no-daemon assembleRelease "-PcheckerBaseUrl=$GATEWAY_URL")
    mkdir -p "$OUT/apk"
    UNSIGNED="$APP/app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ -n "$APK_KS" ]; then
        SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$APP/local.properties" | sed -e 's/\\:/:/g' -e 's/\\\\/\//g')}
        BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
        SIGNER=$BT/apksigner; [ -f "$SIGNER" ] || SIGNER=$BT/apksigner.bat
        APK="$OUT/apk/DxLabAttendance-$NAME.apk"
        "$SIGNER" sign --ks "$APK_KS" --ks-pass env:APK_KEYSTORE_PASSWORD ${APK_ALIAS:+--ks-key-alias "$APK_ALIAS"} \
            --out "$APK" "$UNSIGNED"
        rm -f "$APK.idsig"
    else
        APK="$OUT/apk/DxLabAttendance-$NAME-unsigned.apk"
        cp "$UNSIGNED" "$APK"
        echo "  CẢNH BÁO: APK chưa ký (không cài được). Ký bằng keystore release: xem README.md mục APK."
    fi
fi

echo "== 5. Tài liệu"
sed "s|<host>|$HOST|g" "$ROOT/docs/DEPLOY.md" > "$OUT/README.md"

if $IMAGES; then
    echo "== 6. Docker images"
    mkdir -p "$OUT/images"
    (cd "$OUT/deploy" && docker compose build && docker compose pull postgres caddy)
    docker save -o "$OUT/images/attendance-images.tar" \
        "attendance/backend:$NAME" "attendance/face-ai:$NAME" "attendance/mobile-backend:$NAME" \
        postgres:16-alpine caddy:2.8.4-alpine
fi

(cd "$OUT" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
echo
echo "Xong: $OUT ($(du -sh "$OUT" | cut -f1))"
echo "Gói chứa secret production: chép lên server qua scp/kênh an toàn, chạy deploy/start.sh (xem README.md)."
