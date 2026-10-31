"""Tests for hospital query handler."""
from unittest.mock import MagicMock
from src.query import HospitalQueryHandler


def test_answer_with_results():
    client = MagicMock()
    client.query_hospital_info.return_value = {
        "content": [
            {"title": "Giờ làm việc", "content": "Bệnh viện làm việc từ 7h đến 17h."}
        ]
    }
    handler = HospitalQueryHandler(client)
    result = handler.answer("Mấy giờ mở cửa?")
    assert "7h" in result
    assert "17h" in result


def test_answer_no_results():
    client = MagicMock()
    client.query_hospital_info.return_value = {"content": []}
    handler = HospitalQueryHandler(client)
    result = handler.answer("Câu hỏi không liên quan gì hết")
    assert "chưa tìm thấy" in result.lower() or "liên hệ" in result.lower()


def test_answer_backend_error():
    client = MagicMock()
    client.query_hospital_info.side_effect = Exception("Connection refused")
    handler = HospitalQueryHandler(client)
    result = handler.answer("Giờ khám bệnh?")
    assert "liên hệ" in result.lower()
