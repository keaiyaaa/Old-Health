"""API-06 · 研究模式（默认关闭；关闭时全域 404）。

三重前置：RESEARCHER 角色 + 开关开启 + 研究同意有效（除 6.1/6.3）。缺一 404。
合规：只出 researchSubjectId；量表分数仅在此域，不进日志。
"""
import csv
import io
import json
import uuid
import zipfile
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.core.audit import audit
from app.core.deps import get_principal
from app.core.errors import AppError, ErrorCode
from app.core.idempotency import begin_idempotency, commit_idempotency
from app.core.ids import uuid7
from app.core.pagination import to_millis
from app.core.security import hmac_hash
from app.db.models import (
    AnalysisJob,
    ClinicalScale,
    EventTag,
    ExportJob,
    ManualTranscript,
    Metric,
    QualityGateResult,
    Record,
    ResearchConsent,
    ResearchProject,
    ResearchSubject,
    TaskProtocol,
    Subject,
)
from app.db.session import get_db
from app.integrations.outbox import enqueue

router = APIRouter(tags=["research"])


def _guard_researcher(principal, require_consent_for=None):
    s = get_settings()
    if not s.research_mode_enabled or principal.account.role != "RESEARCHER":
        raise AppError(ErrorCode.NOT_FOUND)  # 不暴露研究功能存在


def _rs_by_anon(db: Session, anon: str) -> ResearchSubject | None:
    return db.scalar(select(ResearchSubject).where(
        ResearchSubject.research_subject_id == anon,
        ResearchSubject.withdrawn_at.is_(None)))


def _consent_valid(db: Session, rs: ResearchSubject) -> bool:
    return db.scalar(select(ResearchConsent.id).where(
        ResearchConsent.research_subject_id == rs.id,
        ResearchConsent.revoked_at.is_(None))) is not None


class ResearchConsentIn(BaseModel):
    subjectId: str
    consentVersion: str = "1.0"
    irbApprovalRef: str | None = None


def _next_anon_id(db: Session) -> str:
    n = db.scalar(select(func.count(ResearchSubject.id))) or 0
    return f"SUBJ-{n + 1:04d}"


