#!/usr/bin/env bash
# Dựng môi trường kiểm thử bảo mật từ gói bàn giao. Chạy trong thư mục deploy/ của gói:
#   ./start.sh            # build (lần đầu) + khởi động + khởi tạo DB
#   ./start.sh --no-build # dùng image đã nạp sẵn từ images/*.tar (docker load)
# Cần: Docker có compose v2, curl. Build cần Internet (thư viện + model InsightFace đóng sẵn vào image face-ai).
set -euo pipefail
cd "$(dirname "$0")"

BUILD=--build
[ "${1:-}" = "--no-build" ] && BUILD=
[ -f ../CREDENTIALS.env ] || { echo "Thiếu ../CREDENTIALS.env (nhận riêng từ team phát triển, đặt ở thư mục gốc gói)"; exit 1; }
set -a; . ./.env; . ../CREDENTIALS.env; set +a

echo "1/4 Khởi động postgres, mailpit, face-ai, backend..."
docker compose up -d $BUILD postgres mailpit face-ai backend

echo "2/4 Chờ backend tạo bảng (Hibernate ddl-auto)..."
for i in $(seq 1 90); do
    if curl -fs -o /dev/null "http://localhost:${BACKEND_PORT:-9090}/auth/login"; then break; fi
    [ "$i" = 90 ] && { echo "Backend không lên sau 7,5 phút: docker compose logs backend"; exit 1; }
    sleep 5
done

echo "3/4 Khởi tạo DB (role gateway, tài khoản kiểm thử, email → Mailpit)..."
docker compose exec -T postgres psql -U postgres -d attendance -q \
    -v gw_pw="$GATEWAY_DB_PASSWORD" -v admin_pw="$IA_ADMIN_PASSWORD" -v manager_pw="$IA_MANAGER_PASSWORD" \
    -v employee_pw="$IA_EMPLOYEE_PASSWORD" -v checker_pw="$IA_CHECKER_PASSWORD" \
    < ./init-db.sql   # qua stdin: Git Bash trên Windows tự đổi đường dẫn /... trong tham số
docker compose restart backend   # nạp lại cấu hình email hệ thống

echo "4/4 Khởi động mobile gateway..."
docker compose up -d $BUILD mobile-backend

cat <<EOF

Xong. Web Attendance: http://<ip-máy>:${BACKEND_PORT:-9090}   Gateway (app máy quét): http://<ip-máy>:${GATEWAY_PORT:-8081}
Hộp thư OTP (Mailpit): http://<ip-máy>:${MAILPIT_UI_PORT:-8025}   Tài khoản: xem CREDENTIALS.txt
EOF
