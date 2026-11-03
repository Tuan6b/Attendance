FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src
COPY .mvn ./.mvn
COPY mvnw .
COPY mvnw.cmd .

RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

# Không chạy bằng root. Volume uploads mới tạo nhận quyền của thư mục này (volume cũ: prod/start.sh đổi chủ)
RUN useradd --system --no-create-home appuser && \
    mkdir uploads && \
    chown -R appuser:appuser /app

COPY --from=build --chown=appuser:appuser /app/target/*.jar app.jar

EXPOSE 9090
USER appuser

ENTRYPOINT ["java","-jar","app.jar"]