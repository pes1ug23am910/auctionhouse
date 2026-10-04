# syntax=docker/dockerfile:1
FROM python:3.13.16-slim-trixie@sha256:3dd7cc108ec1493442514f5c2a871af6af0ec31d768ff6e378a93340c3b3db5f
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="auctionhouse-warehouse" org.opencontainers.image.revision=$SOURCE_REVISION
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client libssl3t64=3.5.7-1~deb13u3 openssl=3.5.7-1~deb13u3 openssl-provider-legacy=3.5.7-1~deb13u3 libpcre2-8-0=10.46-1~deb13u3 \
    && rm -rf /var/lib/apt/lists/*
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
