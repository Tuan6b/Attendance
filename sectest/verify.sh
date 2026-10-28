#!/usr/bin/env bash
# Kiểm thử tích hợp/E2E môi trường kiểm thử bảo mật sau khi start.sh chạy xong. Chạy trong deploy/ của gói:
#   ./verify.sh [host]          # host mặc định: localhost
# Gồm: dịch vụ sống, cổng không publish, phân quyền web theo role, header/cookie, gateway (đăng nhập, xoay vòng
# refresh token, không còn form /login/PKCE), OTP qua Mailpit, API key của face-ai, và luồng chấm công đầy đủ
# (đăng ký mặt → chưa duyệt bị từ chối → duyệt → vào ca → gửi lại cùng requestId → tan ca → người lạ / ảnh không mặt).
# Ảnh khuôn mặt lấy từ ảnh mẫu công khai của InsightFace (faces.py), không dùng ảnh nhân viên thật.
# CHÚ Ý: tạo dữ liệu (mặt + chấm công của ia.employee, giờ ca nới rộng). Bàn giao môi trường sạch thì sau đó chạy
#   docker compose down -v && ./start.sh
# Không in mật khẩu/token. Thoát mã 1 nếu có kiểm tra thất bại.
set -uo pipefail
cd "$(dirname "$0")"
[ -f ../CREDENTIALS.env ] || { echo "Thiếu ../CREDENTIALS.env"; exit 2; }
set -a; . ./.env; . ../CREDENTIALS.env; set +a
H=${1:-localhost}
W=http://$H:${BACKEND_PORT:-9090}; G=http://$H:${GATEWAY_PORT:-8081}; M=http://$H:${MAILPIT_UI_PORT:-8025}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
command -v cygpath >/dev/null && T=$(cygpath -m "$T")   # Git Bash: curl.exe không đọc được đường dẫn /tmp kiểu MSYS
pass=0; fail=0
ok() { if [ "$2" = "$3" ]; then echo "  PASS $1"; pass=$((pass+1)); else echo "  FAIL $1: nhận '$2', cần '$3'"; fail=$((fail+1)); fi; }
code() { curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 "$@"; }
loc() { curl -s -o /dev/null -w '%{redirect_url}' "$@"; }
json() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p"; }   # lấy 1 trường đơn giản từ JSON (không cần jq)
psql_q() { docker compose exec -T postgres psql -U postgres -d attendance -tAc "$1"; }

echo "Chờ web, gateway, face-ai sẵn sàng (tối đa 10 phút; lần đầu face-ai tải model)..."
for i in $(seq 1 120); do
    [ "$(code $W/auth/login)" = 200 ] && [ "$(code $G/actuator/health)" = 200 ]         && docker compose exec -T face-ai python -c "import urllib.request; urllib.request.urlopen('http://localhost:8002/health')" >/dev/null 2>&1         && break
    [ "$i" = 120 ] && echo "  CẢNH BÁO: face-ai chưa sẵn sàng sau 10 phút (docker compose logs face-ai) — phần 5, 6 sẽ thất bại"
    sleep 5
done
echo "== 1. Dịch vụ"
ok "web /auth/login 200" "$(code $W/auth/login)" 200
ok "gateway /actuator/health 200" "$(code $G/actuator/health)" 200
ok "mailpit 200" "$(code $M/)" 200
ok "postgres 5432 không publish" "$(code http://$H:5432/)" 000
ok "face-ai 8002 không publish" "$(code http://$H:8002/)" 000

echo "== 2. Web: đăng nhập, phân quyền, header"
weblogin() { curl -s -o /dev/null -w '%{redirect_url}' -c "$T/$1" -H "Origin: $W" \
    --data-urlencode "login=$1" --data-urlencode "password=$2" $W/auth/login; }
