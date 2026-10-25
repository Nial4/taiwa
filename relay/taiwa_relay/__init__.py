r"""Taiwa relay: the only thing the TV talks to.

TV --(LAN, bearer token)--> relay --> whisper.cpp (local speech-to-text)
                                  \--> api.openai.com/v1/responses (Sign in with ChatGPT)
"""
