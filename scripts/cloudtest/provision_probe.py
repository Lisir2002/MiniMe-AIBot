#!/usr/bin/env python3
"""在真实服务器上复现 CloudProvisioner 的预检命令并校验解析结果。

用法：
  1) 密码认证：
     SSHPASS='你的密码' python3 provision_probe.py --host 1.2.3.4 --user root
  2) 私钥认证：
     python3 provision_probe.py --host 1.2.3.4 --user root --key /path/to/id_ed25519

说明：
  - 预检命令直接从 CloudProvisioner.kt 的 probe 三引号字符串中提取，
    并把 Kotlin 的 ${'$'} 还原为 $，确保测的就是 App 实际发出的命令。
  - 默认只做只读探测；加 --apply 才会执行安装动作（会改变服务器状态）。
  - 凭据只走环境变量 / 本地密钥文件，不写入脚本、不提交仓库。
"""
import argparse, os, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVISIONER = ROOT / "app/src/main/java/com/minime/code/feature/workspace/domain/remote/CloudProvisioner.kt"

# 与 Kotlin commandFor 中一致的安装动作（默认不执行）
APPLY_ACTIONS = {
    "prereq.dirs": 'mkdir -p "$HOME/.minimecode" "$HOME/minimecode-workspace" && echo "工具目录就绪"',
    "prereq.curl": "command -v curl >/dev/null 2>&1 && echo 'curl 已存在' || echo 'curl 缺失'",
    "prereq.git": "command -v git >/dev/null 2>&1 && git --version || echo 'git 缺失'",
    "prereq.bash": "command -v bash && bash --version | head -1 || echo 'bash 缺失'",
    "docker.install": ("if command -v docker >/dev/null 2>&1; then echo 'Docker 已安装'; "
        "else (command -v apt-get >/dev/null && sudo apt-get update -qq && sudo apt-get install -y -qq docker.io) "
        "|| (command -v dnf >/dev/null && sudo dnf install -y docker) "
        "|| (command -v yum >/dev/null && sudo yum install -y docker) "
        "|| echo '未识别发行版，请手动安装 Docker'; fi; docker --version 2>&1"),
    "docker.image": "(docker image inspect {image} >/dev/null 2>&1 && echo '镜像已存在') || docker pull {image}",
    "docker.run": ("docker rm -f minimecode-ai >/dev/null 2>&1 || true; "
        "docker run -d --name minimecode-ai --restart unless-stopped "
        "-v \"$HOME/.minimecode:/root/.minimecode\" -v \"$HOME/minimecode-workspace:/workspace\" {image} "
        "&& sleep 2 && docker exec minimecode-ai sh -c 'echo container-ok; git --version 2>/dev/null || true' "
        "&& echo 'AI 容器已启动'"),
    "native.runtime": ("mkdir -p \"$HOME/.local/bin\" && "
        "{ command -v apt-get >/dev/null 2>&1 && sudo apt-get update -qq && sudo apt-get install -y -qq git curl ripgrep python3; } "
        "|| { command -v dnf >/dev/null 2>&1 && sudo dnf install -y git curl ripgrep python3; } "
        "|| echo '未识别发行版，请手动安装'; echo '原生工具链安装结束'"),
    "native.marker": "printf 'strategy=native\\n' > \"$HOME/.minimecode/cloud-env.marker\" && echo '环境标记写入完成'",
}

def load_probe():
    src = PROVISIONER.read_text(encoding="utf-8")
    m = re.search(r'val probe = """(.*?)"""\.trimIndent\(\)', src, re.S)
    if not m:
        sys.exit("无法从 CloudProvisioner.kt 提取 probe 命令")
    return m.group(1).replace("${'$'}", "$").strip()

def ssh_prefix(a):
    opts = ["-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15",
            "-o", "ServerAliveInterval=10", "-p", str(a.port)]
    if a.key:
        return ["ssh"] + opts + ["-i", a.key, f"{a.user}@{a.host}"]
    return ["sshpass", "-e", "ssh"] + opts + [f"{a.user}@{a.host}"]

def run(a, script, timeout=90):
    env = os.environ.copy()
    if not a.key:
        if not env.get("SSHPASS"):
            sys.exit("密码认证需要设置 SSHPASS 环境变量")
    else:
        env.pop("SSHPASS", None)
    try:
        r = subprocess.run(ssh_prefix(a) + [script], capture_output=True, text=True,
                           timeout=timeout, env=env)
        return r.stdout + ("\n[stderr] " + r.stderr if r.stderr.strip() else ""), r.returncode
    except subprocess.TimeoutExpired:
        return "[超时]", -1
    except FileNotFoundError as e:
        sys.exit(f"缺少命令：{e}")

def parse_kv(out):
    kv = {}
    for line in out.splitlines():
        i = line.find("=")
        if line.startswith("__") and i > 0:
            kv[line[2:i]] = line[i + 1:]
    return kv

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", required=True)
    p.add_argument("--port", type=int, default=22)
    p.add_argument("--user", default="root")
    p.add_argument("--key", help="私钥文件路径（不传则用 SSHPASS 密码）")
    p.add_argument("--image", default="minimecode/ai-runtime:latest")
    p.add_argument("--apply", action="store_true", help="执行安装动作（会改变服务器状态）")
    a = p.parse_args()

    print(f"=== 连接 {a.user}@{a.host}:{a.port} ===")
    probe = load_probe()
    print("--- 发出的预检命令（与 App 一致）---")
    print(probe)
    print("\n--- 执行预检 ---")
    out, code = run(a, probe, timeout=90)
    print(out)
    print(f"[退出码] {code}")

    kv = parse_kv(out)
    print("\n=== 解析结果（对照 Kotlin 预期）===")
    fields = ["OS", "ARCH", "CPU", "MEM", "DISK", "DOCKER", "DOCKVER", "GIT", "CURL",
              "BASH", "PY", "NODE", "ROOT", "SUDO", "PSUDO", "DOCKERHUB", "WS"]
    for k in fields:
        print(f"  {k:10} = {kv.get(k, '<缺失>')}")

    missing = [k for k in fields if k not in kv]
    print(f"\n[字段完整性] {'全部齐全' if not missing else '缺失: ' + ', '.join(missing)}")

    # 复现 buildPlan 决策
    has_docker = kv.get("DOCKER") == "1"
    is_root = kv.get("ROOT") == "1"
    psudo = kv.get("PSUDO") == "1"
    strategy = "DOCKER" if (has_docker or is_root or psudo) else "NATIVE_FALLBACK"
    print(f"[计划策略] {strategy}")
    actions = ["prereq.dirs"]
    for flag, act in [("CURL", "prereq.curl"), ("GIT", "prereq.git"), ("BASH", "prereq.bash")]:
        if kv.get(flag) != "1": actions.append(act)
    if strategy == "DOCKER":
        if not has_docker: actions.append("docker.install")
        actions += ["docker.image", "docker.run"]
    else:
        actions += ["native.runtime", "native.marker"]
    print("[计划动作] " + " -> ".join(actions))

    if a.apply:
        print("\n=== 执行安装（真实改变服务器）===")
        for act in actions:
            cmd = APPLY_ACTIONS[act].format(image=repr(a.image))
            print(f"\n--- [{act}] ---")
            o, c = run(a, cmd, timeout=300)
            print(o)
            print(f"[退出码] {c}")
    else:
        print("\n（只读模式结束。加 --apply 才会执行安装。）")

if __name__ == "__main__":
    main()
