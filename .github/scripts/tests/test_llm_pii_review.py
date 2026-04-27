"""Unit tests for llm_pii_review.py.

These exercise the script's mock mode end-to-end (file selection, mock client
dispatch, comment rendering) without making any real API calls. Run with:

    pytest .github/scripts/tests
"""

from __future__ import annotations

import json
import os
import sys
from io import StringIO
from pathlib import Path
from unittest import mock

# Make the script importable. CI installs the script's deps; tests import it
# as a module to call its functions directly.
SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import llm_pii_review as script  # noqa: E402


# ---------- _should_review --------------------------------------------------


def test_should_review_includes_kotlin():
    assert script._should_review("src/main/kotlin/X.kt", "@@ +1 @@\n+val x = 1\n")


def test_should_review_includes_yaml_and_md():
    patch = "@@ +1 @@\n+x: 1\n"
    assert script._should_review(".github/workflows/ci.yml", patch)
    assert script._should_review("README.md", patch)


def test_should_review_skips_unrelated_extensions():
    patch = "@@ +1 @@\n+x = 1\n"
    assert not script._should_review("foo.py", patch)
    assert not script._should_review("foo.gradle", patch)


def test_should_review_skips_empty_patch():
    assert not script._should_review("X.kt", "")
    assert not script._should_review("X.kt", None)  # type: ignore[arg-type]


# ---------- MockClient ------------------------------------------------------


def test_mock_client_returns_no_findings_for_clean_patch():
    client = script.MockClient()
    out = client.review("prompt", "File: `X.kt`\n\n```diff\n+ clean\n```")
    assert out == "NO_FINDINGS"


def test_mock_client_returns_finding_when_marker_present():
    client = script.MockClient()
    out = client.review("prompt", f"File: `X.kt`\n\n+ {script.MOCK_TRIGGER_MARKER}")
    # Output must be a parseable Markdown table - contract with render_comment.
    assert out.startswith("| File | Line | Quote | Risk | Remediation |")
    assert script.MOCK_TRIGGER_MARKER in out


# ---------- main() in --mock mode -------------------------------------------


def _run(argv, env, capsys):
    with mock.patch.dict(os.environ, env, clear=False):
        rc = script.main(argv)
    captured = capsys.readouterr()
    return rc, captured.out, captured.err


def test_mock_with_clean_files_exits_zero_and_says_clean(tmp_path, capsys):
    diffs = tmp_path / "diffs.json"
    diffs.write_text(json.dumps([
        {"filename": "src/main/kotlin/X.kt",
         "patch": "@@ +1 @@\n+val x = 1\n"},
    ]))
    rc, out, _ = _run(
        ["--mock", "--diffs-file", str(diffs)],
        env={},
        capsys=capsys,
    )
    assert rc == 0
    assert "PII review: clean" in out


def test_mock_with_marker_prints_comment_body(tmp_path, capsys):
    diffs = tmp_path / "diffs.json"
    diffs.write_text(json.dumps([
        {"filename": "src/main/kotlin/Leak.kt",
         "patch": f"@@ +1 @@\n+val x = \"{script.MOCK_TRIGGER_MARKER}\"\n"},
        {"filename": "src/main/kotlin/Clean.kt",
         "patch": "@@ +1 @@\n+val y = 2\n"},
    ]))
    rc, out, _ = _run(
        ["--mock", "--diffs-file", str(diffs)],
        env={},
        capsys=capsys,
    )
    assert rc == 0
    assert "comment that would be posted" in out
    assert "## LLM PII review" in out
    assert "src/main/kotlin/Leak.kt" in out
    # Clean file must not appear as a heading.
    assert "### `src/main/kotlin/Clean.kt`" not in out


def test_missing_api_key_outside_mock_exits_zero_with_skip_message(tmp_path, capsys, monkeypatch):
    # Real-mode path: clear ANTHROPIC_API_KEY and assert the script exits 0
    # cleanly with the documented log line. The workflow always succeeds even
    # without the secret.
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    rc, out, _ = _run(
        argv=[],
        env={"ANTHROPIC_API_KEY": ""},
        capsys=capsys,
    )
    assert rc == 0
    assert "ANTHROPIC_API_KEY not configured" in out


def test_render_comment_emits_one_section_per_finding():
    findings = [
        ("src/main/kotlin/A.kt", "| File | Line | Quote | Risk | Remediation |\n|---|---|---|---|---|\n| A | 1 | x | y | z |"),
        ("src/main/kotlin/B.kt", "| File | Line | Quote | Risk | Remediation |\n|---|---|---|---|---|\n| B | 2 | x | y | z |"),
    ]
    body = script.render_comment(findings)
    assert body.count("### `") == 2
    assert "src/main/kotlin/A.kt" in body
    assert "src/main/kotlin/B.kt" in body
    assert script.ANTHROPIC_MODEL in body  # comment surfaces the model
