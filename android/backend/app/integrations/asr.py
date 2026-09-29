"""ASR 抽象层（README 8.4：C3 未选型，先定接口；选型定了只换 adapter）。

合规硬约束：ASR 必须境内；服务商不留存、不用于训练（合同/配置层面落实）。
"""
import json
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field


class AsrRetryableError(Exception):
    """可重试：超时 / 限流 / 网络抖动。"""


class AsrPermanentError(Exception):
    """不可重试：参数错误、凭证错误（需人工介入）。"""


@dataclass
class AsrResult:
    text: str
    confidence: float
    segments: list = field(default_factory=list)
    engine: str = "mock"
    model_version: str = "mock-1.0"


class ASRProvider(ABC):
    name: str = "abstract"
    model_version: str = "0"

    @abstractmethod
    def transcribe(self, audio_path: str, dialect: str | None) -> AsrResult:
        """同步转写（worker 内调用，任务超时由 Celery 控制）。"""


class MockASR(ASRProvider):
    """开发/测试用：返回确定性伪转写，链路可跑通，不产生真实计费。"""

    name = "mock"
    model_version = "mock-1.0"

    def transcribe(self, audio_path: str, dialect: str | None) -> AsrResult:
        time.sleep(0.1)  # 模拟耗时
        text = "今天天气不错，我在公园走了走，和邻居聊了聊天，中午吃了面条。"
        return AsrResult(
            text=text,
            confidence=0.92,
            segments=[{"startMs": 0, "endMs": 12000, "text": text, "confidence": 0.92}],
            engine=self.name,
            model_version=self.model_version,
        )


class HttpASR(ASRProvider):
    """通用 HTTP adapter：对接境内 ASR 服务商。C3 选型后按厂商细化。"""

    name = "http"
    model_version = "http-1.0"

    def transcribe(self, audio_path: str, dialect: str | None) -> AsrResult:
        import httpx

        from app.config import get_settings

        s = get_settings()
        if not s.asr_http_url:
            raise AsrPermanentError("ASR_HTTP_URL not configured")
        try:
            with open(audio_path, "rb") as f:
                resp = httpx.post(
                    s.asr_http_url,
                    headers={"Authorization": f"Bearer {s.asr_http_key}"},
                    files={"audio": f},
                    data={"dialect": dialect or ""},
                    timeout=90,
                )
        except httpx.TimeoutException as e:
            raise AsrRetryableError(str(e)) from e
        except httpx.HTTPError as e:
            raise AsrRetryableError(str(e)) from e
        if resp.status_code == 429:
            raise AsrRetryableError("rate limited")
        if resp.status_code >= 500:
            raise AsrRetryableError("upstream error")
        if resp.status_code >= 400:
            raise AsrPermanentError(f"asr rejected: {resp.status_code}")
        data = json.loads(resp.text)
        return AsrResult(
            text=data["text"],
            confidence=float(data.get("confidence", 0.0)),
            segments=data.get("segments", []),
            engine=self.name,
            model_version=data.get("modelVersion", self.model_version),
        )


def get_asr() -> ASRProvider:
    from app.config import get_settings

    provider = get_settings().asr_provider
    return HttpASR() if provider == "http" else MockASR()


def new_task_ref() -> str:
    return uuid.uuid4().hex
