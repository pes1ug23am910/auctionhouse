# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS build
WORKDIR /source
COPY package*.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src/ src/
COPY test/ test/
RUN npm test
FROM node:24-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6
WORKDIR /app
COPY --from=build --chown=10001:10001 /source/dist/src/ ./dist/
COPY package.json ./
USER 10001:10001
ENV HOST=0.0.0.0 PORT=3001
EXPOSE 3001
CMD ["node", "dist/main.js"]
