# Rà soát bảo mật — Attendance + Mobile API + App máy quét

Ngày: 2026-10-22. Phạm vi:

| Thành phần | Repo / nhánh đã đọc |
|---|---|
| Attendance (Spring Boot, web + `/api/face-id/**`, OTP) | `Attendance` — nhánh `feature/mobile-gateway` (bản đang tích hợp mobile) và `main` |
| face-ai (FastAPI) | `Attendance/face-ai` — `feature/mobile-gateway` |
| Mobile API gateway | `mobileBackend` — working tree (HEAD `3d23bf6`) |
| App Android máy quét (CHECKER) | `DxLabAttendance` — HEAD `170d04f` |

Chỉ rà soát code, chưa chạy thử khai thác. Đường dẫn file dưới đây tính từ gốc từng repo.

---

## Tóm tắt

**Mobile gateway viết khá chặt, nhưng toàn bộ lớp bảo vệ đó bị bỏ qua được**, vì Attendance phía sau vẫn mở
`/api/face-id/**` cho mọi người, không cần đăng nhập. Gateway gửi header `X-API-Key` khi ghi chấm công, nhưng
Attendance **không kiểm tra header đó** (`FaceIdApiKeyFilter` chỉ cho request đi qua). Comment trong
`mobileBackend/.../AttendanceClient.java:57` và `docs/mobile-api.md:55` nói key này được kiểm tra. Điều đó **sai**,
người tiếp quản đừng tin hai chỗ đó.

Hệ quả: ai vào được cổng 9090 của Attendance (cùng LAN, hoặc Internet nếu cổng bị public) có thể chấm công cho bất kỳ
nhân viên nào mà không cần mặt, không cần mật khẩu.

| # | Mức | Vấn đề | Thành phần |
|---|---|---|---|
| C1 | 🔴 Nghiêm trọng | ✅ đã sửa · `POST /api/face-id/attendance/manual` chấm công cho bất kỳ ai, không cần đăng nhập, không cần mặt | Attendance |
| C2 | 🔴 Nghiêm trọng | ✅ đã sửa · `POST /api/face-id/attendance` không cần đăng nhập và **không kiểm tra người thật**, nên dùng ảnh in hoặc ảnh tải về là qua | Attendance |
| C3 | 🔴 Nghiêm trọng | ✅ đã sửa · Ảnh khuôn mặt đăng ký của mọi nhân viên tải được công khai qua URL đoán trước `/uploads/face_samples/{id}/face_{id}_{n}.jpg` | Attendance |
| C4 | 🔴 Nghiêm trọng | ✅ đã sửa code (còn đổi mật khẩu) · Tài khoản máy quét `checker01` / `Admin@123` nằm cứng trong APK, gửi qua HTTP thường, **trùng mật khẩu admin** ghi trong ONBOARDING | App Android |
| C5 | 🔴 Nghiêm trọng | ✅ đã sửa · Xác thực OTP quên mật khẩu trên web không giới hạn số lần nhập sai, nên dò được mã và chiếm được tài khoản bất kỳ (kể cả ADMIN) | Attendance |
| H1 | 🟠 Cao | ✅ đã sửa · `GET /api/face-id/employees` và `POST /api/face-id/identify` không cần đăng nhập: lộ toàn bộ username, họ tên, và cho phép tra danh tính từ ảnh mặt | Attendance |
| H2 | 🟠 Cao | Toàn bộ đường mobile chạy HTTP thường (không TLS), lộ mật khẩu, refresh token và ảnh mặt trên LAN | App + gateway |
| H3 | 🟠 Cao | CSRF bị tắt toàn cục trên app dùng cookie session | Attendance |
| H4 | 🟠 Cao | `/api/otp/send` và `/api/otp/verify` mở công khai: gửi mail tới địa chỉ tuỳ ý, thử mã OTP không giới hạn | Attendance |
| H5 | 🟠 Cao | ✅ đã sửa · Trang duyệt khuôn mặt `/face-id` (danh sách, accept, reject) mở cho người chưa đăng nhập | Attendance |
| H6 | 🟠 Cao | Ai đã đăng nhập cũng đọc được mọi báo cáo qua `GET /api/report/{id}` (IDOR) | Attendance |
| M1–M8 | 🟡 Trung bình | Secret bị commit, role CHECKER không tồn tại bên Attendance, quyền DB rộng, app không có kiosk mode... | Nhiều chỗ |

