package com.github.highcumontoa.springboottraceobservabilityjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP 层并发场景：链路不串号/不残留、合法头延续/非法头另起、并发指标不重不漏且数量有界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceConcurrencyIntegrationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequestsGetUniqueStableTracesAndBoundedMetrics() throws Exception {
        int threads = 16;
        int perThread = 25;
        int total = threads * perThread;
        // 无界队列保证全部任务先入队、再开闸，避免“线程都在等开闸信号、主线程却无法入队开闸”的死锁
        ExecutorService pool = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch start = new CountDownLatch(1);
        // latch 按“提交的任务数”计数（每个任务内部串行发 perThread 个请求），不是按请求数
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> traceIds = ConcurrentHashMap.newKeySet();
        Set<String> decisions = ConcurrentHashMap.newKeySet();
        Set<String> crossFailures = ConcurrentHashMap.newKeySet();
        AtomicInteger completed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        HttpHeaders headers = new HttpHeaders();
                        // 一半请求带“唯一的”合法上游链路头，一半不带，覆盖两种来源
                        if ((idx + i) % 2 == 0) {
                            String uniqueUpstream =
                                    String.format("%016x%016x", 0x0123456789abcdefL,
                                            ((long) idx << 32) | (i & 0xffffffffL));
                            headers.set("X-Trace-Id", uniqueUpstream);
                            headers.set("X-Span-Id", "aaaaaaaaaaaaaaaa");
                        }
                        ResponseEntity<Map> resp = restTemplate.exchange(url("/ping"),
                                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
                        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
                        String decision = resp.getHeaders().getFirst("X-Trace-Decision");
                        traceIds.add(traceId);
                        decisions.add(decision);
                        // 响应体 traceId 与响应头一致 = 业务代码读到的是同一个上下文
                        Object bodyTrace = resp.getBody() == null ? null : resp.getBody().get("traceId");
                        if (!String.valueOf(bodyTrace).equals(traceId)) {
                            crossFailures.add("body/header mismatch");
                        }
                        int n = completed.incrementAndGet();
                        if (n % 20 == 0) {
                            log.info("completed {}/{}", n, total);
                        }
                    }
                } catch (Throwable e) {
                    log.error("worker failure", e);
                    crossFailures.add(e.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "all concurrent requests must finish");
        pool.shutdownNow();

        log.info("[basis] {} concurrent requests -> {} distinct traceIds, decisions={}",
                total, traceIds.size(), decisions);
        assertEquals(total, traceIds.size(), "each request must have a unique traceId");
        assertTrue(decisions.contains("INHERITED"), "valid upstream headers must be inherited");
        assertTrue(decisions.contains("GENERATED_MISSING"),
                "requests without headers must start a new trace");
        assertTrue(crossFailures.isEmpty(), "no context cross-talk: " + crossFailures);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidHeadersStartNewTraceAndAreDistinguishable() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "!!illegal!!");
        headers.set("X-Span-Id", "bbbbbbbbbbbbbbbb");
        ResponseEntity<Map> resp = restTemplate.exchange(url("/ping"),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        String decision = resp.getHeaders().getFirst("X-Trace-Decision");
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        log.info("[input] X-Trace-Id='!!illegal!!', X-Span-Id legal -> "
                + "[decision] {}, generatedTraceId={}", decision, traceId);
        assertEquals("GENERATED_INVALID", decision);
        assertNotEquals("!!illegal!!", traceId);
        assertTrue(traceId.matches("[0-9a-f]{32}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validHeadersAreEchoedAndInheritedEndToEnd() {
        String traceId = "abcdefabcdefabcdabcdefabcdefabcd";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", traceId);
        headers.set("X-Span-Id", "1234123412341234");
        ResponseEntity<Map> resp = restTemplate.exchange(url("/ping"),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        log.info("[input] valid upstream traceId={} -> [decision] {}, response traceId={}",
                traceId, resp.getHeaders().getFirst("X-Trace-Decision"),
                resp.getHeaders().getFirst("X-Trace-Id"));
        assertEquals("INHERITED", resp.getHeaders().getFirst("X-Trace-Decision"));
        assertEquals(traceId, resp.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncSwitchKeepsSameTraceAndLeaksNothing() throws Exception {
        // 连续多次调用，既验证线程池内 traceId 一致，也验证上一次请求不会残留到下一次
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> resp = restTemplate.getForEntity(url("/async"), Map.class);
            Map<?, ?> body = resp.getBody();
            log.info("[input] GET /async #{} -> requestThreadTraceId={}, workerThreadTraceId={}, leaked={}",
                    i, body.get("requestThreadTraceId"),
                    body.get("workerThreadTraceId"), body.get("leaked"));
            assertEquals(body.get("requestThreadTraceId"), body.get("workerThreadTraceId"),
                    "worker thread must see the same traceId");
            assertEquals(Boolean.FALSE, body.get("leaked"));
            requestIds.add((String) body.get("requestThreadTraceId"));
        }
        assertEquals(5, new HashSet<>(requestIds).size(), "each async request keeps its own trace");
    }

    @Test
    @SuppressWarnings("unchecked")
    void highCardinalityPathsDoNotInflateMetricSeries() {
        // 大量未命中模板的 404 路径 + 路径变量接口，指标标签组合必须有界
        for (long i = 0; i < 200; i++) {
            restTemplate.getForEntity(url("/nope/seg-" + i + "-" + (i * 7L)), String.class);
        }
        for (long i = 0; i < 200; i++) {
            restTemplate.getForEntity(url("/users/" + i), Map.class);
        }
        ResponseEntity<Map> metrics = restTemplate.getForEntity(url("/internal/metrics"), Map.class);
        Map<?, ?> body = metrics.getBody();
        int seriesCount = ((Number) body.get("seriesCount")).intValue();
        List<Map<String, String>> series = (List<Map<String, String>>) body.get("series");
        Set<String> uris = new HashSet<>();
        long unmatchedCount = 0;
        long usersCount = 0;
        for (Map<String, String> row : series) {
            uris.add(row.get("uri"));
            if ("unmatched".equals(row.get("uri"))) {
                unmatchedCount += Long.parseLong(row.get("count"));
            }
            if ("/users/{id}".equals(row.get("uri"))) {
                usersCount += Long.parseLong(row.get("count"));
            }
        }
        log.info("[basis] 200 random 404s + 200 /users/{{id}} calls -> seriesCount={}, uris={}, "
                        + "unmatched.count={}, users.count={}",
                seriesCount, uris, unmatchedCount, usersCount);
        assertTrue(uris.contains("unmatched"));
        assertTrue(uris.contains("/users/{id}"));
        assertFalse(uris.stream().anyMatch(u -> u.startsWith("/nope/")),
                "raw 404 paths must never appear as metric tags");
        assertEquals(200, unmatchedCount, "no request must be lost: " + unmatchedCount);
        assertEquals(200, usersCount, "no request must be lost: " + usersCount);
        // 整个进程到此刻为止的标签组合数应远小于请求数（method/uri/result/exception 都有界）
        assertTrue(seriesCount < 30, "metric series must be bounded, got " + seriesCount);
    }
}
