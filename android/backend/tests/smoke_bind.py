"""补充冒烟：家属创建老人 → 另一账号凭称呼+绑定码绑定（API-01 1.9 matched 路径）。"""
import hashlib
import os
import secrets
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx
import redis as redis_lib

from app.core.security import hmac_hash

BASE = "http://127.0.0.1:8000"
c = httpx.Client(base_url=BASE, timeout=30)
rr = redis_lib.Redis.from_url("redis://localhost:6379/0", decode_responses=True)
for k in rr.scan_iter("ratelimit:*"):
    rr.delete(k)


def register_and_login(tag):
    email = f"bind_{tag}@test.com"
    user = f"bind_user_{tag}"
    rr.setex(f"email_code:REGISTER:{hmac_hash(email).hex()}", 300,
             hashlib.sha256(b"123456").hexdigest())
    r = c.post("/v1/auth/register", json={
        "username": user, "email": email, "code": "123456",
        "password": "password123", "gender": "FEMALE"})
    assert r.status_code == 201, r.text
    r = c.post("/v1/auth/login", json={
        "account": user, "password": "password123", "client": "FAMILY_APP",
        "deviceId": "d1"})
    c.headers["Authorization"] = f"Bearer {r.json()['accessToken']}"
    return r.json()["accountId"]


suffix = secrets.token_hex(4)

# 1. 家属 A 创建老人，拿绑定码
register_and_login("a" + suffix)
elder_name = f"刘大爷{suffix}"
r = c.post("/v1/subjects", headers={"Idempotency-Key": secrets.token_hex(16)},
           json={"elderName": elder_name, "ageBand": "75-79", "hearingIssue": False})
assert r.status_code == 201, r.text
code = r.json()["bindCode"]
subject_a = r.json()["subjectId"]
assert code, "新建必须返回绑定码"
print("[PASS] 1. family create -> bindCode", code)

# 2. 重复创建同名（无码）→ 不允许：返回 409/错误
r = c.post("/v1/subjects", headers={"Idempotency-Key": secrets.token_hex(16)},
           json={"elderName": elder_name, "ageBand": "75-79", "hearingIssue": False})
assert r.status_code in (404, 409), f"expected 404/409 got {r.status_code}"
print("[PASS] 2. duplicate name without code ->", r.status_code)

# 3. 家属 B 用「称呼 + 绑定码」绑定到同一位老人
c.headers.clear()
register_and_login("b" + suffix)
r = c.post("/v1/subjects", headers={"Idempotency-Key": secrets.token_hex(16)},
           json={"elderName": elder_name, "ageBand": "75-79", "hearingIssue": False,
                 "bindCode": code})
assert r.status_code == 201, r.text
j = r.json()
assert j["matched"] is True, j
assert j["subjectId"] == subject_a, j
assert j.get("bindCode") in (None, ""), "匹配路径不得再发新码"
print("[PASS] 3. bind by name+code -> matched, same subject")

# 4. 错误绑定码 → 不允许
c.headers.clear()
register_and_login("c" + suffix)
r = c.post("/v1/subjects", headers={"Idempotency-Key": secrets.token_hex(16)},
           json={"elderName": elder_name, "ageBand": "75-79", "hearingIssue": False,
                 "bindCode": "WRONG1"})
assert r.status_code == 400, f"expected 400 got {r.status_code}"  # BIND_CODE_INVALID，防枚举
print("[PASS] 4. wrong bindCode ->", r.status_code)

print("ALL PASS")
