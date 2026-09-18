#!/usr/bin/env python3
"""
Ingestion CLI — feed content into the Genesis AI knowledge base.

Usage:
  python ingest.py --youtube "https://youtube.com/watch?v=..."
  python ingest.py --vimeo "https://vimeo.com/..."
  python ingest.py --pdf "/path/to/book.pdf"
  python ingest.py --pptx "/path/to/deck.pptx"
  python ingest.py --txt "/path/to/transcript.txt"    # ingest pre-extracted text file
  python ingest.py --url "https://brandingstrategyinsider.com/post"
  python ingest.py --folder "/path/to/docs/"
  python ingest.py --folder "/path/to/client/" --layer layer2
  python ingest.py --reembed                          # re-embed all chunks with current model
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from pipeline import process, reembed


def ingest_youtube(url: str, layer: str = "layer1", force: bool = False) -> None:
    from extractors.youtube import extract
    print(f"YouTube: {url}")
    data = extract(url)
    count = process(
        text=data["text"],
        source_url=url,
        source_type="youtube",
        content_category="youtube",
        title=data["title"],
        author=data["author"],
        date=data["date"],
        layer=layer,
        force=force,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_vimeo(url: str, layer: str = "layer1", force: bool = False) -> None:
    from extractors.vimeo import extract
    print(f"Vimeo: {url}")
    data = extract(url)
    count = process(
        text=data["text"],
        source_url=url,
        source_type="vimeo",
        content_category="video",
        title=data["title"],
        author=data["author"],
        date=data["date"],
        layer=layer,
        force=force,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_pdf(file_path: str, layer: str = "layer1", force: bool = False,
               anonymise: bool = False, content_category: str = "document") -> None:
    from extractors.pdf import extract
    path = Path(file_path).resolve()
    source_url = f"file://{path}"
    print(f"PDF: {path.name}")
    data = extract(str(path))
    count = process(
        text=data["text"],
        source_url=source_url,
        source_type="pdf",
        content_category=content_category,
        title=data["title"],
        author=data["author"],
        layer=layer,
        force=force,
        anonymise=anonymise,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_pptx(file_path: str, layer: str = "layer1", force: bool = False,
                anonymise: bool = False, content_category: str = "document") -> None:
    from extractors.pptx import extract
    path = Path(file_path).resolve()
    source_url = f"file://{path}"
    print(f"PPTX: {path.name}")
    data = extract(str(path))
    count = process(
        text=data["text"],
        source_url=source_url,
        source_type="pptx",
        content_category=content_category,
        title=data["title"],
        author=data["author"],
        layer=layer,
        force=force,
        anonymise=anonymise,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_txt(file_path: str, layer: str = "layer1", force: bool = False, normalise: bool = True,
               content_category: str = "document") -> None:
    path = Path(file_path).resolve()
    source_url = f"file://{path}"
    print(f"Text: {path.name}")
    text = path.read_text(encoding="utf-8", errors="replace")
    count = process(
        text=text,
        source_url=source_url,
        source_type="file",
        content_category=content_category,
        title=path.stem,
        layer=layer,
        force=force,
        normalise=normalise,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_url(url: str, layer: str = "layer1", force: bool = False,
               content_category: str = "web") -> None:
    from extractors.web import extract
    print(f"Web: {url}")
    data = extract(url)
    count = process(
        text=data["text"],
        source_url=url,
        source_type="web",
        content_category=content_category,
        title=data["title"],
        author=data["author"],
        date=data["date"],
        layer=layer,
        force=force,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_channel(channel_url: str, layer: str = "layer1", force: bool = False) -> None:
    import yt_dlp

    # Normalise to /videos tab so yt-dlp returns individual videos not channel tabs
    if "/@" in channel_url or "/channel/" in channel_url or "/c/" in channel_url:
        channel_url = channel_url.rstrip("/") + "/videos"

    print(f"Discovering videos: {channel_url}")
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": "in_playlist",
        "ignoreerrors": True,
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(channel_url, download=False)

    entries = [e for e in (info.get("entries") or []) if e and e.get("id")]
    print(f"Found {len(entries)} videos. Starting ingestion...\n")
    total = 0
    for i, entry in enumerate(entries, 1):
        url = f"https://www.youtube.com/watch?v={entry['id']}"
        print(f"[{i}/{len(entries)}] {entry.get('title', url)}")
        try:
            ingest_youtube(url, layer=layer, force=force)
            total += 1
        except Exception as e:
            print(f"  Skipped (error): {e}\n")
    print(f"\nDone. {total}/{len(entries)} videos ingested.")


def ingest_crawl(index_url: str, layer: str = "layer1", force: bool = False) -> None:
    from extractors.crawl import discover_urls
    print(f"Crawling: {index_url}")
    urls = discover_urls(index_url, verbose=True)
    print(f"\nFound {len(urls)} articles. Starting ingestion...\n")
    total = 0
    for i, url in enumerate(urls, 1):
        print(f"[{i}/{len(urls)}] {url}")
        try:
            ingest_url(url, layer=layer, force=force)
            total += 1
        except Exception as e:
            print(f"  Skipped (error): {e}\n")
    print(f"\nDone. {total}/{len(urls)} articles ingested.")


def ingest_folder(folder_path: str, layer: str = "layer1", force: bool = False,
                  anonymise: bool = False, content_category: str = "") -> None:
    folder = Path(folder_path)
    if not folder.exists() or not folder.is_dir():
        print(f"Folder not found: {folder_path}")
        return

    pdf_files = list(folder.rglob("*.pdf"))
    txt_files = list(folder.rglob("*.txt"))
    md_files = list(folder.rglob("*.md"))

    anon_note = " [anonymise=ON]" if anonymise else ""
    print(f"Folder: {folder_path} — found {len(pdf_files)} PDFs, {len(txt_files)} TXT, {len(md_files)} MD{anon_note}\n")

    pdf_cat = content_category or "document"
    for f in pdf_files:
        ingest_pdf(str(f), layer=layer, force=force, anonymise=anonymise, content_category=pdf_cat)

    for f in txt_files + md_files:
        text = f.read_text(encoding="utf-8", errors="replace")
        source_url = f"file://{f.resolve()}"
        print(f"Text file: {f.name}")
        count = process(
            text=text,
            source_url=source_url,
            source_type="file",
            content_category=content_category or "document",
            title=f.stem,
            layer=layer,
            force=force,
            anonymise=anonymise,
        )
        print(f"  → {count} chunks ingested\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Genesis AI knowledge base ingestion")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--youtube", metavar="URL", help="Ingest a YouTube video transcript")
    group.add_argument("--vimeo", metavar="URL", help="Ingest a Vimeo video (downloads audio, transcribes with Whisper)")
    group.add_argument("--pdf", metavar="FILE", help="Ingest a PDF file")
    group.add_argument("--pptx", metavar="FILE", help="Ingest a PowerPoint (.pptx) file")
    group.add_argument("--url", metavar="URL", help="Ingest a web article")
    group.add_argument("--channel", metavar="URL", help="Ingest all videos from a YouTube channel or playlist")
    group.add_argument("--crawl", metavar="URL", help="Crawl a blog index and ingest all articles")
    group.add_argument("--folder", metavar="PATH", help="Batch ingest a folder of PDFs/text files")
    group.add_argument("--txt", metavar="FILE", help="Ingest a plain text file (pre-extracted transcript or document)")
    group.add_argument("--reembed", action="store_true", help="Re-embed all chunks with current embedding model")
    parser.add_argument("--layer", default="layer1", choices=["layer1", "layer2"], help="Knowledge layer (default: layer1)")
    parser.add_argument("--force", action="store_true", help="Re-ingest even if source was already ingested")
    parser.add_argument("--no-normalise", dest="no_normalise", action="store_true", help="Skip GPT normalisation (use for already-normalised content)")
    parser.add_argument("--type", dest="content_type",
                        choices=["blog", "podcast", "web", "document", "youtube", "video", "playbook", "client"],
                        default=None,
                        help="Content category for display in Knowledge Explorer. "
                             "Use 'playbook' or 'client' for customer documents — anonymisation is applied automatically.")

    args = parser.parse_args()

    normalise = not args.no_normalise
    # Anonymisation is automatic for customer-facing content types
    anonymise = args.content_type in ("playbook", "client")

    if args.channel:
        ingest_channel(args.channel, layer=args.layer, force=args.force)
    elif args.crawl:
        ingest_crawl(args.crawl, layer=args.layer, force=args.force)
    elif args.reembed:
        reembed()
    elif args.youtube:
        ingest_youtube(args.youtube, layer=args.layer, force=args.force)
    elif args.vimeo:
        ingest_vimeo(args.vimeo, layer=args.layer, force=args.force)
    elif args.pdf:
        ingest_pdf(args.pdf, layer=args.layer, force=args.force,
                   anonymise=anonymise, content_category=args.content_type or "document")
    elif args.pptx:
        ingest_pptx(args.pptx, layer=args.layer, force=args.force,
                    anonymise=anonymise, content_category=args.content_type or "document")
    elif args.url:
        ingest_url(args.url, layer=args.layer, force=args.force,
                   content_category=args.content_type or "web")
    elif args.txt:
        ingest_txt(args.txt, layer=args.layer, force=args.force, normalise=normalise,
                   content_category=args.content_type or "document")
    elif args.folder:
        ingest_folder(args.folder, layer=args.layer, force=args.force,
                      anonymise=anonymise, content_category=args.content_type or "")


if __name__ == "__main__":
    main()
