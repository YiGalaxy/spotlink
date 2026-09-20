"""Applies a translation, one whole block at a time, and fails loudly.

A block is located by an anchor that appears somewhere inside it; the whole
block around that anchor is then replaced. Matching the WHOLE block is the
point. An earlier version replaced exact strings, and three half-English,
half-Chinese sentences came out of it without failing anything — comments
compile whatever language they are in.

Anchors are ASCII on purpose. Half these blocks already contain Chinese and em
dashes, and retyping them to build an exact match is how the mangling started.
The anchor only has to be unique; the replacement is the whole block.

Kinds of block, because the text lives in several places:

  comment  a javadoc, `//` run, `#` run or JSX `{/* */}` block — replaced whole
  text     the inside of a Java text block (description), which is what the
           model reads; the closing quotes and the `)` after them are kept
  line     one line, for a single-line annotation the replacement rewrites

Files are read and written as bytes, so nothing is re-encoded on the way
through and the line ending the file already had is the one it keeps.

Paths are relative to the repository root.
"""

import pathlib
import sys

# Resolved from this file, not typed in: the script has to work in a clone,
# which is somewhere else entirely.
ROOT = pathlib.Path(__file__).resolve().parent.parent

JOBS = [
    ("backend/src/main/resources/application.yml", [
        ("comment", "Defaults to the local profile for developer machines",
         """  # 开发者机器上默认使用 local profile；其他任何环境都必须显式设置
  # SPRING_PROFILES_ACTIVE。"""),
        ("comment", "allowPublicKeyRetrieval + useSSL=false", """    # allowPublicKeyRetrieval + useSSL=false 只用于本地 Docker。MySQL 8 默认走 TLS
    # 加 caching_sha2_password；一次性容器里没有值得验证的证书，而要求证书只会让
    # `docker compose up` 在一台全新机器上失败，换不来任何安全收益。
    #
    # connectionTimeZone=Asia/Shanghai 配上 forceConnectionTimeZoneToSession，把
    # JDBC 会话钉住，这样一个以 OffsetDateTime 写入的 DATETIME 取回来还是写入时的
    # 那个时刻。MySQL 没有带时区的时间类型，所以正是这项设置承接了 PostgreSQL 用
    # TIMESTAMPTZ 保住的含义。"""),
        ("comment", "The default matches the container in docker-compose.yml", """    # 默认值与 docker-compose.yml 里的容器一致，只用于本地开发。任何能从本机之外
    # 访问到的部署都必须改掉它：写进仓库的密码就是公开的密码，而之所以在这里从环境
    # 里读，恰恰是为了让改密码成为一次配置，而不是去编辑一个已经在仓库里的文件。"""),
        ("comment", "Same reasoning as the database password above",
         "      # 理由与上面数据库密码那一处相同。"),
        ("comment", "Auto-configuration requires a non-empty key at startup", """      # 自动配置要求启动时 key 非空。占位值让平台在没有顾问 key 时也能启动，
      # 顾问会报告自己不可用，而不是让整个应用拒绝启动。"""),
        ("comment", "Cache the system prompt. The stable half", """          # 缓存系统提示词。它稳定不变的那一半对每个租户都相同，
          # 所以一条缓存就能服务所有调用方。"""),
        ("comment", "Send each system message as its own block", """          # 每条 system 消息各成一个块。否则稳定的那一半和随调用方变化的那一半会被
          # 合并，前缀随用户改变，于是每个请求都命中不了缓存。"""),
        ("comment", "# Application-specific settings",
         "# 本应用自己的设置"),
        ("comment", "Base64-encoded 256-bit secret",
         "      # Base64 编码的 256 位密钥。任何非本地环境都必须覆盖它。"),
        ("comment", "Seeds development accounts", """    # 写入开发账号（admin / seller01 / buyer01 / pending01）。
    # 任何真实用户能访问到的环境都必须设为 false。"""),
        ("comment", "How often the background sweep runs", """    # 后台扫描多久跑一次。它负责答复挂牌方始终未回复的摘牌请求，并让超过有效期的
    # 挂牌过期——这些活没人会手动触发，所以这个间隔决定了一个停滞的要约能滞留多久。"""),
        ("comment", "Let the application finish starting before the first sweep",
         "    # 让应用先启动完，再跑第一次扫描。"),
        ("comment", "How long a lister has to answer an acceptance", """    # 「需挂牌方确认」的挂牌，挂牌方答复一次摘牌的时间上限。受每张挂牌自身的有效期
    # 封顶，所以它只会缩短等待。把它设成 PT1M，就能不必等一天而观察到自动过期的路径。"""),
        ("comment", "Claude has no embedding endpoint", """      # Claude 没有嵌入接口，所以这是顾问技术栈里唯一不属于 Anthropic 的一块。
      # 由 Ollama 提供 BGE-M3：中文强、每次调用免费，而且无需联网即可运行。"""),
        ("comment", "Upper bound on agent-loop iterations", """    # 智能体循环的迭代次数上限。没有上限的工具调用循环可以一直转下去，
    # 而每一轮都是要计费的。"""),
        ("comment", "Endpoint, key, model and token ceiling live under spring.ai.anthropic", """    # 端点、密钥、模型和令牌上限都在 spring.ai.anthropic 下，
    # 这样模型连接只有一个地方需要配置。"""),
    ]),
    ("backend/src/main/resources/application-local.yml.example", [
        ("comment", "Template for application-local.yml", """# application-local.yml 的模板。复制它，填上你自己的值。
#
#   cp application-local.yml.example application-local.yml
#
# 真正的那个文件已被 gitignore，所以密钥不会进仓库。
#
# 整个文件不建也行：应用会退回 application.yml 里的默认值（Claude 官方 API、
# 空密钥）。在有 api-key 之前，顾问会报告自己不可用。"""),
        ("comment", "Official API. Set this to an Anthropic-compatible gateway",
         "    # 官方 API。如果你用 Anthropic 兼容网关，把它改成网关地址。"),
        ("comment", "Prefer an environment variable over a literal", """    # 密钥优先用环境变量而不是字面量，这样它不会留在 shell 历史里，
    # 也不会留在任何你可能分享出去的文件里。"""),
        ("comment", "claude-opus-5 is the strongest model",
         "    # claude-opus-5 是官方 API 上最强的模型。"),
    ]),
    ("backend/src/main/resources/application-local.yml", [
        ("comment", "Local development overrides", """# 本地开发覆盖项。这个文件已被 gitignore——见 .gitignore。
#
# 这台机器上没有 Anthropic 的官方 key，所以顾问指向一个 Anthropic 兼容网关。
# 只有端点和模型不同；应用代码一行没改，这正是把它们放进配置里的意义。
#
# 要切回 Claude 官方 API，删掉下面这两项覆盖，并把 BULK_ADVISOR_API_KEY
# 导出成一个真实的 key。"""),
        ("comment", "The compatible gateway does not implement", """        # 兼容网关没有实现 Anthropic 的显式缓存断点，所以要它只会多出一个被拒的字段。
        # 指向官方 API 时再把它打开。"""),
    ]),
]


