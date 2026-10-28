#!/usr/bin/env bash
# Đóng gói bàn giao cho Team IA kiểm thử bảo mật (xem docs/HANDOVER-SECURITY-TEST.md).
#
#   sectest/package.sh --host <ip máy chạy môi trường test> [--tag sec-test-YYYY-MM-DD | --no-tag]
#                      [--out <thư mục>] [--images] [--allow-dirty] [--contact "<tên, kênh liên lạc>"]
#                      [--mobile-dir <repo mobileBackend>] [--android-dir <repo DxLabAttendance>]
#
# Gói gồm: mã nguồn 3 repo tại cùng một tag (git archive: KHÔNG kèm lịch sử, KHÔNG kèm .env/DB/ảnh), SBOM,
# APK debug trỏ về --host, môi trường docker (deploy/) với secret sinh mới, tài liệu. CREDENTIALS.txt/.env chứa
# mật khẩu tài khoản test: gửi cho IA qua kênh riêng (password manager), không gửi kèm file nén.
# Cần: git, docker (khi --images), JDK (keytool), Android SDK (local.properties của repo app), openssl.
set -euo pipefail

HOST="" TAG="" NO_TAG=false OUT="" IMAGES=false ALLOW_DIRTY=false CONTACT=""
GATEWAY_PORT=8081 BACKEND_PORT=9090 MAILPIT_UI_PORT=8025
ROOT=$(git rev-parse --show-toplevel)
MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # checkout chính (kể cả khi chạy trong worktree)
MOBILE_DIR="$MAIN/../mobileBackend"
ANDROID_DIR="$MAIN/../../../DxLabAttendance"

while [ $# -gt 0 ]; do
    case "$1" in
        --host) HOST=$2; shift 2 ;;
        --tag) TAG=$2; shift 2 ;;
        --no-tag) NO_TAG=true; shift ;;
        --out) OUT=$2; shift 2 ;;
        --images) IMAGES=true; shift ;;
        --allow-dirty) ALLOW_DIRTY=true; shift ;;
        --contact) CONTACT=$2; shift 2 ;;
        --mobile-dir) MOBILE_DIR=$2; shift 2 ;;
        --android-dir) ANDROID_DIR=$2; shift 2 ;;
        *) echo "Tham số không hợp lệ: $1"; exit 2 ;;
    esac
done
[ -n "$HOST" ] || { echo "Thiếu --host <ip máy chạy môi trường test> (APK debug sẽ gọi gateway ở đó)"; exit 2; }
$NO_TAG || [ -n "$TAG" ] || { echo "Thiếu --tag <tên> (hoặc --no-tag để đóng gói HEAD, chỉ dùng thử)"; exit 2; }
REF=${TAG:-HEAD}
NAME=${TAG:-sectest-$(date +%Y%m%d-%H%M)}
OUT=${OUT:-$MAIN/../sectest-dist/$NAME}
[ -e "$OUT" ] && { echo "Đã tồn tại: $OUT"; exit 2; }

REPOS=("attendance|$ROOT" "mobileBackend|$MOBILE_DIR" "DxLabAttendance|$ANDROID_DIR")

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
        git -C "$dir" tag -a "$TAG" -m "Bàn giao kiểm thử bảo mật $TAG"
        echo "  $name: tạo tag $TAG (chỉ local — push nếu cần: git push origin $TAG)"
    fi
done

mkdir -p "$OUT"/{src,deploy/secrets,apk,sbom,docs}
OUT=$(cd "$OUT" && pwd)

