#!/usr/bin/env python3
"""Live interactive voice test using Laptop Microphone and Speaker."""
import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent
load_dotenv(ROOT / ".env", override=False)

from src.audio import MicrophoneCapture
from src.audio.player import play_wav
from src.web.app import chat_query, get_stats
from src.stt import create_stt
from src.tts import SpeechClient
from src.utils.logger import setup_logger

log = setup_logger("live_voice_test")


def main():
    print("\n" + "=" * 65)
    print(" 🎙️  KIỂM THỬ GIAO TIẾP THỰC TẾ: MICROPHONE & LOA LAPTOP")
    print("=" * 65 + "\n")

    openai_key = os.getenv("OPENAI_API_KEY", "")
    stt_provider = os.getenv("STT_PROVIDER", "whisper")

    # 1. Initialize Components
    print("[1/5] Khởi tạo hệ thống thu âm và phát loa...")
    mic = MicrophoneCapture(
        sample_rate=16000,
        channels=1,
        silence_ms=1800,
        max_sec=20,
        energy_threshold=0.010,
    )

    class DummyBackend:
        def synthesize_speech(self, **kwargs):
            return None

    tts = SpeechClient(DummyBackend(), speed_wpm=150, openai_api_key=openai_key)

    # 2. Initialize STT
    print(f"[2/5] Khởi tạo Speech-to-Text ({stt_provider.upper()})...")
    stt = create_stt(stt_provider, api_key=openai_key)

    # 3. Initialize RAG & Vector P
    print("[3/5] Nạp cơ sở dữ liệu Vector ('Vector P')...")
    stats = get_stats()
    print(f"  ↳ {stats['vector_points_count']} đoạn tài liệu; {stats['faq_active_count']} FAQ đang bật.")

    # 4. Speak Greeting
    greeting_text = "Xin chào bạn! Tôi là robot hỗ trợ bệnh viện MediAssist. Hệ thống mic và loa đã sẵn sàng. Mời bạn đặt câu hỏi ạ."
    print("\n[4/5] 🔊 Đang phát lời chào qua LOA LAPTOP...")
    print(f"  ↳ Robot nói: \"{greeting_text}\"")
    tts.speak(greeting_text)

    # 5. Listen through Microphone
    print("\n[5/5] 🎤 ĐANG LẮNG NGHE QUA MICRO LAPTOP (Hãy nói vào micro của bạn ngay bây giờ)...")
    print("  ↳ Ví dụ nói: \"Bệnh viện mấy giờ mở cửa?\" hoặc \"Dẫn tôi đến phòng khám nội\"")

    def on_start():
        print("  🟢 Đã phát hiện giọng nói! Đang ghi âm...")

    wav_bytes = mic.record_until_silence(on_speech_start=on_start)

    if not wav_bytes:
        print("\n⚠️ Không phát hiện thấy âm thanh từ micro (hoặc âm lượng quá nhỏ).")
        print("Robot sẽ thử phát câu nhắc nhở...")
        tts.speak("Dạ, tôi chưa nghe thấy bạn nói gì. Bạn có thể thử lại được không ạ?")
        return

    print("\n⏳ Đang nhận diện giọng nói (Speech-to-Text)...")
    user_text = stt.transcribe(wav_bytes, language="vi")

    if not user_text:
        print("❌ Không nhận diện được nội dung giọng nói.")
        tts.speak("Xin lỗi, tôi chưa nghe rõ câu hỏi. Bạn vui lòng nói lại ạ.")
        return

    print(f"\n🗣️ BẠN ĐÃ NÓI: \"{user_text}\"")

    # Use exactly the same FAQ/intent/RAG path as Studio. This test never
    # creates motion jobs or SOS incidents and must not claim that it does.
    print("🔍 Đang tra cứu cơ sở dữ liệu Vector ('Vector P')...")
    result = chat_query(user_text)
    answer_text = result['answer']
    print(f"🎯 Ý định: {result['intent']}; cách trả lời: {result['answer_mode']}")
    if result['sources']:
        print(f"  ↳ Nguồn: {result['sources'][0]['source']}")

    print(f"\n🤖 ROBOT TRẢ LỜI: \"{answer_text}\"")
    print("🔊 Đang phát giọng nói qua LOA LAPTOP...")
    if tts.speak(answer_text):
        print("\n✅ Đã hoàn tất lượt phát âm thanh; kiểm tra bằng cách nghe loa laptop.\n")
    else:
        print("\nKhông phát được giọng đọc. Hãy kiểm tra thiết bị âm thanh và dịch vụ TTS.\n")


if __name__ == "__main__":
    main()
