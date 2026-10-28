# Bàn giao kiểm thử bảo mật — Hệ thống chấm công khuôn mặt

Tài liệu này là `README.md` của gói bàn giao cho Team IA. Phiên bản chính xác của từng thành phần nằm trong
`VERSIONS.txt` (commit hash + tag). Khi báo lỗi, ghi rõ tag của gói.

> **Gói này được đóng cho máy chạy môi trường test có IP `<host>`.** APK trong `apk/` điền sẵn gateway
> `http://<host>:8081`. Dựng môi trường ở máy khác/IP khác thì sửa ô "Địa chỉ máy chủ" trên màn kích hoạt (mục 4.3).

**Thay đổi so với gói `sec-test-2026-10-27b`** (theo báo cáo kiểm tra của IA ngày 27/10/2026):

| Báo cáo IA | Nguyên nhân | Đã xử lý |
|---|---|---|
| Dashboard admin → "Cài Đặt Hệ Thống" báo 404 | Link trỏ sai `/setting` | Sửa thành `/system/setting` |
| Gateway 8081: đăng nhập trên trình duyệt xong báo 404 | Gateway chỉ là API, không có giao diện web; form `/login` còn sót lại từ luồng OAuth2 PKCE cũ | Bỏ form `/login` và luồng PKCE (audit M8). Mọi đường dẫn ngoài `/api/**`, `/oauth2/token` (client_credentials) và `/actuator/health` trả 403 |
| APK không kết nối được máy chủ dù cùng mạng | APK gắn cứng IP của máy đóng gói, IA chạy môi trường trên máy khác | Bản debug có ô "Địa chỉ máy chủ" trên màn kích hoạt, không cần build lại APK |

Ngoài ra: model InsightFace được đóng sẵn vào image face-ai (trước đây tải ~300 MB mỗi lần tạo lại container); gói có `deploy/verify.sh` để tự kiểm tra môi trường sau khi dựng (mục 3), và `docs/PRE-HANDOVER-TEST-REPORT.md`
— kết quả unit/integration/E2E và thử trên thiết bị thật mà team phát triển đã chạy trên chính gói này trước khi gửi.

## 1. Thành phần

| Thành phần | Công nghệ | Vai trò | Truy cập trong môi trường test |
|---|---|---|---|
| Attendance (`src/attendance`) | Spring Boot 3, Thymeleaf, Postgres | Web quản trị/nhân viên, nghiệp vụ chấm công, OTP | `http://<host>:9090` (session cookie) |
| face-ai (`src/attendance/face-ai`) | FastAPI, InsightFace, ONNX | Phát hiện/nhận diện khuôn mặt, kiểm tra người thật | Chỉ nội bộ (`http://face-ai:8002`, `X-API-Key`) |
| Mobile gateway (`src/mobileBackend`) | Spring Boot, Spring Authorization Server | API cho app máy quét — **không có giao diện web** | `http://<host>:8081/api/v1/...` (JWT Bearer) |
| App máy quét (`src/DxLabAttendance`) | Android (Kotlin, Compose, CameraX) | Máy quét đặt cố định ở cửa, role CHECKER | `apk/*.apk` |
| Mailpit | — | Nhận email OTP của môi trường test (không gửi ra ngoài) | `http://<host>:8025` |

Luồng chấm công: app → gateway (`/api/v1/attendance/check`, JWT của tài khoản CHECKER) → gateway nhận diện 1:N qua
face-ai → gateway gọi Attendance `POST /api/face-id/attendance` kèm `X-API-Key` → Attendance tự kiểm tra lại
khuôn mặt + người thật qua face-ai `/recognize` rồi ghi công.

Ranh giới tin cậy và cơ chế xác thực:

