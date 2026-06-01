#!/usr/bin/env python3
"""
Feed monitor — watches RSS feeds and YouTube channels for new content.
Run manually or schedule as a cron job: 0 */6 * * * python /path/to/monitor.py

Reads watched sources from: knowledge/sources-watchlist.yaml
"""

import sys
import time
from pathlib import Path

import feedparser
import yaml
from dotenv import load_dotenv

sys.path.insert(0, str(Path(__file__).parent))

load_dotenv()

WATCHLIST = Path(__file__).parent.parent / "knowledge" / "sources-watchlist.yaml"


def _load_watchlist() -> dict:
    if not WATCHLIST.exists():
        print(f"No watchlist found at {WATCHLIST}. Create it to start monitoring.")
        return {}
    with open(WATCHLIST) as f:
        return yaml.safe_load(f) or {}


def _check_rss_feed(feed_url: str, name: str) -> int:
    from ingest import ingest_url

    print(f"Checking RSS: {name}")
    feed = feedparser.parse(feed_url)
    count = 0
    for entry in feed.entries[:10]:
        url = entry.get("link", "")
        if not url:
            continue
        try:
            ingest_url(url)
            count += 1
            time.sleep(1)
        except Exception as e:
            print(f"  Skipped {url}: {e}")
    return count


def _check_youtube_channel(channel_id: str, name: str) -> int:
    from ingest import ingest_youtube

    rss_url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    print(f"Checking YouTube channel: {name}")
    feed = feedparser.parse(rss_url)
    count = 0
    for entry in feed.entries[:5]:
        url = entry.get("link", "")
        if not url:
            continue
        try:
            ingest_youtube(url)
            count += 1
            time.sleep(2)
        except Exception as e:
            print(f"  Skipped {url}: {e}")
    return count


def run() -> None:
    config = _load_watchlist()
    if not config:
        return

    total = 0

    for channel in config.get("youtube_channels", []):
        total += _check_youtube_channel(channel["id"], channel.get("name", channel["id"]))

    for feed in config.get("rss_feeds", []):
        total += _check_rss_feed(feed["url"], feed.get("name", feed["url"]))

    print(f"\nMonitor complete. {total} new items ingested.")


if __name__ == "__main__":
    run()
