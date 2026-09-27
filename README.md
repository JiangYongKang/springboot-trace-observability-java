# Spring Boot 请求级可观测性（链路 / 指标 / 日志）

为每个进入服务的 HTTP 请求提供一套**相互串联**的请求级可观测性能力：

- **链路（Trace）**：每个请求都有稳定的 `traceId` / `spanId`，上游带了合法链路信息时延续，
  缺失或非法时另起一条，且两种“另起”可区分；切线程池后不串号、不残留。
- **指标（Metrics）**：基于 Micrometer 按接口记录请求量、耗时、处理结果；
  标签经过归一化，基数严格有界，并发下注册/更新不重复、不丢失。
- **日志（Logging）**：业务日志为逐行 JSON，字段集合固定，并自动携带当次请求的链路字段。
- **故障隔离**：可观测性组件自身出错时不影响业务请求，同时留下 WARN 日志和可查询的计数。

只依赖 Spring Boot 已管理的常规开源库：`spring-boot-starter-web`、`micrometer-core`，
日志用 classpath 上已有的 Logback + Jackson（自实现一个轻量 JSON encoder，无需额外依赖）。

---

## 1. 链路传播规则

### 1.1 请求头

| 请求头 | 含义 | 必填 |
| --- | --- | --- |
| `X-Trace-Id` | 上游链路标识 | 延续时必填 |
| `X-Span-Id` | 上游跨度标识（延续后成为本次请求的 `parentSpanId`） | 延续时必填 |
| `X-Parent-Span-Id` | 上游的父跨度标识 | 可选 |

响应头会回写 `X-Trace-Id`、`X-Span-Id` 和 `X-Trace-Decision`，便于调用方对账。

### 1.2 标识格式与合法性

- `traceId`：16~32 位十六进制；`spanId` / `parentSpanId`：8~16 位十六进制。大小写不敏感，接受后统一转小写。
- 全为 `0` 视为非法。
- 本服务新生成时：`traceId` 为 32 位、`spanId` 为 16 位小写十六进制（`SecureRandom`，保证非全零）。

### 1.3 三种处置（可区分）

| 条件 | `X-Trace-Decision` | 行为 |
| --- | --- | --- |
| 三个头全部缺失/空白 | `GENERATED_MISSING` | 另起一条全新链路 |
| traceId、spanId 均合法，parentSpanId 缺失或合法 | `INHERITED` | 延续上游 `traceId`，上游 `spanId` 作为 `parentSpanId`，本次生成新 `spanId` |
| 任一字段部分缺失、格式非法、全零 | `GENERATED_INVALID` | **整个上游上下文作废**，另起一条全新链路，并在日志 `reason` 中记录判定依据 |

> 注意：“只带了半个上下文”（例如有 traceId 没有 spanId）按 **非法** 处理而不是“缺失”，
> 不会只继承半截链路。`GENERATED_MISSING` 与 `GENERATED_INVALID` 因此可以明确区分
> “上游没传”和“上游传了但不合法”。

### 1.4 异步 / 线程池传播

- `TraceTaskDecorator` 实现 Spring `TaskDecorator`，在**提交线程**拍下 `ThreadLocal`
  上下文与 MDC 快照，在**工作线程**执行前设置、执行后（finally）恢复/清理。
- 因此：工作线程看到的是提交者的链路；任务结束后工作线程不残留；线程被后续请求复用时不会串号。
- 演示线程池 Bean 名为 `obsTaskExecutor`，业务方法用 `@Async("obsTaskExecutor")`。

### 1.5 相关类

`TraceServletFilter`（入口过滤器）、`TraceHeaderParser`（判定）、`TraceIds`（生成/校验）、
`TraceContext` / `TraceContextHolder`（ThreadLocal 载体）、`TraceMdc`（MDC 适配）、
`TraceTaskDecorator`（跨线程传播）。

---

## 2. 指标与归一化规则