| Cạnh | Xác thực |
|---|---|
| Trình duyệt → Attendance | Form login, session cookie (`SameSite=Lax`, `HttpOnly`); chặn POST khác origin (`OriginCheckFilter`) |
| App → gateway | `/api/v1/auth/login` (CHECKER) → access token JWT 15 phút + refresh token 30 ngày, xoay vòng (token cũ còn dùng được 5 phút sau khi xoay) |
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
deploy/                    docker-compose.yml, start.sh, init-db.sql, verify.sh (tự kiểm tra), secret của RIÊNG môi trường test
apk/                       APK debug của app máy quét, trỏ về gateway của môi trường test
sbom/                      CycloneDX (2 dịch vụ Java), danh sách thư viện Android, requirements face-ai
docs/                      SECURITY-AUDIT.md (lỗi đã biết), PRE-HANDOVER-TEST-REPORT.md (kết quả test trước bàn giao),
                           ONBOARDING.md, mobile-api.md
images/ (tuỳ chọn)         docker image đã build sẵn (docker load -i images/sectest-images.tar)
CREDENTIALS.txt/.env       gửi RIÊNG — mật khẩu các tài khoản test
```

## 3. Dựng môi trường

Yêu cầu: Docker có compose v2 (Linux, macOS, hoặc Docker Desktop trên Windows), `bash`, `curl`, ~8 GB RAM, ~10 GB đĩa.

1. Đặt `CREDENTIALS.env` (nhận riêng) vào thư mục gốc của gói (cùng cấp với `README.md`).
2. Nếu có `images/`: `docker load -i images/sectest-images.tar`, rồi `deploy/start.sh --no-build`.
   Không có thì: `deploy/start.sh` (build từ `src/`, lần đầu mất 10–20 phút).
3. Script tự: khởi động Postgres, Mailpit, face-ai, backend → chờ backend tạo bảng → `init-db.sql` (role DB của
   gateway, tài khoản test, trỏ email hệ thống sang Mailpit) → khởi động gateway.
4. Kiểm tra: mở `http://<host>:9090` (trang đăng nhập), `http://<host>:8081/actuator/health` (`{"status":"UP"}`),
   `http://<host>:8025` (Mailpit). Gateway không có trang web: mở `http://<host>:8081/` trên trình duyệt trả 403 là
   đúng; thử gateway bằng API (`docs/mobile-api.md`) hoặc qua app.
5. (Khuyến nghị) Tự kiểm tra toàn bộ: `cd deploy && ./verify.sh <host>` — khoảng 1 phút, in PASS/FAIL cho 60 kiểm tra:
   đăng nhập từng role, phân quyền, gateway, OTP, và một lượt chấm công đầy đủ (đăng ký mặt → duyệt → vào ca → tan ca).
   Script **tạo dữ liệu** (mặt và chấm công của `ia.employee`, giờ ca nới rộng); muốn bắt đầu kiểm thử từ môi trường sạch thì
   chạy tiếp `docker compose down -v && ./start.sh`. Có mục FAIL thì gửi kèm toàn bộ output cho team phát triển.

Cổng publish: 9090 (web), 8081 (gateway), 8025 (Mailpit). Postgres và face-ai không publish. Đổi cổng trong `deploy/.env`.

**Windows**: chạy `start.sh` bằng **Git Bash** (đi kèm Git for Windows) hoặc WSL, không chạy bằng PowerShell/cmd.
Mở tường lửa để điện thoại và máy của IA truy cập được (PowerShell quyền Administrator):
`New-NetFirewallRule -DisplayName "attendance-sectest" -Direction Inbound -Protocol TCP -LocalPort 9090,8081,8025 -Action Allow`.

**Internet**: chỉ cần khi **build** image (Maven, pip, Docker Hub, và model InsightFace `buffalo_l` ~300 MB tải từ GitHub
— được đóng sẵn vào image face-ai). Khởi động, `docker compose down -v` rồi dựng lại đều không tải gì thêm. Dùng `images/`
(nếu gói kèm) thì không cần Internet. Mạng chậm thì bước build face-ai có thể mất lâu — đó là đang tải model, không phải treo.

Dừng: `docker compose down` trong `deploy/`. Xoá sạch dữ liệu để làm lại từ đầu: `docker compose down -v` rồi chạy lại `start.sh`.

## 4. Tài khoản và dữ liệu test

