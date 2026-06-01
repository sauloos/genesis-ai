"""Discover all article URLs from a blog index page."""

import re
from urllib.parse import urljoin, urlparse

HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; GenesisAI/1.0)"}
MAX_RETRIES = 3
RETRY_DELAY = 2


def discover_urls(index_url: str, verbose: bool = True) -> list[str]:
    """
    Discover all article URLs from a blog.
    Tries the WordPress REST API first (reliable, complete).
    Falls back to HTML heading-link crawling for non-WordPress sites.
    """
    import httpx

    parsed = urlparse(index_url)
    base = f"{parsed.scheme}://{parsed.netloc}"

    with httpx.Client(headers=HEADERS, follow_redirects=True, timeout=15) as client:
        urls = _try_wp_rest_api(client, base, verbose)
        if urls is not None:
            return urls
        if verbose:
            print("  WordPress REST API not available, falling back to HTML crawl")
        return _crawl_html(client, index_url, base, verbose)


def _try_wp_rest_api(client, base: str, verbose: bool) -> list[str] | None:
    """
    Fetch all post URLs via the WordPress REST API.
    Returns list of URLs, or None if the API is not available.
    """
    import time

    api_base = f"{base}/wp-json/wp/v2/posts"
    test = _fetch_with_retry(client, f"{api_base}?per_page=100&page=1", verbose=False)
    if test is None or test.status_code != 200:
        return None

    total = int(test.headers.get("X-WP-Total", 0))
    total_pages = int(test.headers.get("X-WP-TotalPages", 1))
    if verbose:
        print(f"  WordPress REST API: {total} posts across {total_pages} pages")

    urls = []
    # page 1 already fetched — collect it first
    for post in test.json():
        link = post.get("link", "").rstrip("/")
        if link and link not in urls:
            urls.append(link)

    for page in range(2, total_pages + 1):
        resp = _fetch_with_retry(client, f"{api_base}?per_page=100&page={page}", verbose)
        if resp is None or resp.status_code != 200:
            if verbose:
                print(f"  Skipped API page {page}")
            continue
        for post in resp.json():
            link = post.get("link", "").rstrip("/")
            if link and link not in urls:
                urls.append(link)
        if verbose:
            print(f"  API page {page}/{total_pages} — {len(urls)} posts collected")
        time.sleep(0.1)  # polite delay

    return urls


def _crawl_html(client, index_url: str, base: str, verbose: bool) -> list[str]:
    """HTML fallback: scan blog index pages and extract article links from headings."""
    parsed = urlparse(index_url)
    index_path = parsed.path.rstrip("/")

    seen: set[str] = set()
    article_urls: list[str] = []

    max_page = _find_max_page(client, index_url, index_path, base)
    if verbose:
        print(f"  Pagination: pages 1–{max_page}")

    all_pages = [index_url] + [
        f"{base}{index_path}/page/{n}" for n in range(2, max_page + 1)
    ]

    for page_url in all_pages:
        if page_url in seen:
            continue
        seen.add(page_url)

        resp = _fetch_with_retry(client, page_url, verbose)
        if resp is None:
            continue

        for link in _extract_heading_links(resp.text, base):
            if link not in article_urls and link not in seen:
                article_urls.append(link)

        if verbose:
            print(f"  Scanned {page_url} — {len(article_urls)} articles found so far")

    return article_urls


def _find_max_page(client, index_url: str, index_path: str, base: str) -> int:
    seen: set[str] = set()
    to_visit = [index_url]
    max_page = 1
    while to_visit:
        url = to_visit.pop(0)
        if url in seen:
            continue
        seen.add(url)
        resp = _fetch_with_retry(client, url, verbose=False)
        if resp is None:
            continue
        for link in _extract_links(resp.text, base):
            lpath = urlparse(link).path.rstrip("/")
            m = re.search(rf"^{re.escape(index_path)}/page/(\d+)$", lpath)
            if m:
                n = int(m.group(1))
                if n > max_page:
                    max_page = n
                if link not in seen:
                    to_visit.append(link)
    return max_page


def _fetch_with_retry(client, url: str, verbose: bool):
    import time
    import httpx
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            resp = client.get(url)
            if resp.status_code >= 500:
                if attempt < MAX_RETRIES:
                    if verbose:
                        print(f"  {resp.status_code} on {url}, retrying ({attempt}/{MAX_RETRIES - 1})...")
                    time.sleep(RETRY_DELAY)
                else:
                    if verbose:
                        print(f"  Giving up on {url} after {MAX_RETRIES} attempts ({resp.status_code})")
                continue
            if len(resp.text) > 10000:
                return resp
            if verbose:
                print(f"  Skipping {url} (status {resp.status_code}, no content)")
            return None
        except Exception as e:
            if attempt < MAX_RETRIES:
                if verbose:
                    print(f"  Error fetching {url}: {e}, retrying ({attempt}/{MAX_RETRIES - 1})...")
                time.sleep(RETRY_DELAY)
            else:
                if verbose:
                    print(f"  Giving up on {url}: {e}")
    return None


def _extract_heading_links(html: str, base: str) -> list[str]:
    try:
        from lxml import etree
        parser = etree.HTMLParser()
        tree = etree.fromstring(html.encode() if isinstance(html, str) else html, parser)
        links = []
        for heading in tree.iter("h1", "h2", "h3", "h4"):
            for el in heading.iter("a"):
                href = el.get("href", "")
                if not href or href.startswith("#") or href.startswith("mailto:"):
                    continue
                full = urljoin(base, href).split("#")[0].rstrip("/")
                if urlparse(full).netloc == urlparse(base).netloc:
                    links.append(full)
        return list(dict.fromkeys(links))
    except Exception:
        return []


def _extract_links(html: str, base: str) -> list[str]:
    try:
        from lxml import etree
        parser = etree.HTMLParser()
        tree = etree.fromstring(html.encode() if isinstance(html, str) else html, parser)
        links = []
        for el in tree.iter("a"):
            href = el.get("href", "")
            if not href or href.startswith("#") or href.startswith("mailto:"):
                continue
            full = urljoin(base, href).split("#")[0].rstrip("/")
            if urlparse(full).netloc == urlparse(base).netloc:
                links.append(full)
        return list(dict.fromkeys(links))
    except Exception:
        return []
