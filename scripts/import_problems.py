# -*- coding: utf-8 -*-
"""把 D:\\todo\\oj_problems_repo 的 1000 道题导入 oj_cloud。

设计要点（与数据库设计审计的结论一致）：

1. **测试数据不存数据库，物化到磁盘**
   题库测试数据合计 6.95 GB，其中 94 个文件超过 16 MB（最大 205 MB），
   而 prob_test_case.input_text/output_text 是 MEDIUMTEXT（上限 16,777,215 字节）。
   同时 MySQL 的 max_allowed_packet 只有 64 MB，205 MB 的单条 INSERT 必失败。
   即使改 LONGTEXT 也扛不住：InnoDB 行溢出会把 6.95 GB 放大到十几 GB，
   buffer pool 只有 256 MB，备份/恢复会慢到不可用。

   正确做法（也正是设计文档 §3 的原意）：
   - prob_test_case 只存**元数据**：seq_no / kind / score / is_public / 文件字节数
   - .in/.out 全文写到 data/testcases/{problem_id}/{seq}.in|.out
   - go-judge 通过 copyIn 直接读文件，不经过数据库
   这也是 docker-compose 已经把 ./data/testcases 挂到容器 /data/testcases 的原因。

2. **input_text / output_text 仍写摘要**
   为兼容契约（前端失败点对比要显示输入输出），超长内容只存前 N 字节摘要 + 截断标记，
   完整内容在磁盘文件里。判题比对一律读文件，不读数据库字段。

3. **难度映射**
   题库源 difficulty 1000 道全是 "Mid"（零区分度），不能直接用。
   改按「分类 + 标签 + 测试点数量」综合判定，落在 1-5 档：
     01_基础语法与入门      → 1 入门
     02/05/06 中无高阶标签  → 2 简单
     含 线段树/网络流/DP 等 → 3 中等
     07_省选与高阶竞赛题    → 4 较难
     07 中带 NOI/CTSC/WC/省选 且测试点≥5 → 5 困难

4. **知识点映射**
   prob_question.knowledge_point_id 是**单值**外键，而题目有多个标签。
   主知识点取「最能代表该题的那一个」，其余全部进 tags JSON。
   这样既不破坏 DDL，又保留了完整的标签信息。

5. **SPJ / 提交答案题**
   题库有 23 道 Special Judge、6 道系统测试、若干「提交答案」题。
   我们的判题内核只做标准输出比对，不支持 SPJ。
   这些题**导入但标记 ai_enabled=0 且 state=0（草稿）**，不进正式题库，
   避免学生提交必然 WA 造成困惑。

用法：
  python scripts/import_problems.py --dry-run          # 只统计，不写库
  python scripts/import_problems.py --limit 10         # 先导 10 道验证
  python scripts/import_problems.py --all              # 全量
  python scripts/import_problems.py --all --category 02_经典算法与动态规划
  python scripts/import_problems.py --all --skip-spj   # 跳过不支持判题类型的题
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(r"D:\todo\oj_problems_repo")
PROJECT = Path(__file__).resolve().parent.parent
TESTCASE_ROOT = PROJECT / "data" / "testcases"
MYSQL_CONTAINER = "oj-cloud-mysql"

# 数据库只存摘要，完整内容在磁盘。摘要上限按契约「前 100 字符脱敏特征」放宽到 4 KB，
# 够前端做失败点输入/输出对比的第一屏展示。
SUMMARY_LIMIT = 4096

CATEGORY_ORDER = [
    "01_基础语法与入门",
    "02_经典算法与动态规划",
    "03_图论与网络流",
    "04_数据结构与树算法",
    "05_数论与数学进阶",
    "06_NOIP与CSP历年真题",
    "07_省选与高阶竞赛题",
]

# 主知识点字典（与现有 5 条对齐，不足的按需补充）
KP_NAMES = ["动态规划", "贪心", "图论", "字符串", "数学",
            "数据结构", "搜索", "模拟", "二分", "组合计数"]

# 标签 → 主知识点优先级（越靠前优先级越高）
TAG_TO_KP = [
    (["网络流", "最短路", "图论", "连通分量", "二分图", "并查集", "拓扑排序"], "图论"),
    (["线段树", "树状数组", "平衡树", "Treap", "Splay", "字典树", "Trie",
      "分块", "树套树", "可并堆", "堆"], "数据结构"),
    (["DP", "动态规划", "树形 DP", "数位 DP", "状压 DP", "背包", "单调队列"], "动态规划"),
    (["字符串", "KMP", "AC自动机", "后缀数组", "哈希", "Manacher"], "字符串"),
    (["数论", "组合计数", "矩阵", "多项式", "FFT", "线性基", "博弈论",
      "逆元", "素数", "同余", "数学"], "数学"),
    (["贪心"], "贪心"),
    (["二分", "三分"], "二分"),
    (["搜索", "DFS", "BFS", "迭代加深"], "搜索"),
    (["模拟", "枚举", "高精度"], "模拟"),
]

# 判题类型不支持的关键词（出现即视为不可自动判题）
UNSUPPORTED_TAGS = {"Special Judge", "系统测试", "提交答案", "交互题", "Output Only"}


def sh(cmd, check=True, input_bytes=None):
    """跑一条命令，返回 (rc, stdout_bytes, stderr_bytes)。"""
    p = subprocess.run(cmd, input=input_bytes, capture_output=True)
    if check and p.returncode != 0:
        raise RuntimeError("命令失败 %s\n%s" % (cmd[:3], p.stderr.decode("utf-8", "replace")[:2000]))
    return p.returncode, p.stdout, p.stderr


def mysql_exec(sql, database="oj_cloud", timeout=600):
    """在容器里执行 SQL（走 stdin，避免命令行长度限制与引号转义）。"""
    cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
           "--default-character-set=utf8mb4", "-uroot", "-proot", database]
    return sh(cmd, input_bytes=sql.encode("utf-8"))


def mysql_query(sql):
    """查一行/多行，返回以 tab 分隔的文本行。"""
    cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
           "--default-character-set=utf8mb4", "-uroot", "-proot", "-N", "-B", "oj_cloud"]
    rc, out, err = sh(cmd, input_bytes=sql.encode("utf-8"))
    return out.decode("utf-8", "replace").splitlines()


# ──────────────────────────────────────────────────────────────────────
# 难度与知识点
# ──────────────────────────────────────────────────────────────────────

HARD_TAGS = {"线段树", "网络流", "平衡树", "Treap", "Splay", "树套树", "可并堆",
             "多项式", "FFT", "线性基", "后缀数组", "AC自动机", "动态树", "LCT"}
CONTEST_HARD = {"NOI", "CTSC", "WC", "THUWC", "THUPC", "集训队", "省选",
                "SDOI", "HNOI", "ZJOI", "SCOI", "JLOI", "SHOI", "CQOI",
                "AHOI", "HAOI", "SNOI", "JSOI", "USACO", "POI", "CEOI",
                "BalticOI", "JOI", "IOI"}


def map_difficulty(problem, tags):
    """把题库的单一 'Mid' 映射到 1-5 档。规则见文件头。"""
    cat = problem["category"]
    tc = problem.get("testcase_count") or 0
    tagset = set(tags)

    if cat == "01_基础语法与入门":
        return 1
    if cat == "07_省选与高阶竞赛题":
        # 省选/NOI 真题里，带 NOI/CTSC/WC 且测试点多的判 5
        if tagset & CONTEST_HARD and tc >= 5:
            return 5
        return 4
    if cat == "06_NOIP与CSP历年真题":
        # NOIP 提高组/普及组真题：带高阶标签的判 3，其余 2
        if tagset & HARD_TAGS:
            return 3
        if tagset & {"NOIP", "CSP"} and tc >= 5:
            return 3
        return 2
    if cat in ("02_经典算法与动态规划", "03_图论与网络流", "04_数据结构与树算法"):
        if tagset & HARD_TAGS:
            return 3
        return 3 if tc >= 5 else 2
    if cat == "05_数论与数学进阶":
        return 3
    return 2


def map_knowledge_point(problem, tags):
    """挑一个主知识点。返回知识点名。"""
    tagset = set(tags)
    for keys, kp in TAG_TO_KP:
        if tagset & set(keys):
            return kp
    cat = problem["category"]
    return {
        "01_基础语法与入门": "模拟",
        "02_经典算法与动态规划": "动态规划",
        "03_图论与网络流": "图论",
        "04_数据结构与树算法": "数据结构",
        "05_数论与数学进阶": "数学",
        "06_NOIP与CSP历年真题": "动态规划",
        "07_省选与高阶竞赛题": "数据结构",
    }.get(cat, "模拟")


def is_unsupported(tags):
    return bool(set(tags) & UNSUPPORTED_TAGS)


# ──────────────────────────────────────────────────────────────────────
# 题面与代码骨架
# ──────────────────────────────────────────────────────────────────────

CODE_TEMPLATES = {
    "cpp": "// 请在此编写你的 C++ 代码\n#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    \n    return 0;\n}\n",
    "java": "import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        \n    }\n}\n",
    "python": "# 请在此编写你的 Python 代码\nimport sys\n\ndef main():\n    pass\n\nif __name__ == '__main__':\n    main()\n",
}


def read_text(path):
    """读文本文件，容忍 BOM 与非 UTF-8。"""
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def build_content(meta, md_path):
    """拼 prob_question.content（Markdown 正文，含输入输出格式）。"""
    parts = []
    if meta.get("description"):
        parts.append("## 题目描述\n\n" + meta["description"].strip())
    if meta.get("input_description"):
        parts.append("## 输入格式\n\n" + meta["input_description"].strip())
    if meta.get("output_description"):
        parts.append("## 输出格式\n\n" + meta["output_description"].strip())
    if meta.get("hint"):
        parts.append("## 数据范围与提示\n\n" + meta["hint"].strip())

    # 如果 problem.json 的描述为空，退回用 problem.md 的正文
    if not parts and md_path.exists():
        md = read_text(md_path)
        # 去掉 problem.md 顶部的元信息块（标题 + 限制列表）
        md = re.sub(r"^#[^\n]*\n", "", md, count=1)
        md = re.sub(r"^- \*\*[^\n]*\n", "", md, flags=re.MULTILINE)
        parts.append(md.strip())

    return "\n\n".join(parts) if parts else "（题面缺失，请参考样例）"


def build_samples(meta):
    """把 samples 转成 prob_question.sample_cases 的 JSON。"""
    out = []
    for s in (meta.get("samples") or [])[:5]:
        out.append({"input": s.get("input", ""), "output": s.get("output", "")})
    return out


# ──────────────────────────────────────────────────────────────────────
# 测试点物化
# ──────────────────────────────────────────────────────────────────────

def materialize_testdata(src_dir, dst_dir):
    """把 testdata 拷到 data/testcases/{pid}/，返回 [(seq, in_bytes, out_bytes)]。

    返回的列表按 seq 排序，只包含 in/out 都存在且**不是损坏数据**的点。
    返回空列表表示这道题没有可用测试数据（调用方应把题目导入为草稿）。
    """
    dst_dir.mkdir(parents=True, exist_ok=True)
    pairs = []
    ins = {int(m.group(1)): p for p in src_dir.glob("*.in")
           if (m := re.match(r"(\d+)\.in$", p.name))}
    outs = {int(m.group(1)): p for p in src_dir.glob("*.out")
            if (m := re.match(r"(\d+)\.out$", p.name))}
    for seq in sorted(set(ins) & set(outs)):
        ip, op = ins[seq], outs[seq]
        if is_corrupt_testdata(ip) or is_corrupt_testdata(op):
            continue                      # .DS_Store 之类的垃圾，跳过
        shutil.copyfile(ip, dst_dir / ("%d.in" % seq))
        shutil.copyfile(op, dst_dir / ("%d.out" % seq))
        pairs.append((seq, ip.stat().st_size, op.stat().st_size))
    return pairs


def summarize(path, limit=SUMMARY_LIMIT):
    """读文件前 limit 字节做摘要。返回 (摘要文本, 原始字节数, 是否被截断)。

    ⚠️ 必须剔除 NUL 字节。MySQL 的文本协议不允许语句里出现裸 \\0
    （会报 "ASCII '\\0' appeared in the statement"），而题库里确实有
    二进制垃圾被误命名成 .in/.out（见 is_corrupt_testdata）。
    清洗后摘要只用于前端展示，判题比对读的是磁盘原文件，不受影响。
    """
    size = path.stat().st_size
    with path.open("rb") as f:
        raw = f.read(limit)
    truncated = size > limit
    text = raw.decode("utf-8", "replace")
    # NUL 与其它控制字符（保留 \n \r \t）一律替换为可见占位，避免污染 SQL
    text = text.replace("\x00", "␀")
    text = "".join(ch if (ch in "\n\r\t" or ord(ch) >= 0x20) else "·" for ch in text)
    if truncated:
        text += "\n…（完整内容共 %d 字节，见 test_case_dir 物化文件）" % size
    return text, size, truncated


def is_corrupt_testdata(path):
    """识别「被误命名成 .in/.out 的非测试数据文件」。

    实测题库里有 4 道 CEOI2011 的题，其 testdata 全是 macOS 的 .DS_Store
    （219 字节，含 'Mac OS X' / 'com.apple.quarantine' / 'Bud1' 魔数），
    5 个测试点内容完全相同。这种数据无法判题，必须识别出来单独处理，
    否则会以「NUL 字节报错」的形式表现为导入失败，掩盖真正原因。
    """
    try:
        if path.stat().st_size == 0 or path.stat().st_size > 4096:
            return False
        raw = path.read_bytes()
    except OSError:
        return False
    # .DS_Store 魔数：00 00 00 01 42 75 64 31 ("Bud1")
    if len(raw) >= 8 and raw[:4] == b"\x00\x00\x00\x01" and raw[4:8] == b"Bud1":
        return True
    probe = raw.decode("latin-1", "replace")
    if "Mac OS X" in probe or "com.apple.quarantine" in probe or "Bud1" in probe:
        return True
    # 含 NUL 的短文件基本不是文本测试数据
    if b"\x00" in raw and len(raw) < 1024:
        return True
    return False



def assign_scores(n, meta_scores):
    """按题库给的 test_case_score 取分值；缺失则均分到 100。"""
    if meta_scores and len(meta_scores) >= n:
        scores = [int(s.get("score", 0)) for s in meta_scores[:n]]
        if sum(scores) == 100:
            return scores
    if n <= 0:
        return []
    base = 100 // n
    scores = [base] * n
    scores[-1] += 100 - base * n          # 余数给最后一个点，保证 Σ=100
    return scores


def pick_kind(seq, n, is_public):
    """测试点类型：前 2 个公开点算 sample，末尾算 stress，其余 normal。"""
    if is_public:
        return "sample"
    if seq == n and n >= 3:
        return "stress"
    return "normal"


# ──────────────────────────────────────────────────────────────────────
# SQL 生成
# ──────────────────────────────────────────────────────────────────────

def sql_escape(s):
    if s is None:
        return "NULL"
    return "'" + str(s).replace("\\", "\\\\").replace("'", "\\'") + "'"


def json_escape(obj):
    return sql_escape(json.dumps(obj, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="导入全部 1000 题")
    ap.add_argument("--limit", type=int, default=0, help="只导入前 N 题（验证用）")
    ap.add_argument("--category", default="", help="只导入某个分类")
    ap.add_argument("--skip-spj", action="store_true", help="跳过不支持自动判题的题目")
    ap.add_argument("--dry-run", action="store_true", help="只统计不写库")
    ap.add_argument("--clean", action="store_true", help="导入前清空现有题库（保留种子 3 题以外的）")
    ap.add_argument("--creator-id", type=int, default=2, help="creator_id（默认教师 T001 的 id=2）")
    args = ap.parse_args()

    catalog = json.loads((REPO / "catalog.json").read_text(encoding="utf-8"))
    if args.category:
        catalog = [p for p in catalog if p["category"] == args.category]
    if args.limit:
        catalog = catalog[: args.limit]

    print("=" * 78)
    print("题库导入：%d 道题" % len(catalog))
    print("=" * 78)

    # ── 预扫描：分类统计 + 不支持判题的题目 ──
    stats = {}
    unsupported = []
    missing = []
    for p in catalog:
        folder = REPO / p["folder"]
        pj = folder / "problem.json"
        if not pj.exists():
            missing.append(p["title"])
            continue
        meta = json.loads(read_text(pj))
        tags = meta.get("tags") or p.get("tags") or []
        if is_unsupported(tags):
            unsupported.append((p["title"], [t for t in tags if t in UNSUPPORTED_TAGS]))
        c = p["category"]
        stats.setdefault(c, {"n": 0, "tc": 0, "bytes": 0})
        stats[c]["n"] += 1
        td = folder / "testdata"
        if td.exists():
            stats[c]["tc"] += len(list(td.glob("*.in")))
            stats[c]["bytes"] += sum(f.stat().st_size for f in td.iterdir() if f.is_file())

    print("\n分类统计：")
    print("%-26s %6s %8s %10s" % ("分类", "题数", "测试点", "数据量"))
    for c in CATEGORY_ORDER:
        if c not in stats:
            continue
        s = stats[c]
        print("%-26s %6d %8d %9.2f GB" % (c, s["n"], s["tc"], s["bytes"] / 1024 ** 3))
    print("%-26s %6d %8d %9.2f GB" % ("合计", sum(s["n"] for s in stats.values()),
                                       sum(s["tc"] for s in stats.values()),
                                       sum(s["bytes"] for s in stats.values()) / 1024 ** 3))

    print("\n不支持自动判题（SPJ / 系统测试 / 提交答案）：%d 道" % len(unsupported))
    for t, why in unsupported[:12]:
        print("   - %s  [%s]" % (t[:40], ",".join(why)))
    if len(unsupported) > 12:
        print("   … 共 %d 道" % len(unsupported))
    if missing:
        print("\n缺 problem.json：%d 道" % len(missing))
        for t in missing[:5]:
            print("   - %s" % t)

    if args.dry_run:
        print("\n[dry-run] 不写库、不落盘。")
        return 0

    # ── 知识点字典补齐 ──
    existing_kp = {}
    for line in mysql_query("SELECT id,name FROM prob_knowledge_point;"):
        if "\t" in line:
            kid, name = line.split("\t", 1)
            existing_kp[name] = int(kid)
    add_kp = [n for n in KP_NAMES if n not in existing_kp]
    if add_kp:
        vals = ",".join("(%s,%d)" % (sql_escape(n), i + 100) for i, n in enumerate(add_kp))
        mysql_exec("INSERT IGNORE INTO prob_knowledge_point(name,sort_order) VALUES %s;" % vals)
        print("\n补充知识点：%s" % "、".join(add_kp))
        existing_kp = {}
        for line in mysql_query("SELECT id,name FROM prob_knowledge_point;"):
            if "\t" in line:
                kid, name = line.split("\t", 1)
                existing_kp[name] = int(kid)

    # ── 起始题目 ID ──
    rows = mysql_query("SELECT IFNULL(MAX(id),1000) FROM prob_question;")
    next_id = int(rows[0]) + 1
    print("起始题目 ID：%d" % next_id)

    TESTCASE_ROOT.mkdir(parents=True, exist_ok=True)

    ok = skip = fail = 0
    total_cases = 0
    damaged = []          # 源测试数据损坏（.DS_Store 伪装）的题
    t0 = time.time()

    for idx, p in enumerate(catalog, 1):
        folder = REPO / p["folder"]
        pj = folder / "problem.json"
        if not pj.exists():
            fail += 1
            continue

        meta = json.loads(read_text(pj))
        tags = list(meta.get("tags") or p.get("tags") or [])
        # 分类标签去掉（那是目录名，不是算法标签）
        tags = [t for t in tags if t not in CATEGORY_ORDER]
        unsup = is_unsupported(tags)

        if unsup and args.skip_spj:
            skip += 1
            continue

        pid = next_id
        next_id += 1

        # 测试点物化（损坏数据会被 materialize_testdata 过滤掉）
        src_td = folder / "testdata"
        dst_td = TESTCASE_ROOT / str(pid)
        pairs = materialize_testdata(src_td, dst_td) if src_td.exists() else []

        # 没有可用测试点：仍导入题目（题面有教学价值），但必须是草稿 + 关 AI，
        # 否则学生点进去提交必然 0 分。不能直接 fail 掉——那样题库就缺这几道题，
        # 而且失败原因（源数据是 .DS_Store）会被误读成脚本 bug。
        no_cases = not pairs
        if no_cases:
            shutil.rmtree(dst_td, ignore_errors=True)
            pairs = []
            damaged.append((p["title"], p["category"]))

        n = len(pairs)
        meta_scores = meta.get("test_case_score") or []
        scores = assign_scores(n, meta_scores)

        # 题面
        content = build_content(meta, folder / "problem.md")
        samples = build_samples(meta)
        diff = map_difficulty(p, tags)
        kp_name = map_knowledge_point(p, tags)
        kp_id = existing_kp.get(kp_name)

        # 状态：不支持判题的题、无测试数据的题一律进草稿（state=0）且关 AI
        blocked = unsup or no_cases
        state = 0 if blocked else 1
        ai_enabled = 0 if blocked else 1
        tag_list = tags[:5] if tags else ["综合"]

        # 题目 INSERT
        sql = """