Ba việc nên sửa ngay, xếp theo thứ tự: **(1)** khoá `/api/face-id/**` và `/uploads/**`, **(2)** giới hạn số lần nhập
OTP, **(3)** bỏ tài khoản cứng khỏi APK và đổi mật khẩu `Admin@123` ở mọi tài khoản.

---

## 🔴 Nghiêm trọng

### C1. Chấm công thủ công không cần xác thực

- **Ở đâu:** `Attendance/src/main/java/com/attendenceSystem/security/SecurityConfig.java:70-71` (`/api/face-id/**` được `permitAll`);
  `.../faceid/filter/FaceIdApiKeyFilter.java:44` (chỉ gọi `filterChain.doFilter`, không kiểm tra gì);
  `.../faceid/api/FaceIdApiController.java` (`POST /attendance/manual`);
  `.../faceid/service/impl/FaceIdAttendanceServiceImpl.java` `processManualAttendance`: chỉ tra `findByUsername(employeeCode)` rồi check-in/check-out.
- **Khai thác:** `curl -X POST :9090/api/face-id/attendance/manual -H 'Content-Type: application/json' -d '{"employeeCode":"nv01","capturedAt":"2026-10-22T08:00:00"}'`
  là nhân viên `nv01` được ghi đã vào ca. Gọi lần hai thì ghi tan ca. Không kiểm tra cả trạng thái tài khoản (user bị khoá vẫn chấm được).
- **Sửa:** Chỉ cho phép endpoint này với phiên đăng nhập role ADMIN/MANAGER (đúng mục đích "điểm danh khi mất AI"). Không để `permitAll`.
- **Trạng thái:** ✅ đã sửa trên nhánh `claude/fix-c1-manual-attendance` (tách từ `feature/mobile-gateway`):
  - `/attendance/manual` và `/employees` chỉ ADMIN/MANAGER gọi được;
  - nhân viên không ACTIVE bị từ chối;
  - log ghi lại `performed_by` (người thao tác);
  - kiosk yêu cầu quản lý đăng nhập và có nút khoá chế độ thủ công.

  Sau đó, khi sửa C2, kiosk web bị gỡ hẳn (chỉ dùng máy quét Android) nên API chấm công thủ công
  (`/attendance/manual`, `/employees`) đã bị **xoá**. Hệ quả: khi face-ai ngừng hoạt động, không còn cách chấm công nào.

### C2. Chấm công bằng ảnh, không kiểm tra người thật, không cần xác thực

- **Ở đâu:** cùng cấu hình `permitAll` như C1; `FaceIdAttendanceServiceImpl.processAttendance` (khoảng dòng 117–150 trên `main`) chỉ chạy
  pose-check, tạo embedding rồi so cosine với mẫu đã đăng ký. Trường `liveness` trong `FaceIdAttendanceRequest` **không được đọc**,
  và code cũng không gọi kiểm tra chống giả mạo (anti-spoof) của face-ai.
- **Khai thác:** gửi ảnh chụp mặt nạn nhân (lấy từ C3 hoặc từ mạng xã hội) kèm `studentCode` lấy từ H1. Cosine ≥ 0.55 là chấm công thành công.
  Bước kiểm tra người thật duy nhất của hệ thống nằm trong gateway (`FaceVerificationService.identifyOne`), tức là sai phía
  của ranh giới tin cậy: kẻ tấn công cứ gọi thẳng Attendance là bỏ qua được.
- **Sửa:** (a) Bật kiểm tra trong `FaceIdApiKeyFilter`: so `X-API-Key` với `face-id.api-key` bằng `MessageDigest.isEqual`, trả 401 nếu sai.
  Đồng thời trang kiosk web (`attendance-check.js`) phải đi qua đường có xác thực, hoặc bỏ hẳn trang này nếu máy quét Android đã thay thế nó.
  (b) Bên Attendance tự gọi kiểm tra người thật (hoặc `/recognize` của face-ai đã kèm liveness), không tin vào cờ do client gửi lên.
