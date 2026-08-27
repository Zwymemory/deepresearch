FROM python:3.12.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV HF_HOME=/models/huggingface

WORKDIR /app

RUN python -m pip install --no-cache-dir --upgrade "pip==26.1.1"
COPY requirements.txt .
RUN python -m pip install --no-cache-dir -r requirements.txt

RUN groupadd --system reranker \
    && useradd --system --gid reranker --create-home --home-dir /home/reranker reranker \
    && mkdir -p /models/huggingface \
    && chown -R reranker:reranker /models/huggingface

COPY app.py runtime_config.py ./
COPY training ./training

USER reranker
EXPOSE 9000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "9000"]
