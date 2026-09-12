package org.sgs.walletservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A small human-readable dashboard summarising the numbers that matter, so the service can
 * be eyeballed without standing up Prometheus/Grafana.
 *
 * <p>{@code /metrics} remains the machine-readable Prometheus scrape endpoint; this is a
 * convenience view derived from the same meters.
 */
@RestController
public class MetricsDashboardController {

    private static final String HTTP_REQUESTS = "http.server.requests";

    private final MeterRegistry registry;

    public MetricsDashboardController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> dashboard() {
        List<Timer> timers = this.registry.find(HTTP_REQUESTS).timers().stream().toList();

        long totalRequests = timers.stream().mapToLong(Timer::count).sum();
        long errorRequests = timers.stream()
                .filter(MetricsDashboardController::isError)
                .mapToLong(Timer::count)
                .sum();

        double uptimeSeconds = uptimeSeconds();

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("total_requests", totalRequests);
        http.put("error_requests", errorRequests);
        http.put("error_rate_percent", percentage(errorRequests, totalRequests));
        http.put("requests_per_second", round(uptimeSeconds > 0 ? totalRequests / uptimeSeconds : 0));
        http.put("latency_p50_ms", percentileMillis(timers, 0.5));
        http.put("latency_p95_ms", percentileMillis(timers, 0.95));
        http.put("latency_p99_ms", percentileMillis(timers, 0.99));
        http.put("max_latency_ms", round(timers.stream()
                .mapToDouble(timer -> timer.max(TimeUnit.MILLISECONDS))
                .max()
                .orElse(0)));

        Map<String, Object> transfers = new LinkedHashMap<>();
        transfers.put("created", counterValue("wallet.transfers.created", null));
        transfers.put("declined_insufficient_funds", counterValue("wallet.transfers.declined", "insufficient_funds"));
        transfers.put("idempotent_replays", counterValue("wallet.transfers.idempotent_replays", null));
        transfers.put("declined_total", this.registry.find("wallet.transfers.declined").counters().stream()
                .mapToDouble(Counter::count).sum());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptime_seconds", round(uptimeSeconds));
        body.put("http", http);
        body.put("transfers", transfers);
        body.put("per_endpoint", perEndpoint(timers));
        return ResponseEntity.ok(body);
    }

    private List<Map<String, Object>> perEndpoint(List<Timer> timers) {
        return timers.stream()
                .map(timer -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("method", timer.getId().getTag("method"));
                    row.put("uri", timer.getId().getTag("uri"));
                    row.put("status", timer.getId().getTag("status"));
                    row.put("count", timer.count());
                    row.put("mean_ms", round(timer.mean(TimeUnit.MILLISECONDS)));
                    row.put("max_ms", round(timer.max(TimeUnit.MILLISECONDS)));
                    return row;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")))
                .toList();
    }

    /**
     * Highest configured percentile value across all HTTP timers. Percentiles cannot be
     * averaged, so the worst observed value is reported rather than a blended figure.
     */
    private double percentileMillis(List<Timer> timers, double percentile) {
        double worst = 0;
        for (Timer timer : timers) {
            for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
                if (Math.abs(value.percentile() - percentile) < 0.0001) {
                    worst = Math.max(worst, value.value(TimeUnit.MILLISECONDS));
                }
            }
        }
        return round(worst);
    }

    private double counterValue(String name, String reason) {
        var search = this.registry.find(name);
        if (reason != null) {
            search = search.tag("reason", reason);
        }
        Counter counter = search.counter();
        return (counter != null) ? counter.count() : 0d;
    }

    private static boolean isError(Timer timer) {
        String status = timer.getId().getTag("status");
        return status != null && (status.startsWith("4") || status.startsWith("5"));
    }

    private double uptimeSeconds() {
        var uptime = this.registry.find("process.uptime").timeGauge();
        return (uptime != null) ? uptime.value(TimeUnit.SECONDS) : 0d;
    }

    private static double percentage(long part, long total) {
        return (total == 0) ? 0 : round((part * 100.0) / total);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

