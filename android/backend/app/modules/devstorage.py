"""开发用对象存储直传/下载端点（LocalStorage 的预签名 URL 目标）。

生产环境不注册此路由（对象存储由境内云厂商承接）。
签名校验：与 LocalStorage._sign 同一算法，≤15 分钟，仅 PUT/GET。
"""
import os

from fastapi import APIRouter, Request, Response

from app.config import get_settings
from app.integrations.storage import LocalStorage

router = APIRouter(tags=["dev-storage"])


@router.put("/dev-storage/{object_key:path}", status_code=200)
async def put_object(object_key: str, request: Request):
    storage = LocalStorage()
    exp = int(request.query_params.get("exp", "0"))
    sig = request.query_params.get("sig", "")
    if not storage.verify_sig(object_key, exp, sig, "PUT"):
        return Response(status_code=403)
    path = storage._path(object_key)  # noqa: SLF001 — 同模块约定
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = await request.body()
    with open(path, "wb") as f:
        f.write(body)
    return Response(status_code=200)


@router.get("/dev-storage/{object_key:path}")
def get_object(object_key: str, request: Request):
    storage = LocalStorage()
    exp = int(request.query_params.get("exp", "0"))
    sig = request.query_params.get("sig", "")
    if not storage.verify_sig(object_key, exp, sig, "GET"):
        return Response(status_code=403)
    path = storage._path(object_key)  # noqa: SLF001
    if not os.path.isfile(path):
        return Response(status_code=404)
    with open(path, "rb") as f:
        data = f.read()
    return Response(content=data, media_type="application/octet-stream")
