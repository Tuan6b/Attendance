# Bàn giao kiểm thử bảo mật — Hệ thống chấm công khuôn mặt

Tài liệu này là `README.md` của gói bàn giao cho Team IA. Phiên bản chính xác của từng thành phần nằm trong
`VERSIONS.txt` (commit hash + tag). Khi báo lỗi, ghi rõ tag của gói.

## 1. Thành phần

| Thành phần | Công nghệ | Vai trò | Truy cập trong môi trường test |
|---|---|---|---|
| Attendance (`src/attendance`) | Spring Boot 3, Thymeleaf, Postgres | Web quản trị/nhân viên, nghiệp vụ chấm công, OTP | `http://<host>:9090` (session cookie) |
| face-ai (`src/attendance/face-ai`) | FastAPI, InsightFace, ONNX | Phát hiện/nhận diện khuôn mặt, kiểm tra người thật | Chỉ nội bộ (`http://face-ai:8002`, `X-API-Key`) |
| Mobile gateway (`src/mobileBackend`) | Spring Boot, Spring Authorization Server | API cho app máy quét | `http://<host>:8081` (JWT Bearer) |
| App máy quét (`src/DxLabAttendance`) | Android (Kotlin, Compose, CameraX) | Máy quét đặt cố định ở cửa, role CHECKER | `apk/*.apk` |
| Mailpit | — | Nhận email OTP của môi trường test (không gửi ra ngoài) | `http://<host>:8025` |

Luồng chấm công: app → gateway (`/api/v1/attendance/check`, JWT của tài khoản CHECKER) → gateway nhận diện 1:N qua
face-ai → gateway gọi Attendance `POST /api/face-id/attendance` kèm `X-API-Key` → Attendance tự kiểm tra lại
khuôn mặt + người thật qua face-ai `/recognize` rồi ghi công.

Ranh giới tin cậy và cơ chế xác thực:

| Cạnh | Xác thực |
|---|---|
| Trình duyệt → Attendance | Form login, session cookie (`SameSite=Lax`, `HttpOnly`); chặn POST khác origin (`OriginCheckFilter`) |
| App → gateway | `/api/v1/auth/login` (CHECKER) → access token JWT 15 phút + refresh token 30 ngày, xoay vòng |
| Gateway → Attendance | `X-API-Key` (`FACE_ID_API_KEY`) — chỉ `/api/face-id/attendance`, `/api/otp/send`, `/api/otp/verify` |
| Attendance, gateway → face-ai | `X-API-Key` (`FACE_AI_API_KEY`), face-ai không publish cổng |
| Gateway → Postgres | Role `mobile_gateway` (SELECT một số bảng, UPDATE `users.password`, toàn quyền schema `mobile_auth`) |

Chi tiết hơn: `docs/ONBOARDING.md` (Attendance), `docs/mobile-api.md` (gateway).

## 2. Cấu trúc gói

```
README.md                  tài liệu này
VERSIONS.txt               commit/tag của 3 repo
SHA256SUMS                 checksum mọi file (trừ CREDENTIALS.*)
src/                       mã nguồn tại tag — không kèm lịch sử git, .env, DB, ảnh khuôn mặt
deploy/                    docker-compose.yml, start.sh, init-db.sql, secret của RIÊNG môi trường test
apk/                       APK debug của app máy quét, trỏ về gateway của môi trường test
sbom/                      CycloneDX (2 dịch vụ Java), danh sách thư viện Android, requirements face-ai
docs/                      SECURITY-AUDIT.md (lỗi đã biết), ONBOARDING.md, mobile-api.md
images/ (tuỳ chọn)         docker image đã build sẵn (docker load -i images/sectest-images.tar)
CREDENTIALS.txt/.env       gửi RIÊNG — mật khẩu các tài khoản test
```

## 3. Dựng môi trường

Yêu cầu: Docker (compose v2), `curl`, ~8 GB RAM. Lần build đầu face-ai tải thư viện và model InsightFace (cần Internet).

1. Đặt `CREDENTIALS.env` (nhận riêng) vào thư mục gốc của gói.
2. Nếu có `images/`: `docker load -i images/sectest-images.tar`, rồi `deploy/start.sh --no-build`.
   Không có thì: `deploy/start.sh` (build từ `src/`).
3. Script tự: khởi động Postgres, Mailpit, face-ai, backend → chờ backend tạo bảng → `init-db.sql` (role DB của
   gateway, tài khoản test, trỏ email hệ thống sang Mailpit) → khởi động gateway.

Cổng publish: 9090 (web), 8081 (gateway), 8025 (Mailpit). Postgres và face-ai không publish. Đổi cổng trong `deploy/.env`.

## 4. Tài khoản và dữ liệu test

| Tài khoản | Role | Dùng ở |
|---|---|---|
| `ia.admin` | ADMIN | Web: quản lý tài khoản, cấu hình hệ thống |
| `ia.manager` | MANAGER | Web: duyệt khuôn mặt, nghỉ phép, xem chấm công phòng DE211 |
| `ia.employee` | EMPLOYEE | Web: đăng ký khuôn mặt, nghỉ phép, báo cáo |
| `ia.checker` | CHECKER | Chỉ màn kích hoạt của app máy quét (web từ chối CHECKER) |

Mật khẩu trong `CREDENTIALS.txt`. Tạo thêm nhân viên qua trang đăng ký (OTP xem ở Mailpit) hoặc bằng `ia.admin`.

