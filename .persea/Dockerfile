FROM maven:3.9.9-eclipse-temurin-17

RUN rm -rf /var/lib/apt/lists/* \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::No-Cache=true update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
       build-essential \
       ca-certificates \
       curl \
       git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY pom.xml ./

RUN mvn -B dependency:resolve dependency:resolve-plugins || true
