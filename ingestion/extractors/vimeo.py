"""Extract transcript from Vimeo videos by downloading audio and transcribing with Whisper."""

import glob
import os
import tempfile


def extract(url: str) -> dict:
    """
    Download audio from a Vimeo video and transcribe with OpenAI Whisper.
    Returns dict with keys: text, title, author, date.
    Whisper supports: mp3, mp4, mpeg, mpga, m4a, wav, webm (max 25 MB each).
    """
    import yt_dlp
    from openai import OpenAI

    client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

    with tempfile.TemporaryDirectory() as tmpdir:
        # Download best audio-only stream without requiring ffmpeg
        ydl_opts = {
            "format": "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio",
            "outtmpl": os.path.join(tmpdir, "audio.%(ext)s"),
            "quiet": True,
            "no_warnings": True,
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)

        title = info.get("title", "")
        author = info.get("uploader", "")
        date = info.get("upload_date", "")
        if date and len(date) == 8:
            date = f"{date[:4]}-{date[4:6]}-{date[6:]}"

        # Find downloaded audio file
        audio_files = glob.glob(os.path.join(tmpdir, "audio.*"))
        if not audio_files:
            raise ValueError(f"Audio download failed for: {url}")

        audio_file = audio_files[0]
        file_size_mb = os.path.getsize(audio_file) / (1024 * 1024)
        ext = os.path.splitext(audio_file)[1]
        print(f"  Audio downloaded ({file_size_mb:.1f} MB{ext}) — transcribing with Whisper...")

        if file_size_mb > 24:
            raise ValueError(
                f"Audio file too large ({file_size_mb:.1f} MB) — install ffmpeg to enable chunked transcription."
            )

        with open(audio_file, "rb") as f:
            transcript = client.audio.transcriptions.create(
                model="whisper-1",
                file=f,
                response_format="text",
            )

        return {"text": str(transcript), "title": title, "author": author, "date": date}
