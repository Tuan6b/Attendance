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
RUN pip install --no-cache-dir -r requirements.txt

# Tải sẵn model InsightFace vào image: khởi động (kể cả sau docker compose down -v) không cần Internet,
# không phải chờ tải ~300 MB mỗi lần tạo lại container.
RUN python -c "from insightface.app import FaceAnalysis; FaceAnalysis(name='buffalo_l')"

# Copy application source
COPY . .

# Ensure data and models directories exist
RUN mkdir -p data ~/.insightface/models

EXPOSE 8002

ENV HOST=0.0.0.0
ENV PORT=8002
ENV RELOAD=false

CMD ["python", "run.py"]
