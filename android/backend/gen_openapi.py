"""生成 OpenAPI 契约到 docs/api/openapi/openapi.yaml（生成物，禁止手改）。"""
import json
import os
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.main import app  # noqa: E402

spec = app.openapi()
out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "..", "docs", "api", "openapi")
out = os.path.abspath(out_dir)

# JSON 是合法的 YAML 1.2；头部注释标记自动生成（openapi/README.md 3 硬规则 2）
body = json.dumps(spec, ensure_ascii=False, indent=2)
header = (
    "# AUTO-GENERATED — DO NOT EDIT\n"
    f"# 生成时间：{datetime.now(timezone.utc).isoformat()}\n"
    "# 生成方式：python gen_openapi.py（FastAPI app.openapi()）\n"
    "# 修改契约：改后端代码后重新生成，见 docs/api/openapi/README.md\n"
)
with open(os.path.join(out, "openapi.yaml"), "w", encoding="utf-8", newline="\n") as f:
    f.write(header + body)

marker = (
    f"生成命令：python gen_openapi.py\n"
    f"生成时间：{datetime.now(timezone.utc).isoformat()}\n"
)
with open(os.path.join(out, ".generated"), "w", encoding="utf-8", newline="\n") as f:
    f.write(marker)

# 合规自检：禁字段不得出现在契约里（openapi/README.md 4 第 5 步）
banned = ["zScore", "mean", "stdDev", "probability", "riskLevel",
          "mocaB", "mmse", "fluencyScore", "interpretation", "conclusion", "diagnosis"]
hits = [b for b in banned if b in body]
print("openapi.yaml written; banned-field hits:", hits or "none")
sys.exit(1 if hits else 0)
