#!/usr/bin/env python3
"""Free, independent AI Report podcast RSS monitor: GitHub notification -> email."""
import json
import os
import pathlib
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

FEED = "https://rss.beehiiv.com/podcasts/019d2587-e790-7b44-bb7a-6eebcaae225c.xml"
STATE = pathlib.Path("podcast-monitor/ai_report_state.json")
REPO = os.environ.get("GITHUB_REPOSITORY", "jessevermeulen14-byte/Daghoroscoop-aanpassen")
ASSIGNEE = "jessevermeulen14-byte"

def parse_date(value):
    try:
        return parsedate_to_datetime(value).astimezone(timezone.utc)
    except (ValueError, TypeError, IndexError):
        return None

def fetch_episodes():
    req = urllib.request.Request(FEED, headers={"User-Agent": "AIReportPodcastNotification/1.0"})
    with urllib.request.urlopen(req, timeout=25) as response:
        xml = response.read()
    channel = ET.fromstring(xml).find("channel")
    if channel is None:
        raise RuntimeError("No RSS channel in response")
    found = []
    for item in channel.findall("item"):
        title = (item.findtext("title") or "").strip()
        url = (item.findtext("link") or "").strip()
        guid = (item.findtext("guid") or url or title).strip()
        pub = parse_date(item.findtext("pubDate"))
        if title and guid and url:
            found.append({"id": guid, "title": title, "url": url,
                          "published": pub.isoformat() if pub else ""})
    if not found:
        raise RuntimeError("Feed contains no playable episodes")
    return found[:100]

def save_state(data):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def send_issue(episode):
    title = "AI Report - nieuwe aflevering: " + episode["title"][:130]
    body = (f"@{ASSIGNEE}\n\n**Nieuwe aflevering van AI Report**\n\n"
            f"**{episode['title']}**\n\nBeluisteren: {episode['url']}\n\n"
            "Automatische podcastmelding via GitHub. "
            "Als GitHub e-mailmeldingen ingeschakeld zijn, komt dit ook in Gmail.")
    subprocess.run(["gh", "issue", "create", "--repo", REPO,
                    "--title", title, "--body", body, "--assignee", ASSIGNEE],
                   check=True, timeout=40)

def main():
    episodes = fetch_episodes()
    if not STATE.exists():
        save_state({"seen": [ep["id"] for ep in episodes],
                    "baseline": datetime.now(timezone.utc).isoformat()})
        print(f"Initial RSS baseline: {episodes[0]['title']!r}; no old episodes notified")
        return

    state = json.loads(STATE.read_text(encoding="utf-8"))
    seen = set(state.get("seen", []))
    baseline = datetime.fromisoformat(state["baseline"])
    new = []
    for ep in reversed(episodes):
        pub = datetime.fromisoformat(ep["published"]) if ep["published"] else None
        if ep["id"] not in seen and (pub is None or pub >= baseline):
            new.append(ep)
    print(f"Checked {len(episodes)} RSS entries, {len(new)} new episodes")
    for ep in new:
        send_issue(ep)
        seen.add(ep["id"])
        state["seen"] = list(dict.fromkeys([e["id"] for e in episodes if e["id"] in seen] + list(seen)))[:150]
        save_state(state)
        print(f"Notified: {ep['title']}")

def commit_state():
    if not STATE.exists():
        return
    subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
    subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)
    subprocess.run(["git", "add", str(STATE)], check=True)
    changes = subprocess.run(["git", "diff", "--cached", "--quiet"])
    if changes.returncode != 0:
        subprocess.run(["git", "commit", "-m", "chore: track AI Report episodes seen [skip ci]"], check=True)
        subprocess.run(["git", "push"], check=True, timeout=40)

if __name__ == "__main__":
    main()
    commit_state()