INSERT INTO prob_question
 (id,title,difficulty,knowledge_point_id,tags,content,sample_cases,code_templates,
  time_limit,memory_limit,test_case_dir,test_case_count,official_solution,
  ai_enabled,ai_quota_limit,ai_stages,state,version,pass_count,submit_count,creator_id)
VALUES (%d,%s,%d,%s,%s,%s,%s,%s,%d,%d,%s,%d,NULL,%d,3,%s,%d,1,0,0,%d);
""" % (
            pid,
            sql_escape(p["title"][:128]),
            diff,
            "NULL" if kp_id is None else str(kp_id),
            json_escape(tag_list),
            sql_escape(content),
            json_escape(samples),
            json_escape(CODE_TEMPLATES),
            int(meta.get("time_limit") or p.get("time_limit_ms") or 1000),
            int(meta.get("memory_limit") or p.get("memory_limit_mb") or 256),
            sql_escape("/data/testcases/%d/" % pid),
            n,
            ai_enabled,
            json_escape([1, 2, 3, 4]),
            state,
            args.creator_id,
        )

        # 测试点 INSERT（只存元数据 + 摘要，全文在磁盘）
        # 无可用测试点的题跳过这段——prob_test_case 一条不插，题目本身照样入库。
        if pairs:
            case_vals = []
            for i, (seq, in_size, out_size) in enumerate(pairs):
                is_public = 1 if i < 2 else 0
                kind = pick_kind(i + 1, n, is_public)
                in_sum, _, _ = summarize(dst_td / ("%d.in" % seq))
                out_sum, _, _ = summarize(dst_td / ("%d.out" % seq))
                case_vals.append("(%d,%d,%s,%s,%s,%d,%d,%d,%d)" % (
                    pid, i + 1, sql_escape(kind),
                    sql_escape(in_sum), sql_escape(out_sum),
                    scores[i], is_public, in_size, out_size,
                ))
            sql += """
INSERT INTO prob_test_case
 (problem_id,seq_no,kind,input_text,output_text,score,is_public,input_size,output_size)
VALUES %s;
""" % ",".join(case_vals)

        try:
            mysql_exec(sql)
            ok += 1
            total_cases += n
        except Exception as e:
            fail += 1
            shutil.rmtree(dst_td, ignore_errors=True)
            print("  [FAIL] %s : %s" % (p["title"][:30], str(e)[:200]))

        if idx % 50 == 0 or idx == len(catalog):
            el = time.time() - t0
            print("  进度 %4d/%d  成功%d 跳过%d 失败%d  测试点%d  %.1fs"
                  % (idx, len(catalog), ok, skip, fail, total_cases, el))

    print("\n" + "=" * 78)
    print("导入完成：成功 %d，跳过 %d，失败 %d，测试点 %d 个，耗时 %.1fs"
          % (ok, skip, fail, total_cases, time.time() - t0))
    print("测试数据落盘：%s" % TESTCASE_ROOT)
    if damaged:
        print("\n源测试数据损坏（已按草稿导入，无测试点）：%d 道" % len(damaged))
        for t, c in damaged:
            print("   - [%s] %s" % (c, t))
    print("=" * 78)
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