- **Trạng thái:** ✅ đã sửa trên nhánh `claude/fix-c1-manual-attendance` (hướng A1 + B):
  - **Gỡ kiosk web:** trang `/attendance/check` cùng JS/CSS đã bị gỡ.
  - **API key:** `POST /api/face-id/attendance` chỉ nhận request có `X-API-Key` đúng (`ROLE_FACE_GATEWAY`).
    Key bắt buộc, dài ≥ 32 ký tự, không còn giá trị mặc định.
  - **Kiểm tra người thật:** Attendance gọi face-ai `/recognize` với mẫu của chính nhân viên đó. Chỉ chấp nhận khi có
    đúng 1 khuôn mặt, `liveness_status = live`, kiểm tra người thật đang bật và model đã nạp (fail closed), và khớp mẫu.
    Trường `liveness` do client gửi bị bỏ.
  - **Phân quyền còn lại:** các API `/api/face-id/*` khác và trang `/face-id/create` yêu cầu đăng nhập.
  - **Chưa kiểm tra:** luồng đầy đủ máy quét Android → gateway → Attendance → face-ai.

### C3. Ảnh khuôn mặt đăng ký tải công khai được

- **Ở đâu:** `SecurityConfig.java:62-63` (`/uploads/**` được `permitAll`); `config/WebConfig.java:18` phục vụ thẳng thư mục `uploads/`;
  `FaceIdCaptureServiceImpl.saveBase64Image`: đường dẫn ảnh có dạng `/uploads/face_samples/{userId}/face_{userId}_{1..5}.jpg`.
  Ảnh bằng chứng chấm công (`AttendanceEvidenceService`) cũng nằm dưới `/uploads/`.
- **Khai thác:** lặp `userId` từ 1 đến N, `n` từ 1 đến 5 là tải về toàn bộ ảnh sinh trắc của nhân viên (dữ liệu cá nhân nhạy cảm theo NĐ 13/2023).
  Ghép với C2 là chấm công hộ được cho bất kỳ ai.
- **Sửa:** Bỏ `/uploads/**` khỏi `permitAll`. Ảnh mặt và ảnh bằng chứng phục vụ qua controller có kiểm tra quyền (chủ ảnh, MANAGER hoặc ADMIN).
  Đặt tên file ngẫu nhiên (UUID) thay vì theo `userId`.
- **Trạng thái:** ✅ đã sửa trên nhánh `claude/fix-c1-manual-attendance`.
  - **Bỏ truy cập công khai:** gỡ resource handler public (`WebConfig`) và `permitAll` của `/uploads/**`.
  - **Phục vụ qua controller:** `UploadController` phục vụ `/uploads/**` (URL giữ nguyên) sau khi đăng nhập, kiểm tra quyền theo loại file:
    - `face_samples/{userId}` và `attendance_evidence/{userId}`: chỉ chủ ảnh, MANAGER, ADMIN;
    - `reports/`: ai đã đăng nhập cũng xem được (xem H6);
    - thư mục khác: 404.
  - **Header trả về:** `Cache-Control: no-store`, `nosniff`; file không phải ảnh tải về dạng attachment.
  - **Không đổi tên file sang UUID:** đã có kiểm tra quyền nên đoán được tên file cũng không xem được ảnh của người khác.

### H6. Ai đã đăng nhập cũng đọc được mọi báo cáo (phát hiện khi sửa C3, chưa sửa)
- `ReportServiceImpl.getReportById` (`GET /api/report/{id}`) không kiểm tra người xem có phải chủ báo cáo, người được chia sẻ
  (`ReportShare`) hay quản lý không. Đổi `id` là đọc được báo cáo của người khác (IDOR). File đính kèm `reports/` vì vậy
  cũng chỉ yêu cầu đăng nhập.
- **Sửa:** chỉ trả báo cáo cho chủ báo cáo, người được chia sẻ, MANAGER/ADMIN. Sau đó áp đúng quy tắc này cho `uploads/reports/`
  (tra báo cáo có `attachment_files` chứa file đó).

### C4. Tài khoản máy quét gắn cứng trong APK

- **Ở đâu:** `DxLabAttendance/app/src/main/java/com/DxLab/dxlabattendance/data/AuthClient.kt:20-21`
  (`CHECKER_USERNAME = "checker01"`, `CHECKER_PASSWORD = "Admin@123"`). Thông tin này đã nằm trong lịch sử git.
  `app/build.gradle.kts:20` dùng `BASE_URL = http://...`, `isMinifyEnabled = false` (dòng 26).
  `Attendance/docs/ONBOARDING.md:259` ghi admin dùng cùng mật khẩu `Admin@123`.
