# Triển khai production — Attendance

Gói do `prod/package.sh` tạo (repo Attendance). Bản này dùng cho server `<host>`.

```
README.md          tài liệu này
VERSIONS.txt       commit của từng repo trong gói
CREDENTIALS.txt    mật khẩu admin ban đầu — lưu vào password manager rồi xoá khỏi server (chỉ có ở gói cài mới)
ca.crt             CA riêng của server (chỉ khi không dùng Let's Encrypt) — cài trên máy tính dùng web
src/               mã nguồn attendance (backend + face-ai) và mobileBackend (gateway)
deploy/            docker-compose.yml, Caddyfile, start.sh, backup.sh, init-db.sql, .env + *.env + secrets/ (SECRET)
apk/               app máy quét bản release, gọi gateway https://<host>:8443
images/            (nếu đóng gói với --images) image docker dựng sẵn
SHA256SUMS         kiểm tra toàn vẹn: sha256sum -c SHA256SUMS
```

## 1. Kiến trúc

```
Internet/LAN ──443──▶ caddy ──▶ backend:9090 (web Spring Boot) ──▶ postgres, face-ai
             ──8443─▶ caddy ──▶ mobile-backend:8080 (gateway app máy quét) ──▶ postgres, face-ai, backend
             ──80───▶ caddy (chuyển sang https, xác thực Let's Encrypt)
```
Chỉ Caddy mở cổng. Postgres, face-ai, backend, gateway không publish ra ngoài (audit H2).
Dữ liệu nằm trong volume docker của project `attendance-prod`: `postgres-data`, `face-ai-data`,
`uploads-data` (ảnh mẫu khuôn mặt, file báo cáo), `caddy-data` (chứng chỉ).

## 2. Đóng gói (máy dev)

Cần: Git Bash/Linux, git + Git LFS, openssl, JDK, Android SDK (repo app có `local.properties`), docker (khi `--images`).
Ba repo đặt cạnh nhau như hiện tại (`Attendance`, `mobileBackend`, `DxLabAttendance`), đã commit.

```bash
# Có tên miền trỏ về server → chứng chỉ Let's Encrypt
APK_KEYSTORE_PASSWORD=... prod/package.sh --host attendance.congty.vn --acme-email it@congty.vn \
    --tag v1.0.0 --images --apk-keystore ~/keys/dxlab-release.jks

# Chỉ chạy trong LAN, gọi bằng IP → CA riêng (APK nhúng sẵn CA, máy tính cài ca.crt)
APK_KEYSTORE_PASSWORD=... prod/package.sh --host 192.168.1.10 --tag v1.0.0 --images --apk-keystore ~/keys/dxlab-release.jks
```
Gói ra ở `../prod-dist/<tag>/` (cạnh repo). `--images` để server không phải build (build face-ai mất 10–20 phút, tải ~1 GB).

**Keystore ký APK:** phải dùng cùng một keystore cho mọi bản về sau (khác keystore thì máy đã cài không cập nhật được).
Chưa có thì tạo một lần và cất kỹ:
`keytool -genkeypair -alias dxlab -keyalg RSA -keysize 2048 -validity 10000 -keystore dxlab-release.jks`.
Không truyền `--apk-keystore` thì gói có APK chưa ký (không cài được); ký sau bằng
`apksigner sign --ks dxlab-release.jks --out app.apk apk/*-unsigned.apk` (apksigner trong `build-tools` của Android SDK).

## 3. Cài đặt lần đầu (server)

Yêu cầu server: Linux x86_64 (ARM64 như Oracle Ampere: chưa thử), Docker Engine + compose v2, RAM ≥ 4 GB, đĩa ≥ 15 GB. Mở cổng 80, 443, 8443
(tường lửa/NAT). Có tên miền: bản ghi DNS A của `<host>` trỏ về IP public của server trước khi khởi động.

