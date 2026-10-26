const COOLDOWN_SECONDS = 90;

const resendBtn = document.getElementById('resendOtpBtn');
const cooldownLabel = document.getElementById('resendCooldown');
const resendAlert = document.getElementById('resendAlert');

function storageKey(emailOrMarker, purpose) {
    return `otp_resend_until:${purpose}:${emailOrMarker}`;
}

function startCooldown(key) {
    localStorage.setItem(key, String(Date.now() + COOLDOWN_SECONDS * 1000));
    tickCooldown(key);
}

function tickCooldown(key) {
    const until = Number(localStorage.getItem(key) || 0);
    const remainingMs = until - Date.now();

    if (remainingMs <= 0) {
        resendBtn.disabled = false;
        cooldownLabel.textContent = '';
        return;
    }

    resendBtn.disabled = true;
    const remainingSec = Math.ceil(remainingMs / 1000);
    cooldownLabel.textContent = `(gửi lại sau ${remainingSec}s)`;
    setTimeout(() => tickCooldown(key), 1000);
}

if (resendBtn) {
    const email = resendBtn.getAttribute('data-email');
    const purpose = resendBtn.getAttribute('data-purpose');
    // Không có email phía client (tới từ login() phát hiện tài khoản chưa xác thực — email
    // thật chỉ nằm ở session server-side, xem AuthController) — vẫn cho gửi lại được, chỉ là
    // gọi endpoint khác không cần biết địa chỉ, và khoá cooldown theo một marker cố định.
    const key = storageKey(email || '__pending__', purpose);

    // Nếu chưa có mốc cooldown nào lưu lại (lần đầu vào trang sau khi vừa đăng ký/đăng nhập —
    // server vừa tự gửi OTP), coi như OTP vừa gửi lúc trang này load để nút "gửi lại" không
    // bấm được ngay, tránh gửi trùng lúc mã đầu tiên còn hiệu lực.
    if (!localStorage.getItem(key)) {
        startCooldown(key);
    } else {
        tickCooldown(key);
    }

    resendBtn.addEventListener('click', async () => {
        resendAlert.style.display = 'none';
        resendBtn.disabled = true;
        try {
            const response = email
                ? await fetch('/auth/verify-otp/resend', {
                    method: 'POST',
                    body: new URLSearchParams({ email, purpose }),
                })
                : await fetch('/auth/verify-otp/resend-pending', { method: 'POST' });

            if (!response.ok) {
                const message = await response.text();
                resendAlert.textContent = message || 'Không thể gửi lại mã OTP.';
                resendAlert.className = 'otp-alert otp-alert--error';
                resendAlert.style.display = 'block';
                resendBtn.disabled = false;
                return;
            }
            startCooldown(key);
            resendAlert.textContent = 'Đã gửi lại mã OTP. Vui lòng kiểm tra email.';
            resendAlert.className = 'otp-alert otp-alert--success';
            resendAlert.style.display = 'block';
        } catch (error) {
            resendAlert.textContent = 'Có lỗi xảy ra, vui lòng thử lại.';
            resendAlert.className = 'otp-alert otp-alert--error';
            resendAlert.style.display = 'block';
            resendBtn.disabled = false;
        }
    });
}
