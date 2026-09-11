const COOLDOWN_SECONDS = 90;

const resendBtn = document.getElementById('resendOtpBtn');
const cooldownLabel = document.getElementById('resendCooldown');
const resendAlert = document.getElementById('resendAlert');

function storageKey(email, purpose) {
    return `otp_resend_until:${purpose}:${email}`;
}

function startCooldown(email, purpose, untilTimestampMs) {
    localStorage.setItem(storageKey(email, purpose), String(untilTimestampMs));
    tickCooldown(email, purpose);
}

function tickCooldown(email, purpose) {
    const until = Number(localStorage.getItem(storageKey(email, purpose)) || 0);
    const remainingMs = until - Date.now();

    if (remainingMs <= 0) {
        resendBtn.disabled = false;
        cooldownLabel.textContent = '';
        return;
    }

    resendBtn.disabled = true;
    const remainingSec = Math.ceil(remainingMs / 1000);
    cooldownLabel.textContent = `(gửi lại sau ${remainingSec}s)`;
    setTimeout(() => tickCooldown(email, purpose), 1000);
}

if (resendBtn) {
    const email = resendBtn.getAttribute('data-email');
    const purpose = resendBtn.getAttribute('data-purpose');

    if (email) {
        // Nếu chưa có mốc cooldown nào lưu lại (lần đầu vào trang sau khi vừa đăng ký/đăng
        // nhập — server vừa tự gửi OTP), coi như OTP vừa gửi lúc trang này load để nút "gửi
        // lại" không bấm được ngay, tránh gửi trùng lúc mã đầu tiên còn hiệu lực.
        if (!localStorage.getItem(storageKey(email, purpose))) {
            startCooldown(email, purpose, Date.now() + COOLDOWN_SECONDS * 1000);
        } else {
            tickCooldown(email, purpose);
        }

        resendBtn.addEventListener('click', async () => {
            resendAlert.style.display = 'none';
            resendBtn.disabled = true;
            try {
                const response = await fetch('/api/otp/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ destination: email, purpose }),
                });
                if (!response.ok) {
                    const message = await response.text();
                    resendAlert.textContent = message || 'Không thể gửi lại mã OTP.';
                    resendAlert.style.display = 'block';
                    resendBtn.disabled = false;
                    return;
                }
                startCooldown(email, purpose, Date.now() + COOLDOWN_SECONDS * 1000);
                resendAlert.textContent = 'Đã gửi lại mã OTP. Vui lòng kiểm tra email.';
                resendAlert.className = 'otp-alert otp-alert--success';
                resendAlert.style.display = 'block';
            } catch (error) {
                resendAlert.textContent = 'Có lỗi xảy ra, vui lòng thử lại.';
                resendAlert.style.display = 'block';
                resendBtn.disabled = false;
            }
        });
    }
}
