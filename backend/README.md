# oj-cloud · 后端微服务工程（Day 2 骨架）

《基于 AI Agent 与微服务架构的启发式智能在线判题系统》后端父工程。
本阶段目标（开发手册第 2 步）：**搭出 Maven 父子工程骨架，`mvn clean compile` 通过，网关 + 5 个空壳服务能注册到 Nacos。**

---

## 1. 版本矩阵

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17** | `maven.compiler.release=17`，Boot 3.x 最低要求 |
| Spring Boot | **3.2.5** | BOM：`spring-boot-dependencies` |
| Spring Cloud | **2023.0.1** | BOM：`spring-cloud-dependencies` |
| Spring Cloud Alibaba | **2023.0.1.0** | BOM：`spring-cloud-alibaba-dependencies` |
| jjwt | **0.12.6** | `jjwt-api` / `jjwt-impl` / `jjwt-jackson` |
| MySQL Driver | 8.3.0 | 仅在 `dependencyManagement` 占位（第 3 步接入 DAO） |
| MyBatis-Plus | 3.5.7 | 占位，`mybatis-plus-spring-boot3-starter`（Boot 3 专用变体） |
| Maven | 3.9.x | 通过容器 `maven:3.9-eclipse-temurin-17` 执行，宿主机无需安装 |
| LangChain4j | **本阶段不引入** | 见下方「取舍说明」 |

**为什么最终选 SCA 2023.0.1.0 而不是 2022.0.0.0？**
任务书给了两套候选。SCA `2022.0.0.0` 官方对应的 Spring Cloud 是 `2022.0.x`，与 Boot 3.2 组合会在
`spring-cloud-context` / `spring-cloud-commons` 的版本约束上告警甚至报错；SCA `2023.0.1.0`
官方对应 `Spring Cloud 2023.0.1` + `Spring Boot 3.2.x`，三者属于同一代际。
**实测 `mvn clean compile` 与 `mvn package` 均 BUILD SUCCESS，无版本冲突。**

---

## 2. 模块职责

```text
oj-cloud-parent (pom)
├── oj-common-core       # 通用内核：Result / ErrorCode / BusinessException / JwtUtil / RedisKeys / PageResult
├── oj-common-api        # 服务间契约：7 个 OpenFeign 客户端（19 个方法）+ 共享 DTO
├── oj-gateway           # :8080  路由转发 + AuthGlobalFilter(JWT) + CorsWebFilter
├── oj-teaching-service  # :8081  认证 + 教务 + 双阶段查重
├── oj-problem-service   # :8082  题库 + 测试点
├── oj-judge-service     # :8083  RabbitMQ 判题 + Go-Judge 适配
├── oj-ai-agent-service  # :8084  端云多模型工厂 + 四阶段启发式 SSE
└── oj-admin-service     # :8085  通道探活 + AI 审计 + 沙箱监控
```

| 模块 | 关键类 | 说明 |
| --- | --- | --- |
| `oj-common-core` | `Result<T>` / `PageResult<T>` / `ErrorCode` / `BusinessException` / `JwtUtil` / `RedisKeys` / `RoleConstants` / `TraceIdHolder` | 普通 jar，**不** repackage |
| `oj-common-api` | `feign.*`（7 个接口）、`dto.*`（17 个 DTO）、`constant.InnerConstants` | 普通 jar，**不** repackage |
| `oj-gateway` | `GatewayApplication` / `AuthGlobalFilter` / `CorsConfig` / `GatewayAuthProperties` / `JwtConfig` | WebFlux，可启动 |
| 5 个 service | `XxxApplication` + `HealthController` | Servlet，可启动 |

### 2.1 9 大标准错误码（`ErrorCode`）

文档口径为「9 大」，但表中实际列出 10 个常量（含成功的 `200`）。本实现**按表格逐字实现全部 10 个**，不做删减。

