"""对象存储抽象（README 8.4 思路：C3/厂商未定时先定接口）。

- 生产：境内 OSS/COS（S3 兼容协议，boto3）
- 开发：本地目录实现同一接口，方便离线联调
- 硬约束：私有读 + 预签名（≤15 分钟）、删除为硬删除
"""
import hashlib
import os
import shutil
from abc import ABC, abstractmethod
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

from app.config import get_settings


class StorageProvider(ABC):
    @abstractmethod
    def presign_put(self, object_key: str, content_type: str) -> tuple[str, datetime]:
        """返回 (上传 URL, 过期时间)。≤15 分钟，仅 PUT。"""

    @abstractmethod
    def presign_get(self, object_key: str) -> tuple[str, datetime]:
        """返回 (下载/播放 URL, 过期时间)。仅 GET。"""

    @abstractmethod
    def exists(self, object_key: str) -> bool:
        ...

    @abstractmethod
    def size_and_sha256(self, object_key: str) -> tuple[int, bytes]:
        ...

    @abstractmethod
    def download(self, object_key: str, dest_path: str) -> None:
        ...

    @abstractmethod
    def delete(self, object_key: str) -> None:
        """硬删除（合规红线 11）。"""

    @abstractmethod
    def upload_file(self, object_key: str, local_path: str, content_type: str) -> None:
        """worker 内部上传（导出产物）。"""


class LocalStorage(StorageProvider):
    """开发用本地实现。URL 直接指向 API 自身的 /v1/dev-storage 端点。"""

    def _path(self, key: str) -> str:
        base = os.path.abspath(get_settings().storage_local_dir)
        path = os.path.abspath(os.path.join(base, key))
        if not path.startswith(base):  # 防路径穿越
            raise ValueError("invalid object key")
        return path

    def presign_put(self, object_key: str, content_type: str) -> tuple[str, datetime]:
        exp = datetime.now(timezone.utc) + timedelta(seconds=get_settings().presign_ttl_sec)
        sig = self._sign(object_key, exp, "PUT")
        url = f"/v1/dev-storage/{object_key}?{urlencode({'exp': int(exp.timestamp()), 'sig': sig})}"
        return url, exp

    def presign_get(self, object_key: str) -> tuple[str, datetime]:
        exp = datetime.now(timezone.utc) + timedelta(seconds=get_settings().presign_ttl_sec)
        sig = self._sign(object_key, exp, "GET")
        url = f"/v1/dev-storage/{object_key}?{urlencode({'exp': int(exp.timestamp()), 'sig': sig})}"
        return url, exp

    def _sign(self, key: str, exp: datetime, method: str) -> str:
        s = get_settings()
        return hashlib.sha256(
            f"{s.jwt_secret}:{method}:{key}:{int(exp.timestamp())}".encode()
        ).hexdigest()

    def verify_sig(self, object_key: str, exp_ts: int, sig: str, method: str) -> bool:
        exp = datetime.fromtimestamp(exp_ts, tz=timezone.utc)
        if exp < datetime.now(timezone.utc):
            return False
        return self._sign(object_key, exp, method) == sig

    def exists(self, object_key: str) -> bool:
        return os.path.isfile(self._path(object_key))

    def size_and_sha256(self, object_key: str) -> tuple[int, bytes]:
        data = self._read(object_key)
        return len(data), hashlib.sha256(data).digest()

    def _read(self, object_key: str) -> bytes:
        with open(self._path(object_key), "rb") as f:
            return f.read()

    def download(self, object_key: str, dest_path: str) -> None:
        shutil.copyfile(self._path(object_key), dest_path)

    def delete(self, object_key: str) -> None:
        p = self._path(object_key)
        if os.path.isfile(p):
            os.remove(p)

    def upload_file(self, object_key: str, local_path: str, content_type: str) -> None:
        p = self._path(object_key)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        shutil.copyfile(local_path, p)


class S3Storage(StorageProvider):
    """生产实现：境内 S3 兼容对象存储（OSS/COS）。"""

    def __init__(self):
        import boto3

        s = get_settings()
        self.client = boto3.client(
            "s3",
            endpoint_url=s.s3_endpoint_url,
            aws_access_key_id=s.s3_access_key,
            aws_secret_access_key=s.s3_secret_key,
        )
        self.bucket = s.s3_bucket
        self.ttl = s.presign_ttl_sec

    def presign_put(self, object_key: str, content_type: str) -> tuple[str, datetime]:
        exp = datetime.now(timezone.utc) + timedelta(seconds=self.ttl)
        url = self.client.generate_presigned_url(
            "put_object",
            Params={"Bucket": self.bucket, "Key": object_key, "ContentType": content_type},
            ExpiresIn=self.ttl,
        )
        return url, exp

    def presign_get(self, object_key: str) -> tuple[str, datetime]:
        exp = datetime.now(timezone.utc) + timedelta(seconds=self.ttl)
        url = self.client.generate_presigned_url(
            "get_object", Params={"Bucket": self.bucket, "Key": object_key},
            ExpiresIn=self.ttl,
        )
        return url, exp

    def exists(self, object_key: str) -> bool:
        try:
            self.client.head_object(Bucket=self.bucket, Key=object_key)
            return True
        except Exception:
            return False

    def size_and_sha256(self, object_key: str) -> tuple[int, bytes]:
        resp = self.client.get_object(Bucket=self.bucket, Key=object_key)
        data = resp["Body"].read()
        return len(data), hashlib.sha256(data).digest()

    def download(self, object_key: str, dest_path: str) -> None:
        self.client.download_file(self.bucket, object_key, dest_path)

    def delete(self, object_key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=object_key)

    def upload_file(self, object_key: str, local_path: str, content_type: str) -> None:
        self.client.upload_file(local_path, self.bucket, object_key,
                                ExtraArgs={"ContentType": content_type})


def get_storage() -> StorageProvider:
    s = get_settings()
    return S3Storage() if s.storage_backend == "s3" else LocalStorage()
