# MiniMe-Code 云端 Agent 服务镜像（一期：合并单一镜像）
# 一个镜像同时含：
#   1) Java 17 运行时（跑 agent-server.jar）
#   2) 完整 AI 工具链（python3/node/git/ripgrep/jq…，与本地 proot / ai-runtime 对齐）
# 云端 Agent 的 skill/memory/context 模块完全独立地运行在容器内（/root/.minimecode 与 /workspace/.minimecode）。
#
# 构建（仓库根目录）：
#   ./gradlew :agent-server:jar
#   docker build -t minimecode/agent-server:<ver> -f docker/agent-server/Dockerfile .
#   docker push minimecode/agent-server:<ver>

FROM eclipse-temurin:17-jre

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# 合并 docker/ai-runtime 的完整工具链 + server 运行所需（bash/ca-certificates/curl/git）。
# BashTool 在容器内直接执行这些命令，无需额外的 ai-runtime 容器。
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash ca-certificates curl wget git ripgrep \
        python3 python3-pip python3-venv nodejs npm \
        jq unzip zip gzip tar less procps file tree vim-tiny \
        openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/agent-server /workspace /root/.minimecode \
    && git config --global --add safe.directory '*' \
    && ln -sfn /workspace /root/workspace

# fat jar（业务代码，频繁升级）——外挂运行，基座镜像只在工具链/Java 升级时才重建推送。
# 部署脚本从 GitHub Release 下载最新 jar 挂到 /data/agent-server/agent-server.jar。
# 镜像内置一个占位指向说明；真实 jar 由 docker run -v 挂载覆盖。
COPY agent-server/build/libs/agent-server.jar /app/agent-server.jar

ENV MINIME_DB=/data/agent-server/events.db \
    MINIME_WORKSPACE=/workspace \
    MINIME_PORT=8080 \
    HOME=/root

WORKDIR /workspace

EXPOSE 8080

# restart: always 由部署脚本（--restart unless-stopped）负责
CMD ["java", "-jar", "/app/agent-server.jar"]
