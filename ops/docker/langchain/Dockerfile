FROM python:3.12.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 UV_PROJECT_ENVIRONMENT=/opt/venv UV_LINK_MODE=copy
RUN pip install --no-cache-dir uv==0.8.22
WORKDIR /app
COPY advisor-langchain/pyproject.toml advisor-langchain/uv.lock ./
RUN uv sync --frozen --no-dev
COPY advisor-langchain/app.py ./
RUN useradd --system --uid 10001 advisor
USER advisor
EXPOSE 8090
CMD ["/opt/venv/bin/uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
