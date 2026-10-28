FROM python:3.11-slim

# System dependencies for OpenCV, InsightFace, and ONNX Runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libgl1 \
    libglib2.0-0 \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies
COPY requirements.txt .
# Mạng chậm/chập: pip mặc định timeout 15 s, thử lại 5 lần → build hỏng giữa chừng.
RUN pip install --no-cache-dir --default-timeout=120 --retries 10 -r requirements.txt

# Tải sẵn model InsightFace vào image: khởi động (kể cả sau docker compose down -v) không cần Internet,
# không phải chờ tải ~300 MB mỗi lần tạo lại container. Tải lỗi thì xoá file dở và thử lại (tối đa 5 lần).
RUN for i in 1 2 3 4 5; do \
        python -c "from insightface.app import FaceAnalysis; FaceAnalysis(name='buffalo_l')" && exit 0; \
        rm -rf ~/.insightface/models/buffalo_l ~/.insightface/models/buffalo_l.zip; \
        echo "Tải model lỗi, thử lại lần $((i + 1))..."; sleep 15; \
    done; exit 1

# Copy application source
COPY . .

# Ensure data and models directories exist
RUN mkdir -p data ~/.insightface/models

EXPOSE 8002

ENV HOST=0.0.0.0
ENV PORT=8002
ENV RELOAD=false

CMD ["python", "run.py"]
