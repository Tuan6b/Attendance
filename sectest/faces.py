# Sinh ảnh khuôn mặt cho verify.sh từ ảnh mẫu công khai đi kèm thư viện InsightFace (insightface/data/images/t1.jpg)
# — không dùng ảnh nhân viên thật. Chạy trong container face-ai: docker compose exec -T face-ai python - < faces.py
# In ra từng dòng "<nhãn> <base64 JPEG>": A1..A5 (mẫu đăng ký của người A), A (ảnh chấm công của A), B (người khác).
import base64, os

import cv2
import insightface
from insightface.app import FaceAnalysis

img = cv2.imread(os.path.join(os.path.dirname(insightface.__file__), "data", "images", "t1.jpg"))
fa = FaceAnalysis(name="buffalo_l", allowed_modules=["detection"])
fa.prepare(ctx_id=-1, det_size=(640, 640))
faces = sorted(fa.get(img), key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]), reverse=True)


def crop(face, scale):
    x1, y1, x2, y2 = face.bbox
    cx, cy, half = (x1 + x2) / 2, (y1 + y2) / 2, max(x2 - x1, y2 - y1) * scale / 2
    h, w = img.shape[:2]
    c = img[int(max(cy - half, 0)):int(min(cy + half, h)), int(max(cx - half, 0)):int(min(cx + half, w))]
    c = cv2.resize(c, (480, int(480 * c.shape[0] / c.shape[1])), interpolation=cv2.INTER_CUBIC)
    return base64.b64encode(cv2.imencode(".jpg", c, [cv2.IMWRITE_JPEG_QUALITY, 92])[1]).decode()


a, b = faces[0], faces[1]
for i, s in enumerate((2.0, 2.2, 2.4, 2.6, 2.8), 1):
    print(f"A{i}", crop(a, s))
print("A", crop(a, 2.3))
print("B", crop(b, 2.4))