| Tài khoản | Role | Dùng ở |
|---|---|---|
| `ia.admin` | ADMIN | Web: quản lý tài khoản, cấu hình hệ thống |
| `ia.manager` | MANAGER | Web: duyệt khuôn mặt, nghỉ phép, xem chấm công phòng DE211 |
| `ia.employee` | EMPLOYEE | Web: đăng ký khuôn mặt, nghỉ phép, báo cáo |
| `ia.checker` | CHECKER | Chỉ màn kích hoạt của app máy quét (web từ chối CHECKER) |

Mật khẩu trong `CREDENTIALS.txt`. Tạo thêm nhân viên qua trang đăng ký (OTP xem ở Mailpit) hoặc bằng `ia.admin`.

**Chính sách mật khẩu** (đăng ký, đổi mật khẩu, quên mật khẩu trên web, đặt lại mật khẩu máy quét qua gateway): 8–72 ký tự,
có ít nhất 1 chữ hoa, 1 chữ thường, 1 chữ số và 1 ký tự đặc biệt (không phải chữ, số hay khoảng trắng; chữ có dấu như "ậ"
tính là chữ). Kiểm tra ở server, kể cả khi gọi thẳng API. Chỉ áp khi **đặt** mật khẩu: tài khoản cũ có mật khẩu yếu vẫn
đăng nhập được cho tới khi đổi.

### 4.1. Giờ ca (làm trước khi thử chấm công)

