# SlopClient cosmetics backend. Build from the repository root:
#   docker build -f deploy/cosmetics/Dockerfile -t slopclient-cosmetics .

# The web page's 3D preview (src/web/viewer.js) is built from the launcher's viewer, so the
# image never ships a stale copy of the one in the repository.
FROM node:22-bookworm-slim AS viewer
WORKDIR /src/launcher
COPY launcher/package.json launcher/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --ignore-scripts --no-audit --no-fund
COPY launcher/vite.viewer.config.ts launcher/tsconfig.json ./
COPY launcher/src/lib src/lib
COPY launcher/src/webviewer src/webviewer
RUN mkdir -p ../crates/slopclient-cosmetics/src/web && npm run build:viewer

FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
COPY --from=viewer /src/crates/slopclient-cosmetics/src/web/viewer.js crates/slopclient-cosmetics/src/web/viewer.js
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    cargo build --release --locked -p slopclient-cosmetics \
    && cp target/release/slopclient-cosmetics /usr/local/bin/

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /data slopclient \
    && mkdir /data && chown slopclient:slopclient /data
COPY --from=build /usr/local/bin/slopclient-cosmetics /usr/local/bin/slopclient-cosmetics

ENV SLOPCLIENT_COSMETICS_BIND=0.0.0.0:8787 \
    SLOPCLIENT_COSMETICS_DATA=/data
USER slopclient
VOLUME /data
EXPOSE 8787
ENTRYPOINT ["slopclient-cosmetics"]
