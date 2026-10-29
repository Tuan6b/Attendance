#!/usr/bin/env bash
# Dựng / cập nhật production từ gói. Chạy trong thư mục deploy/ của gói:
#   ./start.sh            # build image từ ../src (lần đầu 10–20 phút, cần Internet) + khởi động + khởi tạo DB
#   ./start.sh --no-build # dùng image đã nạp từ ../images/*.tar (docker load)
# Chạy lại được (cập nhật bản mới, dữ liệu trong volume giữ nguyên). Cần: Docker có compose v2.
set -euo pipefail
cd "$(dirname "$0")"

BUILD=--build
[ "${1:-}" = "--no-build" ] && BUILD=
set -a; . ./.env; set +a
psql() { docker compose exec -T postgres psql -U postgres -d attendance -v ON_ERROR_STOP=1 "$@"; }

echo "1/4 Khởi động postgres, face-ai, backend, caddy..."
docker compose up -d $BUILD postgres face-ai backend caddy

echo "2/4 Chờ backend tạo bảng (Hibernate ddl-auto)..."
for i in $(seq 1 90); do
    n=$(psql -tAc "SELECT count(to_regclass(t)) FROM unnest(array['users','face_profiles','face_samples',
        'system_settings','work_schedules','otp']) t" 2>/dev/null || true)
    [ "$n" = 6 ] && break
    [ "$i" = 90 ] && { echo "Backend chưa tạo bảng sau 7,5 phút: docker compose logs backend"; exit 1; }
    sleep 5
done

echo "3/4 Khởi tạo DB (role gateway, admin đầu tiên)..."
psql -q -v gw_pw="$GATEWAY_DB_PASSWORD" -v admin_pw="$ADMIN_INITIAL_PASSWORD" < ./init-db.sql

echo "4/4 Khởi động mobile gateway..."
docker compose up -d $BUILD mobile-backend

cat <<EOF

Xong. Web: https://$PUBLIC_HOST   Gateway (app máy quét): https://$PUBLIC_HOST:8443
Lần đầu: đăng nhập admin (mật khẩu trong CREDENTIALS.txt), đổi mật khẩu, cấu hình email hệ thống. Xem README.md.
EOF
