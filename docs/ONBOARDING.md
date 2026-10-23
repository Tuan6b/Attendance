# Tài liệu tiếp nhận dự án — Hệ thống điểm danh bằng nhận diện khuôn mặt

> Viết ngày 2026-09-10 khi tiếp nhận bàn giao. Mục tiêu: hiểu hệ thống chạy thế nào, cấu
> trúc ra sao, deploy kiểu gì. Repo: `github.com/minhhai266/Attendance`, nhánh `main`.

---

## 1. Hệ thống gồm những gì

Có **2 service tách rời**, chạy độc lập:

| Service | Thư mục | Ngôn ngữ | Port | DB | Vai trò |
|---|---|---|---|---|---|
| **Backend chính** | gốc repo (`src/`) | Java 21 / Spring Boot 4.0.6 | **9090** | PostgreSQL | Toàn bộ nghiệp vụ: tài khoản, điểm danh, đơn nghỉ, báo cáo, lịch làm, dashboard, giao diện web |
| **face-ai** | `face-ai/` | Python 3.11 / FastAPI | **8002** | SQLite (riêng) | Chỉ làm 3 việc cho backend: phát hiện khuôn mặt, kiểm tra tư thế, sinh vector đặc trưng (embedding) |

**Backend gọi face-ai qua HTTP nội bộ.** Người dùng cuối chỉ tương tác với backend (port 9090).
face-ai không có giao diện — nó là API thuần.

```
   Trình duyệt nhân viên  ─┐
   Kiosk điểm danh (webcam)─┤──HTTP──►  Backend Spring  ──JDBC──►  PostgreSQL :5432
   (Flutter web? – xem §11)─┘            :9090            │
                                                          └──HTTP (X-API-Key)──►  face-ai :8002
                                                                                    │
                                                                          InsightFace buffalo_l
                                                                          (ONNX, chạy CPU)
                                                                          SQLite face_lab.db (hầu như không dùng)
```

### face-ai vốn là một sản phẩm khác

`face-ai/` nguyên bản là **"Face Lab System"** — một hệ thống quản lý ra vào phòng lab cho
sinh viên, hoàn chỉnh với bảng `students`, `access_logs`, `attendance_records`, `alerts`,
`user_sessions`... trong SQLite riêng. Khi ghép vào dự án này, **chỉ dùng lại đúng phần
sinh embedding**. Toàn bộ schema SQLite, các router `/api/students`, `/api/dashboard`,
`/api/auth`... và endpoint `/api/internal/face/recognize` **không được backend gọi tới** —
coi như code chết, đừng mất thời gian vào đó.

Hệ quả về đặt tên gây nhầm: trong module `faceid` của backend, các DTO dùng chữ
`studentCode` / `student` — thực chất đó là `users.username` bên PostgreSQL. Di sản đặt tên
từ Face Lab System.

---

## 2. Công nghệ

**Backend (`pom.xml`):**
- Java 21, Spring Boot 4.0.6, Maven (dùng `./mvnw`)
- Web MVC + **Thymeleaf** (view ở `src/main/resources/templates/cms/*.html`) — có kèm cả
  starter JSP/JSTL nhưng thực tế toàn bộ view là Thymeleaf
- **Spring Security** — đăng nhập bằng form, phiên (session cookie `JSESSIONID`), không JWT
- **Spring Data JPA** + PostgreSQL (`ddl-auto=update` — Hibernate tự tạo/sửa bảng)
- Spring Mail (gửi OTP), Caffeine (cache embedding + chống double-submit), Spring DevTools
- OAuth2 client (Google login) — có dependency nhưng **đang tắt** (comment trong `application.properties`)
- Còn 2 driver `mysql-connector-j` và `mariadb-java-client` trong pom nhưng không dùng — đang chạy Postgres

**face-ai (`face-ai/requirements.txt`):**
- FastAPI + Uvicorn, `insightface==0.7.3`, `onnxruntime==1.19.2` (CPU), OpenCV, NumPy, Pillow
- Model nhận diện: **`buffalo_l`** của InsightFace (`w600k_r50`, vector 512 chiều). Tự tải
  ~326 MB về `~/.insightface/models/` ở lần chạy đầu.