Để thử chấm công khuôn mặt:
1. Đăng nhập `ia.employee` → Face ID → đăng ký 5 ảnh khuôn mặt (dùng mặt của người trong team IA, **không dùng ảnh nhân viên thật**).
2. Đăng nhập `ia.manager` → Face ID → duyệt hồ sơ (chưa duyệt thì không chấm công được).
3. Cài APK lên máy Android 12+ (`adb install apk/*.apk`), mở app → kích hoạt bằng `ia.checker`.
4. Đứng trước máy quét; trong giờ ca (cấu hình ở trang hệ thống của `ia.admin`).

Chế độ kiosk (khoá máy vào app) chỉ bật khi app là device owner — máy đã reset, chưa có tài khoản Google:
`adb shell dpm set-device-owner com.DxLab.dxlabattendance/.kiosk.CheckerDeviceAdminReceiver`.

## 5. Khác biệt so với bản phát hành (đã biết, không cần báo lại)

- **APK là bản debug**, gọi gateway qua **HTTP** (bản release bắt buộc HTTPS nhưng hệ thống chưa dựng TLS — audit H2).
  Bản debug có `debuggable=true`, log request/response vào logcat và lớp debug trên màn quét.
  Bản release bật R8; có thể build bằng `./gradlew assembleRelease -PcheckerBaseUrl=https://...` nếu dựng TLS cho môi trường test.
- Toàn bộ dịch vụ chạy HTTP trong LAN (H2). Đánh giá rủi ro nghe lén là hợp lệ nhưng đã biết.
- Email hệ thống trỏ vào Mailpit thay vì SMTP thật (cấu hình `APP_MAIL_HOST/PORT/STARTTLS`).

## 6. Phạm vi kiểm thử

**Trong phạm vi**: mọi endpoint của Attendance (9090) và gateway (8081); app máy quét (APK, giao tiếp mạng, lưu trữ
trên máy, chế độ kiosk); chống giả mạo khuôn mặt (ảnh in, màn hình, video) ở máy quét và qua API; phân quyền giữa các
role; luồng OTP/quên mật khẩu; mã nguồn (`src/`) và thư viện (`sbom/`).

**Ngoài phạm vi**: hạ tầng ngoài môi trường test; SMTP và tài khoản email thật của công ty; tấn công từ chối dịch vụ
quy mô lớn; các router "Face Lab" cũ trong face-ai (không publish, chỉ gọi được nội bộ) trừ khi qua được backend/gateway.

**Quy tắc**: chỉ tấn công máy chạy môi trường test (`<host>`), trong khung thời gian đã thống nhất; không dùng ảnh
khuôn mặt, dữ liệu, tài khoản thật của nhân viên; dữ liệu và ảnh tạo ra trong lúc test bị xoá khi kết thúc
(`docker compose down -v` trong `deploy/`).

Liên hệ: `<tên, kênh liên lạc của team phát triển>` — điền trước khi gửi.

## 7. Lỗi đã biết và việc cần kiểm thử lại

Xem đầy đủ trong `docs/SECURITY-AUDIT.md`. Tóm tắt:

**Đã sửa — đề nghị kiểm thử lại (retest):**

| Mã | Nội dung đã sửa | Gợi ý kiểm thử |
|---|---|---|
| C1 | Bỏ API chấm công thủ công không xác thực | `POST /api/face-id/attendance/manual` phải không tồn tại |
| C2 | `/api/face-id/attendance` chỉ nhận `X-API-Key` của gateway; Attendance tự kiểm tra người thật (fail closed) | Gọi không/sai key; gửi ảnh in/ảnh màn hình qua gateway |
| C3 | `/uploads/**` cần đăng nhập + đúng chủ ảnh/MANAGER/ADMIN | Tải ảnh mặt/ảnh bằng chứng của người khác, path traversal |
| C4 | APK không còn tài khoản gắn cứng; kích hoạt máy bằng tài khoản CHECKER | Dịch ngược APK, tìm secret; thử dùng lại refresh token |
| C5 | Mỗi mã OTP chỉ nhập được 5 lần | Dò OTP qua form web và qua gateway |
| H1, H5 | Bỏ danh bạ/nhận diện công khai; trang duyệt khuôn mặt chỉ MANAGER/ADMIN | Truy cập `/face-id`, `/face-id/accept/{id}` với role thấp |
| H3 | Chặn POST khác origin, cookie `SameSite=Lax` | CSRF trên các form quản trị |
| H4 | `/api/otp/*` chỉ cho gateway; nút gửi lại mã luôn trả 204 | Gửi OTP tới địa chỉ tuỳ ý; dò email có tài khoản |
| H6 | Báo cáo chỉ người nộp/được chia sẻ/ADMIN xem được | IDOR `GET /api/report/{id}`, file đính kèm |
| M3, M6, M7 | Role CHECKER; kiosk lock task; R8 cho release | CHECKER đăng nhập web; thoát kiosk |

**Còn mở — đã biết (không cần báo lại, trừ khi tìm ra cách khai thác nghiêm trọng hơn):**
H2 (không TLS), M1–M2 (secret cũ và DB trong lịch sử git — gói này không kèm lịch sử), M4 (quyền DB của gateway),
M5 (gateway đặt lại mật khẩu không qua Attendance), M8 (luồng OAuth2 PKCE thừa ở gateway), các mục "Thấp" và
"Rủi ro đã chấp nhận" trong `docs/SECURITY-AUDIT.md`.

## 8. Báo cáo lỗi

Mỗi lỗi: tag gói, thành phần, endpoint/màn hình, bước tái hiện, request/response mẫu, mức độ (CVSS nếu có),
đề xuất. Không đính kèm ảnh khuôn mặt người thật vào báo cáo.