指标名：`http.server.requests`（Micrometer `Timer`，`count` 即请求量，同时记录耗时分布）。

| 标签 | 取值与归一化规则 |
| --- | --- |
| `method` | HTTP 方法；不匹配 `[A-Z]{1,10}` 时记为 `OTHER` |
| `uri` | 优先用 Spring MVC 的 **best-matching-pattern**（如 `/users/{id}`，天然折叠路径变量）；<br>**拿不到模板的请求（典型是未命中处理器的 404）一律记为常量 `unmatched`**——原始地址不可信，保留任何字面量都会被 `/random-1`、`/random-2` 撑爆；<br>模板还要过字符集（`/A-Za-z0-9_{}.-`）、长度（≤128）与 `..` 路径穿越校验，不通过同样记 `unmatched`；根路径记 `/` |
| `result` | 只有 `SUCCESS`（<400）、`CLIENT_ERROR`（4xx）、`SERVER_ERROR`（5xx）、`ERROR`（有异常穿透过滤器链）四种 |
| `exception` | 只取异常**类简单名**，且必须命中白名单前缀/名单，否则统一 `OtherException`；无异常为 `None`；**异常消息绝不进入标签**；会先剥离 `ServletException`/`CompletionException`/`ExecutionException`/`NestedServletException` 包装取根因（最多 10 层，防自引用） |

为什么 404 不用原始路径：像 Spring 官方 `DefaultUriTemplateHandler` 一样，
不可信的原始路径无法做到“既保留可读性又严格有界”，这里选择严格有界。

- **有界性**：接口数 × method × 4 种 result × (白名单异常数 + 2) 即为标签组合上限。
- **并发安全**：相同标签在 Micrometer `MeterRegistry` 中只会注册成同一个 `Timer`，
  `Timer` 的计数更新本身线程安全，多线程并发注册/更新不重复、不丢失。

异常白名单通过配置调整（见第 4 节）。

---

## 3. 结构化日志与故障隔离

### 3.1 JSON 日志

`JsonLogEncoder`（Logback encoder，Jackson 序列化）输出逐行 JSON，字段固定：

```
timestamp, level, logger, thread, traceId, spanId, parentSpanId, traceDecision, message, stacktrace
```

- 四个链路字段**始终出现**，无上下文时为 `null`，字段稳定便于解析。
- `stacktrace` 单独承载异常堆栈，不与 message 混在一起。
- 其余 MDC 不输出，避免动态字段破坏结构。

### 3.2 故障隔离（不炸业务，也不吞错）

所有观测动作都经 `SelfDiagnostics.runSafely/supplySafely` 执行（捕获 `Throwable`）：

1. 内部异常**不会**抛给业务，请求继续正常执行；
2. 异常以 WARN 写入独立 logger `observability.self-diagnostics`（含 operation、errorType、堆栈）；
3. 同时按操作名在内存累加计数，可经 `GET /internal/metrics` 的 `selfDiagnostics` 字段查询
   （或在代码/测试中用 `SelfDiagnostics.errorCount(operation)` 读取）。

操作名包括：`trace.parseHeaders`、`trace.mdcPut`、`trace.mdcClear`、
`trace.responseHeaders`、`trace.logDecision`、`requestMetrics.record`。

JSON encoder 自身故障时输出一条降级的安全 JSON 行，不向业务线程抛异常。

---

## 4. 配置项（`application.properties`）

```properties
# 总开关：false 时不注册链路过滤器、指标过滤器、异步线程池
observability.enabled=true

# 演示用异步线程池（经 TraceTaskDecorator 传播上下文）
observability.async.core-pool-size=2
observability.async.max-pool-size=4
observability.async.queue-capacity=64
observability.async.thread-name-prefix=obs-async-

# exception 标签白名单：类名前缀（包名）
observability.allowed-exception-prefixes[0]=java.
observability.allowed-exception-prefixes[1]=jakarta.
observability.allowed-exception-prefixes[2]=org.springframework.
observability.allowed-exception-prefixes[3]=com.github.highcumontoa.springboottraceobservabilityjava.

# exception 标签白名单：异常简单类名
observability.allowed-exception-names[0]=IllegalArgumentException
observability.allowed-exception-names[1]=IllegalStateException
observability.allowed-exception-names[2]=RuntimeException
observability.allowed-exception-names[3]=IOException
observability.allowed-exception-names[4]=ServletException
```

