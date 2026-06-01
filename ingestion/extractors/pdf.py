"""Extract text from PDF files."""

from pathlib import Path


def extract(file_path: str) -> dict:
    """
    Extract text from a PDF file.
    Returns dict with keys: text, title, author.
    """
    import pdfplumber

    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"PDF not found: {file_path}")

    pages = []
    title = path.stem
    author = ""

    with pdfplumber.open(path) as pdf:
        if pdf.metadata:
            title = pdf.metadata.get("Title") or title
            author = pdf.metadata.get("Author") or ""

        for page in pdf.pages:
            text = page.extract_text()
            if text:
                pages.append(text.strip())

    return {
        "text": "\n\n".join(pages),
        "title": title,
        "author": author,
    }
