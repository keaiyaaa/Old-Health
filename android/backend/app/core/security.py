"""密码哈希（Argon2id）、JWT 签发校验、邮箱加密与哈希。

schema 全局约定 6：
- 邮箱原文：应用层 AES-GCM 加密存储（email_encrypted）
- 邮箱匹配键：HMAC-SHA256（email_hash）
- pushToken：AES-GCM
"""
import base64
import hashlib
import hmac
import uuid
from datetime import datetime, timedelta, timezone

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.config import get_settings

_hasher = PasswordHasher()


def hash_password(plain: str) -> str:
    return _hasher.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    try:
        return _hasher.verify(hashed, plain)
    except VerifyMismatchError:
        return False
    except Exception:
        return False


def check_password_strength(plain: str) -> bool:
    # API-01 1.2：长度 ≥8
    return len(plain) >= 8


# ---------- AES-GCM ----------

def _aes() -> AESGCM:
    key = base64.b64decode(get_settings().aes_key_b64)
    return AESGCM(key)


def aes_encrypt(plaintext: str) -> bytes:
    nonce = uuid.uuid4().bytes[:12]  # AES-GCM 推荐 12B nonce
    return nonce + _aes().encrypt(nonce, plaintext.encode("utf-8"), None)


def aes_decrypt(blob: bytes) -> str:
    nonce, ct = blob[:12], blob[12:]
    return _aes().decrypt(nonce, ct, None).decode("utf-8")


# ---------- HMAC（登录匹配键，不可逆） ----------

def hmac_hash(value: str) -> bytes:
    key = base64.b64decode(get_settings().hmac_key_b64)
    return hmac.new(key, value.strip().lower().encode("utf-8"), hashlib.sha256).digest()


def mask_email(email: str) -> str:
    """合规：邮箱以 use***@qq.com 形式返回（00-接口通则 13.4）。"""
    local, _, domain = email.partition("@")
    if len(local) <= 3:
        return f"{local[:1]}***@{domain}"
    return f"{local[:3]}***@{domain}"


# ---------- JWT（access token，无状态） ----------

def issue_access_token(account_id: str, session_id: str) -> tuple[str, int]:
    settings = get_settings()
    ttl = settings.access_token_ttl_min * 60
    now = datetime.now(timezone.utc)
    token = jwt.encode(
        {
            "sub": account_id,
            "sid": session_id,
            "iat": int(now.timestamp()),
            "exp": int((now + timedelta(seconds=ttl)).timestamp()),
        },
        settings.jwt_secret,
        algorithm="HS256",
    )
    return token, int((now + timedelta(seconds=ttl)).timestamp() * 1000)


def decode_access_token(token: str) -> dict:
    settings = get_settings()
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError:
        from app.core.errors import AppError, ErrorCode

        raise AppError(ErrorCode.TOKEN_EXPIRED)
    except jwt.PyJWTError:
        from app.core.errors import AppError, ErrorCode

        raise AppError(ErrorCode.TOKEN_INVALID)
    return payload