- Model chống giả mạo (anti-spoofing): `models/anti_spoofing/best_model_quantized.onnx`
  (**xem §7 — đang KHÔNG nằm trên luồng tích hợp**)

---

## 3. Cấu trúc mã nguồn (backend)

Kiến trúc module hoá, mỗi module tự đủ (controller / api / service / repository / entity / dto / mapper).

```
src/main/java/com/attendenceSystem/
├── AttendenceSystemApplication.java   # main
├── config/          # SystemConfig (nạp system_settings), SystemScheduler (cron), TimeConfiguration (ZoneId), WebConfig (/uploads/**), JacksonConfig
├── security/        # SecurityConfig, CustomUserDetailsService, ActiveUserFilter, CorsConfig, FirewallConfig, ForceChangePasswordInterceptor
├── constant/        # Routes.java (toàn bộ đường dẫn), Views.java (tên template)
├── exception/       # GlobalExceptionHandler
└── module/
    ├── user/        (48 file)  # tài khoản, đăng ký/đăng nhập, OTP quên mật khẩu, phân quyền, quản lý nhân viên
    ├── attendance/  (60 file)  # check-in/out, tính đi muộn/về sớm, đơn xin nghỉ (leave), lịch tự động
    ├── faceid/      (41 file)  # đăng ký khuôn mặt, điểm danh bằng khuôn mặt, client gọi face-ai
    ├── report/      (20 file)  # nhân viên nộp báo cáo, quản lý duyệt, chia sẻ báo cáo
    ├── system/      (20 file)  # cấu hình hệ thống (email, giờ làm, cron) — chỉ ADMIN
    ├── schedule/    (19 file)  # WorkSchedule: ngày làm việc trong tuần, giờ ca
    ├── otp/         (18 file)  # module hỗ trợ: sinh/gửi/xác minh OTP qua email
    ├── dashboard/   (11 file)  # số liệu tổng hợp theo từng vai trò
    ├── log/         (12 file)  # audit_logs — nhật ký thao tác
    └── storage/     (5 file)   # lưu file ảnh lên đĩa (thư mục uploads/)
```

Quy ước: `*Controller` = trả về trang HTML (Thymeleaf); `*ApiController` (dưới `/api/...`) =
trả JSON cho JavaScript gọi bằng `fetch`. `constant/Routes.java` là nơi tra cứu mọi URL.

**face-ai:**
```
face-ai/
├── run.py                    # điểm vào: uvicorn app.main:app, đọc PORT/HOST/RELOAD từ env
├── app/main.py               # khai báo FastAPI, mount router, middleware bảo mật + CSRF
├── app/core/config.py        # Settings (pydantic) đọc từ .env
├── app/core/csrf.py          # middleware CSRF (nguồn gây lỗi 403 khi thử /docs — xem §9)
├── app/routers/internal_face.py   # << PHẦN DUY NHẤT BACKEND DÙNG >> /api/internal/face/{embed,embed-batch,pose-check,recognize}
├── app/routers/*.py          # auth, students, attendance, dashboard... (Face Lab System cũ, không dùng)
├── app/services/face_service.py       # bọc InsightFace
├── app/services/liveness_service.py   # anti-spoofing (xem §7)
└── app/services/*(1).py      # FILE TRÙNG do tải về — không được import, nên diff rồi xoá
```

---

## 4. Luồng nghiệp vụ — Điểm danh tại cửa (quan trọng nhất)

> **Cập nhật 2026-10-23 (audit C2):** kiosk web (`/attendance/check`, `/identify`, chấm thủ công) **đã bị gỡ**.
> Điểm danh khuôn mặt giờ chỉ qua **máy quét Android → mobile gateway (`mobileBackend`) →
> `POST /api/face-id/attendance` kèm `X-API-Key`**. Attendance gọi face-ai `/recognize` (khớp mẫu
> của chính nhân viên + kiểm tra người thật, fail closed). Mục (a), (b) dưới đây và §7, §9.3–9.5
> mô tả code cũ — xem `docs/SECURITY-AUDIT.md` (C1, C2, H1, H5).

