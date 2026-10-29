#!/usr/bin/env bash
# Sao lưu production. Chạy trong deploy/ của gói (nên đặt cron hằng ngày):
#   ./backup.sh [thư mục đích]     # mặc định ./backups/<ngày-giờ>
# Gồm: DB (pg_dump), dữ liệu face-ai, file upload (ảnh mẫu khuôn mặt, báo cáo), chứng chỉ Caddy.
# Secret (.env, *.env, secrets/) không nằm trong volume: sao lưu riêng cả thư mục deploy/ một lần.
set -euo pipefail
umask 077   # bản sao lưu chứa toàn bộ dữ liệu (hash mật khẩu, ảnh khuôn mặt): chỉ chủ sở hữu đọc được
cd "$(dirname "$0")"

DEST=${1:-backups/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$DEST"
DEST=$(cd "$DEST" && pwd)
command -v cygpath >/dev/null && DEST=$(cygpath -m "$DEST")   # Git Bash: docker cần đường dẫn kiểu C:/...

docker compose exec -T postgres pg_dump -U postgres -Fc attendance > "$DEST/attendance.dump"
for v in face-ai-data uploads-data caddy-data; do
    MSYS_NO_PATHCONV=1 docker run --rm -v "attendance-prod_$v:/v:ro" -v "$DEST:/b" postgres:16-alpine tar czf "/b/$v.tgz" -C /v .
done
echo "Đã sao lưu vào $DEST ($(du -sh "$DEST" | cut -f1))"