Chưa cấu hình thì hệ thống dùng giờ mặc định: vào ca 08:30, **chậm nhất 10:00 mới được vào ca**, **sớm nhất 16:00
mới được tan ca**, tan ca 17:30; mọi ngày trong tuần đều là ngày làm. Giờ tính theo múi `Asia/Ho_Chi_Minh` (UTC+7).
Ngoài các khung này, chấm công bị từ chối đúng nghiệp vụ ("Đã quá thời gian ca làm...", "Chưa đến thời gian tối thiểu
để checkout") — không phải lỗi.

Để thử bất kỳ lúc nào: đăng nhập `ia.admin` → `http://<host>:9090/system/setting` → mục giờ làm, đặt
`attendance.max-check-in-time` = 23:59 và `attendance.min-check-out-time` = 00:00 → lưu. Mỗi nhân viên chỉ vào ca
1 lần và tan ca 1 lần mỗi ngày; muốn thử lại thì tạo thêm nhân viên hoặc đợi sang ngày.

### 4.2. Thử chấm công khuôn mặt

1. Đăng nhập `ia.employee` → Face ID → đăng ký 5 ảnh khuôn mặt (dùng mặt của người trong team IA, **không dùng ảnh nhân viên thật**).
2. Đăng nhập `ia.manager` → Face ID → duyệt hồ sơ (chưa duyệt thì không chấm công được; mỗi lần cập nhật ảnh phải duyệt lại).
3. Cài APK lên máy Android 12+ có camera trước (`adb install apk/*.apk`), máy cùng mạng với `<host>`.
   Mở app → màn kích hoạt → kiểm tra ô "Địa chỉ máy chủ" (mục 4.3) → nhập `ia.checker` và mật khẩu.
4. Đứng trước máy quét, nhìn thẳng; máy nhận ra 2 khung liên tiếp thì tự chụp và chấm công, hiện kết quả 3 giây.

Chế độ kiosk (khoá máy vào app) chỉ bật khi app là device owner — máy đã reset, chưa có tài khoản Google:
`adb shell dpm set-device-owner com.DxLab.dxlabattendance/.kiosk.CheckerDeviceAdminReceiver`.
Gỡ: `adb shell dpm remove-active-admin com.DxLab.dxlabattendance/.kiosk.CheckerDeviceAdminReceiver` (máy test) hoặc reset máy.

### 4.3. APK và địa chỉ gateway

Màn kích hoạt của APK debug có ô **"Địa chỉ máy chủ (gateway)"**, điền sẵn `http://<host>:8081`. Chạy môi trường ở
máy khác thì nhập `http://<ip-máy-chạy-môi-trường>:8081` (gõ `<ip>:8081` cũng được, app tự thêm `http://`). Lấy IP:
`ipconfig` (Windows) / `ip addr` (Linux) — chọn IP cùng dải mạng với điện thoại, **không** dùng `localhost`/`127.0.0.1`
(trên điện thoại đó là chính điện thoại). Kiểm tra trước bằng trình duyệt của điện thoại:
`http://<ip>:8081/actuator/health` phải ra `{"status":"UP"}`; không ra thì xem tường lửa (mục 3).

Địa chỉ được lưu sau khi kích hoạt thành công và dùng cho mọi lần sau; muốn đổi thì thu hồi phiên (mục 4.4) hoặc xoá
dữ liệu app để quay lại màn kích hoạt. Bản release không có ô này: địa chỉ gateway cố định lúc build và bắt buộc HTTPS.

Muốn đổi địa chỉ điền sẵn thì build lại (JDK 17+, Android SDK):
`cd src/DxLabAttendance && ./gradlew assembleDebug -PcheckerDebugBaseUrl=http://<ip>:8081`.

### 4.4. Phiên đăng nhập của máy quét

App không lưu mật khẩu, chỉ giữ refresh token (30 ngày, đổi mới mỗi lần làm mới access token 15 phút — máy chạy liên tục
không bao giờ phải đăng nhập lại; không giới hạn giờ đăng nhập). App quay về màn kích hoạt khi refresh token hết hạn/bị
thu hồi, hoặc khi xoá dữ liệu app.

**Ân hạn khi xoay vòng (cố ý)**: sau khi bị xoay, refresh token cũ còn dùng được thêm 5 phút (`REFRESH_REUSE_GRACE` trong
`deploy/mobile-backend.env`), mỗi lần dùng cấp cặp token mới. Mục đích: phản hồi làm mới mất giữa đường (Wi-Fi chập lúc
nửa đêm) thì máy quét gửi lại token cũ, không bị văng ra màn kích hoạt. Dùng lại token cũ trong 5 phút đó là hành vi đã
biết; dùng lại **sau** 5 phút mà vẫn được thì là lỗi. Thu hồi mọi phiên của máy quét
(chạy trong `deploy/`):
`docker compose exec postgres psql -U postgres -d attendance -c "DELETE FROM mobile_auth.mobile_refresh_token WHERE username = 'ia.checker'"`.
App phát hiện ở lần làm mới token kế tiếp (tối đa 15 phút).

### 4.5. OTP khi đăng nhập gateway (tuỳ chọn)

Mặc định tắt. Bật: đặt `LOGIN_OTP_ENABLED=true` trong `deploy/mobile-backend.env` → `docker compose up -d mobile-backend`
(trong `deploy/`). App máy quét chưa hỗ trợ màn nhập OTP (sẽ báo lỗi), nên thử luồng này qua API
`/api/v1/auth/login` → `/api/v1/auth/login/otp` (xem `docs/mobile-api.md`); mã OTP xem ở Mailpit.

## 5. Khác biệt so với bản phát hành (đã biết, không cần báo lại)

- **APK là bản debug**, gọi gateway qua **HTTP** (bản release bắt buộc HTTPS nhưng hệ thống chưa dựng TLS — audit H2).
  Bản debug có `debuggable=true`, log request/response vào logcat và lớp debug trên màn quét.
  Bản release bật R8; có thể build bằng `./gradlew assembleRelease -PcheckerBaseUrl=https://...` nếu dựng TLS cho môi trường test.
- Toàn bộ dịch vụ chạy HTTP trong LAN (H2). Đánh giá rủi ro nghe lén là hợp lệ nhưng đã biết.
- Email hệ thống trỏ vào Mailpit thay vì SMTP thật (cấu hình `APP_MAIL_HOST/PORT/STARTTLS`).

## 6. Xử lý sự cố

Chạy trong `deploy/`:

| Hiện tượng | Kiểm tra / cách xử lý |
|---|---|
| `start.sh` báo thiếu `CREDENTIALS.env` | Đặt file nhận riêng vào thư mục gốc gói |
| Build lỗi `Read timed out` / `failed to solve` (pip, Maven, tải model) | Mạng chập khi tải thư viện. Chạy lại `start.sh`: Docker giữ các bước đã xong, chỉ chạy lại bước lỗi (pip và tải model đã tự thử lại nhiều lần) |
| Backend không lên sau 7,5 phút | `docker compose logs backend`; chạy lại `start.sh` (chạy lại nhiều lần an toàn) |
| Gateway khởi động lại liên tục | `docker compose logs mobile-backend`; thường do bước 3/4 của `start.sh` chưa chạy xong → chạy lại `start.sh` |
| Đăng ký khuôn mặt/chấm công báo AI không khả dụng | `docker compose logs face-ai` — face-ai cần ~1 phút để nạp model sau khi khởi động; container dừng/khởi động lại liên tục thì gửi log cho team phát triển |
| App báo "Không kết nối được máy chủ" | Ô "Địa chỉ máy chủ" sai IP hoặc dùng `localhost` (mục 4.3), điện thoại khác mạng, tường lửa chặn 8081 — thử `http://<ip>:8081/actuator/health` trên trình duyệt điện thoại |
| Mở `http://<host>:8081` trên trình duyệt báo 403 | Đúng thiết kế: gateway chỉ có API, không có trang web |
| Không nhận được OTP | Mở Mailpit `http://<host>:8025`; gửi lại có thời gian chờ 90 giây |
| Chấm công bị từ chối "quá thời gian"/"chưa đến thời gian" | Đúng nghiệp vụ giờ ca — xem mục 4.1 |
| "Khuôn mặt chưa được quản lý duyệt" | `ia.manager` duyệt hồ sơ ở trang Face ID |

Trạng thái chung: `docker compose ps`. Log theo thời gian thực: `docker compose logs -f <dịch vụ>`.

## 7. Phạm vi kiểm thử

**Trong phạm vi**: mọi endpoint của Attendance (9090) và gateway (8081); app máy quét (APK, giao tiếp mạng, lưu trữ
trên máy, chế độ kiosk); chống giả mạo khuôn mặt (ảnh in, màn hình, video) ở máy quét và qua API; phân quyền giữa các
role; luồng OTP/quên mật khẩu; mã nguồn (`src/`) và thư viện (`sbom/`).

**Ngoài phạm vi**: hạ tầng ngoài môi trường test; SMTP và tài khoản email thật của công ty; tấn công từ chối dịch vụ
quy mô lớn; các router "Face Lab" cũ trong face-ai (không publish, chỉ gọi được nội bộ) trừ khi qua được backend/gateway.

**Quy tắc**: chỉ tấn công máy chạy môi trường test (`<host>`), trong khung thời gian đã thống nhất; không dùng ảnh
khuôn mặt, dữ liệu, tài khoản thật của nhân viên; dữ liệu và ảnh tạo ra trong lúc test bị xoá khi kết thúc
(`docker compose down -v` trong `deploy/`).

Liên hệ: `<tên, kênh liên lạc của team phát triển>` — điền trước khi gửi.

## 8. Lỗi đã biết và việc cần kiểm thử lại

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
| Mới | Chính sách mật khẩu (8–72 ký tự, chữ hoa, chữ thường, số, ký tự đặc biệt) ở mọi luồng đặt mật khẩu | Đăng ký/đổi/quên mật khẩu bằng mật khẩu yếu qua form, API, gateway |
| M8 (một phần) | Bỏ form `/login` và luồng OAuth2 auth code + PKCE ở gateway; chỉ còn client_credentials cho server (scope `face.embed` vẫn còn, đã biết) | `/login`, `/oauth2/authorize` trên 8081; lấy token bằng `authorization_code` |

**Còn mở — đã biết (không cần báo lại, trừ khi tìm ra cách khai thác nghiêm trọng hơn):**
H2 (không TLS), M1–M2 (secret cũ và DB trong lịch sử git — gói này không kèm lịch sử), M4 (quyền DB của gateway),
M5 (gateway đặt lại mật khẩu không qua Attendance), các mục "Thấp" và
"Rủi ro đã chấp nhận" trong `docs/SECURITY-AUDIT.md`.

## 9. Báo cáo lỗi

Mỗi lỗi: tag gói, thành phần, endpoint/màn hình, bước tái hiện, request/response mẫu, mức độ (CVSS nếu có),
đề xuất. Không đính kèm ảnh khuôn mặt người thật vào báo cáo.