def _anchor_line(lines, anchor):
    hits = [i for i, line in enumerate(lines) if anchor in line]
    if len(hits) != 1:
        raise ValueError(f"anchor matched {len(hits)} lines: {anchor[:60]!r}")
    return hits[0]


def _comment_span(lines, at):
    if "{/*" in lines[at]:
        if "*/}" in lines[at]:
            return at, at + 1
        start = at
        while "{/*" not in lines[start]:
            start -= 1
            if start < 0:
                raise ValueError("no JSX opener above")
        end = at
        while "*/}" not in lines[end]:
            end += 1
            if end >= len(lines):
                raise ValueError("no JSX closer below")
        return start, end + 1

    if lines[at].lstrip().startswith(("/**", "*")):
        start = at
        while not lines[start].lstrip().startswith("/**"):
            start -= 1
            if start < 0:
                raise ValueError("no javadoc opener above")
        end = at
        while not lines[end].rstrip().endswith("*/"):
            end += 1
            if end >= len(lines):
                raise ValueError("no javadoc closer below")
        return start, end + 1

    for marker in ("//", "#"):
        if lines[at].lstrip().startswith(marker):
            start = at
            while start > 0 and lines[start - 1].lstrip().startswith(marker):
                start -= 1
            end = at + 1
            while end < len(lines) and lines[end].lstrip().startswith(marker):
                end += 1
            return start, end

    raise ValueError("anchor is not inside a comment")


def _text_span(lines, at):
    start = at
    while '"""' not in lines[start]:
        start -= 1
        if start < 0:
            raise ValueError("no text block opener above")
    end = start + 1
    while '"""' not in lines[end]:
        end += 1
        if end >= len(lines):
            raise ValueError("no text block closer below")
    closer = lines[end]
    # The closer line is dropped with the block, so what follows its opening
    # quotes — the quotes themselves and the `)` after them — is re-attached.
    return start + 1, end + 1, closer[closer.index('"""'):]


def replace_block(text: str, kind: str, anchor: str, replacement: str, eol: str) -> str:
    lines = text.split(eol)
    at = _anchor_line(lines, anchor)
    suffix = ""
    try:
        if kind == "comment":
            start, end = _comment_span(lines, at)
            if any("*/" in line or "*/}" in line for line in lines[at + 1:end - 1]):
                raise ValueError("block swallowed a second comment")
        elif kind == "text":
            start, end, suffix = _text_span(lines, at)
        elif kind == "line":
            start, end = at, at + 1
        else:
            raise ValueError(f"unknown kind {kind!r}")
    except ValueError as failure:
        raise ValueError(f"{failure}: {anchor[:60]!r}") from failure

    body = replacement.split("\n")
    body[-1] = body[-1] + suffix
    return eol.join(lines[:start] + body + lines[end:])


def apply(path: pathlib.Path, jobs) -> bool:
    text = path.read_bytes().decode("utf-8")
    eol = "\r\n" if "\r\n" in text else "\n"
    before = text
    for index, (kind, anchor, replacement) in enumerate(jobs):
        try:
            text = replace_block(text, kind, anchor, replacement, eol)
        except ValueError as failure:
            print(f"  {path.name}: job {index} failed — {failure}")
            return False
    if text == before:
        print(f"  {path.name}: nothing changed")
        return False
    path.write_bytes(text.encode("utf-8"))
    print(f"  {path.name}: {len(before)} -> {len(text)} chars")
    return True


def main() -> int:
    ok = True
    for relative, jobs in JOBS:
        path = ROOT / relative
        if not path.exists():
            print(f"no such file: {path}")
            ok = False
            continue
        ok = apply(path, jobs) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
