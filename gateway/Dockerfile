# syntax=docker/dockerfile:1
FROM node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe AS build
WORKDIR /source
COPY package*.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src/ src/
COPY test/ test/
COPY tools/ tools/
RUN npm test
FROM node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="auctionhouse-gateway" org.opencontainers.image.revision=$SOURCE_REVISION
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3t64=3.5.7-1~deb13u3 openssl-provider-legacy=3.5.7-1~deb13u3 libpcre2-8-0=10.46-1~deb13u3 \
    && rm -rf /var/lib/apt/lists/*
RUN rm -rf /usr/local/lib/node_modules/npm /opt/yarn-* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/yarn /usr/local/bin/yarnpkg
WORKDIR /app
COPY --from=build --chown=10001:10001 /source/dist/src/ ./dist/
COPY package.json ./
USER 10001:10001
ENV HOST=0.0.0.0 PORT=3001
EXPOSE 3001
CMD ["node", "dist/main.js"]
