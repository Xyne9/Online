# -*- coding: utf-8 -*-
"""题库标签清洗与分类规整。

背景：题库源的 tags 混了三类东西——
  ① 算法标签（DP / 线段树 / 网络流 …）  ← 有用
  ② 赛事与年份（NOI / SDOI / 2017 / POI …）← 对「按算法分类」是噪声
  ③ 数据质量标记（标签待整理 / 数据待检查 / 子任务 / 模板）← 纯噪声
原样入库会导致「分类」维度失效：按 tags[0] 分组时，前 20 名里一半是
年份和赛事名（实测：POI 44、2016 37、标签待整理 30）。

本脚本做三件事（只改 tags / knowledge_point_id，不动题面与测试点）：
  1. 剔除噪声标签
  2. 算法标签归一化（DP→动态规划、最短路→图论 …），每題最多保留 5 个
  3. 重算主知识点 knowledge_point_id

用法：
  python scripts/normalize_problem_tags.py --dry-run
  python scripts/normalize_problem_tags.py --apply
"""
import argparse
import json
import subprocess
import sys
from collections import Counter

MYSQL_BASE = ["docker", "exec", "-i", "oj-cloud-mysql", "mysql",
              "--default-character-set=utf8mb4", "-uroot", "-proot"]

# ── 噪声：赛事 / 年份 / 数据质量标记 ──
NOISE_EXACT = {
    "标签待整理", "数据待检查", "子任务", "模板", "交互题", "提交答案",
    "系统测试", "Special Judge", "Output Only", "早于 2000",
    "LibreOJ β Round", "USACO Contest", "清华集训", "集训队",
}
NOISE_PATTERNS = (
    # 纯年份
    lambda t: t.isdigit() and len(t) == 4 and 1990 <= int(t) <= 2030,
    # 分类目录名
    lambda t: t[:2].isdigit() and "_" in t,
    # 赛事名（省选 / 国际赛）
    lambda t: t in {
        "NOI", "NOIP", "CSP", "CTSC", "CTS", "WC", "THUWC", "THUPC", "IOI",
        "POI", "CEOI", "BalticOI", "JOI", "USACO", "APIO", "COCI", "COCI ",
        "SDOI", "HNOI", "ZJOI", "SCOI", "JLOI", "SHOI", "CQOI", "AHOI",
        "HAOI", "SNOI", "JSOI", "TJOI", "GDOI", "FJOI", "HNOI", "BZOJ",
        "LibreOJ", "LOJ", "UOJ", "Codeforces", "AtCoder", "ICPC", "CCPC",
    },
)

