#!/usr/bin/env python3
"""Validate a generated design document.

Checks structure, evidence anchors, secret leakage and cross-references.
Stdlib only. Exit codes: 0 = pass, 1 = errors, 2 = bad usage.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

FENCE_RE = re.compile(r"^\s*(```|~~~)(.*)$")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*\S)\s*$")
FACT_RE = re.compile(r"〔FACT｜([^〕]+)〕")
INFER_RE = re.compile(r"〔INFER〕")
OBSERVE_RE = re.compile(r"〔OBSERVE｜([^〕]+)〕")
TOC_LINK_RE = re.compile(r"\[[^\]]*\]\(#([^)]+)\)")
SEPARATOR_RE = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")
EVIDENCE_REF_RE = re.compile(r"见\s*E-\d+")
P0_ROW_RE = re.compile(r"^\s*\|\s*P0-\d+")

MERMAID_KEYWORDS = (
    "graph", "flowchart", "sequencediagram", "statediagram", "classdiagram",
    "erdiagram", "gantt", "pie", "journey", "mindmap", "timeline",
    "quadrantchart", "gitgraph", "c4context", "c4container", "sankey",
    "xychart", "block", "requirementdiagram", "architecture",
)

SECRET_PATTERNS: List[Tuple[str, str]] = [
    (r"sk-[A-Za-z0-9]{16,}", "疑似 API Key（sk- 前缀）"),
    (r"\bAKIA[0-9A-Z]{16}\b", "疑似 AWS Access Key"),
    (r"\bgh[pousr]_[A-Za-z0-9]{20,}\b", "疑似 GitHub Token"),
    (r"-----BEGIN [A-Z ]*PRIVATE KEY-----", "疑似私钥"),
    (r"(?i)\b(api[_-]?key|secret|password|passwd|pwd|access[_-]?token)\b"
     r"\s*[:=]\s*['\"]?([A-Za-z0-9_\-./+]{8,})", "疑似明文凭据"),
    (r"\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b",
     "内网 IP"),
    (r"(?<!\d)1[3-9]\d{9}(?!\d)", "手机号"),
]

PLACEHOLDER_HINTS = (
    "your", "example", "placeholder", "change", "xxx", "***", "****",
    "redacted", "dummy", "sample", "<", ">", "$", "{", "}", "env",
)

NO_FACT_TYPES = {"brd-mrd", "prd", "operations-manual", "admin-quick-reference"}

OPERATOR_FORBIDDEN = [
    "API", "Token", "JSON", "HTTP", "Webhook", "OAuth", "Regex",
    "Rate Limit", "Quota", "Middleware",
]


@dataclass
class Issue:
    level: str  # error | warn
    code: str
    line: int
    message: str


@dataclass
class Report:
    path: str
    lines: int = 0
    headings: int = 0
    tables: int = 0
    mermaid: int = 0
    facts: int = 0
    infers: int = 0
    observes: int = 0
    anchors: List[Dict[str, str]] = field(default_factory=list)
    issues: List[Issue] = field(default_factory=list)

    def add(self, level: str, code: str, line: int, message: str) -> None:
        self.issues.append(Issue(level, code, line, message))

    def errors(self) -> List[Issue]:
        return [i for i in self.issues if i.level == "error"]

    def warns(self) -> List[Issue]:
        return [i for i in self.issues if i.level == "warn"]


def slugify(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"[^\w\u4e00-\u9fff\- ]", "", text)
    text = text.replace(" ", "-")
    return text


def is_placeholder_path(text: str) -> bool:
    if not text or not text.strip():
        return False
    low = text.strip().lower()
    if low in {"文件", "路径", "file", "path", "行号", "line"}:
        return True
    return low.startswith("<") or low.startswith("${") or low.startswith("{{")


def is_placeholder_secret(text: str) -> bool:
    if not text:
        return False
    low = text.strip().lower()
    return any(h in low for h in PLACEHOLDER_HINTS)


def count_cells(line: str) -> int:
    stripped = line.strip()
    if stripped.startswith("|"):
        stripped = stripped[1:]
    if stripped.endswith("|"):
        stripped = stripped[:-1]
    # split on pipes that are not escaped
    cells = re.split(r"(?<!\\)\|", stripped)
    return len(cells)


def check_fences(lines: List[str], rep: Report) -> Tuple[List[bool], List[Tuple[int, str, List[str]]]]:
    in_fence = [False] * len(lines)
    blocks: List[Tuple[int, str, List[str]]] = []
    open_at: Optional[int] = None
    lang = ""
    body: List[str] = []

    for idx, raw in enumerate(lines):
        m = FENCE_RE.match(raw)
        if m:
            if open_at is None:
                open_at = idx
                lang = m.group(2).strip().split()[0].lower() if m.group(2).strip() else ""
                body = []
            else:
                blocks.append((open_at, lang, body))
                open_at = None
                lang = ""
                body = []
            in_fence[idx] = True
            continue
        if open_at is not None:
            in_fence[idx] = True
            body.append(raw)

    if open_at is not None:
        rep.add("error", "fence-unclosed", open_at + 1, "代码围栏未闭合")
    return in_fence, blocks


def check_mermaid(blocks: List[Tuple[int, str, List[str]]], rep: Report) -> None:
    for start, lang, body in blocks:
        if lang != "mermaid":
            continue
        rep.mermaid += 1
        first = next((b.strip() for b in body if b.strip()), "")
        if not first:
            rep.add("error", "mermaid-empty", start + 1, "Mermaid 块为空")
            continue
        head = first.split()[0].rstrip(":;").lower()
        head = re.sub(r"-v\d+$", "", head)
        if head not in MERMAID_KEYWORDS:
            rep.add("warn", "mermaid-type", start + 1,
                    f"Mermaid 首行不是已知图类型：{first.split()[0]}")


def check_tables(lines: List[str], in_fence: List[bool], rep: Report) -> None:
    i = 0
    total = len(lines)
    while i < total:
        if in_fence[i] or not lines[i].strip().startswith("|"):
            i += 1
            continue
        start = i
        block = []
        while i < total and not in_fence[i] and lines[i].strip().startswith("|"):
            block.append(lines[i])
            i += 1
        if len(block) < 2:
            rep.add("warn", "table-short", start + 1, "表格缺少表头或分隔行")
            continue
        if not SEPARATOR_RE.match(block[1]):
            rep.add("error", "table-separator", start + 2, "表格第二行不是分隔行")
            continue
        rep.tables += 1
        expected = count_cells(block[0])
        for offset, row in enumerate(block):
            got = count_cells(row)
            if got != expected:
                rep.add("error", "table-columns", start + offset + 1,
                        f"表格列数不一致：表头 {expected} 列，本行 {got} 列")


def resolve_anchor(anchor: str) -> Dict[str, str]:
    baseline = ""
    if "｜" in anchor:
        anchor, baseline = anchor.split("｜", 1)
    symbol = ""
    if "#" in anchor:
        path, rest = anchor.split("#", 1)
        symbol, _, line_part = rest.rpartition(":")
        if not line_part:
            line_part = ""
            symbol = rest
    else:
        path, _, line_part = anchor.rpartition(":")
    start = end = ""
    if line_part:
        if "-" in line_part:
            start, _, end = line_part.partition("-")
        else:
            start = line_part
    return {
        "raw": anchor, "path": path.strip(), "symbol": symbol.strip(),
        "start": start.strip(), "end": end.strip(), "baseline": baseline.strip(),
    }


def _check_one_anchor(raw: str, idx: int, root: str,
                      cache: Dict[str, Optional[List[str]]], rep: Report) -> Dict[str, str]:
    info = resolve_anchor(raw)
    path, symbol = info["path"], info["symbol"]
    start, end = info["start"], info["end"]

    if is_placeholder_path(path) or is_placeholder_path(symbol):
        info["status"] = "placeholder"
        return info

    full = os.path.join(root, path)
    if path not in cache:
        if os.path.isfile(full):
            try:
                with open(full, "r", encoding="utf-8", errors="replace") as fh:
                    cache[path] = fh.read().splitlines()
            except OSError:
                cache[path] = None
        else:
            cache[path] = None
    content = cache[path]

    if content is None:
        info["status"] = "missing"
        rep.add("error", "fact-file", idx + 1, f"锚点文件不存在：{path}")
        return info

    if start:
        if not start.isdigit():
            info["status"] = "unverifiable"
            rep.add("warn", "fact-line", idx + 1, f"行号无法解析：{info['raw']}")
            return info
        begin = int(start)
        finish = int(end) if end.isdigit() else begin
        if begin < 1 or finish < begin or finish > len(content):
            info["status"] = "missing"
            rep.add("error", "fact-range", idx + 1,
                    f"行号越界：{path}:{begin}-{finish}（文件 {len(content)} 行）")
            return info

    if symbol and symbol not in "\n".join(content):
        info["status"] = "missing"
        rep.add("error", "fact-symbol", idx + 1, f"符号未找到：{path}#{symbol}")
        return info

    info["status"] = "ok"
    return info


def check_facts(lines: List[str], root: str, rep: Report) -> None:
    seen: Dict[str, int] = {}
    cache: Dict[str, Optional[List[str]]] = {}

    for idx, line in enumerate(lines):
        for match in FACT_RE.finditer(line):
            rep.facts += 1
            raw = match.group(1)
            parts = [p.strip() for p in re.split(r"[；;]", raw) if p.strip()]
            for part in parts:
                info = _check_one_anchor(part, idx, root, cache, rep)
                rep.anchors.append(info)
                if info["status"] == "ok":
                    key = f"{info['path']}#{info['symbol']}:{info['start']}"
                    seen[key] = seen.get(key, 0) + 1

    for key, count in seen.items():
        if count > 4:
            rep.add("warn", "fact-dup", 0, f"同一锚点重复 {count} 次：{key}")


def check_sensitive(lines: List[str], in_fence: List[bool], rep: Report) -> None:
    for idx, line in enumerate(lines):
        for pattern, label in SECRET_PATTERNS:
            for m in re.finditer(pattern, line):
                value = m.group(0)
                captured = m.group(2) if m.lastindex and m.lastindex >= 2 else ""
                if is_placeholder_secret(value) or (captured and is_placeholder_secret(captured)):
                    continue
                rep.add("error", "secret", idx + 1, f"{label}：{value[:24]}…")


def check_toc(lines: List[str], in_fence: List[bool], rep: Report) -> None:
    headings = []
    for idx, line in enumerate(lines):
        if in_fence[idx]:
            continue
        m = HEADING_RE.match(line)
        if m:
            headings.append(slugify(m.group(2)))
            rep.headings += 1
    heading_set = set(headings)
    for idx, line in enumerate(lines):
        if in_fence[idx]:
            continue
        for anchor in TOC_LINK_RE.findall(line):
            if anchor not in heading_set:
                rep.add("warn", "toc-anchor", idx + 1, f"目录锚点无对应标题：#{anchor}")


def check_expect(lines: List[str], expect_path: str, rep: Report) -> None:
    try:
        with open(expect_path, "r", encoding="utf-8") as fh:
            expected = [l.rstrip("\n") for l in fh if l.strip() and not l.strip().startswith("#!")]
    except OSError as exc:
        rep.add("error", "expect-file", 0, f"无法读取骨架文件：{exc}")
        return
    actual = {re.sub(r"\s+", " ", l.strip()) for l in lines if l.lstrip().startswith("#")}
    for exp in expected:
        norm = re.sub(r"\s+", " ", exp.strip())
        if not any(a.startswith(norm) for a in actual):
            rep.add("error", "skeleton", 0, f"缺少预期章节：{norm}")


def check_annotations(rep: Report, profile: str, audience: str, body_facts: int) -> None:
    if rep.facts == 0 and rep.infers == 0 and profile not in NO_FACT_TYPES:
        rep.add("error", "annotation", 0, "文档没有任何 FACT / INFER 标注")
    if rep.facts == 0 and rep.infers == 0 and profile in NO_FACT_TYPES:
        rep.add("warn", "annotation", 0, "文档没有任何标注（该类型可能正常）")
    if rep.infers > 0 and rep.facts == 0 and profile not in NO_FACT_TYPES:
        rep.add("error", "annotation", 0, "只有 INFER 没有 FACT，证据不足")
    if rep.facts and rep.infers > rep.facts * 3:
        rep.add("warn", "annotation", 0,
                f"INFER 数量（{rep.infers}）远高于 FACT（{rep.facts}）")
    if audience == "business" and body_facts > 0:
        rep.add("warn", "density", 0,
                f"业务受众正文出现 {body_facts} 处 FACT 锚点，应全部移入附录证据索引")


def _appendix_start(lines: List[str], in_fence: List[bool]) -> int:
    """First H2 heading whose title contains 附录; len(lines) if none."""
    for idx, line in enumerate(lines):
        if in_fence[idx]:
            continue
        m = HEADING_RE.match(line)
        if m and len(m.group(1)) == 2 and "附录" in m.group(2):
            return idx
    return len(lines)


def count_body_facts(lines: List[str], in_fence: List[bool]) -> int:
    end = _appendix_start(lines, in_fence)
    return sum(len(FACT_RE.findall(lines[i])) for i in range(end))


def check_evidence_ref_body(lines: List[str], in_fence: List[bool],
                            rep: Report, audience: str) -> None:
    """business docs must not point readers at appendix evidence ids."""
    if audience != "business":
        return
    for idx, line in enumerate(lines):
        if in_fence[idx]:
            continue
        if EVIDENCE_REF_RE.search(line):
            rep.add("error", "evidence-ref-body", idx + 1,
                    "业务受众正文不得引用证据编号，改为章节交叉引用（如 见 4.1）")


def check_p0_pending(lines: List[str], in_fence: List[bool], rep: Report) -> None:
    """Pending-decision items belong in a separate section, not the P0 table."""
    for idx, line in enumerate(lines):
        if in_fence[idx]:
            continue
        if P0_ROW_RE.match(line) and "待裁决" in line:
            rep.add("warn", "p0-pending", idx + 1,
                    "P0 表含“待裁决”项，应拆到独立「待裁决清单」并声明裁决前按阻断处理")


def check_operator_terms(lines: List[str], in_fence: List[bool], rep: Report) -> None:
    for idx, line in enumerate(lines):
        if in_fence[idx]:
            continue
        for term in OPERATOR_FORBIDDEN:
            if re.search(rf"(?<![A-Za-z]){re.escape(term)}(?![A-Za-z])", line):
                rep.add("warn", "term", idx + 1, f"面向运营读者，建议替换术语：{term}")


def validate(path: str, root: str, profile: str, audience: str,
             expect: Optional[str], strict: bool) -> Report:
    rep = Report(path=path)
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError as exc:
        rep.add("error", "io", 0, f"无法读取文档：{exc}")
        return rep

    rep.lines = len(lines)
    in_fence, blocks = check_fences(lines, rep)
    check_mermaid(blocks, rep)
    check_tables(lines, in_fence, rep)
    check_facts(lines, root, rep)
    rep.infers = sum(len(INFER_RE.findall(l)) for l in lines)
    rep.observes = sum(len(OBSERVE_RE.findall(l)) for l in lines)
    check_sensitive(lines, in_fence, rep)
    check_toc(lines, in_fence, rep)
    check_evidence_ref_body(lines, in_fence, rep, audience)
    check_p0_pending(lines, in_fence, rep)
    if profile in {"operations-manual", "admin-quick-reference"}:
        check_operator_terms(lines, in_fence, rep)
    if expect:
        check_expect(lines, expect, rep)
    check_annotations(rep, profile, audience, count_body_facts(lines, in_fence))
    return rep


def print_human(rep: Report, show_anchors: bool = False) -> None:
    print(f"文档：{rep.path}")
    print(f"行数 {rep.lines} | 标题 {rep.headings} | 表格 {rep.tables} | "
          f"Mermaid {rep.mermaid} | FACT {rep.facts} | INFER {rep.infers} | OBSERVE {rep.observes}")

    status = {}
    for a in rep.anchors:
        status[a.get("status", "?")] = status.get(a.get("status", "?"), 0) + 1
    if status:
        print("锚点：" + "，".join(f"{k} {v}" for k, v in sorted(status.items())))

    if show_anchors:
        for a in rep.anchors:
            print(f"  [{a.get('status')}] {a.get('raw')}")

    for level, title in (("error", "错误"), ("warn", "警告")):
        items = [i for i in rep.issues if i.level == level]
        if not items:
            continue
        print(f"\n{title}（{len(items)}）")
        for issue in items:
            loc = f"L{issue.line}" if issue.line else "-"
            print(f"  {loc:>7}  [{issue.code}] {issue.message}")


def main() -> int:
    parser = argparse.ArgumentParser(description="校验设计文档的结构、证据与脱敏")
    parser.add_argument("doc", help="待校验的 Markdown 文件")
    parser.add_argument("--root", default=".", help="项目根目录（解析 FACT 路径用）")
    parser.add_argument("--profile", default="lld", help="文档类型 ID，如 lld / prd / slo")
    parser.add_argument("--audience", default="dev",
                        choices=["dev", "architect", "business", "operator"])
    parser.add_argument("--expect", help="骨架文件：每行一个必须出现的标题")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--strict", action="store_true", help="警告也视为失败")
    parser.add_argument("--show-anchors", action="store_true", help="列出每个锚点状态")
    args = parser.parse_args()

    if not os.path.isfile(args.doc):
        print(f"文件不存在：{args.doc}", file=sys.stderr)
        return 2
    if not os.path.isdir(args.root):
        print(f"根目录不存在：{args.root}", file=sys.stderr)
        return 2

    rep = validate(args.doc, args.root, args.profile, args.audience, args.expect, args.strict)

    if args.json:
        print(json.dumps({
            "path": rep.path,
            "lines": rep.lines,
            "headings": rep.headings,
            "tables": rep.tables,
            "mermaid": rep.mermaid,
            "facts": rep.facts,
            "infers": rep.infers,
            "observes": rep.observes,
            "anchors": rep.anchors,
            "issues": [i.__dict__ for i in rep.issues],
            "errors": len(rep.errors()),
            "warnings": len(rep.warns()),
        }, ensure_ascii=False, indent=2))
    else:
        print_human(rep, args.show_anchors)

    if rep.errors():
        return 1
    if args.strict and rep.warns():
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
