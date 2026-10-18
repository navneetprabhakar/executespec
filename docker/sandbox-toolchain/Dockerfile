# The base is immutable. Refresh deliberately and rerun validate.sh before changing this digest.
FROM debian@sha256:328d16499860ae6cb9b345e2e4cebca08c2a36e4f7278482c7bd1f39d71e5bfd

ARG PRETTIER_VERSION=3.6.2
ARG BLACK_VERSION=25.1.0

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates \
      cargo \
      git \
      golang-go \
      maven \
      nodejs \
      npm \
      openjdk-21-jdk-headless \
      python3 \
      python3-pip \
      python3-venv \
      rustc \
    && pip3 install --break-system-packages --no-cache-dir "black==${BLACK_VERSION}" \
    && npm install --global "prettier@${PRETTIER_VERSION}" \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /root/.cache /root/.npm

# Runtime-contract dependencies are downloaded at image-build time and installed per acceptance
# attempt from this immutable offline wheelhouse. Runtime containers retain --network none; a
# generated requirements file is validation input, never a pip command or an egress authority.
COPY python-http-runtime.lock /opt/executespec/runtime/python-http-runtime.lock
RUN mkdir -p /opt/executespec/runtime/wheels \
    && pip3 download --only-binary=:all: --no-cache-dir \
      --dest /opt/executespec/runtime/wheels \
      --requirement /opt/executespec/runtime/python-http-runtime.lock \
    && pip3 install --break-system-packages --no-index --no-cache-dir \
      --find-links=/opt/executespec/runtime/wheels \
      --requirement /opt/executespec/runtime/python-http-runtime.lock \
    && find /opt/executespec/runtime/wheels -type f -exec chmod 0444 {} + \
    && chmod 0444 /opt/executespec/runtime/python-http-runtime.lock
COPY python-http-acceptance.sh /opt/executespec/runtime/python-http-acceptance.sh
RUN chmod 0555 /opt/executespec/runtime/python-http-acceptance.sh

# CONTAINER_STRICT_V1 overrides the user with 65532:65532 and mounts /tmp as a bounded tmpfs.
ENV HOME=/tmp
WORKDIR /workspace

LABEL ai.executespec.image-purpose="strict-sandbox-toolchain"
LABEL ai.executespec.runtime-profile="CONTAINER_STRICT_V1"

CMD ["sleep", "1800"]
