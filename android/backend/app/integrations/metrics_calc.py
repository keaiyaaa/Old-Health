"""指标计算（分析流水线第 3/5 步）。

边界（分析流水线 1.3）：输出永远只是"指标数值"，不是"结论"。
口径（指标口径字典）：语速=字/分；PAUSE=停顿率；VOCAB=MATTR（非 TTR）；COHERENCE=连接词率。

声学部分优先用 librosa（DSP 口径与论文一致）；不可用时退化为纯 Python 能量 VAD，
仅在开发环境可用（dspLibVersion 记录实际口径，保证可追溯）。
"""
import io
import math
import re
import struct
import wave
from dataclasses import dataclass

DSP_LIB_VERSION = "py-dsp-1.0"

try:
    import librosa  # noqa: F401
    import numpy as np

    HAS_LIBROSA = True
except Exception:  # pragma: no cover - dev 环境无 librosa
    HAS_LIBROSA = False


@dataclass
class AcousticMetrics:
    speech_rate: float | None  # 字/分（需要转写；为 None 时仅声学口径可用）
    pause_ratio: float | None  # 停顿时长占比 0-1
    long_pause_count: int | None  # >1s 的停顿数
    snr_db: float | None
    silence_ratio: float | None


# ---------------- 声学 ----------------

def compute_acoustic(audio_path: str) -> tuple[AcousticMetrics, str]:
    """返回 (指标, dsp_lib_version)。失败抛异常 → 流水线走降级。"""
    if HAS_LIBROSA:
        return _acoustic_librosa(audio_path)
    return _acoustic_wav(audio_path)


def _acoustic_librosa(audio_path: str) -> tuple[AcousticMetrics, str]:
    import librosa
    import numpy as np

    y, sr = librosa.load(audio_path, sr=16000, mono=True)
    frame = int(0.025 * sr)
    hop = int(0.010 * sr)
    energy = librosa.feature.rms(y=y, frame_length=frame, hop_length=hop)[0]
    thresh = max(float(energy.max()) * 0.05, 1e-6)
    voiced = energy > thresh
    total = float(len(voiced) * hop) / sr
    silence = float((~voiced).sum() * hop) / sr
    # 长停顿：连续静音 ≥1s
    long_pauses, run = 0, 0
    for v in voiced:
        run = 0 if v else run + 1
        if run == int(1.0 / 0.010):
            long_pauses += 1
    silence_ratio = silence / total if total > 0 else 1.0
    snr = 10 * math.log10(max(float(energy[voiced].mean()) if voiced.any() else 1e-6, 1e-6)
                          / max(float(energy[~voiced].mean()) if (~voiced).any() else 1e-6, 1e-6))
    return AcousticMetrics(
        speech_rate=None,  # 语速在获得转写后按字数/有效时长计算
        pause_ratio=round(silence_ratio, 4),
        long_pause_count=long_pauses,
        snr_db=round(snr, 2),
        silence_ratio=round(silence_ratio, 4),
    ), "librosa-0.10"


def _acoustic_wav(audio_path: str) -> tuple[AcousticMetrics, str]:
    """dev 兜底：仅支持 PCM WAV。"""
    with wave.open(audio_path, "rb") as w:
        sr = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
        width = w.getsampwidth()
    if width != 2:
        raise ValueError("only 16bit wav supported in dev fallback")
    count = len(raw) // 2
    samples = struct.unpack(f"<{count}h", raw[: count * 2])
    frame = int(0.025 * sr)
    energies = [
        math.sqrt(sum(s * s for s in samples[i : i + frame]) / max(frame, 1))
        for i in range(0, count - frame, frame // 10)
    ]
    if not energies:
        raise ValueError("empty audio")
    peak = max(energies)
    thresh = peak * 0.05
    voiced = [e > thresh for e in energies]
    hop = 0.010
    total = len(voiced) * hop
    silence = sum(1 for v in voiced if not v) * hop
    long_pauses, run = 0, 0
    for v in voiced:
        run = 0 if v else run + 1
        if run == 100:  # 1s @10ms hop
            long_pauses += 1
    silence_ratio = silence / total if total else 1.0
    mean_v = sum(e for e, v in zip(energies, voiced) if v) / max(voiced.count(True), 1)
    mean_s = sum(e for e, v in zip(energies, voiced) if not v) / max(voiced.count(False), 1) \
        if voiced.count(False) else 1e-6
    snr = 10 * math.log10(max(mean_v, 1e-6) / max(mean_s, 1e-6))
    return AcousticMetrics(
        speech_rate=None,
        pause_ratio=round(silence_ratio, 4),
        long_pause_count=long_pauses,
        snr_db=round(snr, 2),
        silence_ratio=round(silence_ratio, 4),
    ), DSP_LIB_VERSION


# ---------------- 文本 ----------------

_CONNECTIVES = ["然后", "所以", "但是", "因为", "接着", "后来", "最后", "首先", "其次", "而且"]
_SENT_SPLIT = re.compile(r"[。！？!?.；;]")

_MATTR_WINDOW = 50  # MATTR 标准窗口（短样本 TTR 不稳，故用 MATTR）


def compute_text_metrics(text: str, speech_duration_sec: float | None = None) -> dict:
    """纯函数。输入转写文本，输出文本指标数值（无任何解读）。"""
    chars = [c for c in text if "\u4e00" <= c <= "\u9fff"]
    tokens = re.findall(r"[\u4e00-\u9fff]|[a-zA-Z]+", text)
    if len(tokens) >= _MATTR_WINDOW:
        windows = [len({tuple(tokens[i : i + _MATTR_WINDOW])}) / _MATTR_WINDOW
                   for i in range(len(tokens) - _MATTR_WINDOW + 1)]
        mattr = sum(windows) / len(windows)
    elif tokens:
        mattr = len(set(tokens)) / len(tokens)
    else:
        mattr = None
    sents = [s for s in _SENT_SPLIT.split(text) if s.strip()]
    mean_sent = (len(tokens) / len(sents)) if sents else None
    connective_rate = (sum(text.count(c) for c in _CONNECTIVES) / len(sents)) if sents else None
    speech_rate = (len(chars) / (speech_duration_sec / 60)) \
        if chars and speech_duration_sec and speech_duration_sec > 0 else None
    return {
        "SPEECH_RATE": round(speech_rate, 2) if speech_rate else None,
        "VOCAB": round(mattr, 4) if mattr is not None else None,
        "COHERENCE": round(connective_rate, 4) if connective_rate is not None else None,
        "meanSentenceLen": round(mean_sent, 2) if mean_sent else None,
    }


# ---------------- 质量门控（流水线第 6 步） ----------------

def quality_gate(*, snr_db, duration_sec, silence_ratio, asr_confidence,
                 has_text: bool) -> tuple[str, list[str]]:
    """返回 (flag, reasons)。flag ∈ VALID | SUSPECT | INVALID。"""
    reasons: list[str] = []
    if duration_sec is not None and duration_sec < 10:
        reasons.append("TOO_SHORT")
    if snr_db is not None and snr_db < 5:
        reasons.append("NOISY")
    if silence_ratio is not None and silence_ratio > 0.6:
        reasons.append("TOO_MUCH_SILENCE")
    if not has_text:
        reasons.append("NO_TRANSCRIPT")
    if snr_db is None:
        return "INVALID", ["AUDIO_MISSING"]
    if reasons and asr_confidence is not None and asr_confidence < 0.6:
        return "SUSPECT", reasons
    if reasons:
        return "SUSPECT", reasons
    return "VALID", []
