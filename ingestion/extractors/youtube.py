"""Extract transcript from YouTube videos using yt-dlp."""

import json
import os
import tempfile


def extract(url: str) -> dict:
    """
    Extract transcript/subtitles from a YouTube video.
    Falls back to auto-generated captions if manual ones aren't available.
    Returns dict with keys: text, title, author, date.
    """
    import yt_dlp

    with tempfile.TemporaryDirectory() as tmpdir:
        ydl_opts = {
            "skip_download": True,
            "writesubtitles": True,
            "writeautomaticsub": True,
            "subtitlesformat": "json3",
            "subtitleslangs": ["en", "en-US", "en-GB"],
            "outtmpl": os.path.join(tmpdir, "%(id)s.%(ext)s"),
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

        # Find subtitle file
        video_id = info.get("id", "")
        subtitle_text = _read_subtitles(tmpdir, video_id)

        if not subtitle_text:
            raise ValueError(f"No transcript available for: {url}")

        return {
            "text": subtitle_text,
            "title": title,
            "author": author,
            "date": date,
        }


def _read_subtitles(tmpdir: str, video_id: str) -> str:
    import glob

    patterns = [
        f"{tmpdir}/{video_id}.en.json3",
        f"{tmpdir}/{video_id}.en-US.json3",
        f"{tmpdir}/{video_id}.en-GB.json3",
    ]
    # Also try any json3 file in the dir
    patterns += glob.glob(f"{tmpdir}/*.json3")

    for path in patterns:
        if os.path.exists(path):
            return _parse_json3(path)

    # Try vtt fallback
    for path in glob.glob(f"{tmpdir}/*.vtt"):
        return _parse_vtt(path)

    return ""


def _parse_json3(path: str) -> str:
    with open(path) as f:
        data = json.load(f)

    lines = []
    for event in data.get("events", []):
        segs = event.get("segs", [])
        text = "".join(s.get("utf8", "") for s in segs).strip()
        if text and text != "\n":
            lines.append(text)

    return " ".join(lines)


def _parse_vtt(path: str) -> str:
    with open(path) as f:
        content = f.read()

    lines = []
    for line in content.splitlines():
        line = line.strip()
        if not line or "-->" in line or line.startswith("WEBVTT") or line.isdigit():
            continue
        lines.append(line)

    return " ".join(lines)