- **Khai thác:** chạy `strings` hoặc jadx trên APK là có ngay mật khẩu. Với token CHECKER, kẻ tấn công:
  (1) gọi `/api/v1/attendance/check` bằng selfie thật từ nhà: chấm công không cần có mặt ở công ty
  (gateway đã bỏ kiểm tra GPS vì "máy đặt cố định");
  (2) gọi `/api/v1/face/identify` để tra họ tên bất kỳ ai từ một tấm ảnh;
  (3) thử luôn mật khẩu này cho `admin` trên web.
  Ngoài ra ai sniff được LAN cũng bắt được mật khẩu này (xem H2).
- **Sửa:** Bỏ mật khẩu khỏi APK. Cấp cho từng máy một **mã kích hoạt dùng 1 lần** (ADMIN tạo, nhập 1 lần khi lắp máy, đổi lấy refresh token)
  hoặc dùng client_credentials riêng cho từng thiết bị. Lưu refresh token mã hoá bằng khoá Android Keystore.
  Ràng buộc token với thiết bị (ví dụ mTLS hoặc Play Integrity) nếu cần chống giả máy quét.
  Đổi mật khẩu `admin`, `checker01` và các tài khoản demo **ngay**, không dùng chung mật khẩu.
- **Trạng thái:** ✅ phần code đã sửa trên nhánh `claude/fix-c4-device-activation` của repo `DxLabAttendance`.
  - **Bỏ tài khoản gắn cứng:** APK không còn tài khoản máy quét (đã kiểm tra dex của bản build).
  - **Màn kích hoạt:** lần đầu chạy, hoặc khi server thu hồi phiên, app hiện màn kích hoạt. Quản trị viên nhập tài khoản
    CHECKER một lần; app không lưu mật khẩu mà chỉ giữ refresh token và tự xoay token. Phiên hết hạn hoặc bị thu hồi
    thì app quay lại màn kích hoạt, không tự đăng nhập lại.
  - **Bản đơn giản:** chưa có mã kích hoạt dùng 1 lần cho từng máy, chưa mã hoá token bằng Keystore, chưa ràng buộc token với thiết bị.
  - **Còn phải làm tay (vận hành):** `Admin@123` vẫn nằm trong lịch sử git của `DxLabAttendance` (commit `170d04f`) và trong ONBOARDING.
    - Đổi mật khẩu `checker01` và `admin`.
    - Đổi mật khẩu **không** thu hồi refresh token đã cấp cho app. Phải chạy thêm
      `DELETE FROM mobile_auth.mobile_refresh_token WHERE username = 'checker01'`, hoặc đặt lại mật khẩu qua
      `/api/v1/auth/password/reset` của gateway (hàm này tự gọi `revokeAll`), rồi kích hoạt lại các máy quét.

### C5. OTP quên mật khẩu trên web dò được

- **Ở đâu:** `Attendance/.../user/controller/AuthController.java:217` gọi `authService.verifyOtp`, dẫn tới `OtpServiceImpl.verify`.
  Hàm này so mã (`OtpServiceImpl.java:74`), nhập sai thì chỉ ném lỗi: không đếm số lần, không vô hiệu mã.
  Trong module user không có giới hạn số lần nhập nào. CSRF bị tắt.
- **Khai thác:** gửi OTP quên mật khẩu tới email nạn nhân, rồi bắn `POST /auth/verify-otp` với 000000–999999 trong 5 phút.
  Với vài trăm request/giây, mỗi vòng có khoảng 10–30% cơ hội trúng. Mỗi 90 giây xin mã mới, lặp vài vòng là gần như chắc chắn
  trúng, sau đó đặt mật khẩu mới qua `otpVerified` trong session, tức chiếm được tài khoản (kể cả ADMIN).
  Gateway mobile giới hạn 5 lần (`MobileAuthController.MAX_OTP_FAILURES`), nhưng đường web không có giới hạn nào nên dùng đường web là bỏ qua được.
- **Sửa:** Đếm số lần sai **trên bản ghi OTP** trong `OtpServiceImpl.verify` (thêm cột `failed_attempts`, sai 5 lần thì `used = true`).
  Như vậy mọi đường gọi (web, `/api/otp/verify`, gateway) đều được bảo vệ ở cùng một chỗ. So mã bằng `MessageDigest.isEqual`.
