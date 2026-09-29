"""基线与状态机（docs/backend/modules/基线与状态机.md）。

- 全产品唯一产生"判断"的地方，输出只有 statusCode + copyKey
- 状态计算是纯函数：compute_status(有效记录, 基线) —— 重算 100 次结果一致
- zScore 只存在于服务端内部，任何接口/日志/导出都不出现
"""
import math
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import (
    Baseline,
    EventTag,
    Metric,
    QualityGateResult,
    Record,
    StatusSnapshot,
    StatusTransition,
)

METRIC_KEYS = ("SPEECH_RATE", "PAUSE", "VOCAB", "COHERENCE")
ACOUSTIC_KEYS = ("SPEECH_RATE", "PAUSE")
BASELINE_MIN_SAMPLES = 4          # 前 4 次有效记录或前 14 天，取先到者
BASELINE_INITIAL_DAYS = 14
BASELINE_ROLLING_DAYS = 28
S4_STREAK = 3                     # 连续 3 次 ≥1 项核心指标低于基线 1.5SD
S5_WEEKS = 4                      # 连续 4 周多指标同向下降
NON_DISEASE_KEY = {"S4": "status.s4.nondisease", "S5": "status.s5.nondisease"}
COPY_KEY = {f"S{i}": f"status.s{i}" for i in range(7)}


@dataclass
class Point:
    record_id: uuid.UUID
    recorded_at: datetime
    task_type: str
    quality_flag: str
    # metricKey -> value（该记录可用指标）
    values: dict


def z_band(value: float, mean: float, std_dev: float) -> str:
    """分档而非数值（API-03：刻意不给 z 分数）。内部 z 计算后立刻转为分档。"""
    if std_dev <= 0:
        return "WITHIN"
    z = (value - mean) / std_dev
    if z <= -1.5:
        return "BELOW_STRONG"
    if z <= -1.0:
        return "BELOW_MILD"
    if z >= 1.0:
        return "ABOVE_MILD"
    return "WITHIN"


# ---------------- 基线重算（幂等：覆盖式） ----------------

def recompute_baselines(db: Session, subject_id: uuid.UUID) -> None:
    """对 (taskType, metricKey, computedFrom) 分组聚合，覆盖式更新。

    有效样本 = qualityFlag=VALID 且指标非 null。基线按 taskType 分组，不混合（3.2）。
    """
    rows = db.execute(
        select(Record.task_type, Record.created_at, Metric.metric_key,
               Metric.value, Metric.computed_from, QualityGateResult.flag)
        .join(Metric, Metric.record_id == Record.id)
        .outerjoin(QualityGateResult, QualityGateResult.record_id == Record.id)
        .where(
            Record.subject_id == subject_id,
            Record.deleted_at.is_(None),
            Record.analysis_state == "DONE",
        )
    ).all()
    samples_by_group: dict[tuple, list[tuple[datetime, float]]] = {}
    for task_type, created_at, key, value, computed_from, flag in rows:
        if value is None or (flag or "VALID") != "VALID":
            continue
        samples_by_group.setdefault((task_type, key, computed_from), []).append(
            (created_at, float(value)))

    now = datetime.now(timezone.utc)
    existing = {
        (b.task_type, b.metric_key, b.computed_from): b
        for b in db.scalars(select(Baseline).where(Baseline.subject_id == subject_id))
    }
    for gkey, samples in samples_by_group.items():
        samples.sort(key=lambda s: s[0])
        task_type, key, computed_from = gkey
        count = len(samples)
        established = count >= BASELINE_MIN_SAMPLES
        if established:
            window_start = now - timedelta(days=BASELINE_ROLLING_DAYS)
            recent = [s for s in samples if s[0] >= window_start]
            if len(recent) >= BASELINE_MIN_SAMPLES:
                samples = recent
            count = len(samples)
            # 滚动窗口内样本可能不足 4：保留全窗口计算，仍视为已建立
        else:
            window_start = samples[0][0] if samples else now
        values = [v for _t, v in samples]
        mean = sum(values) / count if count else 0.0
        std = math.sqrt(sum((v - mean) ** 2 for v in values) / count) if count > 1 else 0.0
        row = existing.pop(gkey, None)
        if row is None:
            row = Baseline(
                id=None, subject_id=subject_id, task_type=task_type, metric_key=key,
                computed_from=computed_from, window_start=window_start, window_end=now,
                mean=mean, std_dev=std, sample_count=count, is_established=established,
            )
            db.add(row)
        row.window_start = window_start
        row.window_end = now
        row.mean = mean
        row.std_dev = std
        row.sample_count = count
        row.is_established = established
        row.updated_at = now
    for stale in existing.values():
        db.delete(stale)


# ---------------- 状态计算（纯函数） ----------------

