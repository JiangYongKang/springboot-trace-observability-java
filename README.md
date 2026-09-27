# 请求级可观测性（链路 / 指标 / 结构化日志 / 故障隔离）

在 Spring Boot 3.4 (Java 21, Servlet 栈) 上实现一套**请求级**可观测性能力，
让一次请求的链路标识、指标和业务日志可以串起来。仅依赖 Spring Web / Logback
（随 spring-boot-starter-web 提供），本地无需任何外部中间件即可运行与验证。

---

## 1. 链路上下文（Trace Context）

### 1.1 标识模型
每个请求在入口建立一个 `TraceContext`（`observability/trace/TraceContext.java`）：

| 字段 | 含义 | 规则 |
| --- | --- | --- |
| `traceId` | 整条链路标识 | 16 字节，32 位小写十六进制，全链路一致 |
| `spanId` | 当前服务/跨度标识 | 8 字节，16 位小写十六进制，每跳新生成 |
| `parentSpanId` | 上游跨度标识 | 延续上游时为上游 spanId；新起链路为 null |
| `source` | 链路来源 | `EXTRACTED`（延续上游）/ `GENERATED`（缺失或非法时新起） |

上下文保存在 `TraceContextHolder`（ThreadLocal），同时写入 SLF4J MDC 的
`traceId / spanId / traceOrigin`，业务日志自动携带。

### 1.2 传播规则（W3C Trace Context）
请求头默认 `traceparent`，格式：

```
version-traceId-spanId-flags
00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
```

入口过滤器 `TraceFilter` 的判定（`DefaultTracePropagator`）：

| 入站情况 | 判定状态 | 处置 |
| --- | --- | --- |
| 头合法 | `EXTRACTED` | **延续** traceId，上游 spanId 记为 parent，本跳生成新 spanId |
| 头为 null / 空串 / 纯空白 | `MISSING` | **另起一条** `GENERATED` |
| 段数不是 4、非小写十六进制、ID 全 0、版本为 `ff`、v0 长度非 55、flags 非法等 | `INVALID` | **拒绝延续并另起一条** `GENERATED`，同时在 `observability.self` 打 WARN，内容含具体 `reason` |

> “缺失”和“非法”是两种**显式区分**的状态（`ExtractResult.Status`），
> 不会混为一谈；非法请求的判定依据会打印出来，便于排查上游问题。
>
> 大写十六进制按 W3C“只接受小写”的约定判为非法，不做隐式归一化吞数据。

响应默认通过同名头回写本次请求最终使用的 traceparent，客户端可据此核对
是“延续”还是“新起”。

### 1.3 异步 / 线程池切换
- `TracingTaskDecorator` 实现 Spring `TaskDecorator`：在**提交线程**捕获
  `TraceContext` 与 MDC 的不可变快照，在**执行线程**恢复，任务结束后
  还原执行线程的旧值（池线程正常为空）。
- 提供 `DecoratedExecutorService` 包装 `ExecutorService`，`execute/submit/
  invokeAll/invokeAny` 全部经过装饰。
- 因此：池化线程复用时不会残留上一个请求的标识，也不会把 A 请求的 traceId
  串到 B 请求；提交方没有上下文时，执行线程也会被清空。

过滤器只在初始 `REQUEST` 分发执行（跳过 FORWARD/ERROR/ASYNC），
避免 500/404 后容器 FORWARD 到 `/error` 时重复建立上下文或产生脏数据。

---

## 2. 请求级指标（Metrics）

### 2.1 采集内容
按“标签组合”在进程内累计，暴露于 `GET /observability/metrics`：

- `count`：请求量
- `totalNanos` / `maxNanos`：总耗时 / 最大耗时（纳秒）

标签（`MetricLabels`）共 4 个，取值全部归一化：

| 标签 | 取值 |
| --- | --- |
| `method` | HTTP 方法，如 `GET` |
| `route` | 归一化接口标识，如 `/api/users/{id}`，见 2.2 |
| `result` | `SUCCESS`(2xx/3xx) / `CLIENT_ERROR`(4xx) / `SERVER_ERROR`(其余；handler 抛异常也归此类) |
| `origin` | `EXTRACTED` / `GENERATED`，与链路来源一致 |