# ── 算法标签归一化：同义 → 标准名 ──
# 覆盖面按「实测出现过的全部标签」补齐（见导入日志的标签频次表）。
# 原则：宁可归到稍大的类（如「倍增」→「倍增」独立保留），也不要丢成「模拟」兜底。
CANON = {
    # 动态规划族
    "DP": "动态规划", "动态规划": "动态规划",
    "树形 DP": "树形DP", "树形DP": "树形DP",
    "数位 DP": "数位DP", "数位DP": "数位DP",
    "状压 DP": "状态压缩", "状态压缩": "状态压缩",
    "背包": "背包", "背包 DP": "背包",
    "区间 DP": "区间DP", "区间DP": "区间DP",
    "单调队列": "单调队列", "单调栈": "单调栈",
    "斜率优化": "斜率优化", "决策单调性": "决策单调性",
    "前缀和": "前缀和", "差分": "差分",
    # 数学族
    "数学": "数学", "数论": "数论", "组合计数": "组合计数",
    "组合数学": "组合计数", "矩阵": "矩阵", "矩阵乘法": "矩阵",
    "多项式": "多项式", "多项式 / 形式幂级数": "多项式",
    "DFT（含 NTT）及FFT": "FFT/NTT", "FFT": "FFT/NTT", "NTT": "FFT/NTT",
    "线性基": "线性基", "博弈论": "博弈论", "概率与期望": "概率与期望",
    "扩展欧几里德算法": "数论", "扩展欧几里得": "数论",
    "欧拉函数": "数论", "逆元": "数论", "素数筛法": "数论",
    "中国剩余定理": "数论", "容斥原理": "组合计数",
    "计算几何": "计算几何", "线性代数": "矩阵",
    # 数据结构族
    "数据结构": "数据结构", "线段树": "线段树", "树状数组": "树状数组",
    "平衡树": "平衡树", "Treap": "平衡树", "Splay": "平衡树",
    "字典树": "字典树", "Trie": "字典树", "分块": "分块",
    "树套树": "树套树", "可并堆": "可并堆", "左偏树": "可并堆",
    "并查集": "并查集", "可持久化": "可持久化",
    "树链剖分": "树链剖分", "LCA": "LCA", "倍增": "倍增",
    "单调栈": "单调栈", "ST 表": "ST表", "ST表": "ST表",
    # 图论族
    "图论": "图论", "最短路": "最短路", "网络流": "网络流",
    "最小生成树": "最小生成树", "二分图": "二分图",
    "二分图匹配": "二分图", "连通分量": "连通分量",
    "拓扑排序": "拓扑排序", "差分约束": "差分约束",
    "强连通分量": "连通分量", "割点": "连通分量", "桥": "连通分量",
    # 字符串族
    "字符串": "字符串", "KMP": "KMP", "AC自动机": "AC自动机",
    "后缀数组": "后缀数组", "哈希": "哈希", "Hash": "哈希",
    "Manacher": "Manacher", "后缀自动机": "后缀自动机",
    "回文串": "字符串", "Z 函数": "字符串",
    # 基础算法族
    "贪心": "贪心", "二分": "二分", "三分": "三分",
    "二分答案": "二分", "整体二分": "二分", "分数规划": "二分",
    "搜索": "搜索", "DFS": "搜索", "BFS": "搜索",
    "迭代加深": "搜索", "剪枝": "搜索", "剪枝优化": "搜索",
    "折半搜索": "搜索", "爬山法": "搜索", "启发式搜索": "搜索",
    "模拟": "模拟", "枚举": "枚举", "构造": "构造",
    "高精度": "高精度", "排序": "排序", "递归": "递归",
    "分治": "分治", "CDQ 分治": "分治", "点分治": "分治",
    "线段树分治": "分治", "树分块": "分块",
    "快速幂": "快速幂", "位运算": "位运算", "二进制": "位运算",
    "随机化": "随机化", "离散化": "离散化", "离线": "离线",
    "复杂度分析": "模拟", "综合": "模拟", "原创": "模拟",
    "文件 IO": "模拟", "正则表达式": "字符串", "自动机": "AC自动机",
    # 补齐：实测出现但前面漏掉的
    "费用流": "网络流", "最大流": "网络流", "最小割": "网络流",
    "上下界网络流": "网络流", "最大权闭合子图": "网络流",
    "k 短路": "最短路", "负环": "最短路", "瓶颈路": "最短路",
    "欧拉回路": "图论", "2-SAT": "图论", "平面图": "图论",
    "仙人掌": "图论", "基环树": "图论", "虚树": "图论",
    "树的直径": "图论", "树上差分": "图论", "DFS 序": "图论",
    "斯坦纳树": "图论", "Gomory–Hu 树": "图论", "Dilworth 定理": "图论",
    "Link-Cut Tree": "数据结构", "K-D Tree": "数据结构",
    "RMQ": "数据结构", "堆": "数据结构", "莫队": "数据结构",
    "扫描线": "数据结构", "树状数组": "树状数组",
    "set": "数据结构", "bitset": "位运算", "hash 表": "哈希",
    "AC 自动机": "AC自动机", "Manacher": "Manacher",
    "Lucas 定理": "组合计数", "Catalan 数": "组合计数",
    "容斥原理": "组合计数", "生成函数 / 母函数": "组合计数",
    "莫比乌斯反演": "数论", "杜教筛": "数论", "类欧几里得算法": "数论",
    "Baby Step Giant Step": "数论", "埃氏筛及欧拉筛": "数论",
    "质因数分解": "数论", "中国剩余定理": "数论",
    "乘法逆元": "数论", "欧拉定理": "数论", "高斯消元": "数学",
    "矩阵树定理": "数学", "凸包": "计算几何", "半平面交": "计算几何",
    "最小圆覆盖": "计算几何", "三维偏序": "分治",
    "轮廓线 DP": "状态压缩", "插头 DP": "状态压缩",
    "递推": "动态规划", "滑动窗口": "单调队列",
    "启发式合并": "数据结构", "可并堆": "可并堆",
    "FWT": "FFT/NTT", "拟阵": "数学", "哈夫曼编码": "贪心",
    "异或": "位运算", "差分约束系统": "差分约束",
}

