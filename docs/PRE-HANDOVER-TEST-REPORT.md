# Báo cáo kiểm thử trước bàn giao — gói `sec-test-2026-10-28d`

> Lịch sử trong ngày 28/10: `28b` (bản đầu của báo cáo này) → `28c` thêm ân hạn 5 phút cho refresh token cũ ở gateway
> (55/55 `verify.sh`) → `28d` thêm chính sách mật khẩu và thử lại khi tải thư viện/model lúc build face-ai. Số liệu dưới
> đây là của `28d` trừ khi ghi khác.

Ngày chạy: 28/10/2026. Người chạy: team phát triển. Máy chạy: Windows 11 + Docker Desktop (Git Bash), IP `10.33.102.101`;
điện thoại vivo V2511, Android 16, cùng mạng Wi-Fi.

Mục đích: xác nhận gói chạy được từ đầu và các bản sửa bảo mật hoạt động **trước khi** Team IA kiểm thử, để IA không
mất thời gian vào lỗi dựng môi trường như ở gói `sec-test-2026-10-27b` (xem bảng "Thay đổi" trong `README.md`).

## Tóm tắt

| Lớp kiểm thử | Công cụ | Kết quả |
|---|---|---|
| Unit test Attendance | JUnit 5 + Mockito (`./mvnw test`) | **45/45 đạt** (11 lớp) |
| Unit + integration test gateway | JUnit 5, Spring Boot Test + MockMvc + H2 (`./mvnw verify`) | **24/24 đạt** (5 lớp, 4 lớp khởi động toàn bộ Spring context) |
| Unit test app Android | JUnit 4 (`./gradlew testDebugUnitTest`) | **10/10 đạt** (3 lớp) |
| Phân tích tĩnh app Android | Android Lint (`./gradlew lintDebug`) | **0 lỗi**, 56 cảnh báo (xem dưới) |
| Tích hợp/E2E trên môi trường đóng gói | `deploy/verify.sh` (curl + docker) | **60/60 đạt** (xem mục 4) |
| Thử trên thiết bị thật | APK debug của gói trên Android 16 | Đạt (xem mục 5) |
| Kiểm tra APK | aapt, apksigner, quét chuỗi | Đạt (xem mục 6) |
| Kiểm tra gói | `sha256sum -c`, quét secret của `package.sh` | Đạt |

Lỗi phát hiện và đã sửa trong đợt này:

| Lỗi | Phát hiện bởi | Sửa |
|---|---|---|
| 2 test `LeaveScheduleServiceImplTest` lỗi `NullPointerException: zone` (lỗi có từ trước, test không truyền `ZoneId` sau khi service thêm múi giờ) | Unit test | Test khởi tạo service với `Asia/Ho_Chi_Minh` |
| Manifest Android khai báo quyền `CAMERA` mà không khai báo `uses-feature` camera → máy chỉ có camera trước (tablet đặt cửa) bị coi là không tương thích | Lint (lỗi) | Khai báo `camera.any` bắt buộc, camera sau không bắt buộc |
| face-ai tải model InsightFace (288 MB, GitHub) **mỗi lần tạo container** — sau `docker compose down -v` (cách làm sạch README hướng dẫn) phải tải lại; mạng chậm (đo được 36 KB/s) thì face-ai không sẵn sàng sau hơn 10 phút, đăng ký mặt/chấm công báo AI không khả dụng | `verify.sh` trên môi trường vừa làm sạch | Đóng sẵn model vào image face-ai lúc build (`face-ai/Dockerfile`); khởi động không cần Internet |

## 1. Unit test Attendance (Spring Boot)

`./mvnw test` — 45 test, 0 lỗi. `PasswordPolicyTest` kiểm chính sách mật khẩu (kể cả chữ có dấu, khoảng trắng, quá 72 ký tự) và việc form đổi mật khẩu không qua `@Valid` vẫn bị chặn ở service. Phủ các phần đã sửa bảo mật: `FaceIdAttendanceServiceImplTest` (C2, kiểm tra người thật
fail-closed, hồ sơ chưa duyệt), `FaceIdApiKeyFilterTest` (khoá API gateway), `UploadControllerTest` (C3, quyền xem ảnh),
`OtpServiceImplTest` (C5, giới hạn 5 lần nhập), `ReportServiceImplTest` (H6, IDOR báo cáo), `OriginCheckFilterTest` (H3, CSRF),
cùng các test nghiệp vụ nghỉ phép/đăng ký khuôn mặt.