### 2.2 路由归一化规则（`RouteNormalizer`）
1. 优先使用 Spring MVC 已匹配的 **URI 模板**
   （`BEST_MATCHING_PATTERN_ATTRIBUTE`），如 `/api/users/{id}`。
2. 没有模板时（典型为 404，未进入 HandlerMapping）对原始 path **逐段归一化**：
   - 纯数字段 → `{id}`
   - UUID 段（8-4-4-4-12）→ `{id}`
   - 16~64 位长十六进制段（trace/资源 hash 风格）→ `{id}`
   - 段内非安全字符（非字母数字 `_.-~`）替换为 `_`
3. 归一化结果长度受 `route-max-length` 限制，超出截断并加 `...`。
4. **查询串永不进入标签**（采集用的是 `requestURI`，天然不含 query）；
   **异常类型与异常 message 永不进入标签**，结果只按状态码三分类。

这样原始地址、资源 ID、异常文本都不会把指标数量撑爆。

### 2.3 有界基数与并发正确性（`DefaultRequestMetricRecorder`）
- 标签组合（时间序列）数量有**硬上限** `max-series`：
  新组合只在同步块内双重检查后注册，已注册组合数**严格不超过上限**。
- 超过上限的组合统一计入 `__overflow__` 兜底桶（`method/result/origin` 标为
  `OTHER`），并在首次发生时向 `observability.self` 打一次 ERROR 告警。
- 读/更新走 `ConcurrentHashMap` + `AtomicLong` 原子累加：并发下
  **注册不重复、计数不丢失**；`snapshot()` 返回只读拷贝。

### 2.4 采集位置（避免 500 误判与重复计数）
- `MetricsFilter`（TraceFilter 之后）只在 `REQUEST` 分发运行，负责统一打点
  起始时间，并对**未进入 handler** 的请求（404 等）兜底记录。
- `MetricsHandlerInterceptor` 在 DispatcherServlet **内部**记录正常请求：
  此时最终状态码与 URI 模板均已确定；若 handler 抛异常（此刻状态可能还是 200），
  依据拦截器的 `ex` 参数归类为 `SERVER_ERROR`，保证口径准确。
- 拦截器记录后打标记，Filter 不再重复记录；FORWARD 到 `/error` 的二次分发
  在 Filter 与拦截器两侧都跳过，杜绝 `route=/error` 的脏指标。
- 注意：Filter 的 finally 中**绝不 return**——那会吞掉正在传播的业务异常，
  导致 500 被当成 200（实现中已规避并加注释）。

---

## 3. 结构化日志与故障隔离

### 3.1 稳定字段 JSON 日志
`logback-spring.xml` 使用自研 `StructuredJsonLogLayout`，每行一条 JSON，
字段集合固定为 9 个，不随调用方变化：

```
timestamp, level, logger, thread, message, traceId, spanId, traceOrigin, stack
```

- 业务日志通过 MDC 自动带上当次请求的 `traceId/spanId/traceOrigin`；
- 非请求线程（无上下文）这三个字段输出 `null`，字段集合仍稳定；
- 按 RFC 8259 转义引号、反斜杠、换行与控制字符；异常进 `stack`；message 超长截断。

### 3.2 可观测性组件自身故障不拖垮业务
所有可观测性扩展点（`TracePropagator`、`RequestMetricRecorder`）都是接口，
默认实现带 `@ConditionalOnMissingBean`，可替换。

组件自身的任何异常都被就地隔离：
- 链路解析/生成失败 → 降级为新起 `GENERATED` 链路，业务继续；
- 响应头写入失败 → 仅记录，业务继续；
- 指标记录失败（拦截器 + Filter 兜底两处）→ 仅记录，业务响应/状态码不变。

故障**不会被静默吞掉**：统一写入名为 **`observability.self`** 的 logger，
带 ERROR/WARN 级别与完整堆栈，可直接在日志里检索定位。

---

## 4. 配置项（`application.properties`）