```bash
scp -r prod-dist/v1.0.0 user@server:/opt/attendance-v1.0.0     # gói chứa secret: chỉ chép qua kênh mã hoá
ssh user@server
cd /opt/attendance-v1.0.0 && sha256sum -c --quiet SHA256SUMS
docker load -i images/attendance-images.tar && deploy/start.sh --no-build   # có images/
deploy/start.sh                                                              # không có images/ (build tại chỗ, cần Internet)
```

Sau khi `start.sh` báo xong:
1. Mở `https://<host>`, đăng nhập `admin` với mật khẩu trong `CREDENTIALS.txt`, **đổi mật khẩu ngay**
   (hệ thống chưa bắt buộc đổi). Lưu mật khẩu vào password manager, xoá `CREDENTIALS.txt`.
2. Cài đặt hệ thống → nhập email hệ thống + mật khẩu ứng dụng (Gmail: App Password). Email này gửi OTP đăng ký/đổi mật khẩu.
   SMTP khác Gmail: sửa `SMTP_HOST/PORT/STARTTLS` trong `deploy/.env` rồi `docker compose up -d backend` (trong `deploy/`).
3. Tạo tài khoản CHECKER cho máy quét, cài `apk/*.apk` lên máy quét, kích hoạt bằng tài khoản đó.
4. Không dùng Let's Encrypt: cài `ca.crt` vào "Trusted Root Certification Authorities" trên các máy tính dùng web
   (Windows: nhấp đúp ca.crt → Install Certificate → Local Machine). Máy quét không cần — APK đã tin CA này.
5. Đặt sao lưu hằng ngày (mục 5).

Kiểm tra nhanh: `cd deploy && docker compose ps` (5 service `running`), `docker compose logs -f backend`.

## 4. Cập nhật bản mới

Bản cập nhật **phải dùng lại secret của bản đang chạy** (mật khẩu DB lưu trong volume, APK đã cài tin CA cũ):

```bash
# máy dev: lấy deploy/ đang chạy về (hoặc dùng bản đã lưu khi cài), rồi đóng gói
scp -r user@server:/opt/attendance-v1.0.0/deploy ./deploy-v1.0.0
APK_KEYSTORE_PASSWORD=... prod/package.sh --secrets-from ./deploy-v1.0.0 --tag v1.1.0 --images --apk-keystore ~/keys/dxlab-release.jks
# server
cd /opt/attendance-v1.1.0 && docker load -i images/attendance-images.tar && deploy/start.sh --no-build
```
Project docker luôn là `attendance-prod` nên volume dữ liệu được giữ, chỉ container được thay. Nên `deploy/backup.sh` trước khi cập nhật.
Quay lại bản cũ: `cd /opt/attendance-v1.0.0/deploy && docker compose up -d` (nếu bản mới không đổi cấu trúc DB theo cách bản cũ không đọc được).
Bản mới thêm biến cấu hình face-ai: so `src/attendance/face-ai/.env.example` với `deploy/face-ai.env` và bổ sung.

## 5. Sao lưu, khôi phục

```bash
cd /opt/attendance-<tag>/deploy && ./backup.sh                 # → deploy/backups/<ngày-giờ>/
# cron hằng ngày 2h sáng, giữ 14 ngày:
# 0 2 * * * cd /opt/attendance-<tag>/deploy && ./backup.sh && find backups -mindepth 1 -maxdepth 1 -mtime +14 -exec rm -rf {} +
```
Sao chép `backups/` sang máy khác. Thư mục `deploy/` (secret) sao lưu riêng một lần, cất như mật khẩu.

Khôi phục (trong `deploy/`, B = thư mục bản sao lưu):
```bash
docker compose stop backend mobile-backend face-ai
docker compose exec -T postgres pg_restore -U postgres -d attendance --clean --if-exists < B/attendance.dump
for v in face-ai-data uploads-data; do
  docker run --rm -v attendance-prod_$v:/v -v "$(cd B && pwd)":/b postgres:16-alpine sh -c "rm -rf /v/* && tar xzf /b/$v.tgz -C /v"
done
docker compose up -d
```

## 6. Vận hành

