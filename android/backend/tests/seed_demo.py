"""给测试账号灌演示数据。

用法（需 uvicorn 已启动）：
    python tests\\seed_demo.py --username demo --password demo123456 --days 14

内容：注册/登录账号 → 创建老人 → 生成 N 天录音（每天时长/停顿略有变化）→
走真实「预签名直传 → 登记 → 分析流水线」，指标、基线、状态全部真实计算。
绑定码会打印出来：家属端「添加老人」同名无法重复创建，如需用另一个账号看
同一份数据，用「称呼 + 绑定码」绑定即可。
"""
import argparse
import hashlib
import math
import os
import secrets
import struct
import sys
import time
import uuid
import wave

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx
import redis as redis_lib

from app.core.security import hmac_hash

BASE = os.environ.get("BASE", "http://127.0.0.1:8000")
c = httpx.Client(base_url=BASE, timeout=60)


def make_wav(path, seconds=32, sr=16000, seed=0):
    """语气自然些的合成音：字 bursts + 长短不一的停顿。seed 控制每天的差异。"""
    rnd = secrets.SystemRandom(seed)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        pos = 0
        phase = 0.0
        while pos < seconds * sr:
            burst = rnd.randint(int(0.8 * sr), int(2.2 * sr))   # 说话 0.8–2.2s
            gap = rnd.randint(int(0.3 * sr), int(1.4 * sr))     # 停顿 0.3–1.4s
            freq = rnd.choice([140, 165, 180, 200])
            for _ in range(min(burst, seconds * sr - pos)):
                v = int(7000 * math.sin(phase) * (0.7 + 0.3 * math.sin(phase / 9)))
                w.writeframes(struct.pack("<h", v))
                phase += 2 * math.pi * freq / sr
                pos += 1
            for _ in range(min(gap, seconds * sr - pos)):
                w.writeframes(struct.pack("<h", 0))
                pos += 1


def register_or_login(username, password, email=None):
    # 测试机常跑多次：先清登录限流键（仅本地开发库）
    rr = redis_lib.Redis.from_url("redis://localhost:6379/0", decode_responses=True)
    for k in rr.scan_iter("ratelimit:*"):
        rr.delete(k)
    # 先试登录
    r = c.post("/v1/auth/login", json={
        "account": username, "password": password, "client": "FAMILY_APP",
        "deviceId": "seed"})
    if r.status_code == 200:
        print(f"[OK] 已有账号 {username}，直接登录")
        c.headers["Authorization"] = f"Bearer {r.json()['accessToken']}"
        return
    # 注册（dev 模式：验证码写进 Redis）
    email = email or f"{username}@demo.local"
    rr.setex(f"email_code:REGISTER:{hmac_hash(email).hex()}", 300,
             hashlib.sha256(b"123456").hexdigest())
    r = c.post("/v1/auth/register", json={
        "username": username, "email": email, "code": "123456",
        "password": password, "gender": "FEMALE"})
    assert r.status_code == 201, r.text
    c.headers["Authorization"] = f"Bearer {r.json()['accessToken']}"
    print(f"[OK] 已注册账号 {username}")


def grant_sensitive_as_elder(username, password):
    """上传需要敏感同意，且仅老人端会话可授（合规：单独同意）。
    同一账号再开一个 ELDER_APP 会话完成授予，模拟真机上老人端 P-O2 的动作。"""
    elder = httpx.Client(base_url=BASE, timeout=60)
    r = elder.post("/v1/auth/login", json={
        "account": username, "password": password, "client": "ELDER_APP",
        "deviceId": "seed-elder"})
    assert r.status_code == 200, r.text
    elder.headers["Authorization"] = f"Bearer {r.json()['accessToken']}"
    r = elder.post("/v1/consents", headers={"Idempotency-Key": str(uuid.uuid4())},
                   json={"type": "SENSITIVE", "version": "1.0"})
    assert r.status_code == 201, r.text
    elder.close()


def create_subject(elder_name):
    r = c.post("/v1/subjects", headers={"Idempotency-Key": str(uuid.uuid4())},
               json={"elderName": elder_name, "ageBand": "70-74",
                     "hearingIssue": False})
    if r.status_code == 409:
        # 同名已存在（之前灌过）：直接用已有老人的第一位
        items = c.get("/v1/subjects").json()["items"]
        print(f"[OK] 老人「{elder_name}」已存在，复用第一条")
        return items[0]["subjectId"], None
    assert r.status_code == 201, r.text
    j = r.json()
    return j["subjectId"], j.get("bindCode")


def seed_records(subject_id, days):
    from app.worker.tasks import process_analysis

    tmp = os.path.join(os.environ.get("TEMP", "."), f"seed_{secrets.token_hex(4)}.wav")
    ok = fail = 0
    for i in range(days):
        days_ago = days - i - 1          # 最早的一天在前
        started_ms = int((time.time() - days_ago * 86400) * 1000)
        seconds = 30 + (i * 7) % 21      # 30–50s 变化
        make_wav(tmp, seconds=seconds, seed=i * 977 + 13)
        audio = open(tmp, "rb").read()
        sha = hashlib.sha256(audio).hexdigest()
        r = c.post("/v1/records/upload-url", json={
            "subjectId": subject_id, "contentType": "audio/wav",
            "sizeBytes": len(audio), "durationSec": seconds,
            "taskType": "DAILY_FREE"})
        assert r.status_code == 200, r.text
        up = r.json()
        r = c.put(up["uploadUrl"], content=audio, headers={"Content-Type": "audio/wav"})
        assert r.status_code == 200, f"上传失败 {r.status_code}"
        r = c.post("/v1/records", headers={"Idempotency-Key": str(uuid.uuid4())}, json={
            "sessionId": up["sessionId"], "objectKey": up["objectKey"], "sha256": sha,
            "subjectId": subject_id, "recorderRole": "FAMILY_PROXY",
            "taskType": "DAILY_FREE", "durationSec": seconds, "sampleRate": 16000,
            "actualStartedAt": started_ms})
        assert r.status_code == 201, r.text
        record_id = r.json()["recordId"]
        res = process_analysis.apply(args=[record_id.split("_")[-1]]).get()
        if res.get("result") == "done":
            ok += 1
        else:
            fail += 1
        print(f"  第 {i + 1}/{days} 天：{res.get('result')} ({seconds}s)")
    os.remove(tmp)
    print(f"[OK] 分析完成：成功 {ok}，失败 {fail}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--username", default="demo")
    ap.add_argument("--password", default="demo123456")
    ap.add_argument("--email", default=None)
    ap.add_argument("--elder-name", default=None)
    ap.add_argument("--days", type=int, default=14)
    args = ap.parse_args()

    elder_name = args.elder_name or f"王奶奶{secrets.token_hex(2)}"
    register_or_login(args.username, args.password, args.email)
    grant_sensitive_as_elder(args.username, args.password)
    subject_id, bind_code = create_subject(elder_name)
    print(f"[OK] 老人：{elder_name}（subjectId={subject_id}）")
    if bind_code:
        print(f"[绑定码] {bind_code}（24h 内有效：其他账号可用「称呼+绑定码」绑到这份数据）")
    seed_records(subject_id, args.days)

    # 验证
    st = c.get(f"/v1/status/{subject_id}").json()
    n = len(c.get(f"/v1/records?subjectId={subject_id}&limit=100").json()["items"])
    print(f"[验证] 记录数={n}，状态={st.get('statusCode')}（{st.get('copyKey')}）")


if __name__ == "__main__":
    main()