指标注册表默认是内存版 `SimpleMeterRegistry`；classpath 上存在其它 `MeterRegistry`
（如 Prometheus registry）时会自动让位、直接替换。

---

## 5. 本地验证

### 5.1 自动化测试

```bash
mvn test
```

共 25 个用例，覆盖：

| 测试类 | 覆盖点 |
| --- | --- |
| `TraceHeaderParserTest` | 延续 / 缺失新建 / 非法新建三类判定、大小写归一、全零非法、半上下文非法；日志打印输入与判定依据 |
| `MetricNameNormalizerTest` | 模板折叠路径变量、无模板路径→`unmatched`、1000 条随机路径只有 1 个标签、恶意模板拒绝、result 有界、异常消息不入标签、非白名单异常→`OtherException` |
| `TraceTaskDecoratorTest` | 线程池传播、任务结束无残留、8 提交者×50 任务并发不串号、无上下文任务保持工作线程干净 |
| `TraceConcurrencyIntegrationTest` | 400 并发 HTTP 请求 traceId 唯一且与响应体一致、合法头延续/非法头另起、异步 5 次调用 traceId 一致且彼此不同、200 随机 404 + 200 路径变量请求指标不丢（count 各 200）且组合数有界 |
| `ObservabilityFailureIsolationTest` | MeterRegistry 故障、解析器抛 InternalError 时业务仍成功且内部错误被计数（不吞错）；健康路径正常写入指标 |

单测日志中带 `[input]` / `[basis]` 前缀，显式打印输入数据和判定依据。

### 5.2 手工启动验证

```bash
mvn spring-boot:run            # 默认随机端口；固定端口：
# mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18099"
```

演示接口：

| 接口 | 用途 |
| --- | --- |
| `GET /ping` | 最简单成功请求，响应体/响应头带 traceId |
| `GET /users/{id}` | 路径变量接口，验证 `uri=/users/{id}` |
| `GET /async` | 切线程池，返回请求线程与工作线程的 traceId 是否一致、是否泄漏 |
| `GET /fail` | 抛白名单异常 `IllegalStateException` |
| `GET /boom` | 抛非白名单包异常，指标折叠为 `OtherException` |
| `GET /internal/metrics` | 列出全部 `http.server.requests` 标签组合与 count，以及观测组件内部错误计数 |

示例（设端口 18099）：

```bash
# 1) 无头：GENERATED_MISSING
curl -s -D- http://localhost:18099/ping -o /dev/null | grep -i x-trace

# 2) 合法头：INHERITED，traceId 原样延续
curl -s -D- -H 'X-Trace-Id: aabbccdd00112233aabbccdd00112233' \
              -H 'X-Span-Id: 0011223344556677' \
              http://localhost:18099/ping -o /dev/null | grep -i x-trace

# 3) 非法头：GENERATED_INVALID，并生成全新 traceId
curl -s -D- -H 'X-Trace-Id: garbage' -H 'X-Span-Id: 0011223344556677' \
              http://localhost:18099/ping -o /dev/null | grep -i x-trace

# 4) 异步不串号：leaked=false，两个 traceId 相同
curl -s http://localhost:18099/async

# 5) 高基数路径不撑爆指标
for i in $(seq 1 200); do curl -s -o /dev/null http://localhost:18099/nope/$RANDOM-$i; done
curl -s http://localhost:18099/internal/metrics | python3 -m json.tool
```

控制台日志为 JSON，可直接用 `jq` 等按 `traceId` 串起同一次请求的所有日志。
