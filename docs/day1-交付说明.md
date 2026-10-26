# Day 1 交付说明 —— 底层环境容器化与数据库一次性建表

> 对应《从 0 到 1 的 6 步标准开发顺序与操作手册》**第 1 步 / 第 1 天**。
> 执行日期：2026-10-26　｜　验收脚本：`python scripts/verify-day1.py`（当前 **31/31 全绿**）

---

## 1. 交付物清单

| 文件 | 作用 |
|---|---|
| [`docker-compose.yml`](../docker-compose.yml) | 一键拉起 5 个基础容器：MySQL 8.0 / Redis 7 / RabbitMQ 3.12 / Nacos 2.2.3 / go-judge |
| [`docker/mysql/init/init_oj_cloud.sql`](../docker/mysql/init/init_oj_cloud.sql) | 17 张业务表 + 1 张字典表 = **18 张表** 的完整 DDL + 开箱即用种子数据（4880 行） |
| [`docker/judge/Dockerfile`](../docker/judge/Dockerfile) | 把 gcc/g++/make/python3/openjdk-21 固化进 go-judge 镜像层 |
| [`docker/judge/smoke_test.py`](../docker/judge/smoke_test.py) | 三语言真实判题冒烟（C++ / Java / Python 各编译运行一次） |
| [`scripts/verify-day1.py`](../scripts/verify-day1.py) | 一键跑完本日全部验收项 |

---

## 2. 五容器拓扑与端口

| 容器 | 镜像 | 宿主端口 | 用途 |
|---|---|---|---|
| `oj-cloud-mysql` | `mysql:8.0` | 3306 | 业务库 `oj_cloud`，首启自动执行 init SQL |
| `oj-cloud-redis` | `redis:7-alpine` | 6379 | 登录态 / 判题缓存 / AI 配额（12 类 Key） |
| `oj-cloud-rabbitmq` | `rabbitmq:3.12-management` | 5672 / 15672 | 判题异步削峰 + 管理台 |
| `oj-cloud-nacos` | `nacos/nacos-server:v2.2.3` | 8848 / **9848** | 注册中心 + 配置中心 |
| `oj-cloud-go-judge` | `oj-go-judge:latest` | 5050 | 判题沙箱内核（含三语言工具链） |

固定 `name: oj-cloud`、`networks.default.name: oj-cloud_default`，避免因目录名含中文与空格导致网络名漂移。

### 三个容易踩的坑（均已在本机复现并规避）

1. **Nacos 必须同时映射 9848**。Nacos 2.x 客户端走 gRPC 长连接，端口 = 8848 + 1000。只映射 8848 时服务能注册，但客户端永远连不上。
2. **go-judge 必须 `privileged: true` + `cgroup: host`**。Docker Desktop 默认 `cgroupns=private`，容器内 `/proc/self/cgroup` 只有 `0::/`，go-judge 会以 `create environment builder failed: cgroup path is empty` 直接退出。
3. **go-judge 的 `cpuLimit / clockLimit / memoryLimit / procLimit / stackLimit` 必须写在每个 `cmd` 对象内部**，不是请求顶层。写在顶层会被静默忽略 → `procLimit` 取默认 0 → 目标 cgroup `pids.max=0` → `clone3(CLONE_INTO_CGROUP)` 返回 `EAGAIN`，接口报
   `{"status":"Internal Error","error":"execve: start: /bin/xxx: clone: resource temporarily unavailable"}`。
   这个报错极具误导性，看起来像宿主机资源不足，实际是参数位置写错。详见 [`docker/judge/smoke_test.py`](../docker/judge/smoke_test.py) 文件头注释。

---

## 3. 数据库：18 张表

> 手册原文写「17 张表」，设计文档总述为「**17 张核心业务表 + 1 张字典辅助表**」，
> 因此实际 `CREATE TABLE` 共 **18** 条。分区如下：