- **Trạng thái:** ✅ đã sửa trên nhánh `claude/fix-c1-manual-attendance`.
  - **Giới hạn lượt nhập:** bảng `otp` có thêm cột `attempts` (mặc định 0). `OtpServiceImpl.verify` ghi nhận lượt nhập **trước** khi so mã,
    bằng một câu `UPDATE ... WHERE attempts < 5` nguyên tử chạy trong transaction riêng (REQUIRES_NEW), nên lượt đếm vẫn được lưu
    dù transaction của form web bị rollback. Hết 5 lượt thì mã đó bị từ chối, kể cả khi nhập đúng; phải xin mã mới (cooldown 90 giây).
  - **So mã:** dùng `MessageDigest.isEqual`, không lộ thông tin qua thời gian so sánh.
  - **Kiểm tra trên app thật:** qua `/api/otp/verify`, 5 lần sai rồi nhập mã đúng vẫn bị từ chối. Qua form web, lượt sai vẫn được đếm,
    và nhập đúng ngay sau 1 lần sai thì vẫn đổi được mật khẩu.
  - **Chưa làm:** giới hạn theo IP hoặc tài khoản cho việc gửi OTP (H4). Mỗi vòng 90 giây kẻ tấn công vẫn thử được 5 lần,
    khoảng 1/200.000 cơ hội trúng mỗi vòng.

---

## 🟠 Cao

### H1. Danh bạ nhân viên và tra cứu mặt 1:N mở công khai
- `GET /api/face-id/employees` → `listEmployeesForManualAttendance()` dùng `userRepository.findAll()`: trả **mọi** user, kể cả ADMIN và tài khoản bị khoá
  (code đã có `TODO` ghi nhận điều này). Username lấy được ở đây chính là `studentCode` dùng cho C1/C2.
- `POST /api/face-id/identify`: gửi một ảnh là nhận về họ tên và mã nhân viên. Đây là công cụ nhận diện người từ ảnh, cho bất kỳ ai dùng.
- **Sửa:** cùng cách khoá như C1. Danh bạ chỉ trả user `ACTIVE` có role nhân viên.
- **Trạng thái:** ✅ cả hai endpoint đã bị xoá cùng kiosk web (C2). Gateway vẫn có `/api/v1/face/identify` riêng, yêu cầu token CHECKER.

### H5. Trang duyệt khuôn mặt `/face-id` mở công khai (phát hiện khi sửa C2)
- `FaceProfileController` (`/face-id`, `/face-id/accept/{id}`, `/face-id/reject/{id}`) nằm trong `permitAll` của `/face-id/**`.
  Người chưa đăng nhập xem được danh sách hồ sơ khuôn mặt, và duyệt hoặc từ chối khuôn mặt của bất kỳ ai.
- **Trạng thái:** ✅ đã sửa cùng C2: danh sách, accept và reject chỉ ADMIN/MANAGER được dùng; `/face-id/create` yêu cầu đăng nhập.
- **Chấm công chỉ với khuôn mặt đã duyệt:** ✅ Attendance từ chối nếu `FaceProfile.isAccept != true` (chờ duyệt hoặc bị từ chối),
  kiểm tra trước khi gọi face-ai. Mỗi lần nhân viên cập nhật ảnh, hồ sơ quay về chờ duyệt và phải được duyệt lại.
  Gateway (`mobileBackend/.../FaceTemplateRepository`) vẫn nạp cả mặt chưa duyệt vào nhận diện 1:N. Việc này không gây ghi công sai,
  vì Attendance chặn, nhưng màn hình máy quét vẫn hiện tên người chưa được duyệt. Có thể thêm `AND p.is_accept = true` vào SQL bên gateway.
- **`POST /face-id/create` nhận `userId` từ client:** ✅ chỉ ADMIN/MANAGER gọi được.
  Nhân viên tự đăng ký qua `/api/face-id/register`, luôn gắn với chính họ.

### H2. Không có TLS trên đường mobile
- `DxLabAttendance/app/src/main/res/xml/network_security_config.xml:4-6` cho phép cleartext tới `192.168.137.1` (dải mạng hotspot/ICS của Windows).
  `BASE_URL` là `http://192.168.137.1:8081`.
- Trên đường này đi qua: mật khẩu CHECKER, refresh token 30 ngày, access token và ảnh mặt nhân viên. Bất kỳ máy nào trong cùng Wi-Fi đều đọc hoặc sửa được.
  Comment trong compose ghi "Public traffic must come through a TLS reverse proxy", nhưng app lại trỏ thẳng vào cổng HTTP.
