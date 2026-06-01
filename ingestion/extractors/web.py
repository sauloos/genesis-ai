"""Extract article text from web URLs using trafilatura."""


def extract(url: str) -> dict:
    """
    Extract clean article text from a web URL.
    Returns dict with keys: text, title, author, date.
    """
    import trafilatura
    from trafilatura.settings import use_config

    config = use_config()
    config.set("DEFAULT", "EXTRACTION_TIMEOUT", "30")

    downloaded = trafilatura.fetch_url(url)
    if not downloaded:
        raise ValueError(f"Failed to fetch URL: {url}")

    result = trafilatura.extract(
        downloaded,
        include_comments=False,
        include_tables=True,
        favor_precision=True,
        output_format="txt",
        with_metadata=True,
    )

    if not result:
        raise ValueError(f"No article content extracted from: {url}")

    # Extract metadata separately
    metadata = trafilatura.extract_metadata(downloaded)
    title = metadata.title if metadata and metadata.title else ""
    author = metadata.author if metadata and metadata.author else ""
    date = metadata.date if metadata and metadata.date else ""

    return {
        "text": result,
        "title": title,
        "author": author,
        "date": date,
    }