| code | 枚举 | 语义 |
| --- | --- | --- |
| 200 | `SUCCESS` | 成功 |
| 400 | `BAD_REQUEST` | 参数校验失败 |
| 401 | `UNAUTHORIZED` | 未登录或 Token 失效 |
| 403 | `FORBIDDEN` | 权限不足（含越权） |
| 404 | `NOT_FOUND` | 资源不存在 |
| 409 | `CONFLICT` | 状态冲突（重复提交 / 乐观锁 / 窗口已过） |
| 429 | `TOO_MANY_REQUESTS` | 限流 / 防抖 |
| 500 | `INTERNAL_ERROR` | 服务内部错误 |
| 502 | `BAD_GATEWAY` | 上游依赖失败（模型通道全挂 / Go-Judge 非 200） |
| 504 | `GATEWAY_TIMEOUT` | 上游超时（探活 / AI 首字 / 判题） |

### 2.2 统一响应结构（`Result<T>`）

```json
{
  "code": 200,
  "message": "ok",
  "data": "oj-teaching-service UP",
  "traceId": "d90eaa530fb9",
  "timestamp": 1793034794331,
  "success": true
}
```

- `code` 与 HTTP 状态码严格同值；
- `traceId` 由网关生成并透传（响应头 `X-Trace-Id` 同值），排查问题时可直接按 traceId 串日志；
- `success` 是 `code == 200` 的派生字段，方便前端 `if (res.success)` 判断。

### 2.3 Redis 12 类 Key（`RedisKeys`）

`oj:auth:token:{uid}`、`oj:judge:lock:{uid}`、`oj:ai:quota:{assignId}:{probId}:{userId}`、
`oj:ai:level:{userId}:{probId}:{stage}`、`oj:ai:memory:{sessionId}`、`oj:ai:config:channels`、
`oj:auth:refresh:{uid}`、`oj:seq:{biz}`、`oj:admin:probe:{channelCode}`、
`oj:plagiarism:task:{taskId}`、`oj:exam:seq:{examId}`、`oj:rate:ai:stream:{uid}`。
全项目禁止手写 key 字符串，一律调用 `RedisKeys` 的静态方法。

### 2.4 7 个 OpenFeign 客户端 / 19 个方法

| # | 客户端 | `path` | `contextId` | 方法数 |
| --- | --- | --- | --- | --- |
| 1 | `TeachingInnerFeignClient` | `/inner/teaching` | `teachingInner` | 2 |
| 2 | `TeachingAggregateFeignClient` | `/inner/teaching` | `teachingAggregate` | 4 |
| 3 | `ProblemInnerFeignClient` | `/inner/problem` | `problemInner` | 3 |
| 4 | `ProblemTestCaseFeignClient` | `/inner/problem` | `problemTestCase` | 3 |
| 5 | `JudgeInnerFeignClient` | `/inner/judge` | `judgeInner` | 2 |
| 6 | `AiInnerFeignClient` | `/inner/ai` | `aiInner` | 3 |
| 7 | `AdminAggregateFeignClient` | `/inner/admin` | `adminAggregate` | 2 |
| | | | **合计** | **19** |

> ⚠️ 同名服务（`oj-problem-service` / `oj-teaching-service` 各被两个接口引用）必须给
> `contextId`，否则 Spring 会因 Bean 名冲突启动失败。本项目已全部显式指定。

---

## 3. 网关路由与前缀

| 前缀 | 目标服务 | 说明 |
| --- | --- | --- |
| `/api/auth/**` | `lb://oj-teaching-service` | 登录 / 刷新 / 注销 / 当前用户 |
| `/api/teaching/**` | `lb://oj-teaching-service` | 班级、作业、考试、提交记录、查重 |
| `/api/problem/**` | `lb://oj-problem-service` | 题目 CRUD、测试点、样例运行 |
| `/api/judge/**` | `lb://oj-judge-service` | 提交、状态轮询、重判 |
| `/api/ai/**` | `lb://oj-ai-agent-service` | 四阶段 SSE、渐进提示、诊断 |
| `/api/admin/**` | `lb://oj-admin-service` | 通道探活、审计、沙箱监控 |

