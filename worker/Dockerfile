FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr libgl1 libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN mkdir /models && cd /models \
 && curl -fsSLO https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx \
 && curl -fsSLO https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx
COPY . .
ENV STORAGE_DIR=/app/data/uploads TZ=Africa/Harare
CMD ["celery", "-A", "tasks", "worker", "-B", "--loglevel=info", "--concurrency=4"]