| 归属服务 | 表数 | 表名 |
|---|---:|---|
| `oj-teaching-service` | 9 | `sys_user`、`teach_class`、`teach_assignment`、`assignment_submission`、`teach_student_score`、`exam_monitor_event`、`teach_plagiarism_task`、`teach_plagiarism_report`、`teach_plagiarism_segment` |
| `oj-problem-service` | 3 | `prob_knowledge_point`（字典表）、`prob_question`、`prob_test_case` |
| `oj-judge-service` | 2 | `judge_submission`、`judge_sandbox_instance` |
| `oj-ai-agent-service` | 4 | `ai_model_config`、`ai_model_role`、`ai_chat_record`、`ai_call_feedback` |

DDL 与《04- 终极完整版设计文档》第 3 章**逐字对齐**（脚本化 diff，唯一允许差异是 `CREATE TABLE` → `CREATE TABLE IF NOT EXISTS`）。

### 种子数据

| 项 | 数量 | 内容 |
|---|---:|---|
| 测试账号 | 3 | 学生 `20220101`（林昭）／教师 `T001`（王砚）／管理员 `admin`（运维管理员），口令均为 **`123456`**（BCrypt 哈希存储） |
| 班级 | 1 | 计算机2022级1班 |
| 知识点字典 | 5 | 动态规划 / 贪心 / 图论 / 字符串 / 数学 |
| 题目 | 3 | `#1001 最大子段和`、`#1002 两数之和`、`#1003 回文串判断`，均含保密 `official_solution` 与三语言代码骨架 |
| 测试点 | 30 | 每题 10 个，`SUM(score) = 100`，前 2 个公开（`is_public=1`），覆盖 sample / normal / edge / stress 四档 |
| 模型通道 | 4 | `local-qwen`（Ollama 本机）、`cloud-ds`、`cloud-qwen`、`cloud-zhipu` |
| 角色绑定 | 5 | `local-qwen` → ROUTER + GUARDRAIL；三条云端 → TUTOR（优先级 10 / 20 / 30） |

**测试点正确性**经过三重复核：独立算法对拍（30/30 期望输出一致）、代码骨架实跑、字节数与 `OCTET_LENGTH()` 核对。

### 幂等性

脚本可重复导入：建表用 `IF NOT EXISTS`，固定主键种子用 `INSERT IGNORE`；`prob_test_case` 因唯一键含可空列 `deleted_at`（MySQL 中 NULL 不参与判重），改用「先 DELETE 该题旧行再 INSERT」。实测连跑 3 次全部 exit 0。

---

## 4. 本地大模型：显存预算的实测取舍

机器为 **RTX 4060 Laptop / 8 GB 显存 + 16 GB 内存**。手册允许在显存紧张时降级，实测数据如下：

| 模型 | 默认上下文 | 实测显存占用 | 推理分布 | 结论 |
|---|---|---:|---|---|
| `qwen2.5-coder:7b` | 32768 | **7160 MiB** | CPU 9% / GPU 91% | 会顶满显存并溢出到 CPU，与 Nacos + MySQL + RabbitMQ 同机时有 OOM 风险 |
| `qwen2.5-coder:7b` | **8192** | **5960 MiB** | **100% GPU** | ✅ 采用：留出约 2.2 GB 余量给显示与其他进程 |
| `qwen2.5-coder:1.5b` | 32768 | 约 2.1 GB | 100% GPU | 备用 / 快速冒烟 |

**结论：主模型采用 `qwen2.5-coder:7b` + `num_ctx=8192`**，在 8 GB 显存内可满速运行；`1.5b` 作为极端情况下的降级备份。

两个模型都已落在 **`D:\ollma-models`**（通过用户级环境变量 `OLLAMA_MODELS` 固定，不污染 C 盘）。

> 注意：`OLLAMA_MODELS` 需在**启动 Ollama 服务之前**生效。当前机器上它只在当前会话设置过，若重启后 `ollama list` 变空，请把该变量写入系统环境变量并重启 Ollama。

### OpenAI 兼容端点