**注意：网关不做 `StripPrefix`。** 5 个服务的 Controller 自身就带完整前缀
（如 `@RequestMapping("/api/teaching")`），网关原样透传，避免「网关剥一层、服务又加一层」的双重前缀事故。
`spring.cloud.gateway.discovery.locator.enabled=false` 也已关闭，全站只认上表 6 个前缀，
不会出现 `/oj-teaching-service/**` 这种第二套可用路径。

### 3.1 AuthGlobalFilter 鉴权流程

`Ordered.HIGHEST_PRECEDENCE + 10`，早于 `RouteToRequestUrlFilter(10000)`，未鉴权请求打不到下游。

1. 生成 / 透传 `X-Trace-Id`，写入响应头；
2. 命中白名单直放：`/api/auth/login`、`/api/auth/refresh`、`/actuator/**`、`/api/*/health`；
3. 取 `Authorization: Bearer <token>`，缺失或格式错 → 401；
4. 校验 JWT 签名与 `exp` → 失败 401；
5. 校验 Redis 在线态 `RedisKeys.authToken(uid)` → key 不存在 401（可关闭）；
6. 比对 Redis 中存的值与请求 token → 不一致 401（可关闭，单点登录关键）；
7. 注入 `X-User-Id` / `X-User-Role` / `X-Class-Id` 头给下游做资源归属校验。

**Day 2 降级开关：**

```yaml
oj:
  gateway:
    jwt-check-enabled: true
    redis-check:
      enabled: false      # 默认 false
```

本阶段 5 个服务尚未实现登录接口，Redis 里没有任何 `oj:auth:token:*`。
若 `redis-check.enabled=true`，验收 curl 会全部 401。**第 4 步认证接口上线后再置为 `true`。**

401 响应体与普通响应同构（HTTP 状态码与 `body.code` 严格同值）：

```json
{"code":401,"message":"未登录或 Token 失效","data":null,"traceId":"b1fcf0335d5a","timestamp":1793034796667,"success":false}
```

---

## 4. 用容器编译 / 打包

宿主机**不需要**安装 JDK 与 Maven，全部走容器。工作目录 `D:\毕设\agent oj`：

```powershell
# 编译
docker run --rm `
  -v "D:\毕设\agent oj\backend:/w" `
  -v "$env:USERPROFILE\.m2:/root/.m2" `
  -w /w maven:3.9-eclipse-temurin-17 `
  mvn -B clean compile

# 打包（跳过测试，产出 6 个可启动 fat jar）
docker run --rm `
  -v "D:\毕设\agent oj\backend:/w" `
  -v "$env:USERPROFILE\.m2:/root/.m2" `
  -w /w maven:3.9-eclipse-temurin-17 `
  mvn -B -DskipTests clean package
```

镜像拉不动时可换国内镜像 `docker.m.daocloud.io/maven:3.9-eclipse-temurin-17`；
依赖下载慢时在 `~/.m2/settings.xml` 配阿里云：

```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

> 首次构建约 3 分钟（要下 BOM 和全部 starter），之后有 `~/.m2` 缓存约 30 秒。

---

## 5. 启动 6 个服务

### 5.1 前置：基础环境已在跑

| 服务 | 地址 | 凭据 |
| --- | --- | --- |
| Nacos | `http://127.0.0.1:8848`（v2.2.3 standalone） | **默认关闭鉴权**，直连即可 |
| MySQL | `127.0.0.1:3306` | 见 `docker/mysql/init/init_oj_cloud.sql` 头部 |
| Redis | `127.0.0.1:6379` | 无密码 |
| RabbitMQ | `127.0.0.1:5672`（管理台 `:15672`） | `admin / admin123` |