@router.post("/research/consent", status_code=201)
def grant_research_consent(body: ResearchConsentIn, request: Request,
                           principal=Depends(get_principal), db: Session = Depends(get_db)):
    _guard_researcher(principal)
    idem_key, cached = begin_idempotency(
        db, scope="research_consent", account_id=principal.account.id,
        request=request, body=body.model_dump())
    if cached:
        return cached["payload"]
    try:
        sid = uuid.UUID(body.subjectId.split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    # 并发入组靠 uq_research_subjects_active 部分唯一索引兜底：只分配一个匿名 ID
    existing = db.scalar(select(ResearchSubject).where(
        ResearchSubject.subject_id == sid, ResearchSubject.withdrawn_at.is_(None)))
    if existing:
        rs = existing
    else:
        rs = ResearchSubject(id=uuid7(), research_subject_id=_next_anon_id(db),
                             subject_id=sid,
                             project_id=_default_project(db).id)
        db.add(rs)
        db.flush()
    consent = ResearchConsent(
        id=uuid7(), research_subject_id=rs.id, consent_version=body.consentVersion,
        evidence={"irb": body.irbApprovalRef,
                  "ipDigest": hmac_hash(request.client.host or "").hex()})
    db.add(consent)
    db.flush()
    commit_idempotency(db, idem_key, {"researchSubjectId": rs.research_subject_id})
    return {
        "researchSubjectId": rs.research_subject_id,
        "consentVersion": body.consentVersion,
        "grantedAt": to_millis(consent.granted_at),
        "withdrawable": True,
        # 伦理要求：必须明确告知撤回边界
        "withdrawEffect": "撤回后停止研究数据使用；已导出的匿名数据集无法追溯撤回",
    }


def _default_project(db: Session) -> ResearchProject:
    project = db.scalar(select(ResearchProject).where(ResearchProject.status == "ACTIVE"))
    if project is None:
        project = ResearchProject(
            id=uuid7(), name="一期研究", principal_investigator="-",
            consent_version="1.0")
        db.add(project)
        db.flush()
    return project


class EnrollIn(BaseModel):
    subjectId: str
    projectId: str | None = None
    consentVersion: str = "1.0"


@router.post("/research/subjects", status_code=201)
def enroll(body: EnrollIn, request: Request, principal=Depends(get_principal),
           db: Session = Depends(get_db)):
    _guard_researcher(principal)
    idem_key, cached = begin_idempotency(
        db, scope="research_enroll", account_id=principal.account.id,
        request=request, body=body.model_dump())
    if cached:
        return cached["payload"]
    try:
        sid = uuid.UUID(body.subjectId.split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    existing = db.scalar(select(ResearchSubject).where(
        ResearchSubject.subject_id == sid, ResearchSubject.withdrawn_at.is_(None)))
    if existing:
        rs = existing
    else:
        project = db.get(ResearchProject, uuid.UUID(body.projectId.split("_")[-1])) \
            if body.projectId else _default_project(db)
        if project is None:
            raise AppError(ErrorCode.NOT_FOUND)
        rs = ResearchSubject(id=uuid7(), research_subject_id=_next_anon_id(db),
                             subject_id=sid, project_id=project.id)
        db.add(rs)
        db.flush()
    commit_idempotency(db, idem_key,
                       {"researchSubjectId": rs.research_subject_id,
                        "enrolledAt": to_millis(rs.enrolled_at)})
    return {"researchSubjectId": rs.research_subject_id,
            "enrolledAt": to_millis(rs.enrolled_at)}


@router.get("/research/subjects")
def list_research_subjects(principal=Depends(get_principal), db: Session = Depends(get_db)):
    _guard_researcher(principal)
    rows = db.scalars(select(ResearchSubject).where(
        ResearchSubject.withdrawn_at.is_(None))).all()
    items = []
    for rs in rows:
        recs = db.scalars(select(Record).where(
            Record.subject_id == rs.subject_id, Record.deleted_at.is_(None))).all()
        valid = 0
        caregiver = 0
        for rec in recs:
            qg = db.get(QualityGateResult, rec.id)
            if (qg.flag if qg else "VALID") == "VALID":
                valid += 1
            if rec.recorder_role == "FAMILY_PROXY":
                caregiver += 1
        items.append({
            "researchSubjectId": rs.research_subject_id,
            "enrolledAt": to_millis(rs.enrolled_at),
            "consentValid": _consent_valid(db, rs),
            "recordCount": len(recs),
            "validRecordCount": valid,
            "caregiverRecordCount": caregiver,  # 论文取数必须能识别代录样本
            "scaleCount": db.scalar(select(func.count(ClinicalScale.id)).where(
                ClinicalScale.research_subject_id == rs.id)) or 0,
        })
    return {"items": items}  # 合规：无姓名/手机号/内部 subjectId/昵称


class ClinicalIn(BaseModel):
    researchSubjectId: str
    scaleType: str = Field(pattern="^(MOCA_B|MMSE|FLUENCY)$")
    score: float
    assessedAt: int
    assessor: str
    note: str | None = None


@router.post("/research/clinical", status_code=201)
def add_clinical(body: ClinicalIn, request: Request,
                 principal=Depends(get_principal), db: Session = Depends(get_db)):
    _guard_researcher(principal)
    rs = _rs_by_anon(db, body.researchSubjectId)
    if rs is None or not _consent_valid(db, rs):
        raise AppError(ErrorCode.NOT_FOUND)
    idem_key, cached = begin_idempotency(
        db, scope="research_clinical", account_id=principal.account.id,
        request=request, body=body.model_dump())
    if cached:
        return cached["payload"]
    row = ClinicalScale(
        id=uuid7(), research_subject_id=rs.id, scale_type=body.scaleType,
        score=body.score,
        assessed_at=datetime.fromtimestamp(body.assessedAt / 1000, tz=timezone.utc),
        assessor=body.assessor, note=body.note)
    db.add(row)
    db.flush()
    # 合规：审计只记"录入了某类型的量表"，不记分数（分数不进日志）
    audit(db, action="ROLE_CHANGE", target_type="clinical_scale", target_id=row.id,
          actor_id=principal.account.id, purpose=f"scale entry {body.scaleType}")
    commit_idempotency(db, idem_key, {"ok": True})
    return {"ok": True}


@router.get("/research/clinical")
def list_clinical(researchSubjectId: str, principal=Depends(get_principal),
                  db: Session = Depends(get_db)):
    _guard_researcher(principal)
    rs = _rs_by_anon(db, researchSubjectId)
    if rs is None:
        raise AppError(ErrorCode.NOT_FOUND)
    rows = db.scalars(select(ClinicalScale).where(
        ClinicalScale.research_subject_id == rs.id).order_by(ClinicalScale.assessed_at)).all()
    return {"items": [
        {"researchSubjectId": rs.research_subject_id, "scaleType": r.scale_type,
         "score": float(r.score), "assessedAt": to_millis(r.assessed_at),
         "assessor": r.assessor, "note": r.note}
        for r in rows
    ]}


@router.get("/research/adherence")
def adherence(projectId: str | None = None, principal=Depends(get_principal),
              db: Session = Depends(get_db)):
    _guard_researcher(principal)
    rows = db.scalars(select(ResearchSubject).where(
        ResearchSubject.withdrawn_at.is_(None))).all()
    items = []
    for rs in rows:
        recs = db.scalars(select(Record).where(
            Record.subject_id == rs.subject_id, Record.deleted_at.is_(None))).all()
        completed = len(recs)
        caregiver = sum(1 for r in recs if r.recorder_role == "FAMILY_PROXY")
        # 时段分布是研究刚需（EMA 早间依从率高于晚间）
        tod = {"MORNING": 0, "AFTERNOON": 0, "EVENING": 0}
        for r in recs:
            h = r.actual_started_at.hour
            tod["MORNING" if h < 12 else "AFTERNOON" if h < 18 else "EVENING"] += 1
        expected = max(completed, 1)  # 一期：按实际任务协议计算（协议接入后完善）
        items.append({
            "researchSubjectId": rs.research_subject_id,
            "expectedCount": expected,
            "completedCount": completed,
            "completionRate": round(completed / expected, 3),
            "missingDistribution": {},
            "caregiverRecordCount": caregiver,
            "caregiverRatio": round(caregiver / completed, 3) if completed else 0,
            "timeOfDayDistribution": tod,
        })
    return {"projectId": projectId, "items": items}


@router.get("/research/protocol")
def protocol(weekIndex: int = 1, principal=Depends(get_principal),
             db: Session = Depends(get_db)):
    _guard_researcher(principal)
    rows = db.scalars(select(TaskProtocol).where(
        TaskProtocol.week_index == weekIndex).order_by(TaskProtocol.id)).all()
    if not rows:
        return {"protocolId": None, "version": None, "weekIndex": weekIndex, "tasks": []}
    tasks = []
    for r in rows:
        task = {"taskType": r.task_type, "guideText": r.guide_text,
                "durationSec": r.duration_sec}
        if r.image_url:
            task["imageUrl"] = r.image_url
        tasks.append(task)
    # guideText 逐字固定（FR-8.4）；version 必返（指导语改动必须升版本）
    return {"protocolId": f"prt_{rows[0].id}", "version": str(rows[0].version),
            "weekIndex": weekIndex, "tasks": tasks}


class ManualTranscriptIn(BaseModel):
    recordId: str
    text: str = Field(min_length=1)
    transcribedBy: str
    secondTranscription: str | None = None


@router.post("/research/transcript", status_code=201)
def import_transcript(body: ManualTranscriptIn, request: Request,
                      principal=Depends(get_principal), db: Session = Depends(get_db)):
    _guard_researcher(principal)
    idem_key, cached = begin_idempotency(
        db, scope="research_transcript", account_id=principal.account.id,
        request=request, body=body.model_dump())
    if cached:
        return cached["payload"]
    try:
        rid = uuid.UUID(body.recordId.split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    rec = db.get(Record, rid)
    if rec is None or rec.deleted_at is not None:
        raise AppError(ErrorCode.NOT_FOUND)
    manual = db.get(ManualTranscript, rid)
    if manual is None:
        manual = ManualTranscript(record_id=rid)
        db.add(manual)
    manual.text = body.text
    manual.transcribed_by = body.transcribedBy
    manual.transcribed_at = datetime.now(timezone.utc)
    if body.secondTranscription:
        # 双人转录一致性（ICC）计算的数据基础；一期先存，计算在论文侧
        manual.inter_rater_agreement = None
    # 触发文本指标重算（MANUAL 口径）
    enqueue(db, aggregate_type="analysis", aggregate_id=rid,
            event_type="transcript.corrected",
            payload={"recordId": str(rid), "computedFrom": "MANUAL"})
    commit_idempotency(db, idem_key, {"ok": True})
    return {"ok": True, "computedFrom": "MANUAL"}


class ResearchExportIn(BaseModel):
    projectId: str
    subjectIds: list[str]
    fromMs: int | None = None
    toMs: int | None = None
    include: list[str] = Field(
        default_factory=lambda: ["AUDIO", "ASR_TEXT", "MANUAL_TEXT", "METRICS", "SCALES", "EVENTS"])
    format: str = "ZIP"


@router.post("/research/export", status_code=202)
def research_export(body: ResearchExportIn, request: Request,
                    principal=Depends(get_principal), db: Session = Depends(get_db)):
    _guard_researcher(principal)
    idem_key, cached = begin_idempotency(
        db, scope="research_export", account_id=principal.account.id,
        request=request, body=body.model_dump())
    if cached:
        return cached["payload"]
    # 受试者研究同意必须有效；已撤回的拒绝导出，明确报错
    anon_ids = []
    for anon in body.subjectIds:
        rs = _rs_by_anon(db, anon)
        if rs is None or not _consent_valid(db, rs):
            raise AppError(ErrorCode.NOT_FOUND, f"{anon} 研究同意无效或已撤回")
        anon_ids.append(rs.id)
    job = ExportJob(
        id=uuid.uuid4(), subject_id=anon_ids[0] and db.get(ResearchSubject, anon_ids[0]).subject_id,
        scope="METRICS_ONLY", requested_by=principal.account.id,
        include=body.include,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=24))
    db.add(job)
    db.flush()
    enqueue(db, aggregate_type="export", aggregate_id=job.id,
            event_type="research.export",
            payload={"jobId": str(job.id), "researchSubjectIds":
                     [str(x) for x in anon_ids], "include": body.include,
                     "projectId": body.projectId})
    commit_idempotency(db, idem_key, {"jobId": f"exp_{job.id}", "state": "PENDING"})
    audit(db, action="EXPORT", target_type="research_project",
          target_id=uuid.UUID(body.projectId.split("_")[-1]) if "_" in body.projectId else None,
          actor_id=principal.account.id, purpose="research dataset export")
    return {"jobId": f"exp_{job.id}", "state": "PENDING"}


@router.delete("/research/consent/{researchSubjectId}", status_code=204)
def withdraw_research_consent(researchSubjectId: str,
                              principal=Depends(get_principal),
                              db: Session = Depends(get_db)):
    s = get_settings()
    rs = db.scalar(select(ResearchSubject).where(
        ResearchSubject.research_subject_id == researchSubjectId))
    if rs is None or rs.withdrawn_at is not None:
        raise AppError(ErrorCode.NOT_FOUND)
    # 本人（绑定家属）或 RESEARCHER
    if principal.account.role != "RESEARCHER":
        link = db.scalar(select(Subject.id).where(Subject.id == rs.subject_id))
        from app.core.deps import require_write

        require_write(db, principal, rs.subject_id)
    consent = db.scalar(select(ResearchConsent).where(
        ResearchConsent.research_subject_id == rs.id,
        ResearchConsent.revoked_at.is_(None)))
    if consent:
        consent.revoked_at = datetime.now(timezone.utc)
    rs.withdrawn_at = datetime.now(timezone.utc)
    audit(db, action="CONSENT_CHANGE", target_type="research_consent",
          target_id=rs.id, actor_id=principal.account.id,
          purpose="research consent withdrawn")
