import { post } from '../api/method.js';

const form = document.getElementById('absentCreateForm');
const successBox = document.getElementById('absentSuccessMessage');
const errorBox = document.getElementById('absentErrorMessage');

function showSuccess(message) {
    errorBox.style.display = 'none';
    successBox.textContent = message;
    successBox.style.display = 'block';
}

function showError(message) {
    successBox.style.display = 'none';
    errorBox.textContent = message;
    errorBox.style.display = 'block';
}

if (form) {
    form.addEventListener('submit', async (event) => {
        event.preventDefault();

        const body = {
            startDate: document.getElementById('startDate').value,
            endDate: document.getElementById('endDate').value,
            reason: document.getElementById('reason').value,
        };

        try {
            await post('/api/attendance/leave', body);
            showSuccess('Yêu cầu nghỉ phép đã được gửi.');
            form.reset();
        } catch (error) {
            showError(error.message || 'Có lỗi xảy ra, vui lòng thử lại sau');
        }
    });
}