- **Sửa:** đặt reverse proxy TLS (Caddy hoặc nginx) trước gateway, đổi `BASE_URL` sang `https://`, xoá `domain-config` cleartext.
  Nếu dùng CA nội bộ thì khai báo trong `network_security_config` và pin CA.

### H3. CSRF bị tắt trên app dùng cookie session
- `Attendance/.../security/SecurityConfig.java:50` (`csrf.disable()`), CORS `allowCredentials=true`.
  Các form của ADMIN (đổi role, khoá tài khoản, cấu hình email...) chỉ còn dựa vào mặc định SameSite=Lax của trình duyệt.
  Safari cũ hoặc request top-level GET vẫn có thể bị lợi dụng.
- **Sửa:** bật lại CSRF cho các route web (Thymeleaf đã tự chèn token vào form), chỉ bỏ qua cho `/api/face-id/**` sau khi đã có API key (C2).

### H4. API OTP của Attendance mở công khai
- `SecurityConfig.java:65-66` cho phép công khai `/api/otp/**`. `/api/otp/send` nhận `destination` tuỳ ý: dùng SMTP của công ty gửi mail tới
  bất kỳ địa chỉ nào, cooldown 90 giây chỉ tính theo từng địa chỉ. Gửi lại liên tục cho email nạn nhân còn làm mất hiệu lực OTP thật
  của họ (DoS quên mật khẩu). `/api/otp/verify` cho phép thử mã không giới hạn (C5).
- Gateway là nơi duy nhất cần gọi 2 endpoint này.
- **Sửa:** chỉ cho phép khi có `X-API-Key` của gateway, bỏ `permitAll`.

---

## 🟡 Trung bình

| # | Vấn đề | Ở đâu | Sửa |
|---|---|---|---|
| M1 | `face-ai/.env` vẫn được git theo dõi, chứa `INTERNAL_API_KEY` (bằng đúng key cũ từng hard-code trong `application.properties`), `SECRET_KEY`, `DEFAULT_ADMIN_PASSWORD`. Bản local đã đổi key nhưng chưa commit. Key cũ vẫn nằm trong lịch sử git. ONBOARDING §13 đã ghi việc này nhưng **chưa làm** | `Attendance/face-ai/.env` | `git rm --cached face-ai/.env`, coi mọi giá trị cũ là đã lộ và đổi hết |
| M2 | `face-ai/data/face_lab.db` (SQLite của app face-ai cũ) được commit | `Attendance/face-ai/data/` | Xoá khỏi repo, nếu có dữ liệu thật thì xoá cả khỏi lịch sử git |
| M3 | Gateway đòi role `CHECKER = 4` (`AttendanceUserDetailsService.java:23`) nhưng enum `Role` của Attendance chỉ có ADMIN/MANAGER/EMPLOYEE. ADMIN không tạo được CHECKER qua giao diện, phải sửa DB tay, và `Role.fromValue(4)` sẽ ném lỗi nếu user đó vào web | `Attendance/.../user/entity/enums/Role.java` | Thêm `CHECKER(4)` vào enum, chặn CHECKER đăng nhập web |
| M4 | Role DB `mobile_gateway` có `UPDATE (password)` trên **toàn bộ** bảng `users`. Gateway chỉ cần đổi mật khẩu cho CHECKER, nhưng nếu gateway có lỗi SQL hoặc logic thì đổi được mật khẩu ADMIN | `mobileBackend/src/main/resources/schema-mobile-auth.sql:7` | Dùng hàm `SECURITY DEFINER` chỉ cho đổi mật khẩu của user có `role = 4`, hoặc gọi API của Attendance |
| M5 | Đặt lại mật khẩu từ gateway ghi thẳng vào DB: bỏ qua chính sách mật khẩu và audit log của Attendance, không huỷ session web | `MobileAuthController.resetPassword` | Gọi qua API của Attendance |
| M6 | App không chạy kiosk mode: chỉ chặn nút Back, bấm Home hoặc Recents là thoát ra hệ điều hành, vào được Settings và gỡ app | `DxLabAttendance/.../MainActivity.kt:28` | Device Owner + `startLockTask()`, tắt status bar |
| M7 | Bản release để `isMinifyEnabled = false`, đọc ngược APK rất dễ | `app/build.gradle.kts:26` | Bật R8. Nhưng **không thay thế** việc sửa C4 |
| M8 | Luồng OAuth2 PKCE (`/oauth2/authorize` + form `/login`) và scope `face.embed` (trả embedding thô) vẫn bật dù app không dùng. Redirect URI dùng custom scheme `com.attendance://` nên app khác có thể chiếm (PKCE giảm thiểu được phần này) | `mobileBackend/.../config/AuthServerConfig.java:82-92`, `SecurityConfig.java:25-37,70-77` | Gỡ luồng không dùng để thu hẹp bề mặt tấn công |

