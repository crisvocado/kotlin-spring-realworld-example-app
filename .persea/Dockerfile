FROM maven:3.9.6-eclipse-temurin-17

WORKDIR /workspace

COPY pom.xml ./
RUN mvn dependency:resolve -B || true