ok "ADMIN đăng nhập" "$(weblogin ia.admin "$IA_ADMIN_PASSWORD")" "$W/dashboard/admin"
ok "MANAGER đăng nhập" "$(weblogin ia.manager "$IA_MANAGER_PASSWORD")" "$W/dashboard/manager"
ok "EMPLOYEE đăng nhập" "$(weblogin ia.employee "$IA_EMPLOYEE_PASSWORD")" "$W/dashboard/employee"
ok "CHECKER bị web từ chối" "$(weblogin ia.checker "$IA_CHECKER_PASSWORD")" ""
ok "sai mật khẩu bị từ chối" "$(curl -s -o /dev/null -w '%{redirect_url}' -H "Origin: $W" --data-urlencode login=ia.admin --data-urlencode password=wrong-password-123 $W/auth/login)" ""
ok "ADMIN /system/setting 200" "$(code -b $T/ia.admin $W/system/setting)" 200
ok "dashboard admin trỏ /system/setting" "$(curl -s -b $T/ia.admin $W/dashboard/admin | grep -c 'href="/setting"')" 0
ok "EMPLOYEE /system/setting bị chặn" "$(loc -b $T/ia.employee $W/system/setting)" "$W/"
ok "EMPLOYEE /face-id (duyệt mặt) bị chặn" "$(loc -b $T/ia.employee $W/face-id)" "$W/"
ok "EMPLOYEE GET /api/report 403" "$(code -b $T/ia.employee $W/api/report)" 403
ok "MANAGER GET /api/report 403 (chỉ ADMIN)" "$(code -b $T/ia.manager $W/api/report)" 403
ok "ẩn danh /uploads bị chặn" "$(loc $W/uploads/face_samples/x.jpg)" "$W/"
ok "ẩn danh API trả 401" "$(code -H 'X-Requested-With: XMLHttpRequest' $W/api/face-id/latest)" 401
ok "/api/face-id/attendance không key 401" "$(code -X POST -H 'Content-Type: application/json' -d '{}' $W/api/face-id/attendance)" 401
ok "/api/face-id/attendance sai key 401" "$(code -X POST -H 'X-API-Key: wrong-key-wrong-key-wrong-key-wrong' -H 'Content-Type: application/json' -d '{}' $W/api/face-id/attendance)" 401
ok "/api/otp/send không key 401" "$(code -X POST -H 'Content-Type: application/json' -d '{}' $W/api/otp/send)" 401
ok "POST khác origin 403 (CSRF)" "$(code -b $T/ia.admin -X POST -H 'Origin: http://evil.example' $W/auth/logout)" 403
ok "POST Origin: null 403" "$(code -b $T/ia.admin -X POST -H 'Origin: null' $W/auth/logout)" 403
ok "API thủ công cũ (C1) không còn" "$(code -b $T/ia.admin -X POST -H "Origin: $W" -H 'Content-Type: application/json' -d '{}' $W/api/face-id/attendance/manual | cut -c1)" 4
curl -s -D "$T/h" -o /dev/null -H "Origin: $W" --data-urlencode login=ia.admin --data-urlencode "password=$IA_ADMIN_PASSWORD" $W/auth/login
ok "cookie phiên HttpOnly + SameSite=Lax" "$(grep -i '^set-cookie: JSESSIONID' "$T/h" | grep -ci 'httponly.*samesite=lax\|samesite=lax.*httponly')" 1
ok "header X-Content-Type-Options" "$(curl -s -D - -o /dev/null $W/auth/login | grep -ci '^x-content-type-options: nosniff')" 1
ok "header X-Frame-Options" "$(curl -s -D - -o /dev/null $W/auth/login | grep -ci '^x-frame-options:')" 1

echo "== 3. Gateway"
glogin() { curl -s -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" $G/api/v1/auth/login; }
R=$(glogin ia.checker "$IA_CHECKER_PASSWORD"); AT=$(echo "$R" | json accessToken); RT=$(echo "$R" | json refreshToken)
ok "CHECKER đăng nhập" "$([ -n "$AT" ] && [ -n "$RT" ] && echo có-token)" có-token
ok "/api/v1/me 200" "$(code -H "Authorization: Bearer $AT" $G/api/v1/me)" 200
ok "không token 401" "$(code $G/api/v1/me)" 401
ok "token giả 401" "$(code -H "Authorization: Bearer ${AT}x" $G/api/v1/me)" 401
ok "EMPLOYEE không đăng nhập gateway được" "$(code -H 'Content-Type: application/json' -d "{\"username\":\"ia.employee\",\"password\":\"$IA_EMPLOYEE_PASSWORD\"}" $G/api/v1/auth/login)" 401
R2=$(curl -s -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}" $G/api/v1/auth/refresh)
ok "refresh cấp token mới" "$([ -n "$(echo "$R2" | json accessToken)" ] && echo có)" có
ok "refresh token cũ dùng lại được trong ân hạn" "$(code -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}" $G/api/v1/auth/refresh)" 200
# Giả lập hết ân hạn: token đã xoay có hạn ngắn hơn token mới (30 ngày) nhiều — so tương đối, không phụ thuộc múi giờ
psql_q "UPDATE mobile_auth.mobile_refresh_token SET expires_at = expires_at - interval '400 days' WHERE username = 'ia.checker'
        AND expires_at < (SELECT max(expires_at) - interval '1 day' FROM mobile_auth.mobile_refresh_token WHERE username = 'ia.checker')" >/dev/null
ok "refresh token cũ bị từ chối sau ân hạn" "$(code -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$RT\"}" $G/api/v1/auth/refresh)" 401
ok "refresh token mới vẫn dùng được" "$(code -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$(echo "$R2" | json refreshToken)\"}" $G/api/v1/auth/refresh)" 200
ok "không có trang web / (403)" "$(code $G/)" 403
ok "không có form /login (403)" "$(code --data-urlencode username=ia.checker --data-urlencode "password=$IA_CHECKER_PASSWORD" $G/login)" 403
ok "không có luồng PKCE /oauth2/authorize" "$(code "$G/oauth2/authorize?response_type=code&client_id=mobile-app&redirect_uri=com.attendance://oauth2callback&scope=attendance.check&code_challenge=x&code_challenge_method=S256" | cut -c1)" 4

