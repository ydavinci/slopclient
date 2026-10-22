# SlopClient cosmetics backend. Build from the repository root:
#   docker build -f deploy/cosmetics/Dockerfile -t slopclient-cosmetics .

FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
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