> **Nacos 鉴权说明**：基线环境 `nacos.core.auth.enabled` 未开启，此时客户端若仍携带
> `username/password`，会反复请求 `/v1/auth/users/login` 并拿到 500，日志每 5 秒刷一次 ERROR。
> 因此 6 个 `application.yml` 里都写成 `username: ${NACOS_USERNAME:}` / `password: ${NACOS_PASSWORD:}`，
> **默认不发送凭据**。后期给 Nacos 开了鉴权，启动时加
> `-DNACOS_USERNAME=nacos -DNACOS_PASSWORD=nacos` 即可，凭据不落仓库。

### 5.2 启动命令（宿主机 JVM）

```powershell
$java = "D:\Java\jdk-17\bin\java.exe"   # 换成你的 JDK 17 路径
cd "D:\毕设\agent oj\backend"

Start-Process $java -ArgumentList '-jar','oj-teaching-service\target\oj-teaching-service-1.0.0-SNAPSHOT.jar'
Start-Process $java -ArgumentList '-jar','oj-problem-service\target\oj-problem-service-1.0.0-SNAPSHOT.jar'
Start-Process $java -ArgumentList '-jar','oj-judge-service\target\oj-judge-service-1.0.0-SNAPSHOT.jar'
Start-Process $java -ArgumentList '-jar','oj-ai-agent-service\target\oj-ai-agent-service-1.0.0-SNAPSHOT.jar'
Start-Process $java -ArgumentList '-jar','oj-admin-service\target\oj-admin-service-1.0.0-SNAPSHOT.jar'
Start-Process $java -ArgumentList '-jar','oj-gateway\target\oj-gateway-1.0.0-SNAPSHOT.jar'
```

### 5.3 启动命令（全容器，可选）

```powershell
docker network create oj-cloud_default   # 已存在则跳过

docker run -d --name oj-teaching --network oj-cloud_default -p 8081:8081 `
  -e NACOS_ADDR=oj-cloud-nacos:8848 -e REDIS_HOST=oj-cloud-redis `
  -v "D:\毕设\agent oj\backend\oj-teaching-service\target:/app" `
  eclipse-temurin:17-jre java -jar /app/oj-teaching-service-1.0.0-SNAPSHOT.jar
```

> 容器内注册的 IP 是容器 IP，跨容器 `lb://` 通信没问题；但**宿主机浏览器/curl 经网关访问时**，
> 网关会转发到容器 IP，Docker Desktop（WSL2）下通常可通。若不通，改用 5.2 的宿主机方案，
> 或给服务加 `--spring.cloud.nacos.discovery.ip=<宿主机可达 IP>`。

### 5.4 端口与配置覆盖

| 服务 | 端口 | 可覆盖的环境变量 |
| --- | --- | --- |
| `oj-gateway` | 8080 | `NACOS_ADDR`、`OJ_JWT_SECRET` |
| `oj-teaching-service` | 8081 | `NACOS_ADDR`、`REDIS_HOST`、`REDIS_PORT`、`OJ_JWT_SECRET` |
| `oj-problem-service` | 8082 | `NACOS_ADDR`、`OJ_JWT_SECRET` |
| `oj-judge-service` | 8083 | `NACOS_ADDR`、`RABBITMQ_HOST`、`RABBITMQ_PORT` |
| `oj-ai-agent-service` | 8084 | `NACOS_ADDR` |
| `oj-admin-service` | 8085 | `NACOS_ADDR` |

---

## 6. 验证 Nacos 注册

### 6.1 健康检查（经网关）

```bash
curl http://127.0.0.1:8080/api/teaching/health
# {"code":200,"message":"ok","data":"oj-teaching-service UP","traceId":"d90eaa530fb9","timestamp":1793034794331,"success":true}
```

5 个服务都有同名端点：`/api/{teaching,problem,judge,ai,admin}/health`。

### 6.2 服务列表（直连 Nacos OpenAPI）