# ── 标签 → 主知识点（优先级从上到下）──
# 顺序很重要：图论/数据结构/DP 这些"大类"优先，避免一道线段树题被算成"模拟"。
TAG_TO_KP = [
    ({"网络流", "最短路", "图论", "连通分量", "二分图", "并查集",
      "拓扑排序", "最小生成树", "差分约束", "树链剖分", "LCA",
      "倍增", "割点", "强连通分量", "桥"}, "图论"),
    ({"线段树", "树状数组", "平衡树", "字典树", "分块", "树套树",
      "可并堆", "数据结构", "可持久化", "ST表", "单调栈"}, "数据结构"),
    ({"动态规划", "树形DP", "数位DP", "状态压缩", "背包", "区间DP",
      "单调队列", "斜率优化", "决策单调性"}, "动态规划"),
    ({"字符串", "KMP", "AC自动机", "后缀数组", "哈希", "Manacher",
      "后缀自动机", "Z 函数", "回文串"}, "字符串"),
    ({"数论", "组合计数", "矩阵", "多项式", "FFT/NTT", "线性基",
      "博弈论", "概率与期望", "数学", "计算几何"}, "数学"),
    ({"贪心"}, "贪心"),
    ({"二分", "三分"}, "二分"),
    ({"搜索"}, "搜索"),
    ({"模拟", "枚举", "构造", "高精度", "排序", "递归",
      "分治", "快速幂", "位运算", "随机化", "前缀和", "差分"}, "模拟"),
]

KP_NAMES = ["动态规划", "贪心", "图论", "字符串", "数学",
            "数据结构", "搜索", "模拟", "二分", "组合计数"]


def run(sql, db="oj_cloud"):
    p = subprocess.run(MYSQL_BASE + [db], input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:1500])
    return p.stdout.decode("utf-8", "replace")


def query(sql, db="oj_cloud"):
    # -B 让结果以 tab 分隔、-N 去掉列名。注意这两个开关只能在这里加一次，
    # 放进 MYSQL_BASE 会被 mysql 当成要执行的 SQL（实测会让查询静默返回空）。
    p = subprocess.run(MYSQL_BASE + ["-N", "-B", db],
                       input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:1500])
    return [l for l in p.stdout.decode("utf-8", "replace").splitlines() if l.strip()]


def is_noise(t):
    if t in NOISE_EXACT:
        return True
    for f in NOISE_PATTERNS:
        try:
            if f(t):
                return True
        except Exception:
            pass
    return False


def normalize(tags):
    """返回 (干净的算法标签列表, 主知识点名或 None)。

    注意：**返回 None 是合法结果**。
    实测 1005 道题里有 257 道在源数据里只有赛事/年份标签
    （如 ['2016','FJOI']、['标签待整理','SHOI','数据待检查','2014']），
    一个算法标签都没有。这种题不该硬塞进某个知识点——
    强行归类会让「知识点分布」这个统计维度失真，答辩时解释不清。
    正确做法：knowledge_point_id 留 NULL，tags 里保留一个「未分类」标记，
    等人工或后续模型补标注。
    """
    clean = []
    for t in tags:
        t = str(t).strip()
        if not t or is_noise(t):
            continue
        std = CANON.get(t)
        if std is None:
            continue                      # 未识别的算法词，忽略
        if std not in clean:
            clean.append(std)
    s = set(clean)
    kp = None
    for keys, name in TAG_TO_KP:
        if s & keys:
            kp = name
            break
    return clean[:5], kp