`http://localhost:11434/v1/models` 已返回两个模型；`/v1/chat/completions` 实测可用（`ai_model_config.base_url` 里写的是 `http://host.docker.internal:11434/v1`，供容器内访问宿主机 Ollama）。

---

## 5. 验收结果

```
$ python scripts/verify-day1.py
结果：31/31 通过
```

| 验收项 | 结果 |
|---|---|
| 5 个容器 Up 且 healthy | ✅ |
| `oj_cloud` 库 18 张表齐全 | ✅ |
| 3 账号 / 1 班级 / 4 通道 / 3 题目 / 30 测试点 / 每题 100 分 | ✅ |
| BCrypt 口令 `123456` 可校验（错误口令被拒） | ✅ |
| Redis PING → PONG、可写可读 | ✅ |
| RabbitMQ 管理台 15672 HTTP 200 | ✅ |
| Nacos 8848/nacos HTTP 200、liveness OK、配置读写通过 | ✅ |
| go-judge `/version` HTTP 200（v1.13.0） | ✅ |
| Ollama `/v1/models` 可访问、7b 已就位 | ✅ |
| go-judge 三语言真实判题（C++ / Java / Python 各 AC） | ✅ |

---

## 6. 常用命令

```powershell
# 启动全部 5 个容器
docker compose up -d

# 查看状态
docker compose ps

# 一键验收
python scripts/verify-day1.py

# 只跑判题冒烟
python docker/judge/smoke_test.py

# 连库
docker exec -it oj-cloud-mysql mysql -uroot -proot oj_cloud

# 重置数据库（会清空 data/mysql 后重新执行 init SQL）
docker compose down
Remove-Item -Recurse -Force .\data\mysql
docker compose up -d mysql

# 停止（保留数据）
docker compose down
```

---

## 7. 已知边界

1. **init SQL 只在首次初始化数据卷时执行**。改了 SQL 必须删 `./data/mysql` 重建，否则不会重跑。
2. **`D:\毕设\agent oj\data\` 是运行期数据目录**（MySQL/Redis/RabbitMQ/Nacos 的持久化卷），已加入 `.gitignore`，不进仓库。
3. **go-judge 镜像与 Nacos 镜像体积较大**（1.14 GB / 1.25 GB）。国内网络下已改用 `docker.m.daocloud.io` 等镜像源拉取；`docker/judge/Dockerfile` 的 `APT_MIRROR` 默认指向阿里云，但**本机实测阿里云 apt 源存在证书校验失败**，构建该派生镜像时若失败可改回 `deb.debian.org`（本机已预置可用的 `oj-go-judge:latest`，无需重新构建）。
4. **`prob_test_case` 重跑会重建 30 行**，自增 id 会变。若后续有外键指向 `prob_test_case.id` 需注意。
5. 本机 **没有 g++ / javac**，因此 Java / C++ 代码骨架只做了静态体检（main 存在、TODO 存在、括号平衡），真实编译验证是在 **go-judge 容器内**完成的（已通过）。

---

## 8. 下一步（第 2 天）

按手册进入 **第 2 步**：

1. 前端 Stage 0 适配底座：`prototype/app/` 下新建 `api/`（`http.js` / `sse.js` / `poll.js` / `mock.js`）、`adapter/`（7 张映射表 + `index.js`）、`boot.js`、`login.html`；把 `shell.js` 的 8 组硬编码常量迁到 `api/mock.js`。
2. 后端 Maven 多模块骨架：`oj-cloud-parent` + `oj-common-core` / `oj-common-api` / `oj-gateway:8080` / `oj-teaching-service:8081` / `oj-problem-service:8082` / `oj-judge-service:8083` / `oj-ai-agent-service:8084` / `oj-admin-service:8085`。

> 环境提示：本机**尚未安装 JDK 与 Maven**（`java` / `mvn` 均不在 PATH）。第 2 天开工前需先装 JDK 17 或 21 + Maven 3.9，或将构建放进 `maven:3.9.9-eclipse-temurin-17` 容器（本机已有该镜像）。
