"""Intent classification for Vietnamese hospital voice commands.

Two-tier approach:
1. Fast keyword matching (< 1ms) for common intents.
2. Optional LLM fallback (OpenAI GPT-4o-mini) for ambiguous queries.
"""
from __future__ import annotations

import logging
import re
import unicodedata
from dataclasses import dataclass
from typing import Any

log = logging.getLogger("mar_voice")


@dataclass
class IntentResult:
    intent: str
    confidence: float
    entities: dict[str, Any]
    raw_text: str


# ── Vietnamese keyword patterns ────────────────────────────────────────
# Each pattern maps a compiled regex → (intent_code, base_confidence).
# Patterns are checked top-down; first strong match wins.

_PATTERNS: list[tuple[re.Pattern, str, float]] = [
    # ── Escort / Navigation (checked before emergency so "Đưa tôi đến phòng cấp cứu" is escorted) ──
    (re.compile(r"(dẫn|chỉ|đưa).*(đến|tới|đi|phòng|nhà thuốc|xét nghiệm|cấp cứu|tiếp đón)", re.I), "ESCORT_REQUEST", 0.95),
    (re.compile(r"(phòng|nhà thuốc|khu|khoa).*(ở đâu|nằm đâu|đi (đường )?nào|chỗ nào)", re.I), "ESCORT_REQUEST", 0.90),
    (re.compile(r"(đường|lối|hướng).*(đi|đến|tới|vào)", re.I), "ESCORT_REQUEST", 0.85),

    # ── Emergency / SOS ──
    (re.compile(r"(cứu tôi|giúp tôi|tôi bị ngã|ngất|đau ngực|khó thở|chảy máu|tai nạn|khẩn cấp)", re.I), "EMERGENCY_SOS", 0.98),
    (re.compile(r"(?<!phòng\s)(?<!khoa\s)\bcấp cứu\b", re.I), "EMERGENCY_SOS", 0.95),

    # ── Hospital information queries ──
    (re.compile(r"(giờ|thời gian|lúc nào|mấy giờ).*(làm việc|mở cửa|hoạt động|khám|đóng cửa)", re.I), "QUERY_HOSPITAL_INFO", 0.90),
    (re.compile(r"(bệnh viện|phòng khám|nhà thuốc|xét nghiệm).*(thông tin|giới thiệu|địa chỉ|số điện thoại|liên hệ)", re.I), "QUERY_HOSPITAL_INFO", 0.88),
    (re.compile(r"(khám|chữa|điều trị|xét nghiệm).*(gì|những gì|loại nào|bệnh|dịch vụ)", re.I), "QUERY_HOSPITAL_INFO", 0.85),
    (re.compile(r"(chi phí|giá|phí|bảo hiểm|viện phí|thanh toán)", re.I), "QUERY_HOSPITAL_INFO", 0.85),
    (re.compile(r"(bác sĩ|chuyên khoa|chuyên gia|giáo sư|tiến sĩ)", re.I), "QUERY_HOSPITAL_INFO", 0.82),
    (re.compile(r"(quy trình|thủ tục|hồ sơ|giấy tờ|đăng ký|thẻ bảo hiểm)", re.I), "QUERY_HOSPITAL_INFO", 0.82),
    (re.compile(r"(thuốc|kê đơn|toa thuốc|mua thuốc|nhà thuốc)", re.I), "QUERY_HOSPITAL_INFO", 0.80),
    (re.compile(r"(wifi|nhà vệ sinh|toilet|căn tin|bãi đỗ xe|thang máy|cầu thang)", re.I), "QUERY_HOSPITAL_INFO", 0.80),
    (re.compile(r"(nội quy|quy định|lưu ý|cần biết|hướng dẫn)", re.I), "QUERY_HOSPITAL_INFO", 0.78),

    # ── Greeting ──
    (re.compile(r"^(xin chào|chào|hello|hi|hey|ê robot|robot ơi)[\s!?.]*$", re.I), "GREETING", 0.95),
    (re.compile(r"(xin chào|chào bạn|chào robot)", re.I), "GREETING", 0.85),

    # ── Farewell ──
    (re.compile(r"(tạm biệt|cảm ơn|cám ơn|bye|goodbye|hẹn gặp lại)", re.I), "FAREWELL", 0.90),

    # ── Robot status ──
    (re.compile(r"(bạn là|robot|tên (bạn|gì)|bạn có thể|giúp được gì)", re.I), "SELF_INTRO", 0.80),
]

# Rooms / destinations that can be extracted as entities.
_ROOM_KEYWORDS: dict[str, str] = {
    "cấp cứu": "P_CAP_CUU",
    "khám nội": "P_KHAM_NOI_01",
    "nội khoa": "P_KHAM_NOI_01",
    "khám ngoại": "P_KHAM_NGOAI_02",
    "ngoại khoa": "P_KHAM_NGOAI_02",
    "nhà thuốc": "P_NHA_THUOC",
    "xét nghiệm": "P_XET_NGHIEM",
    "tiếp đón": "P_TIEP_DON",
    "tiếp nhận": "P_TIEP_DON",
    "phòng khám": "P_KHAM_NOI_01",
}


