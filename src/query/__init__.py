"""Hospital information query handler – bridges intent to Backend API."""
from __future__ import annotations

import logging
from typing import Any

log = logging.getLogger("mar_voice")


class HospitalQueryHandler:
    """Resolves QUERY_HOSPITAL_INFO intents using the Backend's HospitalInformationQueryService."""

    def __init__(self, backend_client: Any) -> None:
        self._client = backend_client

    def answer(self, question: str, language: str = "vi-VN") -> str:
        """Query the backend for hospital information and return a human-friendly answer."""
        try:
            data = self._client.query_hospital_info(question, language)

            # The response is a Spring Page<HospitalInformation> wrapper.
            content = data.get("content", []) if isinstance(data, dict) else data
            if not content:
                return self._fallback(question)

            # Return the first matching article's content.
            best = content[0]
            title = best.get("title", "")
            body = best.get("content", "")
            answer = f"{title}. {body}" if title else body
            log.info("[QUERY] Found answer for: '%s' → '%s'", question[:40], answer[:60])
            return answer.strip()

        except Exception as exc:
            log.warning("[QUERY] Backend query failed: %s", exc)
            return self._fallback(question)

    @staticmethod
    def _fallback(question: str) -> str:
        return (
            "Tôi chưa tìm thấy thông tin phù hợp với câu hỏi của bạn. "
            "Vui lòng liên hệ quầy tiếp đón hoặc gọi số nội bộ để được hỗ trợ trực tiếp."
        )
