"""端到端冒烟测试：注册 → 登录 → 绑定/创建老人 → 选人 → 同意 → 上传 → 登记 → 状态 → 导出。

运行前提：postgres/redis 已起、alembic 已迁移、uvicorn 已启动。
"""
import hashlib
import os
import struct
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx

BASE = os.environ.get("BASE", "http://127.0.0.1:8000")
c = httpx.Client(base_url=BASE, timeout=30)

fails = []

# 清理限流键（登录限流默认 5 次/小时，冒烟测试会连发多次登录）
import redis as _redis

_rl = _redis.Redis.from_url("redis://localhost:6379/0", decode_responses=True)
for k in _rl.scan_iter("ratelimit:*"):
    _rl.delete(k)


def check(name, cond, extra=""):
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name} {extra if not cond else ''}")
    if not cond:
        fails.append(name)


def make_wav(path, seconds=12, sr=16000):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        import math
        for i in range(seconds * sr):
            v = int(8000 * math.sin(i / 20) * (1 if (i // (sr // 2)) % 2 else 0.05))
            w.writeframes(struct.pack("<h", v))


# 1. 注册（随机账号，支持重复运行）
import secrets as _secrets

_suffix = _secrets.token_hex(4)
_email = f"smoke_{_suffix}@test.com"
_username = f"smoke_user_{_suffix}"
r = c.post("/v1/auth/email-code", json={"email": _email, "scene": "REGISTER"})
check("1.1 email-code", r.status_code == 200, r.text)
code = None
# dev 模式验证码打在日志里；从日志拿不到则用固定测试后门？——无后门。读 uvicorn 日志困难，
# 因此用 Redis 直接读验证码：与 service 相同的键规则
import redis as redis_lib

rr = redis_lib.Redis.from_url("redis://localhost:6379/0", decode_responses=True)
from app.core.security import hmac_hash

eh = hmac_hash(_email).hex()
raw = rr.get(f"email_code:REGISTER:{eh}")
check("1.2 code in redis", raw is not None)
import hashlib as _h

code = None
# 哈希存储不可逆 → 测试通过枚举 6 位数字不可行；改为直接造码
# （生产不发码，测试环境用 setex 覆盖已知值）
rr.setex(f"email_code:REGISTER:{eh}", 300, _h.sha256(b"123456").hexdigest())

r = c.post("/v1/auth/register", json={
    "username": _username, "email": _email, "code": "123456",
    "password": "password123", "gender": "MALE"})
check("1.3 register", r.status_code == 201, r.text)
tok = r.json()
access = tok.get("accessToken")
check("1.4 tokens", bool(access))
H = {"Authorization": f"Bearer {access}"}
c.headers.update(H)

# 2. 老人端登录（同一账号 + ELDER_APP client）
rr.setex(f"email_code:REGISTER:{eh}", 300, _h.sha256(b"123456").hexdigest())
r = c.post("/v1/auth/login", json={
    "account": _username, "password": "password123", "client": "FAMILY_APP",
    "deviceId": "d_family"})
check("2.1 family login", r.status_code == 200, r.text)
r = c.post("/v1/auth/login", json={
    "account": _username, "password": "password123", "client": "ELDER_APP",
    "deviceId": "d_elder"})
check("2.2 elder login", r.status_code == 200, r.text)
elder_access = r.json()["accessToken"]
elder_c = httpx.Client(base_url=BASE, timeout=30,
                       headers={"Authorization": f"Bearer {elder_access}"})

# 3. 家属创建老人（系统生成绑定码）
import uuid as _u

r = c.post("/v1/subjects", headers={"Idempotency-Key": str(_u.uuid4())}, json={
    "elderName": f"测试老人{_suffix}", "ageBand": "70-74", "hearingIssue": False})
check("3.1 create subject", r.status_code == 201, r.text)
subj = r.json()
subject_id = subj.get("subjectId")
check("3.2 bindCode once", subj.get("matched") is False and bool(subj.get("bindCode")))

# 4. 老人端选人 + 授同意
r = elder_c.post("/v1/session/subject", json={"subjectId": subject_id})
check("4.1 lock subject", r.status_code == 200, r.text)
r = elder_c.post("/v1/consents", headers={"Idempotency-Key": str(_u.uuid4())},
                 json={"type": "SENSITIVE", "version": "1.0"})
check("4.2 grant SENSITIVE (elder)", r.status_code == 201, r.text)
r = c.post("/v1/consents", headers={"Idempotency-Key": str(_u.uuid4())},
           json={"type": "SENSITIVE", "version": "1.0"})
check("4.3 family grant SENSITIVE -> 404", r.status_code == 404, str(r.status_code))
r = c.post("/v1/consents", headers={"Idempotency-Key": str(_u.uuid4())},
           json={"type": "PRODUCT", "version": "1.0"})
check("4.4 grant PRODUCT -> 404", r.status_code == 404, str(r.status_code))

# 5. 上传音频（家属端代录）
wav_path = os.path.join(os.environ.get("TEMP", "."), "smoke.wav")
make_wav(wav_path)
r = c.post("/v1/records/upload-url", json={
    "subjectId": subject_id, "contentType": "audio/wav",
    "sizeBytes": os.path.getsize(wav_path), "durationSec": 12,
    "taskType": "DAILY_FREE"})
check("5.1 upload-url", r.status_code == 200, r.text)
up = r.json()
audio = open(wav_path, "rb").read()
r = c.put(up["uploadUrl"], content=audio,
          headers={"Content-Type": "audio/wav"})
check("5.2 PUT audio", r.status_code == 200, str(r.status_code))
sha = hashlib.sha256(audio).hexdigest()

# 6. 登记记录（幂等）
idem = str(_u.uuid4())
r = c.post("/v1/records", headers={"Idempotency-Key": idem}, json={
    "sessionId": up["sessionId"], "objectKey": up["objectKey"], "sha256": sha,
    "subjectId": subject_id, "recorderRole": "FAMILY_PROXY", "taskType": "DAILY_FREE",
    "durationSec": 12, "sampleRate": 16000, "actualStartedAt": 1790000000000})
check("6.1 register record", r.status_code == 201, r.text)
rec = r.json()
record_id = rec.get("recordId")
r2 = c.post("/v1/records", headers={"Idempotency-Key": idem}, json={
    "sessionId": up["sessionId"], "objectKey": up["objectKey"], "sha256": sha,
    "subjectId": subject_id, "recorderRole": "FAMILY_PROXY", "taskType": "DAILY_FREE",
    "durationSec": 12, "sampleRate": 16000, "actualStartedAt": 1790000000000})
check("6.2 idempotent replay", r2.status_code == 201 and r2.json()["recordId"] == record_id,
      r2.text)

# 7. 分析（同步调用 worker 函数，绕过队列验证全链路）
from app.worker.tasks import process_analysis

res = process_analysis.apply(args=[record_id.split("_")[-1]]).get()
check("7.1 analysis done", res.get("result") == "done", str(res))
r = c.get(f"/v1/records/{record_id}/status")
check("7.2 status DONE", r.json().get("analysisState") == "DONE", r.text)

# 8. 指标与状态（家属端）
r = c.get(f"/v1/metrics/{subject_id}/series?key=PAUSE&range=30d")
check("8.1 series", r.status_code == 200 and "points" in r.json(), r.text)
r = c.get(f"/v1/metrics/{subject_id}/baseline")
check("8.2 baseline", r.status_code == 200, r.text)
r = c.get(f"/v1/status/{subject_id}")
check("8.3 status", r.status_code == 200 and r.json().get("copyKey"), r.text)
r = elder_c.get(f"/v1/metrics/{subject_id}/series?key=PAUSE")
check("8.4 elder metrics -> 404 (B2)", r.status_code == 404, str(r.status_code))
r = c.get("/v1/metrics/plain/series?key=PAUSE")
check("8.5 /plain removed", r.status_code == 404, str(r.status_code))

# 9. 记录列表 / 详情 / 音频地址
r = c.get(f"/v1/records?subjectId={subject_id}")
check("9.1 records list", r.status_code == 200 and len(r.json()["items"]) == 1, r.text)
r = c.get(f"/v1/records/{record_id}")
check("9.2 detail", r.status_code == 200 and r.json().get("transcript"), r.text)
r = c.get(f"/v1/records/{record_id}/audio-url")
check("9.3 audio-url", r.status_code == 200 and r.json().get("playUrl"), r.text)

# 10. 合规契约扫描：禁字段不得出现在任何响应
import json as _json

banned = ["zScore", "z_score", "mean", "stdDev", "stddev", "variance", "probability",
          "prob", "score", "riskLevel", "risk", "mocaB", "moca", "mmse", "ad8",
          "fluencyScore", "clinical", "interpretation", "conclusion", "diagnosis",
          "assessment", "abnormal", "normal", "severity"]
for path, q in [
    (f"/v1/metrics/{subject_id}/series?key=PAUSE", {}),
    (f"/v1/metrics/{subject_id}/baseline", {}),
    (f"/v1/status/{subject_id}", {}),
    (f"/v1/records?subjectId={subject_id}", {}),
    (f"/v1/records/{record_id}", {}),
    ("/v1/consents", {}),
    ("/v1/accounts/me", {}),
]:
    resp = c.get(path, params=q)
    text = resp.text
    hits = [b for b in banned if f'"{b}"' in text]
    check(f"10.x contract scan {path}", not hits, str(hits))

# 11. 通知
r = c.get("/v1/notifications")
check("11.1 notifications", r.status_code == 200, r.text)
items = r.json().get("items", [])
if items:
    r = c.post(f"/v1/notifications/{items[0]['notificationId']}/read")
    check("11.2 mark read", r.status_code == 200, r.text)

# 12. 导出（OUTBOX 未消费 → 同步调 worker）
r = c.post("/v1/export", headers={"Idempotency-Key": str(_u.uuid4())},
           json={"subjectId": subject_id, "scope": "FULL"})
check("12.1 export 202", r.status_code == 202, r.text)
job_id = r.json()["jobId"]
from app.worker.tasks import generate_export

res = generate_export.apply(args=[job_id.split("_")[-1]]).get()
check("12.2 export ready", res.get("result") == "ready", str(res))
r = c.get(f"/v1/export/{job_id}")
check("12.3 export READY", r.json().get("state") == "READY", r.text)

# 13. 越权 404：未绑定账号访问
r2 = c.post("/v1/auth/login", json={
    "account": "smoke_user", "password": "password123", "client": "FAMILY_APP",
    "deviceId": "d_other"})
# 未注册第二账号，跳过：改测假 subjectId
r = c.get("/v1/status/sub_00000000-0000-0000-0000-000000000000")
check("13.1 fake subject -> 404", r.status_code == 404, str(r.status_code))

# 14. 注销冷静期（注销会吊销会话 → 重新登录后撤销）
r = c.request("DELETE", "/v1/accounts/me", json={"confirmText": "注销"})
check("14.1 delete account 202", r.status_code == 202, r.text)
r = c.post("/v1/auth/login", json={
    "account": _username, "password": "password123", "client": "FAMILY_APP",
    "deviceId": "d_family"})
check("14.2 relogin while deleting", r.status_code == 200
      and r.json().get("accountDeleting") is True, r.text)
c.headers.update({"Authorization": f"Bearer {r.json()['accessToken']}"})
r = c.post("/v1/accounts/me/cancel-deletion")
check("14.3 cancel deletion", r.status_code == 200 and r.json().get("state") == "ACTIVE",
      r.text)

print()
if fails:
    print("FAILED:", len(fails), fails)
    sys.exit(1)
print("ALL PASS")
