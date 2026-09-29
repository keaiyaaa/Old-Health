"""数据库会话。一期用同步引擎（FastAPI 线程池执行），模块边界在代码包上体现。

engine 惰性创建：导入应用不要求 DB 可达（便于 CI/单测导入路由）。
"""
import threading
from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import get_settings

_engine: Engine | None = None
_lock = threading.Lock()


def get_engine() -> Engine:
    global _engine
    if _engine is None:
        with _lock:
            if _engine is None:
                _engine = create_engine(
                    get_settings().database_url, pool_pre_ping=True, future=True)
    return _engine


def _new_session_factory() -> sessionmaker:
    return sessionmaker(bind=get_engine(), autoflush=False, expire_on_commit=False)


def get_db() -> Generator[Session, None, None]:
    db = _new_session_factory()()
    try:
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()