Trang kiosk: `GET /attendance/check` (`templates/cms/attendance/attendance-check.html` +
`static/js/attendance/attendance-check.js`). **Không cần đăng nhập** (permitAll)... nhưng
xem cảnh báo ở §9.

Có **3 cách điểm danh**:

### (a) Bằng khuôn mặt — luồng chính
1. Bật webcam. JS **liên tục** chụp frame gửi `POST /api/face-id/identify` (nhận diện 1:N)
   — so ảnh với **toàn bộ** embedding đã đăng ký trong cache (`FaceEmbeddingCacheService`),
   hiện tên người khớp lên màn hình.
2. Người dùng bấm **"Quét điểm danh"** → `POST /api/face-id/attendance` với `studentCode`
   (= username người vừa nhận ra) + ảnh + `trackingId` (UUID chống double-submit).
3. Backend xử lý (`FaceIdAttendanceServiceImpl.processAttendance`):
   - gọi face-ai `pose-check` (mặt có nhìn thẳng, đủ to không)
   - gọi face-ai `embed` (sinh vector từ ảnh vừa chụp)
   - **so cosine similarity ngay trong backend** giữa vector đó và các `face_samples` đã lưu
     của đúng user đó → lấy điểm cao nhất
   - nếu `< 0.55` (`face-id.confidence-threshold`) → từ chối
   - nếu đạt → xét bản ghi `attendance_records` hôm nay:
     - chưa có → **CHECK-IN** (tính đi muộn nếu quá giờ)
     - có, chưa check-out → **CHECK-OUT** (tính về sớm)
     - đã check-out → bỏ qua
   - lưu ảnh bằng chứng vào `uploads/`, ghi `face_id_recognition_logs`

### (b) Thủ công — khi face-ai chết
Nếu mất kết nối AI, JS chuyển sang panel offline: chọn nhân viên từ danh sách
(`GET /api/face-id/employees`) rồi `POST /api/face-id/attendance/manual` — **không kiểm tra
khuôn mặt gì cả**, chỉ chụp ảnh lưu lại. Cùng logic check-in/out như trên.

### (c) Nhân viên tự bấm nút (đã đăng nhập)
`POST /attendance/check-in` và `/check-out` từ trang có đăng nhập — không liên quan khuôn mặt.

> **Điểm mấu chốt:** phép so khớp khuôn mặt (cosine) **nằm ở backend Java**, không phải
> face-ai. face-ai chỉ *phát hiện + kiểm tư thế + sinh vector*. Endpoint `/recognize` của
> face-ai không được dùng.

---

## 5. Luồng nghiệp vụ — Đăng ký khuôn mặt

1. Nhân viên đăng nhập → `GET /face-id/create` (`faceID-create.html` + `faceid-create.js`).
2. Webcam hướng dẫn chụp nhiều góc (thẳng/trái/phải/lên/xuống) — mỗi lần chụp gọi
   `pose-check` để xác nhận đúng tư thế.
3. Bấm lưu → `POST /api/face-id/register` gửi **tất cả** ảnh mẫu.
4. Backend gọi face-ai `embed-batch` → nhận về danh sách embedding → lưu:
   - 1 dòng `face_profiles` (mỗi user 1 profile, `is_accept = null` = **chờ duyệt**)
   - N dòng `face_samples` (ảnh + embedding base64)
5. ADMIN vào `GET /face-id` (danh sách) → **Accept / Reject** từng profile
   (`POST /face-id/accept/{id}` / `/reject/{id}`).
6. Khi accept, embedding vào cache để dùng cho nhận diện 1:N ở kiosk.

Ảnh mẫu lưu tại `uploads/face_samples/{userId}/face_{userId}_{order}.jpg`, phục vụ qua
`/uploads/**` (`WebConfig`).

