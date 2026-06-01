#!/usr/bin/env python3
"""
Ingestion CLI — feed content into the Genesis AI knowledge base.

Usage:
  python ingest.py --youtube "https://youtube.com/watch?v=..."
  python ingest.py --pdf "/path/to/book.pdf"
  python ingest.py --url "https://brandingstrategyinsider.com/post"
  python ingest.py --folder "/path/to/docs/"
  python ingest.py --reembed                          # re-embed all chunks with current model
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from pipeline import process, reembed


def ingest_youtube(url: str, force: bool = False) -> None:
    from extractors.youtube import extract
    print(f"YouTube: {url}")
    data = extract(url)
    count = process(
        text=data["text"],
        source_url=url,
        source_type="youtube",
        title=data["title"],
        author=data["author"],
        date=data["date"],
        force=force,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_pdf(file_path: str, force: bool = False) -> None:
    from extractors.pdf import extract
    path = Path(file_path).resolve()
    source_url = f"file://{path}"
    print(f"PDF: {path.name}")
    data = extract(str(path))
    count = process(
        text=data["text"],
        source_url=source_url,
        source_type="pdf",
        title=data["title"],
        author=data["author"],
        force=force,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_url(url: str, force: bool = False) -> None:
    from extractors.web import extract
    print(f"Web: {url}")
    data = extract(url)
    count = process(
        text=data["text"],
        source_url=url,
        source_type="web",
        title=data["title"],
        author=data["author"],
        date=data["date"],
        force=force,
    )
    print(f"  → {count} chunks ingested\n")


def ingest_crawl(index_url: str, force: bool = False) -> None:
    from extractors.crawl import discover_urls
    print(f"Crawling: {index_url}")
    urls = discover_urls(index_url, verbose=True)
    print(f"\nFound {len(urls)} articles. Starting ingestion...\n")
    total = 0
    for i, url in enumerate(urls, 1):
        print(f"[{i}/{len(urls)}] {url}")
        try:
            ingest_url(url, force=force)
            total += 1
        except Exception as e:
            print(f"  Skipped (error): {e}\n")
    print(f"\nDone. {total}/{len(urls)} articles ingested.")


def ingest_folder(folder_path: str, force: bool = False) -> None:
    folder = Path(folder_path)
    if not folder.exists() or not folder.is_dir():
        print(f"Folder not found: {folder_path}")
        return

    pdf_files = list(folder.rglob("*.pdf"))
    txt_files = list(folder.rglob("*.txt"))
    md_files = list(folder.rglob("*.md"))

    print(f"Folder: {folder_path} — found {len(pdf_files)} PDFs, {len(txt_files)} TXT, {len(md_files)} MD\n")

    for f in pdf_files:
        ingest_pdf(str(f), force=force)

    for f in txt_files + md_files:
        text = f.read_text(encoding="utf-8", errors="replace")
        source_url = f"file://{f.resolve()}"
        print(f"Text file: {f.name}")
        count = process(
            text=text,
            source_url=source_url,
            source_type="file",
            title=f.stem,
            force=force,
        )
        print(f"  → {count} chunks ingested\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Genesis AI knowledge base ingestion")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--youtube", metavar="URL", help="Ingest a YouTube video transcript")
    group.add_argument("--pdf", metavar="FILE", help="Ingest a PDF file")
    group.add_argument("--url", metavar="URL", help="Ingest a web article")
    group.add_argument("--crawl", metavar="URL", help="Crawl a blog index and ingest all articles")
    group.add_argument("--folder", metavar="PATH", help="Batch ingest a folder of PDFs/text files")
    group.add_argument("--reembed", action="store_true", help="Re-embed all chunks with current embedding model")
    parser.add_argument("--force", action="store_true", help="Re-ingest even if source was already ingested")

    args = parser.parse_args()

    if args.crawl:
        ingest_crawl(args.crawl, force=args.force)
    elif args.reembed:
        reembed()
    elif args.youtube:
        ingest_youtube(args.youtube, force=args.force)
    elif args.pdf:
        ingest_pdf(args.pdf, force=args.force)
    elif args.url:
        ingest_url(args.url, force=args.force)
    elif args.folder:
        ingest_folder(args.folder, force=args.force)


if __name__ == "__main__":
    main()
