"""UUID v7（时间有序，schema 全局约定 1）与对外 ID 前缀。"""
import os
import time
import uuid


def uuid7() -> uuid.UUID:
    """RFC 9562 UUID v7。Python 3.13 标准库尚无 uuid7，自行实现。"""
    ts_ms = time.time_ns() // 1_000_000
    rand = os.urandom(10)
    value = int(ts_ms & 0xFFFFFFFFFFFF) << 80
    value |= int.from_bytes(rand, "big")
    value = (value & ~(0xF << 76)) | (0x7 << 76)  # version 7
    value = (value & ~(0x3 << 62)) | (0x2 << 62)  # variant 10
    return uuid.UUID(int=value)


def external_id(prefix: str, value: uuid.UUID) -> str:
    """DB 内裸 UUID，API 层加业务前缀（schema 全局约定 1）。"""
    return f"{prefix}_{value}"


def parse_external_id(value: str) -> uuid.UUID:
    """接受 `rec_xxx` 或裸 UUID；非法一律由调用方转 404。"""
    raw = value.split("_", 1)[-1] if "_" in value else value
    return uuid.UUID(raw)