---

## 6. Các nghiệp vụ khác (tóm tắt)

| Nghiệp vụ | Đường dẫn | Ai dùng | Ghi chú |
|---|---|---|---|
| Đăng ký tài khoản | `/auth/register` | công khai | Tạo user `EMPLOYEE` / `PENDING` |
| Đăng nhập | `/auth/login` (form POST) | công khai | CSRF đã tắt ở backend |
| Quên mật khẩu | `/auth/forgot-password` → OTP email → `/auth/change-password` | công khai | **Cần cấu hình email**, xem §8 |
| Duyệt tài khoản chờ | `/admin/...` | ADMIN | Đổi `status` PENDING→ACTIVE, set role |
| Đơn xin nghỉ phép | `/attendance/leave/...` | EMPLOYEE gửi, MANAGER/ADMIN duyệt | `leave_requests`; có bug tự động, xem `README.md` |
| Báo cáo công việc | `/report/...` | EMPLOYEE nộp, cấp trên duyệt | `reports`, `report_shares` |
| Lịch làm việc | `/schedule/...` | ADMIN | `work_schedules` — ngày làm trong tuần, giờ ca (id=1 mặc định) |
| Cấu hình hệ thống | `/system/...` | ADMIN | email, giờ làm, biểu thức cron cho automation |
| Dashboard | `/dashboard/{admin\|manager\|employee}` | theo vai trò | |

### Automation (`config/SystemScheduler.java`)
3 job chạy theo cron (sửa được ở trang `/system`, mặc định trong ngoặc):

| Job | Cron mặc định | Việc |
|---|---|---|
| `autoMarkAbsent` | `0 0 10 * * *` (10:00) | Đánh vắng người chưa check-in |
| `autoHandleMissingCheckOut` | `0 50 23 * * *` (23:50) | Xử lý người quên check-out |
| `autoRejectExpiredLeaveRequests` | `0 0 22 * * *` (22:00) | Tự từ chối đơn nghỉ quá hạn |

Timezone toàn hệ thống: `Asia/Ho_Chi_Minh` (`app.timezone`).

---

## 7. Cảnh báo: chống giả mạo (anti-spoofing) đang bị vô hiệu trên luồng tích hợp

`face-ai` **có** model liveness và code dùng nó — nhưng chỉ trong các hàm của Face Lab System
cũ. Hai hàm mà backend thực sự gọi (`extract_single_embedding`, `analyze_single_face_pose`
trong `face_service.py`) **không đụng tới `liveness_service`**.

➡️ **Giơ ảnh in / màn hình điện thoại vào camera kiosk vẫn check-in được.** Đây là rủi ro
bảo mật cần biết ngay khi tiếp nhận. Muốn bật thật thì phải sửa `internal_face.py` /
`FaceIdAttendanceServiceImpl` để gọi qua nhánh có liveness.

---

## 8. Cấu hình

### Backend
`src/main/resources/application.properties` — đọc biến từ **file `.env` ở gốc repo**
(`spring.config.import=optional:file:.env[.properties]`) và biến môi trường.

`.env` (gốc repo — **git-ignore, không commit**):
```properties
DB_URL=jdbc:postgresql://localhost:5432/attendance
DB_USERNAME=postgres
DB_PASSWORD=password              # đã đổi từ 'annguyenthanh2k5' cho khớp docker-compose
```
Các khoá khác nằm sẵn trong `application.properties`:
- `server.port=${PORT:9090}` — đã đổi từ 8080 (8080 bị dùng cho việc khác)
- `face-ai.base-url=${FACE_AI_BASE_URL:http://127.0.0.1:8002}`
- `face-ai.api-key=doi-thanh-chuoi-bi-mat-cua-ban-123456` — phải khớp `INTERNAL_API_KEY` của face-ai
- `face-id.api-key` — key cho `/api/face-id/**` nhưng **bộ lọc đang tắt** (xem §9)
- `face-id.confidence-threshold=0.55`