## 🔵 Thấp và ghi chú

- `JwtDecoder` chỉ kiểm tra `iss`, không kiểm tra `aud` (`AuthServerConfig.java:133-136`). Hiện chỉ có 1 issuer nên chưa khai thác được.
- `server.forward-headers-strategy=framework`: nếu gateway bị public mà không có proxy đứng trước, client tự đặt được `X-Forwarded-*`.
- Khoá tài khoản sau 10 lần sai (`LoginAttemptTracker`) cho phép người ngoài cố tình khoá `checker01`. Mã 423 cũng tiết lộ username đó có tồn tại.
- `/face/identify` của gateway trả họ tên cả khi mặt **không** qua kiểm tra người thật: giơ ảnh trước máy quét là biết tên.
- `AttendanceClient.check` luôn gửi `"liveness": true` và `cameraId: "checker"`. Hai giá trị này vô nghĩa vì Attendance không đọc (xem C2).
- Lỗi chức năng phát hiện khi rà soát: `LOGIN_OTP_ENABLED=true` cần nhánh `feature/mobile-gateway` (`OtpPurpose.LOGIN`). Chạy với `main` thì trả 400.

## Đã làm tốt (giữ nguyên)

- Gateway: refresh token chỉ lưu SHA-256, dùng 1 lần, xoá theo cách nguyên tử. Tra lại user ACTIVE và CHECKER mỗi lần refresh.
  Đếm lượt OTP trước khi verify. `/forgot` luôn trả 202. Kiểm tra magic byte và kích thước ảnh. Ngưỡng nhận diện do server quyết định.
  Có semaphore chặn quá tải face-ai. Thông báo lỗi không lộ stacktrace. Ép secret dài ≥ 32 ký tự.
- face-ai (nhánh `feature/mobile-gateway`): không publish cổng 8002, so key bằng `hmac.compare_digest`, giới hạn kích thước payload.
- App: refresh token được loại khỏi backup và device-transfer. Log và ảnh debug chỉ có ở bản debug. Tự lùi 2 phút khi bị từ chối đăng nhập để không tự khoá tài khoản.
- Secret của `mobileBackend` (`.env`, `secrets/*.p12`) đã nằm trong `.gitignore`.

## Rủi ro đã được ghi nhận và chấp nhận (comment `ponytail:` trong code)

Đây là đánh đổi có chủ đích, đã ghi rõ hướng nâng cấp. Không phải lỗ hổng mới, nhưng cần xem lại khi triển khai thật:

- Rate limit, bộ đếm đăng nhập sai và phiên OTP lưu trong bộ nhớ, chạy 1 instance (`ApiGuardFilter`, `LoginAttemptTracker`, `MobileAuthController`).
- Chưa giới hạn theo IP cho `/api/v1/auth/**`. Cần đặt ở reverse proxy.
- Chưa phát hiện refresh token bị dùng lại (reuse detection theo family).
- Refresh token lưu dạng thường trong SharedPreferences của máy quét.

## Thứ tự đề xuất

1. **Hôm nay:** đổi mật khẩu `admin`, `checker01` và tài khoản demo. Đảm bảo cổng 9090 và 8081 không public ra Internet.
2. **C1 + C2 + H1 + H4:** bật `FaceIdApiKeyFilter`, bỏ `permitAll` cho `/api/face-id/**` và `/api/otp/**`,
   chuyển `/attendance/manual` sang yêu cầu ADMIN/MANAGER, bên Attendance tự kiểm tra người thật.
3. **C3:** khoá `/uploads/**`.
4. **C5:** đếm số lần sai trong `OtpServiceImpl.verify`.
5. **C4 + H2:** bỏ tài khoản cứng khỏi app, chuyển sang kích hoạt thiết bị, dùng TLS.
6. Phần M còn lại.
