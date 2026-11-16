#!/usr/bin/env python3
"""用真实模型完整测试 Anthropic 工具链路（凭据只走环境变量，不落盘）。

被测端点为 Anthropic Messages 协议兼容实现，请求/响应格式与项目
AnthropicAdapter 完全一致（tools={name,description,input_schema}、
content block、SSE input_json_delta 等）。

四层验证：
  L1 纯文本对话        —— 端点/认证/模型名
  L2 tool_use 单轮      —— 工具定义被正确解析、返回 tool_use block
  L3 完整工具循环      —— 模型发 tool_use → 真实云端沙箱执行 → tool_result 回传 → 最终答案
  L4 流式(含多轮回传)   —— 项目实际使用的 streamMessage 路径，SSE 事件与 thinking block 回传兼容

用法：
  XFKEY='xxx' SSHPASS='服务器密码' python3 test_llm_toolchain.py
  如只测模型链路不测沙箱执行，不设 SSHPASS 即可（L3 会用模拟输出）。
"""
import json, os, subprocess, sys, urllib.request

KEY = os.environ.get("XFKEY")
if not KEY:
    sys.exit("需要 XFKEY 环境变量")
API = os.environ.get("LLM_API", "https://maas-api.cn-huabei-1.xf-yun.com/anthropic/v1/messages")
MODEL = os.environ.get("LLM_MODEL", "spark-x2.6")
SSH_HOST = os.environ.get("SSH_HOST", "root@154.89.153.169")
SANDBOX_CONTAINER = os.environ.get("SANDBOX_CONTAINER", "minimecode-ai")
TOOLS = [{
    "name": "execute_command",
    "description": "在云端沙箱环境中执行 shell 命令并返回输出。工作目录为 ~/workspace。",
    "input_schema": {"type": "object", "properties": {
        "command": {"type": "string", "description": "要执行的 shell 命令"}
    }, "required": ["command"]}
}]
HEADERS = {"x-api-key": KEY, "anthropic-version": "2023-06-01", "content-type": "application/json"}


def call(messages, tools=None, max_tokens=1024, stream=False):
    body = {"model": MODEL, "max_tokens": max_tokens, "messages": messages}
    if tools: body["tools"] = tools
    if stream: body["stream"] = True
    req = urllib.request.Request(API, data=json.dumps(body).encode(), headers=HEADERS)
    with urllib.request.urlopen(req, timeout=120) as r:
        raw = r.read().decode()
    if stream:
        events = []
        for line in raw.splitlines():
            if line.startswith("data: "):
                try: events.append(json.loads(line[6:]))
                except Exception: pass
        return events
    return json.loads(raw)


def run_in_sandbox(command):
    if not os.environ.get("SSHPASS"):
        return "(模拟输出) mock-result"
    ssh = subprocess.run([
        "sshpass", "-e", "ssh", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15",
        "-p", "22", SSH_HOST,
        "docker exec -i " + SANDBOX_CONTAINER + " /bin/bash -lc " + repr(command)
    ], capture_output=True, text=True, timeout=60,
        env={**os.environ, "SSHPASS": os.environ["SSHPASS"]})
    return (ssh.stdout + ssh.stderr).strip() or "(无输出)"


def l1_plain():
    print("\n[L1] 纯文本对话")
    r = call([{"role": "user", "content": "只回复四个字：链路正常"}], max_tokens=128)
    text = "".join(b.get("text", "") for b in r["content"] if b["type"] == "text")
    blocks = [b["type"] for b in r["content"]]
    print(f"  reply={text!r}  blocks={blocks}  stop={r.get('stop_reason')}")
    return text.strip() == "链路正常"


def l2_tool():
    print("\n[L2] tool_use 单轮")
    r = call([{"role": "user", "content": "用 execute_command 运行 pwd 命令"}], tools=TOOLS)
    tus = [b for b in r["content"] if b["type"] == "tool_use"]
    ok = bool(tus) and tus[0]["name"] == "execute_command" and "command" in tus[0]["input"]
    print(f"  stop={r.get('stop_reason')}  tool_use={[(t['name'], t['input']) for t in tus]}")
    return ok


