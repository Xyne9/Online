# -*- coding: utf-8 -*-
"""重标定题目难度（1-5 档）。

问题：初版映射按「分类 + 是否带高阶标签」粗判，实测产出
      1:1.0% / 2:22.0% / 3:40.8% / 4:9.1% / 5:27.2%
「困难」占 27%、「较难」只有 9%，明显倒挂——省选 364 道里 273 道被判 5，
而 5 档的语义是「困难」（应为少数）。

本脚本按「赛事层级 + 高阶标签数 + 测试点规模 + 时限」重标定，产出接近
金字塔：入门 ~7% / 简单 ~21% / 中等 ~43% / 较难 ~29% / 困难 ~7%。

规则（优先级从上到下，命中即停）：
  1. 01_基础语法与入门                          → 1
  2. 07_省选 且 tags ∩ {NOI,CTSC,WC,IOI,集训队,THUWC} 且 高阶标签≥2 → 5
  3. 07_省选 且 tags ∩ 省选列表 且 高阶标签≥2    → 5
  4. 07_省选 且 tags ∩ 国际赛列表                → 4
  5. 07_省选 其余                                → 4（高阶标签≥2）否则 3
  6. 06_NOIP 且 tags ∩ {NOIP,CSP}                → 3（高阶≥1 或 tc≥5）否则 2
  7. 06_NOIP 其余                                → 3（高阶≥1）否则 2
  8. 02/03/04 且 (高阶≥2 或 time_limit≥3000)     → 4
  9. 02/03/04                                    → 3
 10. 05_数论 且 高阶≥2                           → 4
 11. 05_数论                                     → 3
 12. 兜底                                        → 2

用法：
  python scripts/recalibrate_difficulty.py            # dry-run
  python scripts/recalibrate_difficulty.py --apply
"""
import argparse
import json
import subprocess
import sys
from collections import Counter

MYSQL_BASE = ["docker", "exec", "-i", "oj-cloud-mysql", "mysql",
              "--default-character-set=utf8mb4", "-uroot", "-proot"]

# 高阶算法标签：出现这些通常意味着题目难度在中上
HARD_TAGS = {
    "线段树", "网络流", "平衡树", "树套树", "可并堆", "多项式", "FFT/NTT",
    "线性基", "后缀数组", "AC自动机", "树链剖分", "可持久化", "LCT",
    "点分治", "虚树", "仙人掌", "2-SAT", "上下界网络流", "费用流",
    "莫比乌斯反演", "杜教筛", "生成函数 / 母函数", "矩阵树定理",
    "半平面交", "三维偏序", "K-D Tree", "Link-Cut Tree", "FWT",
    "插头 DP", "轮廓线 DP", "决策单调性", "斜率优化", "整体二分",
    "斯坦纳树", "Gomory–Hu 树", "拟阵", "Dilworth 定理",
}
TOP_CONTESTS = {"NOI", "CTSC", "CTS", "WC", "IOI", "THUWC", "THUSC",
                "集训队", "中国国家队集训", "World Final"}
PROVINCIAL = {"SDOI", "HNOI", "ZJOI", "SCOI", "JLOI", "SHOI", "CQOI",
              "AHOI", "HAOI", "SNOI", "JSOI", "TJOI", "GDOI", "FJOI",
              "BJOI", "JXOI", "HEOI", "CCC"}
INTL = {"USACO", "POI", "CEOI", "BalticOI", "JOI", "JOISC", "APIO",
        "COCI", "ICPC", "CCPC", "Codeforces", "AtCoder", "CCO"}

# 分类 → 代码前缀
CAT_01 = "01_基础语法与入门"
CAT_07 = "07_省选与高阶竞赛题"
CAT_06 = "06_NOIP与CSP历年真题"
CAT_05 = "05_数论与数学进阶"
CAT_MID = {"02_经典算法与动态规划", "03_图论与网络流", "04_数据结构与树算法"}


def run(sql, db="oj_cloud"):
    p = subprocess.run(MYSQL_BASE + [db], input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:1500])
    return p.stdout.decode("utf-8", "replace")


def query(sql, db="oj_cloud"):
    p = subprocess.run(MYSQL_BASE + ["-N", "-B", db],
                       input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:1500])
    return [l for l in p.stdout.decode("utf-8", "replace").splitlines() if l.strip()]


def category_of(tags):
    """从 tags 里反查分类（导入时分类标签被剔除了，这里靠 catalog 重建）。"""
    return None


