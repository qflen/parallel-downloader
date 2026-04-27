#!/usr/bin/env python3
"""
LLM-driven PII review for parallel-downloader pull requests.

Reads the PR diff, sends each .kt / .kts / .java / .yml / .md file's patch to
Claude with the prompt template at prompts/pii_review.md, aggregates findings,
and posts a single PR comment if any findings exist.

Bails out cleanly (exit 0) when ANTHROPIC_API_KEY is not configured so the
workflow can be a no-op until the secret is wired into the repo. Supports a
--mock mode for unit tests and a --dry-run mode that prints what would be
posted instead of posting.

Required env when not in --mock or --dry-run:
  PR_NUMBER         pull request number
  REPO              "owner/name"
  GITHUB_TOKEN      token with PR comment write
  ANTHROPIC_API_KEY API key (omitted -> graceful no-op exit 0)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Iterable

# Pin the model name as a top-level constant so a future bump is one line. Use
# the latest top model; --mock skips the import path entirely.
ANTHROPIC_MODEL = "claude-opus-4-7"
PROMPT_PATH = Path(__file__).resolve().parents[2] / "prompts" / "pii_review.md"

# Files we care about. Other extensions are skipped to keep the budget bounded.
REVIEWED_EXTENSIONS = (".kt", ".kts", ".java", ".yml", ".yaml", ".md")

# Sentinel the mock client looks for: any patch containing this marker triggers
# a synthetic finding so the unit tests can exercise the findings code path
# without a real API call.
MOCK_TRIGGER_MARKER = "PII_TEST_MARKER"


def main(argv: list[str]) -> int:
    args = _parse_args(argv)

    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not args.mock and not args.dry_run and not api_key:
        # Graceful no-op when the secret isn't configured. The workflow stays
        # green; the skip surfaces in the action log.
        print("ANTHROPIC_API_KEY not configured, skipping LLM PII review.")
        return 0

    pr_number = os.environ.get("PR_NUMBER")
    repo = os.environ.get("REPO")
    token = os.environ.get("GITHUB_TOKEN")
    if not args.mock and (not pr_number or not repo or not token):
        print("PR_NUMBER, REPO, and GITHUB_TOKEN must be set; got "
              f"PR_NUMBER={bool(pr_number)} REPO={bool(repo)} GITHUB_TOKEN={bool(token)}",
              file=sys.stderr)
        return 64

    if args.diffs_file:
        files = json.loads(Path(args.diffs_file).read_text())
    elif args.mock:
        files = _stub_pr_files()
    else:
        files = fetch_pr_files(repo, pr_number, token)

    prompt_template = PROMPT_PATH.read_text()
    client = _build_client(args.mock, api_key)

    findings = []
    for entry in files:
        filename = entry.get("filename", "")
        patch = entry.get("patch") or ""
        if not _should_review(filename, patch):
            continue
        result = run_review(client, prompt_template, filename, patch)
        if result and result.strip() != "NO_FINDINGS":
            findings.append((filename, result.strip()))

    if not findings:
        print(f"PII review: clean ({len(files)} file(s) scanned).")
        return 0

    body = render_comment(findings)
    if args.dry_run or args.mock:
        print("--- comment that would be posted ---")
        print(body)
        return 0

    post_comment(repo, pr_number, token, body)
    print(f"PII review: posted comment with {len(findings)} file(s) flagged.")
    return 0


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LLM PII review")
    parser.add_argument("--mock", action="store_true",
                        help="Use a stub Anthropic client; for tests")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print the comment instead of posting")
    parser.add_argument("--diffs-file",
                        help="Read PR file list from this JSON file (test override)")
    return parser.parse_args(argv)


def _should_review(filename: str, patch: str) -> bool:
    return bool(patch) and filename.lower().endswith(REVIEWED_EXTENSIONS)


def fetch_pr_files(repo: str, pr_number: str, token: str) -> list[dict]:
    import requests  # local import so --mock doesn't require requests installed
    url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}/files"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    files = []
    page = 1
    while True:
        resp = requests.get(url, headers=headers, params={"per_page": 100, "page": page}, timeout=30)
        resp.raise_for_status()
        batch = resp.json()
        if not batch:
            break
        files.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return files


def post_comment(repo: str, pr_number: str, token: str, body: str) -> None:
    import requests
    url = f"https://api.github.com/repos/{repo}/issues/{pr_number}/comments"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    resp = requests.post(url, headers=headers, json={"body": body}, timeout=30)
    resp.raise_for_status()


def run_review(client, prompt_template: str, filename: str, patch: str) -> str:
    user_message = (
        f"File: `{filename}`\n\n"
        "Diff (unified format, only the hunks changed in this PR):\n\n"
        f"```diff\n{patch}\n```"
    )
    return client.review(prompt_template, user_message)


def render_comment(findings: Iterable[tuple[str, str]]) -> str:
    lines = [
        "## LLM PII review",
        "",
        "Findings from `prompts/pii_review.md` (model: `" + ANTHROPIC_MODEL + "`).",
        "Static counterpart: `./gradlew piiScan`.",
        "",
    ]
    for filename, body in findings:
        lines.append(f"### `{filename}`")
        lines.append("")
        lines.append(body)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


# ---------- clients --------------------------------------------------------

def _build_client(mock: bool, api_key: str):
    if mock:
        return MockClient()
    return AnthropicClient(api_key)


class MockClient:
    """Deterministic stub for tests. Returns NO_FINDINGS unless the patch
    contains MOCK_TRIGGER_MARKER, in which case it returns a synthetic
    structured finding row."""

    def review(self, prompt_template: str, user_message: str) -> str:
        if MOCK_TRIGGER_MARKER in user_message:
            return (
                "| File | Line | Quote | Risk | Remediation |\n"
                "|------|------|-------|------|-------------|\n"
                "| (mock) | 1 | `" + MOCK_TRIGGER_MARKER + "` | Mock finding "
                "triggered by sentinel marker | Remove the marker |"
            )
        return "NO_FINDINGS"


class AnthropicClient:
    """Real client. Imported lazily so the script can run with --mock and no
    `anthropic` package installed."""

    def __init__(self, api_key: str):
        from anthropic import Anthropic  # imported here on purpose
        self._client = Anthropic(api_key=api_key)

    def review(self, prompt_template: str, user_message: str) -> str:
        message = self._client.messages.create(
            model=ANTHROPIC_MODEL,
            max_tokens=2048,
            system=prompt_template,
            messages=[{"role": "user", "content": user_message}],
        )
        # Concatenate all text blocks - structured outputs come back as one
        # text block in practice but the API permits multiple.
        return "".join(b.text for b in message.content if getattr(b, "type", "") == "text")


# ---------- mock data for --mock without --diffs-file ----------------------

def _stub_pr_files() -> list[dict]:
    return [
        {
            "filename": "src/main/kotlin/com/example/downloader/ChunkPlan.kt",
            "patch": "@@ -1 +1,2 @@\n-old line\n+ new clean line\n",
        },
        {
            "filename": "src/main/kotlin/com/example/downloader/Leak.kt",
            "patch": f"@@ -1 +1 @@\n+val x = \"{MOCK_TRIGGER_MARKER}\"\n",
        },
    ]


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
