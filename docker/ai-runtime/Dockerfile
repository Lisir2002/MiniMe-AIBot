# MiniMe-Code 云端 AI 运行环境镜像
# 内含 AI agent 执行 Bash / 文件操作所需的完整工具链，与本地 proot rootfs 对齐。
# 构建：docker build -t minimecode/ai-runtime:latest .
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# 基础工具链：版本控制 / 搜索 / 脚本运行时 / 网络 / 压缩 / 编辑器
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        wget \
        git \
        ripgrep \
        python3 \
        python3-pip \
        python3-venv \
        nodejs \
        npm \
        openssh-client \
        jq \
        unzip \
        zip \
        gzip \
        tar \
        less \
        procps \
        file \
        tree \
        vim-tiny \
        iptables \
    && rm -rf /var/lib/apt/lists/*

# git 安全目录（容器内挂载的 workspace 归属可能与 root 不同）与工作目录。
# 关键：~(=root 的 HOME /root)/workspace 符号链接指向挂载点 /workspace，
# 使 AI 沿用的 ~/workspace 逻辑路径在容器内同样成立（与本地 proot 语义一致）。
RUN git config --global --add safe.directory '*' \
    && mkdir -p /workspace /root/.minimecode \
    && ln -sfn /workspace /root/workspace

WORKDIR /workspace

# 保持容器常驻，供 App 通过 docker exec 执行工具命令
CMD ["sleep", "infinity"]
