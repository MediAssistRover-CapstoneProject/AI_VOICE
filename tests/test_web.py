"""Unit tests for the Web Studio FastAPI application."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from src.web.app import app, DOCS_DIR


@pytest.fixture
def client():
    return TestClient(app)


def test_index_html(client):
    response = client.get("/")
    assert response.status_code == 200
    assert "MediAssistRover" in response.text
    assert "Vector P" in response.text


def test_get_stats(client):
    response = client.get("/api/stats")
    assert response.status_code == 200
    data = response.json()
    assert "vector_points_count" in data
    assert "documents_count" in data
    assert data["documents_count"] >= 1


def test_list_documents(client):
    response = client.get("/api/documents")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    assert len(data) >= 1
    assert any(d["filename"].endswith(".docx") or d["filename"].endswith(".md") for d in data)


def test_create_and_delete_text_document(client):
    test_title = "quy_trinh_test_don_vi"
    test_content = "Khoa Nhi làm việc từ thứ 2 đến chủ nhật từ 7h00 đến 17h00 tại tầng 3."
    
    # 1. Create doc
    res_create = client.post(
        "/api/documents/text",
        data={"title": test_title, "content": test_content, "auto_reindex": "true"},
    )
    assert res_create.status_code == 200
    created_data = res_create.json()
    assert created_data["status"] == "success"
    created_file = created_data["filename"]

    # Verify file exists
    assert (DOCS_DIR / created_file).exists()

    # 2. Chat with RAG to see if it answers about Khoa Nhi
    res_chat = client.post("/api/chat", data={"query": "Khoa Nhi làm việc ở tầng mấy?"})
    assert res_chat.status_code == 200
    chat_data = res_chat.json()
    assert "answer" in chat_data
    assert "tầng 3" in chat_data["answer"].lower() or "nhi" in chat_data["answer"].lower()

    # 3. Delete doc
    res_del = client.delete(f"/api/documents/{created_file}")
    assert res_del.status_code == 200
    assert not (DOCS_DIR / created_file).exists()


def test_chat_intents(client):
    # Greeting
    res1 = client.post("/api/chat", data={"query": "Xin chào robot"})
    assert res1.status_code == 200
    assert res1.json()["intent"] == "GREETING"

    # Escort
    res2 = client.post("/api/chat", data={"query": "Dẫn tôi đến nhà thuốc"})
    assert res2.status_code == 200
    assert res2.json()["intent"] == "ESCORT_REQUEST"

    # SOS
    res3 = client.post("/api/chat", data={"query": "Cứu tôi với tôi đau tim quá"})
    assert res3.status_code == 200
    assert res3.json()["intent"] == "EMERGENCY_SOS"


def test_list_chunks(client):
    response = client.get("/api/chunks?limit=10")
    assert response.status_code == 200
    chunks = response.json()
    assert isinstance(chunks, list)
    assert len(chunks) > 0
    assert "text" in chunks[0]
    assert "dimensions" in chunks[0]