def _extract_room(text: str) -> str | None:
    text_lower = text.lower()
    for keyword, room_code in _ROOM_KEYWORDS.items():
        if keyword in text_lower:
            return room_code
    return None


def endpoint_destination(text: str) -> str | None:
    """Only an explicit navigation phrase proposes a label; BE resolves its ID."""
    plain=unicodedata.normalize('NFD',text.casefold().replace('đ','d'))
    plain=''.join(c for c in plain if unicodedata.category(c)!='Mn')
    plain=' '.join(plain.strip(' .!?').split())
    match=re.fullmatch(r'(?:(?:hay|vui long|lam on|please|can you|could you) )?(?:(?:dan|dua|take|guide|bring|lead)(?: toi| minh| chung toi| me| us)? (?:den|toi|to)|(?:di|go|navigate|move) (?:den|toi|to)) (?:(?:diem|point|endpoint|vi tri|location) )?(.{1,160})',plain)
    if not match:return None
    label=re.sub(r' (?:nhe|voi|giup toi|please|thanks)$','',match.group(1)).strip()
    return label or None


class IntentClassifier:
    """Two-tier intent classifier: regex patterns + optional LLM fallback."""

    def __init__(
        self,
        confidence_threshold: float = 0.6,
        use_llm_fallback: bool = True,
        openai_api_key: str = "",
        llm_model: str = "gpt-4o-mini",
    ) -> None:
        self._threshold = confidence_threshold
        self._use_llm = use_llm_fallback and bool(openai_api_key)
        self._api_key = openai_api_key
        self._llm_model = llm_model

    def classify(self, text: str) -> IntentResult:
        """Classify a Vietnamese text into an intent."""
        if not text or not text.strip():
            return IntentResult("UNKNOWN", 0.0, {}, text or "")

        cleaned = text.strip()

        endpoint=endpoint_destination(cleaned)
        if endpoint:
            entities = {'destination_endpoint': endpoint}
            # Keep the legacy VoiceEngine contract for known rooms. New/custom
            # map labels still require resolution against the live BE catalog.
            room = _extract_room(cleaned)
            if room:
                entities['destination_room'] = room
            return IntentResult('ESCORT_REQUEST',.99,entities,cleaned)

        # Tier 1: Fast keyword matching
        for pattern, intent, base_conf in _PATTERNS:
            if pattern.search(cleaned):
                entities: dict[str, Any] = {}
                if intent == "ESCORT_REQUEST":
                    room = _extract_room(cleaned)
                    if room:
                        entities["destination_room"] = room
                return IntentResult(intent, base_conf, entities, cleaned)

        # Tier 2: LLM fallback for ambiguous queries
        if self._use_llm:
            return self._llm_classify(cleaned)

        return IntentResult("UNKNOWN", 0.3, {}, cleaned)

    def _llm_classify(self, text: str) -> IntentResult:
        """Use GPT-4o-mini for intent classification when keywords fail."""
        try:
            from openai import OpenAI
            client = OpenAI(api_key=self._api_key)

            system_prompt = """Bạn là hệ thống phân loại ý định (intent) cho robot bệnh viện.
Phân loại câu nói của bệnh nhân vào một trong các intent sau:
- QUERY_HOSPITAL_INFO: Hỏi thông tin bệnh viện (giờ làm việc, khoa phòng, dịch vụ, chi phí, bác sĩ, quy trình)
- ESCORT_REQUEST: Yêu cầu dẫn đường đến phòng/khoa nào đó
- EMERGENCY_SOS: Tình huống cấp cứu khẩn cấp
- GREETING: Chào hỏi
- FAREWELL: Tạm biệt / cảm ơn
- SELF_INTRO: Hỏi về robot
- UNKNOWN: Không phân loại được

Trả lời ĐÚNG format JSON: {"intent": "...", "confidence": 0.0-1.0, "entities": {}}
Nếu ESCORT_REQUEST, thêm entities.destination_room nếu nhận diện được phòng."""

            response = client.chat.completions.create(
                model=self._llm_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": text},
                ],
                temperature=0.1,
                max_tokens=100,
                response_format={"type": "json_object"},
            )
            import json
            result = json.loads(response.choices[0].message.content)
            intent = result.get("intent", "UNKNOWN")
            confidence = float(result.get("confidence", 0.5))
            entities = result.get("entities", {})
            log.info("[INTENT-LLM] %s (conf=%.2f) for: '%s'", intent, confidence, text[:60])
            return IntentResult(intent, confidence, entities, text)
        except Exception as exc:
            log.warning("[INTENT-LLM] Fallback failed: %s", exc)
            return IntentResult("UNKNOWN", 0.3, {}, text)
