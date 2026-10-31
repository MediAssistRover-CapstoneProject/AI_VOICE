"""Unit tests for Intent Classifier."""
import pytest
from src.intent import IntentClassifier


@pytest.fixture
def classifier():
    return IntentClassifier(confidence_threshold=0.6, use_llm_fallback=False)


class TestGreeting:
    def test_xin_chao(self, classifier):
        r = classifier.classify("Xin chào")
        assert r.intent == "GREETING"
        assert r.confidence >= 0.8

    def test_hello(self, classifier):
        r = classifier.classify("Hello")
        assert r.intent == "GREETING"

    def test_chao_robot(self, classifier):
        r = classifier.classify("Chào robot")
        assert r.intent == "GREETING"


class TestEscort:
    def test_dan_den_phong_kham(self, classifier):
        r = classifier.classify("Dẫn tôi đến phòng khám nội")
        assert r.intent == "ESCORT_REQUEST"
        assert r.entities.get("destination_room") == "P_KHAM_NOI_01"

    def test_nha_thuoc_o_dau(self, classifier):
        r = classifier.classify("Nhà thuốc ở đâu?")
        assert r.intent == "ESCORT_REQUEST"
        assert r.entities.get("destination_room") == "P_NHA_THUOC"

    def test_chi_duong_xet_nghiem(self, classifier):
        r = classifier.classify("Chỉ đường đến phòng xét nghiệm")
        assert r.intent == "ESCORT_REQUEST"
        assert r.entities.get("destination_room") == "P_XET_NGHIEM"

    def test_dua_toi_cap_cuu(self, classifier):
        r = classifier.classify("Đưa tôi đến phòng cấp cứu")
        assert r.intent == "ESCORT_REQUEST"
        assert r.entities.get("destination_room") == "P_CAP_CUU"


class TestHospitalInfo:
    def test_gio_lam_viec(self, classifier):
        r = classifier.classify("Bệnh viện mấy giờ mở cửa?")
        assert r.intent == "QUERY_HOSPITAL_INFO"

    def test_chi_phi(self, classifier):
        r = classifier.classify("Chi phí khám bệnh bao nhiêu?")
        assert r.intent == "QUERY_HOSPITAL_INFO"

    def test_bac_si(self, classifier):
        r = classifier.classify("Bác sĩ chuyên khoa nội là ai?")
        assert r.intent == "QUERY_HOSPITAL_INFO"

    def test_thu_tuc(self, classifier):
        r = classifier.classify("Thủ tục đăng ký khám bệnh như thế nào?")
        assert r.intent == "QUERY_HOSPITAL_INFO"

    def test_wifi(self, classifier):
        r = classifier.classify("Wifi bệnh viện mật khẩu là gì?")
        assert r.intent == "QUERY_HOSPITAL_INFO"

    def test_nha_ve_sinh(self, classifier):
        r = classifier.classify("Nhà vệ sinh ở đâu?")
        assert r.intent == "QUERY_HOSPITAL_INFO"

    def test_bao_hiem(self, classifier):
        r = classifier.classify("Bệnh viện có nhận bảo hiểm y tế không?")
        assert r.intent == "QUERY_HOSPITAL_INFO"


class TestSOS:
    def test_cap_cuu(self, classifier):
        r = classifier.classify("Cấp cứu! Tôi bị ngã!")
        assert r.intent == "EMERGENCY_SOS"
        assert r.confidence >= 0.9

    def test_giup_toi(self, classifier):
        r = classifier.classify("Giúp tôi, tôi khó thở")
        assert r.intent == "EMERGENCY_SOS"


class TestFarewell:
    def test_tam_biet(self, classifier):
        r = classifier.classify("Tạm biệt")
        assert r.intent == "FAREWELL"

    def test_cam_on(self, classifier):
        r = classifier.classify("Cảm ơn robot")
        assert r.intent == "FAREWELL"


class TestUnknown:
    def test_gibberish(self, classifier):
        r = classifier.classify("abc xyz 123")
        assert r.intent == "UNKNOWN"

    def test_empty(self, classifier):
        r = classifier.classify("")
        assert r.intent == "UNKNOWN"
