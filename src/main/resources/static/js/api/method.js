async function fetchAPI(url, method, body = null) {
  const options = {
    method: method,
    headers: {
      "Content-Type": "application/json"
    }
  };
  if (body) {
    options.body = JSON.stringify(body);
  }

  const response = await fetch(url, options);

  // Phiên hết hạn/đã đăng xuất: server redirect về trang đăng nhập (invalidSessionUrl) thay vì 401,
  // fetch tự đi theo và nhận HTML 200 → coi như 401 để caller xử lý đúng.
  if (response.redirected && new URL(response.url).pathname === '/auth/login') {
    const err = new Error('Phiên đăng nhập đã hết hạn');
    err.status = 401;
    throw err;
  }

  if (!response.ok) {
    const err = new Error(await response.text());
    err.status = response.status; // để caller phân biệt 401/403 với lỗi khác
    throw err;
  }
  
  return response.json();
}

export const get = (url) => fetchAPI(url, "GET");
export const post = (url, body) => fetchAPI(url, "POST", body);
export const put = (url, body) => fetchAPI(url, "PUT", body);
export const patch = (url, body) => fetchAPI(url, "PATCH", body);