def compute_status(points: list[Point], baselines: dict[tuple[str, str], tuple[float, float]],
                   reference: datetime) -> tuple[str, list[str]]:
    """返回 (statusCode, changedMetricKeys)。

    baselines 键 = (taskType, metricKey)，值 = (mean, stdDev)（ASR 口径）。
    升级缓慢、降级自动、不保留历史最高状态（4.2/4.3）。
    """
    valid = [p for p in points if p.quality_flag == "VALID"]
    if not valid:
        return "S0", []
    if len(valid) < BASELINE_MIN_SAMPLES:
        return "S1", []

    # 每条记录各指标的分档
    banded: list[tuple[Point, dict[str, str]]] = []
    for p in sorted(valid, key=lambda x: x.recorded_at):
        bands: dict[str, str] = {}
        for key in METRIC_KEYS:
            v = p.values.get(key)
            if v is None:
                continue
            base = baselines.get((p.task_type, key))
            if base is None:
                continue
            mean, std = base
            if std <= 0 and abs(v - mean) < 1e-9:
                bands[key] = "WITHIN"
                continue
            bands[key] = z_band(v, mean, std)
        banded.append((p, bands))

    # S4：连续 3 次（或 2 周内 3 次）≥1 项核心指标低于基线 1.5SD
    streak = 0
    changed: list[str] = []
    for _p, bands in reversed(banded):
        strong = [k for k in ACOUSTIC_KEYS + ("VOCAB", "COHERENCE")
                  if bands.get(k) == "BELOW_STRONG"]
        if strong:
            streak += 1
            if streak >= S4_STREAK:
                changed = strong
                break
        else:
            streak = 0

    if streak >= S4_STREAK:
        # S5：连续 4 周多指标同向下降（≥2 项指标持续走低）
        window = reference - timedelta(weeks=S5_WEEKS)
        recent = [bp for bp in banded if bp[0].recorded_at >= window]
        declining = []
        for key in METRIC_KEYS:
            vals = [bands.get(key) for _p, bands in recent if bands.get(key)]
            vals = [v for v in vals if v in ("BELOW_MILD", "BELOW_STRONG")]
            if len(vals) >= S5_WEEKS and len(set(vals)) >= 1 and len(recent) >= S5_WEEKS:
                declining.append(key)
        if len(declining) >= 2:
            return "S5", declining
        return "S4", changed

    # S3：仅单项轻度偏离
    mild = [k for _p, bands in [banded[-1]] for k, b in bands.items()
            if b in ("BELOW_MILD", "ABOVE_MILD")]
    if len(mild) == 1:
        return "S3", mild
    return "S2", []


# ---------------- 重算编排（worker / 删除 / 事件变更共用） ----------------

def recompute_subject(db: Session, subject_id: uuid.UUID, *, trigger: str = "RECOMPUTE") -> None:
    recompute_baselines(db, subject_id)

    rows = db.execute(
        select(Record, QualityGateResult.flag)
        .outerjoin(QualityGateResult, QualityGateResult.record_id == Record.id)
        .where(Record.subject_id == subject_id, Record.deleted_at.is_(None))
        .order_by(Record.created_at)
    ).all()
    metric_rows = db.scalars(
        select(Metric).join(Record, Record.id == Metric.record_id).where(
            Record.subject_id == subject_id, Record.deleted_at.is_(None),
            Metric.computed_from == "ASR")
    ).all()
    metrics_by_record: dict[uuid.UUID, dict] = {}
    for m in metric_rows:
        metrics_by_record.setdefault(m.record_id, {})[m.metric_key] = (
            float(m.value) if m.value is not None else None)

    points = [
        Point(
            record_id=rec.id,
            recorded_at=rec.created_at,
            task_type=rec.task_type,
            quality_flag=flag or "VALID",
            values=metrics_by_record.get(rec.id, {}),
        )
        for rec, flag in rows
    ]
    baselines = {
        (b.task_type, b.metric_key): (float(b.mean), float(b.std_dev))
        for b in db.scalars(select(Baseline).where(
            Baseline.subject_id == subject_id, Baseline.computed_from == "ASR"))
    }
    reference = datetime.now(timezone.utc)
    code, _changed = compute_status(points, baselines, reference)

    # 事件（感冒/失眠等）影响非疾病解释呈现，但不改变状态码本身
    _ = db.scalars(select(EventTag).where(EventTag.record_id.in_(
        [p.record_id for p in points]))).all() if points else []

    snapshot = db.get(StatusSnapshot, subject_id)
    prev = snapshot.status_code if snapshot else None
    if snapshot is None:
        snapshot = StatusSnapshot(subject_id=subject_id, status_code=code)
        db.add(snapshot)
    snapshot.status_code = code
    snapshot.copy_key = COPY_KEY[code]
    snapshot.non_disease_key = NON_DISEASE_KEY.get(code)
    snapshot.valid_record_count = sum(1 for p in points if p.quality_flag == "VALID")
    snapshot.since = snapshot.since if (prev == code and snapshot.since) else reference
    snapshot.computed_at = reference
    if prev != code:
        db.add(StatusTransition(
            subject_id=subject_id, from_code=prev, to_code=code, trigger=trigger))
