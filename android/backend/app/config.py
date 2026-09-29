"""全局配置。全部走环境变量，代码不写死密钥（AGENTS.md 6 / README 8.7）。"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="APP_", env_file=".env", extra="ignore")

    environment: str = "dev"  # dev | prod
    # --- 数据库 / Redis ---
    database_url: str = "postgresql+psycopg://cogni:cogni@localhost:5432/cogni"
    redis_url: str = "redis://localhost:6379/0"
    # --- 令牌（README 8.6：access JWT ≤30min；refresh 有状态存 PG） ---
    jwt_secret: str = "dev-only-change-me"
    access_token_ttl_min: int = 30
    refresh_token_ttl_days: int = 90
    # --- 应用层加密（schema 全局约定 6：邮箱 AES-GCM、哈希 HMAC） ---
    aes_key_b64: str = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"  # dev-only 32B base64
    hmac_key_b64: str = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
    # --- 对象存储（境内；dev 用本地目录实现同一接口） ---
    storage_backend: str = "local"  # local | s3
    storage_local_dir: str = "./var/storage"
    s3_endpoint_url: str = ""
    s3_bucket: str = "cogni-audio"
    s3_access_key: str = ""
    s3_secret_key: str = ""
    presign_ttl_sec: int = 900  # 签名 ≤15 分钟（合规硬约束）
    max_audio_bytes: int = 10 * 1024 * 1024
    # --- ASR（C3 未选型，先走 Mock adapter，接口见 integrations/asr.py） ---
    asr_provider: str = "mock"  # mock | http
    asr_http_url: str = ""
    asr_http_key: str = ""
    # --- 功能开关 ---
    research_mode_enabled: bool = False  # API-06：关闭时全域 404
    # --- 限流（00-接口通则 11.2 默认值，可被环境变量覆盖） ---
    ratelimit_read_per_min: int = 300
    ratelimit_write_per_min: int = 60
    ratelimit_auth_per_hour: int = 5
    ratelimit_login_ip_per_hour: int = 20
    ratelimit_bind_per_hour: int = 10
    ratelimit_export_per_hour: int = 5
    ratelimit_research_export_per_day: int = 10

    @property
    def is_prod(self) -> bool:
        return self.environment == "prod"


@lru_cache
def get_settings() -> Settings:
    return Settings()