```bash
curl "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20"
# {"count":6,"doms":["oj-judge-service","oj-problem-service","oj-ai-agent-service","oj-teaching-service","oj-gateway","oj-admin-service"]}
```

`count` 必须为 **6**（网关自己 + 5 个业务服务）。

### 6.3 实例健康状态

```bash
curl "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=oj-teaching-service"
# hosts[0].ip=127.0.0.1, port=8081, healthy=true, enabled=true
```

> PowerShell 用户请用 `Invoke-RestMethod` 代替 `curl`，或直接调 `curl.exe`（`curl` 在 pwsh 里是
> `Invoke-WebRequest` 的别名，会把 JSON 当对象处理）。

### 6.4 鉴权链路自测

```bash
# 无 token → 401
curl -i http://127.0.0.1:8080/api/teaching/students
# HTTP/1.1 401 Unauthorized  {"code":401,...}

# 白名单 → 直放
curl http://127.0.0.1:8080/api/teaching/health
# {"code":200,...}
```

---

## 7. 取舍与已知偏差

1. **SCA 用 2023.0.1.0 而非任务书首选 2022.0.0.0**
   理由：SCA 2022.0.0.0 官方对应 Spring Cloud 2022，与 Boot 3.2 代际不匹配。
   任务书已授权「以能编译通过为准」，实测 2023 组合构建通过。

2. **用宿主机 `java -jar` 而非容器跑 6 个服务**
   理由：Windows + Docker Desktop 下，容器内注册的是容器 IP，宿主机 `curl` 访问 `lb://` 目标
   存在连通性风险；宿主机 JVM 起服务时注册 `127.0.0.1`，验收最直接。
   **构建仍然全程走 Maven 容器**，宿主机只用到已存在的 JDK 17（未新装任何东西）。

3. **`spring-cloud-starter-alibaba-nacos-config` 未接入**
   任务书明确「可选，先不接 config 也行」。本阶段配置全部写在各模块 `application.yml`，
   避免多一个 Nacos 配置中心连接点在 Day 2 引入不稳定因素。第 3 步再统一迁到配置中心。

4. **LangChain4j 未引入实际依赖**
   父 POM 的 `dependencyManagement` 中已用注释占位并说明理由：
   langchain4j 各版本对 jackson / okhttp / reactor 的传递依赖较杂，过早引入会与 Boot 3.2 BOM
   冲突且显著拖慢首次构建。等第 3 阶段开工时在此处统一锁版本，子模块只写 `groupId/artifactId`。

5. **MySQL / MyBatis-Plus 只在 `dependencyManagement` 占位**
   本阶段不接 DAO 层。服务 `application.yml` 里也**不配 `spring.datasource.url`**，
   否则启动时 HikariCP 会立即尝试建连并报错。

6. **`oj-judge-service` 只装 RabbitMQ 连接，不声明队列**
   队列/交换机的声明留到第 5 步判题实现（避免消费者未就绪时消息堆积）。

7. **`ErrorCode` 实现了 10 个常量而非 9 个**
   文档称「9 大错误码」，但表格实际列出 10 行（含 `200 成功`）。按表格逐字实现，
   少一个前端就无法区分「通道挂了(502)」和「参数错了(400)」。

8. **网关 `redis-check.enabled` 默认 `false`**
   任务书明确要求：「Day2 验收时避免全 401」。第 4 步认证接口上线后置 `true`。

9. **网关不做 `StripPrefix`、`discovery.locator.enabled=false`**
   避免出现第二套可用路径。服务 Controller 自带完整前缀，网关原样透传。

---

## 8. 目录约定

- `target/` 已被 `backend/.gitignore` 忽略，**不提交构建产物**；
- 根目录 `.gitignore` 未改动；
- 本模块只写 `backend/`，**未触碰 `prototype/` 下任何文件**；
- `docker-compose.yml` 未改动（第 2 步的 6 个服务不写进 compose）。