def l3_loop():
    print("\n[L3] 完整工具循环（模型→沙箱执行→回传→答案）")
    messages = [{"role": "user", "content": "执行 pwd 和 python3 --version，告诉我目录和 Python 版本"}]
    for i in range(5):
        r = call(messages, tools=TOOLS)
        messages.append({"role": "assistant", "content": r["content"]})
        tus = [b for b in r["content"] if b["type"] == "tool_use"]
        if not tus:
            text = "".join(b.get("text", "") for b in r["content"] if b["type"] == "text")
            print(f"  第{i+1}轮 最终答案: {text[:120]!r}")
            return True
        results = []
        for t in tus:
            cmd = t["input"].get("command", "")
            out = run_in_sandbox(cmd)
            print(f"  第{i+1}轮 工具 {cmd!r} → 沙箱: {out!r}")
            results.append({"type": "tool_result", "tool_use_id": t["id"], "content": out})
        messages.append({"role": "user", "content": results})
    return False


def l4_stream():
    print("\n[L4] 流式 tool-calling（SSE input_json_delta）")
    events = call([{"role": "user", "content": "用 execute_command 运行 echo STREAM_OK"}],
                  tools=TOOLS, stream=True)
    partial = "".join(e["delta"].get("partial_json", "") for e in events
                      if e.get("type") == "content_block_delta" and e["delta"].get("type") == "input_json_delta")
    start = [e["content_block"] for e in events if e.get("type") == "content_block_start"]
    has_tool = any(b.get("type") == "tool_use" for b in start)
    try:
        parsed = json.loads(partial)
    except Exception:
        parsed = None
    sig_events = sum(1 for e in events if e.get("type") == "content_block_delta"
                     and e["delta"].get("type") == "signature_delta")
    print(f"  content_block_start={[b.get('type') for b in start]}")
    print(f"  input_json_delta 拼接={partial!r} → 解析={parsed}")
    print(f"  signature_delta 事件数={sig_events}（0 表示端点不发签名，回传 thinking 不带签名即可）")
    return has_tool and parsed is not None and parsed.get("command") == "echo STREAM_OK"


def l4_multi_turn_stream():
    print("\n[L4b] 多轮回传 thinking+tool_use+tool_result（流式）")
    events = call([
        {"role": "user", "content": "运行 echo ROUND2"},
        {"role": "assistant", "content": [
            {"type": "thinking", "thinking": "用户要我运行命令"},
            {"type": "tool_use", "id": "call_prev123", "name": "execute_command", "input": {"command": "echo ROUND2"}}]},
        {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "call_prev123", "content": "ROUND2"}]},
    ], tools=TOOLS, stream=True)
    text = "".join(e["delta"].get("text", "") for e in events
                   if e.get("type") == "content_block_delta" and e["delta"].get("type") == "text_delta")
    errs = [e for e in events if e.get("type") == "error"]
    print(f"  final_text={text[:120]!r}  errors={len(errs)}")
    return not errs and bool(text.strip())


def main():
    print("=" * 60)
    print(f"真实模型工具链路验证  model={MODEL}  endpoint={API}")
    print("=" * 60)
    checks = [
        ("L1 纯文本对话", l1_plain),
        ("L2 tool_use 单轮", l2_tool),
        ("L3 完整工具循环", l3_loop),
        ("L4 流式 tool-calling", l4_stream),
        ("L4b 多轮回传流式", l4_multi_turn_stream),
    ]
    results = []
    for name, fn in checks:
        try:
            ok = fn()
        except Exception as e:
            ok = False
            print(f"  ❌ 异常: {e}")
        results.append((name, ok))
        print(f"  {'✅ 通过' if ok else '❌ 失败'}")
    print("\n" + "=" * 60)
    print("汇总")
    for name, ok in results:
        print(f"  {'✅' if ok else '❌'} {name}")
    print("=" * 60)
    sys.exit(0 if all(ok for _, ok in results) else 1)


if __name__ == "__main__":
    main()