echo "== 4. OTP → Mailpit"
before=$(curl -s $M/api/v1/messages | json total)
curl -s -o /dev/null -H "Origin: $W" --data-urlencode "email=ia.employee@sectest.local" $W/auth/forgot-password
curl -s -o /dev/null -H 'Content-Type: application/json' -d '{"login":"ia.checker"}' $G/api/v1/auth/password/forgot
sleep 4
ok "2 email OTP tới Mailpit (web + gateway)" "$(( $(curl -s $M/api/v1/messages | json total) - before ))" 2

echo "== 5. face-ai (nội bộ)"
ok "face-ai không key 401/403" "$(docker compose exec -T face-ai python -c "
import urllib.request,urllib.error
try: urllib.request.urlopen(urllib.request.Request('http://localhost:8002/api/internal/face/recognize',b'{}',{'Content-Type':'application/json'}))
except urllib.error.HTTPError as e: print(e.code//100*100 if e.code in (401,403) else e.code)")" 400

echo "== 6. Luồng chấm công đầy đủ (web → gateway → face-ai → Attendance)"
docker compose exec -T face-ai python - < ./faces.py 2>/dev/null | grep -E '^(A[1-5]?|B) [A-Za-z0-9+/=]{1000,}$' > "$T/faces"
img() { sed -n "s/^$1 //p" "$T/faces"; }
ok "sinh 7 ảnh mẫu" "$(wc -l < "$T/faces" | tr -d ' ')" 7
for l in A B; do img $l | base64 -d > "$T/$l.jpg"; done
# Giờ ca nới rộng để chấm công được bất kỳ lúc nào (mục 4.1 README)
ok "ADMIN nới giờ ca" "$(code -b $T/ia.admin -X PUT -H "Origin: $W" -H 'Content-Type: application/json' \
    -d '{"startWorkTime":"00:00","endWorkTime":"23:59","maxCheckinTime":"23:59","minCheckoutTime":"00:00"}' $W/api/system/work-shift-time)" 204
printf '{"samples":["%s","%s","%s","%s","%s"]}' "$(img A1)" "$(img A2)" "$(img A3)" "$(img A4)" "$(img A5)" > "$T/samples.json"
ok "EMPLOYEE đăng ký 5 mẫu khuôn mặt" "$(code -b $T/ia.employee -X POST -H "Origin: $W" -H 'Content-Type: application/json' -d @"$T/samples.json" $W/api/face-id/register)" 200
check() { curl -s -w '\n%{http_code}' -H "Authorization: Bearer $AT" -F "image=@$T/$1.jpg;type=image/jpeg" ${2:+-F "requestId=$2"} $G/api/v1/attendance/check; }
AT=$(glogin ia.checker "$IA_CHECKER_PASSWORD" | json accessToken)
ok "chưa duyệt → bị từ chối (422)" "$(check A | tail -1)" 422
PID=$(psql_q "SELECT fp.id FROM face_profiles fp JOIN users u ON u.id = fp.user_id WHERE u.username = 'ia.employee'")
ok "MANAGER duyệt hồ sơ khuôn mặt" "$(loc -b $T/ia.manager -X POST -H "Origin: $W" $W/face-id/accept/$PID)" "$W/face-id"
RID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "11111111-2222-3333-4444-$(date +%s)00")
R=$(check A "$RID")
ok "vào ca thành công (CHECKIN, nhận đúng ia.employee)" "$(echo "$R" | head -1 | json action)/$(echo "$R" | head -1 | json employeeCode)" CHECKIN/ia.employee
ok "gửi lại cùng requestId không chấm 2 lần (IGNORED)" "$(check A "$RID" | head -1 | json action)" IGNORED
ok "tan ca (CHECKOUT)" "$(check A | head -1 | json action)" CHECKOUT
ok "người chưa đăng ký không chấm được (4xx)" "$(check B | tail -1 | cut -c1)" 4
ok "ảnh không có mặt (4xx)" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $AT" -F "image=@$T/B.jpg;type=text/plain" $G/api/v1/attendance/check | cut -c1)" 4
ok "1 bản ghi chấm công trong DB" "$(psql_q "SELECT count(*) FROM attendance_records a JOIN users u ON u.id = a.user_id WHERE u.username = 'ia.employee' AND a.check_in_time IS NOT NULL AND a.check_out_time IS NOT NULL")" 1
SAMPLE=$(psql_q "SELECT s.image_path FROM face_samples s JOIN face_profiles fp ON fp.id = s.face_profile_id JOIN users u ON u.id = fp.user_id WHERE u.username = 'ia.employee' LIMIT 1")
ok "có ảnh mẫu trong DB" "$([ -n "$SAMPLE" ] && echo có)" có
if [ -n "$SAMPLE" ]; then
    ok "chủ ảnh xem được ảnh mẫu của mình" "$(code -b $T/ia.employee "$W/${SAMPLE#/}")" 200
    ok "ẩn danh không xem được ảnh mẫu" "$(code "$W/${SAMPLE#/}")" 302
fi

echo "== Kết quả: $pass đạt, $fail thất bại"
[ "$fail" = 0 ]