| Việc | Lệnh (trong `deploy/`) |
|---|---|
| Trạng thái | `docker compose ps` |
| Log | `docker compose logs -f --tail 200 backend` (log tự xoay vòng, tối đa 100 MB/service) |
| Khởi động lại | `docker compose restart backend` |
| Dừng / chạy | `docker compose down` / `docker compose up -d` (không mất dữ liệu) |
| Vào DB | `docker compose exec postgres psql -U postgres -d attendance` |

**Không** chạy `docker compose down -v`: xoá toàn bộ dữ liệu.

## 7. Chưa có / lưu ý

- Giới hạn tần suất theo IP cho `/api/v1/auth/**` của gateway chưa đặt ở Caddy (SECURITY-AUDIT, mục đánh đổi).
  Backend và gateway đã tự khoá theo tài khoản.
- Chế độ CA riêng: CA hạn 10 năm; mất `deploy/secrets/ca/` thì phải cài lại ca.crt mới và APK mới trên mọi máy.
- Chuyển từ IP sang tên miền về sau: trong bản sao `deploy/` của bản đang chạy sửa `PUBLIC_HOST`, `TLS_MODE=<email>`
  (`.env`) và `OAUTH2_ISSUER=https://<tên miền>:8443` (`mobile-backend.env`), rồi đóng gói `--secrets-from` như mục 4
  và cài APK mới lên máy quét.

## 8. Bản beta: chạy thẳng từ repo trên server

Dùng khi thử nghiệm, sửa lỗi liên tục: server giữ bản clone, cập nhật bằng `git pull`, build tại chỗ, không cần `package.sh`.
Chỉ dùng với tên miền (Let's Encrypt), ví dụ subdomain miễn phí DuckDNS. Chế độ IP cần APK nhúng CA, nên dùng `package.sh`.

Server cần: Docker + compose v2, git, git-lfs, openssl, keytool (Ubuntu:
`apt install git git-lfs openssl openjdk-21-jre-headless`), RAM ≥ 4 GB. DNS của `<host>` trỏ về IP server, mở cổng 80, 443,
8443 (Oracle Cloud: mở ở cả Security List lẫn iptables trong VM).

```bash
git lfs install                 # trước khi clone: thiếu thì model chống giả mạo chỉ là file con trỏ (start.sh sẽ chặn)
mkdir -p ~/app && cd ~/app      # Attendance/ và mobileBackend/ phải nằm cạnh nhau (hai repo, hoặc một repo tổng chứa cả hai)
git clone https://github.com/Tuan6b/Attendance.git
git clone https://github.com/Tuan6b/mobileBackend.git     # repo private: gh auth login hoặc deploy key
cd Attendance
prod/init-secrets.sh prod <host> <email>    # một lần: sinh prod/.env, prod/*.env, prod/secrets/
prod/start.sh                                # build + khởi động; mật khẩu admin: ADMIN_INITIAL_PASSWORD trong prod/.env
```
Sau đó làm các bước sau cài đặt ở mục 3 (bỏ bước 4).

Cập nhật sau khi push bản sửa (chỉ build lại image có thay đổi, dữ liệu trong volume giữ nguyên, `init-db.sql` chạy lại không sao):
```bash
cd ~/app && git -C Attendance pull && git -C mobileBackend pull && Attendance/prod/start.sh
```
Bản sửa gây lỗi: `git -C Attendance checkout <commit cũ>` rồi `Attendance/prod/start.sh`.

APK (máy dev, repo DxLabAttendance): `./gradlew assembleRelease -PcheckerBaseUrl=https://<host>:8443`, ký như mục 2.

Lưu ý:
- Secret nằm trong `Attendance/prod/` (đã có `.gitignore`). Không `git add -f`, không `git clean -x` (xoá mất secret).
  Sao lưu `prod/.env`, `prod/*.env`, `prod/secrets/` một lần, cất như mật khẩu.
- Sao lưu, vận hành như mục 5, 6, chạy trong `Attendance/prod/` thay cho `deploy/`.
- Muốn chuyển sang gói chính thức về sau: đóng gói `--secrets-from Attendance/prod` (mục 4), dữ liệu giữ nguyên.
