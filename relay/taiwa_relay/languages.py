"""Conversation languages: what Whisper listens for, what ChatGPT answers in, what the TV reads aloud.

Add a language by adding a row; the TV picks its text-to-speech voices by the same BCP-47 code.
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Language:
    code: str            # BCP-47, also used by the TV for its TTS locale
    name: str            # English name, used in the instructions to ChatGPT
    native: str          # shown in the TV's language list
    whisper: str         # whisper.cpp language code
    prompt: str = ""     # whisper initial prompt: nudges script / punctuation
    opencc: str | None = None   # OpenCC conversion applied to transcripts
    preview: str = "Hi, hello"  # TTS voice preview on the TV
    cjk: bool = False    # length limits for the spoken version are in characters
    hallucinations: tuple[str, ...] = field(default_factory=tuple)  # whole-utterance junk on silence


LANGUAGES = [
    Language("en-US", "English", "English", "en",
             hallucinations=("thank you for watching", "thanks for watching", "you")),
    Language("zh-CN", "Simplified Chinese", "简体中文", "zh", "以下是普通话的句子，使用简体中文。", "t2s",
             "Hi，你好", True, ("谢谢观看", "感谢观看", "谢谢收看", "字幕", "中文字幕")),
    Language("zh-TW", "Traditional Chinese", "繁體中文", "zh", "以下是普通話的句子，使用繁體中文。", "s2twp",
             "Hi，你好", True, ("謝謝觀看", "感謝觀看", "字幕")),
    Language("ja-JP", "Japanese", "日本語", "ja", "以下は日本語の文です。", None,
             "こんにちは", True, ("ご視聴ありがとうございました", "ご視聴ありがとうございました。")),
    Language("ko-KR", "Korean", "한국어", "ko", preview="안녕하세요", cjk=True,
             hallucinations=("시청해 주셔서 감사합니다",)),
    Language("es-ES", "Spanish", "Español", "es", preview="Hola"),
    Language("fr-FR", "French", "Français", "fr", preview="Bonjour"),
    Language("de-DE", "German", "Deutsch", "de", preview="Hallo"),
    Language("it-IT", "Italian", "Italiano", "it", preview="Ciao"),
    Language("pt-BR", "Brazilian Portuguese", "Português (Brasil)", "pt", preview="Olá"),
    Language("ru-RU", "Russian", "Русский", "ru", preview="Привет"),
    Language("vi-VN", "Vietnamese", "Tiếng Việt", "vi", preview="Xin chào"),
    Language("th-TH", "Thai", "ไทย", "th", preview="สวัสดี", cjk=True),
]

BY_CODE = {lang.code: lang for lang in LANGUAGES}


def get(code: str | None) -> Language:
    return BY_CODE.get(code or "", LANGUAGES[0])