### Cấu hình động trong DB (bảng `system_settings`, sửa ở trang `/system`)
Không nằm trong file config — ADMIN phải nhập qua giao diện:
- **Email gửi OTP**: `system_email` + `system_email_password` (Gmail thì dùng *App Password*).
  `DynamicMailService` đọc từ đây lúc chạy. **Chưa cấu hình thì quên mật khẩu / xác thực
  đăng ký sẽ lỗi.**
- Giờ ca làm (`start_work_time`, `end_work_time`, `max_checkin_time`, `min_checkout_time`)
- Biểu thức cron của 3 job automation
- `leave_blackout_minutes`

### face-ai
`face-ai/.env` (⚠️ **đang bị commit vào git** — xem §10):
```properties
INTERNAL_API_KEY=doi-thanh-chuoi-bi-mat-cua-ban-123456
DEFAULT_ADMIN_USERNAME=admin
DEFAULT_ADMIN_PASSWORD=admin123     # admin của SQLite face-ai, khác admin backend
PUBLIC_DOCS_ENABLED=true            # /docs mở
TRUSTED_HOSTS=*
LIVENESS_ENABLED=true               # (nhưng xem §7)
```
Có sẵn `face-ai/.env.example` làm mẫu.

---

## 9. Tài khoản đăng nhập

Dự án bàn giao **không kèm tài khoản nào**. Không tự đăng ký ra admin được (đăng ký luôn ra
`EMPLOYEE`/`PENDING`, mà duyệt PENDING lại cần admin sẵn có).

Đã tạo thủ công 1 admin trực tiếp trong PostgreSQL (2026-09-10):

| | |
|---|---|
| Username | `admin` |
| Password | `Admin@123` |
| Email | `admin@attendance.local` |

Đã test đăng nhập `/auth/login` → vào được `/dashboard/admin`.

**Cột enum trong DB là số nguyên** (qua `@Converter`, dùng giá trị `.value` tự định nghĩa
chứ không phải ordinal):
- `role`: ADMIN=1, MANAGER=2, EMPLOYEE=3
- `status`: ACTIVE=1, INACTIVE=2, PENDING=3
- `specialization`: UNASSIGNED=0, ...

Cài mới trên máy khác (DB trống) — chạy SQL này sau khi app khởi động lần đầu (đã tạo bảng):
```sql
INSERT INTO users (username, email, password, role, status, full_name,
                   specialization, must_change_password, created_at, updated_at)
VALUES ('admin', 'admin@attendance.local',
        '$2a$10$u6zvj1nUJuuzDBW4dRwSKeMwojtyxBLsJzA46m0.ymdDK.bB.kt2.',  -- BCrypt của 'Admin@123'
        1, 1, 'System Administrator', 0, false, now(), now());
```
> Nên cân nhắc thêm một `CommandLineRunner` tự seed admin khi bảng `users` rỗng — sạch hơn
> là dặn nhau chạy SQL.

---

## 10. Cách deploy

### Cách A — Chạy local để phát triển  ✅ (đã kiểm chứng chạy được)

**Yêu cầu:** JDK 21, Docker Desktop, Python 3.11, Git + Git LFS.

```bash
# 0. Lấy file lớn (model .onnx, DB mẫu) — .gitattributes dùng LFS
git lfs pull

# 1. Database (Postgres 16 trong Docker)
#    Mở Docker Desktop trước, rồi:
docker compose up -d postgres          # tạo container attendance-postgres, port 5432, db 'attendance'

# 2. File .env ở gốc repo (xem §8) — DB_PASSWORD=password

# 3. Backend: mở bằng IntelliJ, Run AttendenceSystemApplication
#    (hoặc: ./mvnw spring-boot:run) → http://localhost:9090
#    ddl-auto=update sẽ tự tạo 13 bảng. Sau đó chạy SQL tạo admin (§9).

# 4. face-ai
cd face-ai
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python run.py            # hoặc: start_face_ai.bat
#    Lần đầu tải buffalo_l ~326 MB về ~/.insightface — chờ vài phút.
#    → http://localhost:8002  (GET / trả JSON; /docs là Swagger)
```

