-- Khởi tạo DB môi trường kiểm thử. Chạy SAU khi backend đã khởi động 1 lần (Hibernate ddl-auto tạo bảng),
-- do deploy/start.sh gọi với các biến psql: gw_pw, admin_pw, manager_pw, employee_pw, checker_pw.
-- Chạy lại nhiều lần được (idempotent).
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Role DB của mobile gateway: quyền giống cấu hình đang chạy thật (xem schema-mobile-auth.sql, audit M4)
SELECT 'CREATE ROLE mobile_gateway LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'mobile_gateway') \gexec
ALTER ROLE mobile_gateway PASSWORD :'gw_pw';
ALTER ROLE mobile_gateway SET search_path = mobile_auth, public;

\i /sectest/schema-mobile-auth.sql

GRANT USAGE ON SCHEMA public, mobile_auth TO mobile_gateway;
GRANT SELECT ON users, face_profiles, face_samples, system_settings, work_schedules TO mobile_gateway;
GRANT UPDATE (password, updated_at) ON users TO mobile_gateway;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mobile_auth TO mobile_gateway;

-- OTP đăng nhập của gateway (purpose LOGIN = 4); Hibernate không tự nới ràng buộc cũ
ALTER TABLE otp DROP CONSTRAINT IF EXISTS otp_purpose_check;
ALTER TABLE otp ADD CONSTRAINT otp_purpose_check CHECK (purpose BETWEEN 1 AND 4);

-- Email hệ thống (gửi OTP) → Mailpit nhận mọi tài khoản; backend phải khởi động lại để nạp
UPDATE system_settings SET system_email = 'attendance@sectest.local', system_email_password = 'sectest' WHERE id = 1;

-- Tài khoản kiểm thử cho đủ 4 role. role: 1 ADMIN, 2 MANAGER, 3 EMPLOYEE, 4 CHECKER; status 1 ACTIVE.
-- BCrypt bằng pgcrypto ($2a$, cùng định dạng Spring BCryptPasswordEncoder).
INSERT INTO users (username, email, password, role, status, full_name, specialization, department,
                   must_change_password, email_verified, created_at, updated_at)
VALUES
    ('ia.admin',    'ia.admin@sectest.local',    crypt(:'admin_pw',    gen_salt('bf', 10)), 1, 1, 'IA Admin',    0, NULL,    false, true, now(), now()),
    ('ia.manager',  'ia.manager@sectest.local',  crypt(:'manager_pw',  gen_salt('bf', 10)), 2, 1, 'IA Manager',  0, 'DE211', false, true, now(), now()),
    ('ia.employee', 'ia.employee@sectest.local', crypt(:'employee_pw', gen_salt('bf', 10)), 3, 1, 'IA Employee', 0, 'DE211', false, true, now(), now()),
    ('ia.checker',  'ia.checker@sectest.local',  crypt(:'checker_pw',  gen_salt('bf', 10)), 4, 1, 'IA Checker',  0, NULL,    false, true, now(), now())
ON CONFLICT (username) DO UPDATE SET password = EXCLUDED.password, role = EXCLUDED.role, status = 1, updated_at = now();
