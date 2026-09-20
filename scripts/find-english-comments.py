"""Lists source files whose comments are still in English.

A translation aid, not part of the system. It reads a file, extracts the lines
that are comment text — Java/JSDoc blocks, `//` lines, SQL `--` lines, JSX
`{/* */}` blocks — and reports how many of them read as English prose.

"Prose" is the part that took a second attempt. A first version counted any
comment line with three consecutive letters and no Chinese, which flagged
`<pre>`, `</pre>`, `@param orderNo`, and — the reason it was rewritten — an
English sentence quoted verbatim inside a Chinese paragraph. That last one is a
quotation of a language model's actual output, kept as evidence for why
something is the way it is, and translating it would destroy the point.
"""

import pathlib
import re
import sys

# Resolved from this file, not typed in: the script has to work in a clone,
# which is somewhere else entirely.
REPO = pathlib.Path(__file__).resolve().parent.parent

ROOTS = [
    ("backend", REPO / "backend/src/main/java", (".java",)),
    ("frontend", REPO / "frontend/src", (".ts", ".tsx", ".css")),
    ("sql", REPO / "backend/src/main/resources/db/migration", (".sql",)),
]

HAN = re.compile(r"[\u4e00-\u9fff]")

# Two or more whole words of three letters or more. Skips markup tags, a bare
# identifier, an annotation with one argument, and a short quoted phrase.
WORDY = re.compile(r"[A-Za-z]{3,}(?:[^A-Za-z]+[A-Za-z]{3,})+")


def is_comment(line: str) -> bool:
    stripped = line.strip()
    return (
        stripped.startswith("*")
        or stripped.startswith("//")
        or stripped.startswith("--")
        or stripped.startswith("/*")
        or stripped.startswith("{/*")
    )


def report(root_name: str, root: str, suffixes) -> list:
    findings = []
    for path in pathlib.Path(root).rglob("*"):
        if path.suffix not in suffixes or not path.is_file():
            continue
        english = 0
        total = 0
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if not is_comment(line):
                continue
            if not WORDY.search(line):
                continue
            total += 1
            if not HAN.search(line):
                english += 1
        if english:
            findings.append((english, total, root_name, path))
    return findings


def main() -> None:
    everything = []
    for name, root, suffixes in ROOTS:
        everything.extend(report(name, root, suffixes))

    everything.sort(reverse=True, key=lambda row: row[0])
    grand = sum(row[0] for row in everything)
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for english, total, root_name, path in everything:
        if only and root_name != only:
            continue
        print(f"{english:4d} / {total:4d}  {root_name:8s} {path.name}")
    print(f"\n{grand} comment lines read as English across {len(everything)} files "
          f"(migrations included; they are excluded from translation on purpose)")


if __name__ == "__main__":
    sys.exit(main())
