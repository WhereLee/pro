# syntax=docker/dockerfile:1
# ============================================================
# buddy 后端镜像：多阶段构建（Maven 构建 → JRE 运行），非 root 运行，容器感知内存。
# 构建：docker build -t buddy-backend:latest ./buddy
# 运行需注入生产环境变量（见 application-prod.yml），profile 默认 prod。
# ============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先只拷 pom 拉依赖，利用层缓存（源码变动不必重下依赖）
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# curl 供容器健康检查；创建非 root 运行用户
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r buddy && useradd -r -g buddy buddy
COPY --from=build /build/target/buddy.jar /app/app.jar
RUN mkdir -p /app/uploads /app/logs && chown -R buddy:buddy /app
USER buddy

EXPOSE 8200
# 容器感知堆（按容器内存百分比），并统一 UTF-8 输出避免中文日志乱码
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS http://localhost:8200/api/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=$SPRING_PROFILES_ACTIVE"]
