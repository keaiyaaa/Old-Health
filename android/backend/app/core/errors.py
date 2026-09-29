"""统一错误模型（00-接口通则 6.2/7）。

硬规则：
- code 是稳定枚举，只能新增不能改语义
- message 面向用户，不含堆栈/SQL/内部路径
- 越权一律 404（唯一例外：同意未授予 403）
"""
from fastapi import Request
from fastapi.responses import JSONResponse

from app.core.request_context import get_request_id


class ErrorCode:
    # 通用
    INVALID_PARAM = "INVALID_PARAM"
    TOKEN_EXPIRED = "TOKEN_EXPIRED"
    TOKEN_INVALID = "TOKEN_INVALID"
    REFRESH_INVALID = "REFRESH_INVALID"
    CONSENT_REQUIRED = "CONSENT_REQUIRED"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    RATE_LIMITED = "RATE_LIMITED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    # 认证与账号
    EMAIL_TAKEN = "EMAIL_TAKEN"
    USERNAME_TAKEN = "USERNAME_TAKEN"
    PASSWORD_WRONG = "PASSWORD_WRONG"
    WEAK_PASSWORD = "WEAK_PASSWORD"
    SUBJECT_NOT_SELECTED = "SUBJECT_NOT_SELECTED"
    BIND_CODE_INVALID = "BIND_CODE_INVALID"
    CODE_INVALID = "CODE_INVALID"
    CODE_EXPIRED = "CODE_EXPIRED"
    CODE_TOO_MANY = "CODE_TOO_MANY"
    ACCOUNT_DELETING = "ACCOUNT_DELETING"
    # 上传与分析
    URL_EXPIRED = "URL_EXPIRED"
    INVALID_OBJECT_KEY = "INVALID_OBJECT_KEY"
    OBJECT_NOT_FOUND = "OBJECT_NOT_FOUND"
    CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    INVALID_FILE = "INVALID_FILE"
    # 导出与注销
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    EXPORT_TOO_LARGE = "EXPORT_TOO_LARGE"
    PROJECT_CLOSED = "PROJECT_CLOSED"

    @classmethod
    def http_status(cls, code: str) -> int:
        return _CODE_STATUS.get(code, 500)


_CODE_STATUS = {
    ErrorCode.INVALID_PARAM: 400,
    ErrorCode.TOKEN_EXPIRED: 401,
    ErrorCode.TOKEN_INVALID: 401,
    ErrorCode.REFRESH_INVALID: 401,
    ErrorCode.CONSENT_REQUIRED: 403,  # 唯一允许 403 的场景
    ErrorCode.NOT_FOUND: 404,
    ErrorCode.CONFLICT: 409,
    ErrorCode.QUOTA_EXCEEDED: 429,
    ErrorCode.RATE_LIMITED: 429,
    ErrorCode.INTERNAL_ERROR: 500,
    ErrorCode.SERVICE_UNAVAILABLE: 503,
    ErrorCode.EMAIL_TAKEN: 409,
    ErrorCode.USERNAME_TAKEN: 409,
    ErrorCode.PASSWORD_WRONG: 400,
    ErrorCode.WEAK_PASSWORD: 400,
    ErrorCode.SUBJECT_NOT_SELECTED: 400,
    ErrorCode.BIND_CODE_INVALID: 400,
    ErrorCode.CODE_INVALID: 400,
    ErrorCode.CODE_EXPIRED: 400,
    ErrorCode.CODE_TOO_MANY: 429,
    ErrorCode.ACCOUNT_DELETING: 409,
    ErrorCode.URL_EXPIRED: 400,
    ErrorCode.INVALID_OBJECT_KEY: 400,
    ErrorCode.OBJECT_NOT_FOUND: 400,
    ErrorCode.CHECKSUM_MISMATCH: 400,
    ErrorCode.INVALID_FILE: 400,
    ErrorCode.INSUFFICIENT_DATA: 400,
    ErrorCode.EXPORT_TOO_LARGE: 400,
    ErrorCode.PROJECT_CLOSED: 403,
}

_DEFAULT_MESSAGE = {
    ErrorCode.INVALID_PARAM: "请求参数有误",
    ErrorCode.NOT_FOUND: "内容不存在",
    ErrorCode.CONSENT_REQUIRED: "需要先完成授权",
    ErrorCode.INTERNAL_ERROR: "服务开小差了，请稍后再试",
}


class AppError(Exception):
    """业务异常。service 层抛出，全局 handler 统一转响应。"""

    def __init__(self, code: str, message: str | None = None,
                 details: list[dict] | None = None):
        self.code = code
        self.status = ErrorCode.http_status(code)
        self.message = message or _DEFAULT_MESSAGE.get(code, "请求未能完成")
        self.details = details
        super().__init__(f"{code}: {self.message}")


async def app_error_handler(_: Request, exc: AppError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status,
        content={
            "code": exc.code,
            "message": exc.message,
            "requestId": get_request_id(),
            **({"details": exc.details} if exc.details else {}),
        },
        headers={"Cache-Control": "no-store"},
    )


async def unhandled_error_handler(_: Request, exc: Exception) -> JSONResponse:
    # 内部异常不泄露堆栈（README 5.8.3）
    import logging

    logging.getLogger("app").exception("unhandled error: %s", type(exc).__name__)
    return JSONResponse(
        status_code=500,
        content={
            "code": ErrorCode.INTERNAL_ERROR,
            "message": _DEFAULT_MESSAGE[ErrorCode.INTERNAL_ERROR],
            "requestId": get_request_id(),
        },
        headers={"Cache-Control": "no-store"},
    )