Thứ tự khởi động hằng ngày: Docker Desktop → `docker compose start postgres` → Run backend
(IntelliJ) → `start_face_ai.bat`.

### Cách B — Docker Compose toàn bộ  ⚠️ (chưa kiểm chứng — build image backend chưa test)

`docker-compose.yml` định nghĩa 3 service: `postgres`, `face-ai`, `backend`.

```bash
git lfs pull
docker compose up -d          # build cả face-ai và backend rồi chạy
```
- `backend` build từ `Dockerfile` gốc (maven → jar), chạy port **9090**, tự trỏ
  `DB_URL=jdbc:postgresql://postgres:5432/attendance`, `FACE_AI_BASE_URL=http://face-ai:8002`
- `face-ai` build từ `face-ai/Dockerfile` (python:3.11-slim)

**Chưa test build image backend.** Rủi ro đã thấy:
- Service `backend` **không mount volume cho `uploads/`** → ảnh mẫu khuôn mặt + ảnh bằng
  chứng check-in **mất khi container tạo lại**. Cần thêm volume `./uploads:/app/uploads`.
- face-ai trong container sẽ tải lại buffalo_l 326 MB (volume `insightface-models` giữ lại
  cho lần sau).
- Mật khẩu Postgres trong compose là `password` — khớp `.env` đã sửa.

### Kiểm tra tích hợp hoạt động (đã chạy thử, không cần đăng nhập)
```bash
# backend → face-ai:
curl -X POST http://localhost:9090/api/face-id/pose-check \
     -H "Content-Type: application/json" -d '{"image_base64":"<base64 ảnh>"}'
# → {"ok":false,"message":"Không tìm thấy khuôn mặt.",...}  ← chuỗi này chứng tỏ backend gọi face-ai OK
```

---

## 11. Rủi ro & nợ kỹ thuật (đọc kỹ trước khi nhận)

### Bảo mật — ưu tiên xử lý
1. **`face-ai/.env` bị commit vào git** (từ commit `bb3c5c5`), chứa `SECRET_KEY` và
   `INTERNAL_API_KEY`. Repo ở `github.com/minhhai266/Attendance`.
   → `git rm --cached face-ai/.env`, xoay (rotate) cả 2 khoá, dùng `.env.example` làm mẫu.
2. **Ảnh mặt thật + DB có dữ liệu bị commit**: `face-ai/data/face_lab.db` (495 KB, có dữ
   liệu), và trong lịch sử có `face-ai/storage/private/faces/HE*.jpg`, `evidence/*.jpg` —
   ảnh khuôn mặt gắn mã sinh viên FPT. Cần dọn khỏi repo/lịch sử.
3. **`/api/face-id/**` mở toàn bộ**: `FaceIdApiKeyFilter` bị comment hết phần kiểm key, và
   `SecurityConfig` để `permitAll`. → **Bất kỳ ai trong mạng gọi được
   `POST /api/face-id/attendance/manual` để điểm danh hộ bất kỳ nhân viên nào**, và
   `GET /api/face-id/employees` **lộ danh sách toàn bộ tài khoản** (chính comment TODO trong
   `listEmployeesForManualAttendance` đã thừa nhận).
4. **Anti-spoofing bị bỏ qua** — xem §7. Ảnh in qua được.
5. `/api/face-id/latest` trả **500** khi trình duyệt kiosk chưa đăng nhập
   (`attendance-check.js` gọi nó lúc load). Trên thực tế **trang kiosk cần một phiên đăng
   nhập** trong trình duyệt đó, dù route là permitAll — cần làm rõ / sửa.

### Vận hành
6. **Git LFS bắt buộc**: `.db` và `.onnx` theo LFS. Clone mà không `git lfs pull` → file
   con trỏ, face-ai crash.
