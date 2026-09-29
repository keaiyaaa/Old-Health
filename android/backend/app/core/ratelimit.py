"""限流中间件：FastAPI + Redis 滑动窗口（00-接口通则 11.4）。

- 键 = ratelimit:{类别}:{维度}:{标识}
- 维度顺序：账号（已认证）→ IP（公开接口）
- 认证类接口 fail-closed；其余 fail-open
- 响应带 X-RateLimit-* 头
"""
import time

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import get_settings
from app.core.errors import ErrorCode
from app.core.redis_client import get_redis

# 接口类别 → (窗口秒, 限额配置项, fail_closed)
_CATEGORIES: list[tuple[str, str, str, int, bool]] = [
    # (方法前缀, 路径, 类别, 窗口秒, fail_closed)
    ("POST", "/v1/auth/email-code", "auth", 3600, True),
    # 登录按契约走「单 IP 20 次/小时」维度（通则 11.2）；
    # 未登录请求没有账号维度，IP 是唯一把手。App 一次登录建两端会话，消耗 2 次。
    ("POST", "/v1/auth/login", "login", 3600, True),
    ("POST", "/v1/auth/password-reset", "auth", 3600, True),
    ("POST", "/v1/auth/refresh", "auth", 3600, True),
    ("POST", "/v1/subjects", "bind", 3600, True),
]


def _match(path: str, method: str) -> tuple[str, int, bool] | None:
    for m, prefix, category, window, fail_closed in _CATEGORIES:
        if path == prefix and method == m:
            return category, window, fail_closed
    if path.startswith("/v1/export") and method == "POST":
        return "export", 3600, False
    if path.startswith("/v1/research/export") and method == "POST":
        return "research_export", 86400, False
    if method in ("GET",):
        return "read", 60, False
    if method in ("POST", "PATCH", "PUT", "DELETE"):
        return "write", 60, False
    return None


def _limit_for(category: str) -> int:
    s = get_settings()
    return {
        "auth": s.ratelimit_auth_per_hour,
        "login": s.ratelimit_login_ip_per_hour,
        "bind": s.ratelimit_bind_per_hour,
        "export": s.ratelimit_export_per_hour,
        "research_export": s.ratelimit_research_export_per_day,
        "read": s.ratelimit_read_per_min,
        "write": s.ratelimit_write_per_min,
    }.get(category, 60)


class RateLimitMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        match = _match(request.url.path, request.method)
        if match is None:
            return await call_next(request)
        category, window, fail_closed = match
        limit = _limit_for(category)
        # 维度：账号优先（从 Authorization 解析失败不影响），否则 IP
        auth = request.headers.get("Authorization", "")
        identity = auth.removeprefix("Bearer ").strip()[:32] if auth else (
            request.client.host if request.client else "unknown")
        key = f"ratelimit:{category}:{identity}"
        try:
            r = get_redis()
            now = time.time()
            pipe = r.pipeline()
            pipe.zremrangebyscore(key, 0, now - window)
            pipe.zcard(key)
            pipe.zadd(key, {f"{now}": now})
            pipe.expire(key, window)
            _, count, _, _ = pipe.execute()
            remaining = max(0, limit - int(count))
            if int(count) >= limit:
                if fail_closed:
                    return _limited(remaining, int(now + window))
                return _limited(remaining, int(now + window))
            response = await call_next(request)
            response.headers["X-RateLimit-Limit"] = str(limit)
            response.headers["X-RateLimit-Remaining"] = str(remaining)
            return response
        except Exception:
            # Redis 不可用：认证类 fail-closed，其余 fail-open（11.4.3）
            if fail_closed:
                return JSONResponse(
                    status_code=503,
                    content={"code": ErrorCode.SERVICE_UNAVAILABLE,
                             "message": "服务暂时不可用，请稍后再试", "requestId": "-"},
                )
            return await call_next(request)


def _limited(remaining: int, reset_at: int) -> JSONResponse:
    return JSONResponse(
        status_code=429,
        content={"code": ErrorCode.RATE_LIMITED, "message": "操作太频繁了，请稍后再试",
                 "requestId": "-"},
        headers={
            "X-RateLimit-Remaining": str(remaining),
            "X-RateLimit-Reset": str(reset_at),
            "Retry-After": "60",
            "Cache-Control": "no-store",
        },
    )
