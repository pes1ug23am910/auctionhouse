# syntax=docker/dockerfile:1
FROM python:3.13-slim-bookworm@sha256:3e2de9c40ca4e3d73240059f9d48baff27908f10293e985a2f382a0378e6df4a
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-client && rm -rf /var/lib/apt/lists/*
WORKDIR /warehouse
COPY requirements.lock pyproject.toml ./
RUN pip install --no-cache-dir setuptools==84.0.0 && pip install --no-cache-dir -r requirements.lock
COPY src/ src/
COPY dbt/ dbt/
COPY tools/ tools/
COPY container-cut.sh ./
RUN pip install --no-cache-dir --no-deps --no-build-isolation . && chmod 0555 container-cut.sh
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp
ENTRYPOINT ["/warehouse/container-cut.sh"]