Attendance không có integration test riêng (test `@SpringBootTest` mặc định đã bị comment vì cần Postgres). Phần tích hợp
được kiểm bằng `verify.sh` trên Postgres thật (mục 4).

## 2. Unit + integration test gateway (mobileBackend)

`./mvnw verify` — 24 test, 0 lỗi. `AttendanceCheckTest`, `MobileAuthFlowTest`, `ServerTokenFlowTest`, `FaceApiSecurityTest`
khởi động toàn bộ ứng dụng (Spring Security, Authorization Server, JWT ký bằng keystore test, H2) và gọi qua MockMvc:
đăng nhập CHECKER, OTP, xoay vòng refresh token, phân quyền theo scope, giới hạn tần suất, form `/login` và luồng PKCE
đã bị gỡ (M8), token client_credentials thật. `ImageValidatorTest` kiểm tra lọc ảnh tải lên.

## 3. App Android

- `testDebugUnitTest` — 10 test: phân tích token/OTP, máy chưa kích hoạt không gọi mạng, quyết định chụp/chấm công,
  chuẩn hoá ô "Địa chỉ máy chủ".
- `lintDebug` — 0 lỗi. Cảnh báo: 35 `Typos` (từ tiếng Việt), 10 thư viện/AGP có bản mới hơn, 7 resource không dùng,
  1 `OldTargetApi`, 1 `InsecureBaseConfiguration` (cấu hình mạng **bản debug** cho phép HTTP — cố ý, đã biết, audit H2), 2 mục nhỏ khác.
- Không có instrumented UI test (chỉ có test mẫu của Android Studio); luồng giao diện được thử tay trên máy thật (mục 5).

## 4. Tích hợp/E2E — `deploy/verify.sh`

Chạy trên môi trường dựng từ chính gói này (`docker compose down -v` → `start.sh` → `verify.sh 10.33.102.101`).
Ảnh khuôn mặt sinh trong container face-ai từ ảnh mẫu công khai đi kèm thư viện InsightFace (`deploy/faces.py`),
không dùng ảnh nhân viên thật. Kết quả: **60/60 đạt** trên DB sạch, gồm 5 kiểm tra chính sách mật khẩu (đổi mật khẩu
qua API và qua form, đăng ký, đặt lại qua gateway đều từ chối mật khẩu yếu; mật khẩu cũ không bị đổi) và 3 kiểm tra ân
hạn refresh token. Ngoài `verify.sh`: đổi sang mật khẩu đạt chính sách thành công và đăng nhập được bằng mật khẩu mới.

Lần chạy 60/60 dùng image backend và gateway build từ đúng mã nguồn của `28d`, chạy thành stack riêng (cổng khác) để không
ảnh hưởng môi trường đang kiểm thử; image face-ai là bản của `28c` (mã face-ai không đổi, chỉ Dockerfile `28d` thêm thử lại
khi tải — bước `pip install` và tải model của Dockerfile mới đã chạy thành công khi build thử).

| Nhóm | Kiểm tra |
|---|---|
| Dịch vụ | Web, gateway, Mailpit sống; Postgres (5432) và face-ai (8002) không publish ra ngoài |
| Web | Đăng nhập ADMIN/MANAGER/EMPLOYEE, CHECKER và sai mật khẩu bị từ chối; trang cài đặt chỉ ADMIN; duyệt khuôn mặt chỉ MANAGER/ADMIN; `/api/report` chỉ ADMIN; `/uploads` cần đăng nhập; API chấm công/OTP cần khoá gateway (không/sai khoá → 401); POST khác origin và `Origin: null` → 403; API chấm công thủ công (C1) không còn; cookie `HttpOnly` + `SameSite=Lax`; header `X-Content-Type-Options`, `X-Frame-Options`; link "Cài Đặt Hệ Thống" đúng |
| Gateway | CHECKER đăng nhập; không token/token giả → 401; EMPLOYEE không đăng nhập được; refresh xoay vòng, token cũ bị từ chối; `/`, form `/login`, `/oauth2/authorize` không còn dùng được |
| OTP | Quên mật khẩu trên web và trên gateway đều gửi mã tới Mailpit |
| face-ai | API nội bộ không có khoá → 401/403 |
| Luồng chấm công | ADMIN nới giờ ca → EMPLOYEE đăng ký 5 mẫu → máy quét chấm khi **chưa duyệt** bị từ chối (422) → MANAGER duyệt → **vào ca** đúng người → gửi lại cùng `requestId` không chấm 2 lần → **tan ca** → người chưa đăng ký và file không phải ảnh bị từ chối → DB có đúng 1 bản ghi vào/ra ca → chủ ảnh xem được ảnh mẫu, ẩn danh không xem được |