| Key | 默认值 | 说明 |
| --- | --- | --- |
| `observability.trace.header-name` | `traceparent` | 上游/下游链路头名称 |
| `observability.trace.response-header-enabled` | `true` | 是否在响应头回写最终 traceparent |
| `observability.metrics.max-series` | `200` | 指标标签组合数量硬上限，超出进 `__overflow__` 兜底桶 |
| `observability.metrics.route-max-length` | `128` | 归一化后路由标签最大长度，超出截断 |
| `server.port` | `0` | 随机端口；本地手动验证可改成固定端口（示例用 18080） |

---

## 5. 本地验证

### 5.1 构建与测试

```bash
# 全量测试（31 个用例），离线环境也可跑：
mvn -o test

# 打包
mvn -o package -DskipTests
```

测试覆盖（用例日志均打印“输入 + 判定依据”）：

| 测试类 | 覆盖点 |
| --- | --- |
| `DefaultTracePropagatorTest` | 合法延续、缺失(MISSING)新起、9 类非法(INVALID)拒绝 |
| `TraceContextConcurrencyTest` | 64 线程 × 500 轮并发不串号不残留；200 个池任务快照传播与执行后清空 |
| `DefaultRequestMetricRecorderTest` | 32 线程并发累加 count/total 精确；500 个标签严格收敛到 50 序列 + 兜底桶且不丢计数 |
| `RouteNormalizerTest` | 模板优先、数字/UUID/长 hex 归并、危险字符替换、超长截断、空路径兜底 |
| `StructuredJsonLogLayoutTest` | JSON 合法、9 字段稳定、无上下文为 null、转义与堆栈 |
| `TraceAndMetricsWebMvcTest` | 端到端：延续/另起、路径归并、500→SERVER_ERROR、404→CLIENT_ERROR、无 /error 脏指标 |
| `ObservabilityFaultIsolationWebMvcTest` | 传播器与记录器内部全抛异常时业务仍 200，且 `observability.self` 留有带堆栈错误 |

### 5.2 启动应用手工验证

```bash
mvn -o package -DskipTests
java -jar target/springboot-trace-observability-java-1.0-SNAPSHOT.jar --server.port=18080
```

> 若本机走 HTTP 代理，curl 请加 `--noproxy '*'` 或用 127.0.0.1 直连。

```bash
# 1) 合法头：响应头延续同一 traceId，spanId 是新的
curl -s --noproxy '*' -D - -o /dev/null \
  -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' \
  http://127.0.0.1:18080/api/ping

# 2) 非法头：响应 traceId 与入参完全不同（另起），日志有 reject WARN
curl -s --noproxy '*' -D - -o /dev/null \
  -H 'traceparent: ZZ-not-a-valid-header' http://127.0.0.1:18080/api/ping

# 3) 无头：新起
curl -s --noproxy '*' -D - -o /dev/null http://127.0.0.1:18080/api/ping

# 4) 不同路径参数归并为同一条指标 /api/users/{id}
curl -s --noproxy '*' http://127.0.0.1:18080/api/users/123 >/dev/null
curl -s --noproxy '*' http://127.0.0.1:18080/api/users/456 >/dev/null

# 5) 切线程池：返回的三个 traceId 必须一致；池线程日志也带同一 traceId
curl -s --noproxy '*' \
  -H 'traceparent: 00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01' \
  http://127.0.0.1:18080/api/async

# 6) 业务异常 5xx；无匹配路由 404
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/boom
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/no/such/999

# 7) 查看进程内累计指标（序列数有界、计数不重不丢）
curl -s --noproxy '*' http://127.0.0.1:18080/observability/metrics
```

控制台日志为单行 JSON，可用 `jq` 直接解析，例如检索可观测性自检事件：

```bash
# 应用启动输出重定向到文件后
grep '"logger":"observability.self"' app.log | jq .
```

### 5.3 演示接口

| 接口 | 用途 |
| --- | --- |
| `GET /api/ping` | 正常请求，业务日志带 traceId |
| `GET /api/users/{id}` | 路径参数，验证不同 id 归并成一条指标 |
| `GET /api/async?delayMillis=20` | 切到装饰过的线程池，验证异步传播与不串号 |
| `GET /api/boom` | 主动抛异常，验证 500 归类与链路下指标记录 |
| `GET /observability/metrics` | 暴露进程内累计指标快照及当前 traceId |