7. Email chưa cấu hình → luồng quên mật khẩu / xác thực OTP hỏng (§8).
8. `CorsConfig` mở cho `http://localhost:5000` với ghi chú *"frontend Flutter web"* — **có
   thể có một client Flutter riêng không nằm trong repo này**. Hỏi team cũ.
9. Thư mục rác `dev/null/` (4 file `post-checkout`, `post-commit`... — hậu quả `cp ... /dev/null`
   trên Windows). Xoá được.
10. `face-ai/app/services/*(1).py` — file trùng do tải về, không được import. `diff` với bản
    gốc rồi xoá.
11. Hai driver `mysql` + `mariadb` trong `pom.xml` không dùng.

### Bug đã biết (theo `README.md` / `TODO.md` của team cũ)
- Automation phần đơn nghỉ phép: **không chặn gửi đơn** (phần tự động reject thì OK).
- Luôn trả HTTP 500 cho mọi lỗi thay vì 400/401/403/404 phù hợp.
- `/attendance` của MANAGER cần sửa, thiếu API cho phần "chi tiết".
- Cần tách `ExceptionHandler`, tách `CustomException`, refactor module face-id.

---

## 12. Mô hình dữ liệu

### PostgreSQL (backend) — 13 bảng, Hibernate tự tạo
| Bảng | Nội dung |
|---|---|
| `users` | tài khoản; `role`/`status`/`specialization`/`department` là enum số |
| `attendance_records` | 1 dòng / user / ngày (unique); check-in/out time, ảnh, `status`, `check_status` (bitset), `version` (optimistic lock) |
| `leave_requests` | đơn xin nghỉ; `status` enum |
| `work_schedules` | lịch ca (id=1 mặc định): `working_days`, các mốc giờ |
| `system_settings` | 1 dòng (id=1): email, giờ làm, cron |
| `face_profiles` | 1 / user; `is_accept` (null=chờ, true/false); `face_code` |
| `face_samples` | N / profile; `embedding` (TEXT base64 float32), `image_path` |
| `face_id_recognition_logs` | nhật ký mỗi lần điểm danh khuôn mặt; có `request_payload`/`response_payload` JSON |
| `reports`, `report_shares` | báo cáo công việc + chia sẻ |
| `otp` | mã OTP (6 số, hết hạn 5') |
| `audit_logs` | nhật ký thao tác (`action`, `entity_type` là mã số qua converter) |
| `holidays` | ngày lễ |

### SQLite (`face-ai/data/face_lab.db`)
Schema của Face Lab System cũ (`students`, `student_faces`, `access_logs`,
`attendance_records`, `alerts`, `user_sessions`, `audit_logs`, `settings`...). **Backend
không đọc bảng nào ở đây.** Chỉ bảng `users` (admin `admin`/`admin123`) và `settings` được
face-ai tự dùng nội bộ. Có thể xoá file để reset — `init_db()` tạo lại.

---

## 13. Việc nên làm ngay khi tiếp nhận

- [ ] Đổi mật khẩu admin (`Admin@123`) sau khi đăng nhập
- [ ] `git rm --cached face-ai/.env`, xoay `SECRET_KEY` + `INTERNAL_API_KEY` + `face-ai.api-key`
- [ ] Dọn `face-ai/data/face_lab.db`, ảnh mặt trong `face-ai/storage/` khỏi repo/lịch sử
- [x] Bật lại `FaceIdApiKeyFilter` (hoặc siết `/api/face-id/**` trong `SecurityConfig`) — xong ở audit C2
- [ ] Cấu hình email trong trang `/system` để test luồng OTP
- [ ] Hỏi team cũ: (1) có frontend Flutter (port 5000) không? (2) môi trường production đang ở đâu?
- [ ] Quyết định: có cần anti-spoofing thật không → nếu có, nối `liveness_service` vào luồng
- [ ] Thêm `CommandLineRunner` seed admin; xoá `dev/null/`, file `*(1).py`
- [ ] Thêm volume `uploads/` vào service `backend` trong `docker-compose.yml`
```