echo "== 2. Xuất mã nguồn tại $REF (không lịch sử, không secret/dữ liệu)"
{
    echo "Gói: $NAME   Tạo lúc: $(date '+%Y-%m-%d %H:%M %z')"
    for entry in "${REPOS[@]}"; do
        name=${entry%%|*} dir=${entry#*|}
        ref_var="PACKAGE_REF_$name"; ref=${!ref_var:-$REF}   # chỉ để chạy thử script: PACKAGE_REF_attendance=<commit>
        git -C "$dir" archive --format=tar --prefix="$name/" "$ref" -- . \
            ':(exclude).idea' ':(exclude,glob)**/.env' ':(exclude)face-ai/data/*.db' ':(exclude)uploads' \
            | tar -x -C "$OUT/src"
        printf '%-16s %s  (nhánh %s)\n' "$name" "$(git -C "$dir" rev-parse "$ref^{commit}")" "$(git -C "$dir" branch --show-current)"
    done
} > "$OUT/VERSIONS.txt"
cat "$OUT/VERSIONS.txt"
if grep -rlI -e 'Admin@123' -e 'doi-thanh-chuoi-bi-mat' "$OUT/src" --exclude=*.md --exclude=package.sh; then
    echo "  CẢNH BÁO: còn secret cũ trong mã nguồn ở các file trên"
fi

echo "== 3. Sinh secret mới cho môi trường test"
rand() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
FACE_AI_API_KEY=$(rand 48) FACE_ID_API_KEY=$(rand 48) POSTGRES_PASSWORD=$(rand 32)
GATEWAY_DB_PASSWORD=$(rand 32) JWT_KEYSTORE_PASSWORD=$(rand 32) SERVER_CLIENT_SECRET=$(rand 48)
cat > "$OUT/deploy/.env" <<EOF
PACKAGE_TAG=$NAME
BACKEND_PORT=$BACKEND_PORT
GATEWAY_PORT=$GATEWAY_PORT
MAILPIT_UI_PORT=$MAILPIT_UI_PORT
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
FACE_AI_API_KEY=$FACE_AI_API_KEY
FACE_ID_API_KEY=$FACE_ID_API_KEY
EOF
sed -e "s|^SECRET_KEY=.*|SECRET_KEY=$(rand 48)|" -e "s|^DEFAULT_ADMIN_PASSWORD=.*|DEFAULT_ADMIN_PASSWORD=$(rand 24)|" \
    -e "s|^INTERNAL_API_KEY=.*|INTERNAL_API_KEY=$FACE_AI_API_KEY|" "$ROOT/face-ai/.env.example" > "$OUT/deploy/face-ai.env"
cat > "$OUT/deploy/mobile-backend.env" <<EOF
PORT=8080
DB_USERNAME=mobile_gateway
DB_PASSWORD=$GATEWAY_DB_PASSWORD
OAUTH2_ISSUER=http://$HOST:$GATEWAY_PORT
JWT_KEYSTORE_PATH=/app/secrets/jwt.p12
JWT_KEYSTORE_PASSWORD=$JWT_KEYSTORE_PASSWORD
JWT_KEY_ALIAS=jwt
SERVER_CLIENT_ID=sectest-server
SERVER_CLIENT_SECRET=$SERVER_CLIENT_SECRET
MOBILE_CLIENT_ID=mobile-app
LOGIN_OTP_ENABLED=false
EOF
keytool -genkeypair -alias jwt -keyalg RSA -keysize 2048 -storetype PKCS12 -validity 365 -dname "CN=sectest-gateway" \
    -keystore "$OUT/deploy/secrets/jwt.p12" -storepass "$JWT_KEYSTORE_PASSWORD" -keypass "$JWT_KEYSTORE_PASSWORD" 2>/dev/null
cat > "$OUT/CREDENTIALS.env" <<EOF
GATEWAY_DB_PASSWORD=$GATEWAY_DB_PASSWORD
IA_ADMIN_PASSWORD=$(rand 20)
IA_MANAGER_PASSWORD=$(rand 20)
IA_EMPLOYEE_PASSWORD=$(rand 20)
IA_CHECKER_PASSWORD=$(rand 20)
EOF
. "$OUT/CREDENTIALS.env"
cat > "$OUT/CREDENTIALS.txt" <<EOF
Tài khoản môi trường kiểm thử $NAME — CHỈ dùng cho môi trường này. Gửi qua kênh riêng.
Web Attendance  http://$HOST:$BACKEND_PORT
  ia.admin     (ADMIN)     $IA_ADMIN_PASSWORD
  ia.manager   (MANAGER)   $IA_MANAGER_PASSWORD
  ia.employee  (EMPLOYEE)  $IA_EMPLOYEE_PASSWORD
App máy quét (gateway http://$HOST:$GATEWAY_PORT), màn kích hoạt:
  ia.checker   (CHECKER)   $IA_CHECKER_PASSWORD
Hộp thư OTP (Mailpit)  http://$HOST:$MAILPIT_UI_PORT
Các API key/secret còn lại nằm trong deploy/.env, deploy/*.env (secret của môi trường test).
CREDENTIALS.env (cùng nội dung, dạng máy đọc) phải đặt ở thư mục gốc gói trước khi chạy deploy/start.sh.
EOF
cp "$ROOT/sectest/docker-compose.yml" "$ROOT/sectest/init-db.sql" "$ROOT/sectest/start.sh" \
   "$ROOT/sectest/verify.sh" "$ROOT/sectest/faces.py" "$OUT/deploy/"
cp "$OUT/src/mobileBackend/src/main/resources/schema-mobile-auth.sql" "$OUT/deploy/"
chmod +x "$OUT/deploy/start.sh" "$OUT/deploy/verify.sh"

echo "== 4. SBOM"
for svc in attendance mobileBackend; do
    (cd "$OUT/src/$svc" && sh ./mvnw -q -B org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
        -DoutputFormat=json -DoutputName=bom && cp target/bom.json "$OUT/sbom/$svc.cdx.json" && rm -rf target)
done
cp "$OUT/src/attendance/face-ai/requirements.txt" "$OUT/sbom/face-ai-requirements.txt"

echo "== 5. APK debug (gateway http://$HOST:$GATEWAY_PORT)"
APP="$OUT/src/DxLabAttendance"
cp "$ANDROID_DIR/local.properties" "$APP/"
(cd "$APP" && sh ./gradlew -q --console=plain :app:dependencies --configuration releaseRuntimeClasspath > "$OUT/sbom/android-release-deps.txt" \
    && sh ./gradlew -q --console=plain assembleDebug "-PcheckerDebugBaseUrl=http://$HOST:$GATEWAY_PORT")
cp "$APP/app/build/outputs/apk/debug/app-debug.apk" "$OUT/apk/DxLabAttendance-$NAME-debug.apk"
rm -rf "$APP/local.properties" "$APP/app/build" "$APP/build" "$APP/.gradle" "$APP/.kotlin"

echo "== 6. Tài liệu"
# README: điền IP máy test và (nếu có) thông tin liên hệ
sed "s|<host>|$HOST|g" "$ROOT/docs/HANDOVER-SECURITY-TEST.md" > "$OUT/README.md"
if [ -n "$CONTACT" ]; then
    # qua ENVIRON để ký tự | & \ trong thông tin liên hệ không bị hiểu là cú pháp
    CONTACT="$CONTACT" awk '/^Liên hệ: /{print "Liên hệ: " ENVIRON["CONTACT"]; next} {print}' \
        "$OUT/README.md" > "$OUT/README.md.tmp" && mv "$OUT/README.md.tmp" "$OUT/README.md"
fi
cp "$ROOT/docs/SECURITY-AUDIT.md" "$ROOT/docs/ONBOARDING.md" "$ROOT/docs/PRE-HANDOVER-TEST-REPORT.md" "$OUT/docs/"
cp "$OUT/src/mobileBackend/docs/mobile-api.md" "$OUT/docs/" 2>/dev/null || true

if $IMAGES; then
    echo "== 7. Docker images"
    mkdir -p "$OUT/images"
    (cd "$OUT/deploy" && docker compose build && docker compose pull postgres mailpit)
    docker save -o "$OUT/images/sectest-images.tar" \
        "attendance-sectest/backend:$NAME" "attendance-sectest/face-ai:$NAME" "attendance-sectest/mobile-backend:$NAME" \
        postgres:16-alpine axllent/mailpit:v1.20.0
fi

(cd "$OUT" && find . -type f ! -name SHA256SUMS ! -name 'CREDENTIALS.*' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
echo
echo "Xong: $OUT ($(du -sh "$OUT" | cut -f1))"
echo "Gửi thư mục (trừ CREDENTIALS.txt, CREDENTIALS.env) qua kênh an toàn; 2 file CREDENTIALS.* gửi riêng."
[ -n "$CONTACT" ] || echo "Chưa có --contact: điền mục \"Liên hệ\" trong README.md trước khi gửi (rồi cập nhật SHA256SUMS)."