def sql_escape(s):
    return "'" + str(s).replace("\\", "\\\\").replace("'", "\\'") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真正写库（默认 dry-run）")
    ap.add_argument("--min-id", type=int, default=1004, help="只处理 id >= 此值的题")
    args = ap.parse_args()

    # 知识点字典
    kp_map = {}
    for line in query("SELECT id,name FROM prob_knowledge_point;"):
        kid, name = line.split("\t", 1)
        kp_map[name] = int(kid)

    rows = query("SELECT id,title,tags,knowledge_point_id FROM prob_question "
                 "WHERE id >= %d AND deleted_at IS NULL;" % args.min_id)
    print("待规整题目：%d 道" % len(rows))

    before_counter, after_counter = Counter(), Counter()
    kp_before, kp_after = Counter(), Counter()
    updates = []
    changed = 0

    id2name = {v: k for k, v in kp_map.items()}
    for line in rows:
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        qid, title, tags_json, kp_id = parts[0], parts[1], parts[2], parts[3]
        try:
            tags = json.loads(tags_json) if tags_json else []
        except Exception:
            tags = []
        for t in tags:
            before_counter[str(t)] += 1
        kp_before[id2name.get(int(kp_id)) if kp_id.isdigit() else "(空)"] += 1

        clean, kp_name = normalize(tags)
        if not clean:
            # 源数据没有算法标签：标成「未分类」，knowledge_point_id 留 NULL。
            # 不硬塞「模拟」——那会让知识点分布失真（实测会多出 257 道假模拟题）。
            clean = ["未分类"]
        for t in clean:
            after_counter[t] += 1
        kp_after[kp_name or "(未分类)"] += 1

        new_kp = kp_map.get(kp_name) if kp_name else None
        # knowledge_point_id 允许写 NULL（未分类），所以比较时要处理 None
        kp_changed = (new_kp != (int(kp_id) if kp_id.isdigit() else None))
        if json.dumps(clean, ensure_ascii=False) != json.dumps(tags, ensure_ascii=False) or kp_changed:
            changed += 1
            updates.append((int(qid), clean, new_kp))

    print("\n标签去重后 TOP 20：")
    print("%-16s %8s %8s" % ("标签", "清洗前", "清洗后"))
    all_tags = set(before_counter) | set(after_counter)
    for t, _ in sorted(((t, after_counter[t]) for t in all_tags),
                       key=lambda x: -x[1])[:20]:
        print("%-16s %8d %8d" % (t[:16], before_counter[t], after_counter[t]))

    print("\n主知识点分布变化：")
    for k in sorted(set(kp_before) | set(kp_after), key=lambda x: -kp_after[x]):
        print("  %-12s %5d → %5d" % (k[:12], kp_before[k], kp_after[k]))

    print("\n需要更新：%d / %d 道" % (changed, len(rows)))

    if not args.apply:
        print("\n[dry-run] 未写库。加 --apply 执行。")
        return 0

    # 分批 UPDATE（每批 200 条，单条 SQL 内用 CASE WHEN 减少往返）
    # ⚠️ updates 里第 3 项已经是**知识点 id**（构建时就 kp_map.get(kp_name) 换过了），
    # 这里不能再查一次 kp_map —— 那样等于拿 id 当名字查，必然查不到，
    # 结果全部写成 NULL（实测 983 道题的知识点被清空）。
    B = 200
    done = 0
    for i in range(0, len(updates), B):
        chunk = updates[i:i + B]
        cases_tags = []
        cases_kp = []
        ids = []
        for qid, clean, kp_id in chunk:
            ids.append(str(qid))
            cases_tags.append("WHEN %d THEN %s" % (qid, sql_escape(json.dumps(clean, ensure_ascii=False))))
            cases_kp.append("WHEN %d THEN %s" % (qid, "NULL" if kp_id is None else str(kp_id)))
        sql = ("UPDATE prob_question SET tags = CASE id %s END, "
               "knowledge_point_id = CASE id %s END WHERE id IN (%s);"
               % (" ".join(cases_tags), " ".join(cases_kp), ",".join(ids)))
        run(sql)
        done += len(chunk)
        if done % 200 == 0 or done == len(updates):
            print("  已更新 %d/%d" % (done, len(updates)))

    print("\n完成：更新 %d 道题。" % done)
    return 0


if __name__ == "__main__":
    sys.exit(main())
