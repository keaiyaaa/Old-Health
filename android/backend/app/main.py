"""FastAPI 应用入口。

- /v1 前缀版本化（契约原则 8）
- 统一错误模型、requestId、安全响应头（00-接口通则 6/12）
- 越权 404；CONSENT_REQUIRED 唯一 403
"""
import time

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.middleware.base import BaseHTTPMiddleware

from app.core.errors import AppError, ErrorCode, app_error_handler, unhandled_error_handler
from app.core.request_context import new_request_id, set_request_id
from app.core.ratelimit import RateLimitMiddleware
from app.modules.lifecycle.router import router as lifecycle_router
from app.modules.metrics.router import router as metrics_router
from app.modules.notifications.router import router as notifications_router
from app.modules.records.router import router as records_router
from app.modules.research.router import router as research_router


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        rid = request.headers.get("X-Request-Id") or new_request_id()
        set_request_id(rid)
        start = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Request-Id"] = rid
        # 安全响应头（网关层兜底，应用层同样设置）
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault("Cache-Control", "no-store")
        import logging

        logging.getLogger("app.access").info(
            "%s %s -> %s %.1fms", request.method, request.url.path,
            response.status_code, (time.perf_counter() - start) * 1000,
        )
        return response


def create_app() -> FastAPI:
    app = FastAPI(
        title="家庭认知健康日记 API",
        version="1.0",
        docs_url="/docs" if not _prod() else None,
        openapi_url="/openapi.json" if not _prod() else None,  # openapi 供 CI 生成产物
    )
    app.add_middleware(RequestContextMiddleware)
    app.add_middleware(RateLimitMiddleware)

    app.add_exception_handler(AppError, app_error_handler)
    app.add_exception_handler(Exception, unhandled_error_handler)

    async def validation_handler(_: Request, exc: RequestValidationError):
        raise AppError(ErrorCode.INVALID_PARAM, details=[
            {"field": ".".join(str(x) for x in e["loc"][1:]), "reason": e["msg"]}
            for e in exc.errors()
        ])

    app.add_exception_handler(RequestValidationError, validation_handler)

    for router in (
        auth_router(), consents_router(),
        records_router, metrics_router,
        lifecycle_router, research_router, notifications_router,
    ):
        app.include_router(router, prefix="/v1")

    # 开发用对象存储直传端点（生产不注册，音频只走境内云厂商）
    from app.config import get_settings

    if get_settings().storage_backend == "local" and not get_settings().is_prod:
        from app.modules.devstorage import router as dev_storage_router

        app.include_router(dev_storage_router, prefix="/v1")

    @app.get("/healthz", include_in_schema=False)
    def healthz():
        return {"ok": True}

    return app


def _prod() -> bool:
    from app.config import get_settings

    return get_settings().is_prod


def auth_router():
    from app.modules.auth.router import router

    return router


def consents_router():
    from app.modules.consents.router import router

    return router


app = create_app()