Lần chạy này dùng image của gói `sec-test-2026-10-28` (face-ai còn tải model lúc khởi động — mất ~25 phút với mạng
36–100 KB/s). Gói `sec-test-2026-10-28b` khác ở: model đóng sẵn trong image face-ai, khai báo camera trong manifest,
sửa test và tài liệu — không đổi mã nghiệp vụ của web/gateway.

`verify.sh` tạo dữ liệu; sau khi chạy, môi trường bàn giao được làm sạch bằng `docker compose down -v && ./start.sh`.

## 5. Thử trên thiết bị thật

APK debug của gói, cài bằng `adb install -r` lên vivo V2511 (Android 16):

- Cài đặt, mở app: màn "Kích hoạt máy quét" có ô "Địa chỉ máy chủ" điền sẵn `http://10.33.102.101:8081`.
- Đổi địa chỉ sang `192.168.137.1:8081` (IP khác của cùng máy) + tài khoản không tồn tại → server trả "Tên đăng nhập hoặc
  mật khẩu không đúng" (app gọi đúng địa chỉ mới qua mạng Wi-Fi).
- Địa chỉ sai → "Không kết nối được máy chủ. Kiểm tra mạng."; kích hoạt thất bại không lưu địa chỉ.
- Gói trước: refresh token cũ không còn hợp lệ → app tự xoá phiên, quay về màn kích hoạt.
- Chưa thử trên thiết bị: kích hoạt bằng mật khẩu thật của `ia.checker` và quét mặt người thật trước camera
  (luồng này đã kiểm qua API ở mục 4); chế độ kiosk device owner (máy thử có tài khoản Google, không đặt được).

## 6. Kiểm tra APK và gói

- APK debug: `minSdk 31`, `targetSdk 36`; quyền `INTERNET`, `CAMERA`, `ACCESS_NETWORK_STATE`; ký bằng khoá debug;
  thành phần export: `MainActivity` (launcher), `CheckerDeviceAdminReceiver` (yêu cầu `BIND_DEVICE_ADMIN`), receiver của
  androidx (yêu cầu `DUMP`), và `PreviewActivity`/`ComponentActivity` của thư viện Compose **chỉ có ở bản debug**.
  Không tìm thấy mật khẩu, khoá API, private key, tài khoản test hay chuỗi JDBC trong APK.
- APK release (build thử với `-PcheckerBaseUrl=https://...`): không `debuggable`, không có activity debug, R8 gộp còn 1 dex.
- Gói: `sha256sum -c SHA256SUMS` khớp; không có `.git`, `.env` của dev, file DB, ảnh khuôn mặt; script kết thúc dòng LF;
  `package.sh` quét secret cũ trong gói không thấy.

## 7. Chưa làm (ngoài phạm vi đợt này)

- Quét lỗ hổng thư viện (CVE): chưa chạy công cụ (OWASP Dependency-Check, pip-audit, Trivy). SBOM có sẵn trong `sbom/`.
- face-ai không có test tự động riêng; được kiểm gián tiếp qua luồng chấm công ở mục 4.
- Kiểm thử tải/từ chối dịch vụ; TLS (chưa dựng, audit H2).
- Lỗi nhỏ đã biết: trên DB mới chưa lưu giờ ca, gateway `GET /api/v1/attendance/shift` trả 404 trong khi web dùng giờ mặc định.
  App máy quét không gọi endpoint này; lưu giờ ca ở `/system/setting` (README mục 4.1) thì hết.

## Chạy lại

```
# Attendance (thư mục src/attendance của gói, hoặc repo)
./mvnw test
# Gateway
cd src/mobileBackend && ./mvnw verify
# Android (cần JDK 17+, Android SDK)
cd src/DxLabAttendance && ./gradlew testDebugUnitTest lintDebug
# Môi trường đóng gói (sau start.sh), trong deploy/
./verify.sh <host>
```