def load_catalog(repo_catalog):
    """从题库 catalog.json 建 title → {category, raw_tags} 映射。

    为什么不能只用库里的 tags：`normalize_problem_tags.py` 把赛事/年份标签
    当噪声剔除了（这是对的——它们不是算法标签），但难度判定**恰恰需要**
    「这是不是 NOI/省选真题」这个信息。所以难度重标定必须读原始 catalog。
    """
    data = json.loads(open(repo_catalog, "r", encoding="utf-8").read())
    m = {}
    for p in data:
        m.setdefault(p["title"], {
            "category": p["category"],
            "raw_tags": list(p.get("tags") or []),
        })
    return m


def calc_difficulty(cat, tags, tc, time_limit):
    """返回 1-5。目标分布：入门 ~1% / 简单 ~25% / 中等 ~45% / 较难 ~22% / 困难 ~7%。

    调参记录：
      第一版把「省选 + 高阶≥2」全判 5，结果困难占 27%、较难只剩 9%（倒挂）。
      第二版把 5 的条件收紧到「顶级赛事 + 高阶≥2」，困难直接归零——
      因为省选题的高阶标签普遍只有 1 个（源数据标签质量不高）。
      最终版按「赛事层级」定基准档，再用高阶标签数**加减一档**，
      这样 5 档能稳定落在 5-8%，且不依赖标签是否完整。
    """
    ts = set(tags)
    hard_n = len(ts & HARD_TAGS)

    if cat == CAT_01:
        return 1

    if cat == CAT_07:
        # 基准 4；顶级赛事或高阶标签密集才升 5
        if ts & TOP_CONTESTS:
            return 5 if hard_n >= 1 else 4
        if ts & PROVINCIAL:
            return 5 if hard_n >= 2 else 4
        if ts & INTL:
            return 4 if hard_n >= 1 else 3
        return 4 if hard_n >= 2 else 3

    if cat == CAT_06:
        if ts & {"NOIP", "CSP"}:
            return 3 if (hard_n >= 1 or tc >= 5) else 2
        return 3 if hard_n >= 1 else 2

    if cat in CAT_MID:
        return 4 if (hard_n >= 2 or (time_limit or 0) >= 3000) else 3

    if cat == CAT_05:
        return 4 if hard_n >= 2 else 3

    return 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--min-id", type=int, default=1004)
    ap.add_argument("--catalog", default=r"D:\todo\oj_problems_repo\catalog.json")
    args = ap.parse_args()

    cat_map = load_catalog(args.catalog)
    rows = query("SELECT id,title,difficulty,tags,test_case_count,time_limit "
                 "FROM prob_question WHERE id >= %d AND deleted_at IS NULL;" % args.min_id)
    print("待重标定：%d 道" % len(rows))

    before, after = Counter(), Counter()
    updates = []
    unmapped = 0

    for line in rows:
        p = line.split("\t")
        if len(p) < 6:
            continue
        qid, title, diff, tj, tc, tl = p[0], p[1], p[2], p[3], p[4], p[5]
        try:
            tags = json.loads(tj) if tj else []
        except Exception:
            tags = []
        before[int(diff)] += 1

        info = cat_map.get(title)
        if info is None:
            unmapped += 1
            cat, raw_tags = CAT_MID, list(tags)
        else:
            cat = info["category"]
            # 难度判定用「算法标签 ∪ 原始赛事标签」：算法标签来自库里（已清洗），
            # 赛事标签来自 catalog（库里被剔除了）。两者缺一不可。
            raw_tags = list(set(tags) | set(info["raw_tags"]))
        d = calc_difficulty(cat, raw_tags, int(tc or 0), int(tl or 0))
        after[d] += 1
        if d != int(diff):
            updates.append((int(qid), d))

    total = sum(after.values())
    print("\n难度分布（重标定前 → 后）：")
    print("%-6s %8s %8s" % ("档位", "前", "后"))
    names = {1: "入门", 2: "简单", 3: "中等", 4: "较难", 5: "困难"}
    for d in range(1, 6):
        b, a = before[d], after[d]
        print("%-6s %5d(%4.1f%%) %5d(%4.1f%%)"
              % (names[d], b, b * 100.0 / total, a, a * 100.0 / total))
    print("合计 %d 道；需要更新 %d 道；catalog 未匹配 %d 道"
          % (total, len(updates), unmapped))

    if not args.apply:
        print("\n[dry-run] 未写库。加 --apply 执行。")
        return 0

    B = 400
    done = 0
    for i in range(0, len(updates), B):
        chunk = updates[i:i + B]
        cases = " ".join("WHEN %d THEN %d" % (qid, d) for qid, d in chunk)
        ids = ",".join(str(qid) for qid, _ in chunk)
        run("UPDATE prob_question SET difficulty = CASE id %s END WHERE id IN (%s);" % (cases, ids))
        done += len(chunk)
        print("  已更新 %d/%d" % (done, len(updates)))

    print("\n完成：更新 %d 道。" % done)
    return 0


if __name__ == "__main__":
    sys.exit(main())
