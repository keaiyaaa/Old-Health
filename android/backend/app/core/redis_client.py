"""Redis 客户端（队列 / 缓存 / 限流 / 验证码共用，00-接口通则 11.4）。"""
import redis

from app.config import get_settings


def get_redis() -> redis.Redis:
    return redis.Redis.from_url(
        get_settings().redis_url, decode_responses=True, socket_timeout=2,
    )
