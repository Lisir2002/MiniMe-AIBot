# MiniMe-Code 云端 Agent 服务镜像
# 在用户自己的云服务器上常驻运行：接收 App 提问 → 跑 LLM + 工具循环 → 事件落库 → App 增量拉取。
# 构建（在仓库根目录，先把 agent-server fat jar 放进来或改用多阶段构建见下）：
#   ./gradlew :agent-server:jar
#   docker build -t minimecode/agent-server:latest -f docker/agent-server/Dockerfile .

FROM eclipse-temurin:17-jre

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# Bash 工具需要 /bin/bash；ca-certificates 供 HTTPS 调用 LLM
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash ca-certificates curl git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/agent-server /workspace

# fat jar（约 31MB，含所有依赖）
COPY agent-server/build/libs/agent-server.jar /app/agent-server.jar

ENV MINIME_DB=/data/agent-server/events.db \
    MINIME_WORKSPACE=/workspace \
    MINIME_PORT=8080

EXPOSE 8080

# restart: always 由 compose 负责；进程退出即重启
CMD ["java", "-jar", "/app/agent-server.jar"]
