FROM python:3.12.13-slim-bookworm AS builder

ENV PIP_NO_CACHE_DIR=1 \
    UV_LINK_MODE=copy

WORKDIR /app
RUN python -m pip install --upgrade "pip==26.1.1" \
    && python -m pip install "uv==0.10.9"
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --locked --no-dev --no-editable

FROM python:3.12.13-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LANGGRAPH_STRICT_MSGPACK=true \
    PATH="/app/.venv/bin:$PATH"

RUN groupadd --system workflow && useradd --system --gid workflow --home /app workflow

WORKDIR /app
COPY --from=builder /app/.venv /app/.venv

USER workflow
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=4 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8091/internal/health/ready', timeout=2)"

CMD ["uvicorn", "deepresearch_workflow.app:app", "--host", "0.0.0.0", "--port", "8091", "--no-access-log"]
