-- Khởi tạo DB production. Chạy SAU khi backend đã khởi động 1 lần (Hibernate ddl-auto tạo bảng),
-- do deploy/start.sh gọi với các biến psql: gw_pw, admin_pw. Chạy lại nhiều lần được (idempotent).
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Role DB của mobile gateway: chỉ quyền cần dùng (audit M4, giống sectest/init-db.sql)
SELECT 'CREATE ROLE mobile_gateway LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'mobile_gateway') \gexec
ALTER ROLE mobile_gateway PASSWORD :'gw_pw';
ALTER ROLE mobile_gateway SET search_path = mobile_auth, public;

\i /init/schema-mobile-auth.sql

GRANT USAGE ON SCHEMA public, mobile_auth TO mobile_gateway;
GRANT SELECT ON users, face_profiles, face_samples, system_settings, work_schedules TO mobile_gateway;
GRANT UPDATE (password, updated_at) ON users TO mobile_gateway;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mobile_auth TO mobile_gateway;

-- OTP đăng nhập của gateway (purpose LOGIN = 4); Hibernate không tự nới ràng buộc cũ
ALTER TABLE otp DROP CONSTRAINT IF EXISTS otp_purpose_check;
ALTER TABLE otp ADD CONSTRAINT otp_purpose_check CHECK (purpose BETWEEN 1 AND 4);

-- Admin đầu tiên (đăng ký web chỉ tạo EMPLOYEE chờ duyệt, cần ADMIN để duyệt). Chỉ tạo khi chưa có ADMIN nào,
-- nên chạy lại không đổi mật khẩu admin đã đổi. role 1 ADMIN, status 1 ACTIVE; BCrypt $2a$ như Spring.
INSERT INTO users (username, email, password, role, status, full_name, specialization, department,
                   must_change_password, email_verified, created_at, updated_at)
SELECT 'admin', 'admin@attendance.local', crypt(:'admin_pw', gen_salt('bf', 10)), 1, 1, 'System Administrator', 0, NULL,
       false, true, now(), now()
WHERE NOT EXISTS (SELECT FROM users WHERE role = 1);
