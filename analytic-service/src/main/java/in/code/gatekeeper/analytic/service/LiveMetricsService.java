package in.code.gatekeeper.analytic.service;

import in.code.gatekeeper.analytic.dto.responseDto.LivePointResponse;
import in.code.gatekeeper.analytic.dto.responseDto.LiveRouteResponse;
import in.code.gatekeeper.analytic.util.PrometheusClient;
import in.code.gatekeeper.analytic.util.PrometheusClient.Point;
import in.code.gatekeeper.analytic.util.PrometheusClient.Sample;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Live per-route performance from the gateways' Prometheus metrics, for tenants too (decision #67). The organization
 * label is put into every query here, from the caller's scope (a number), so a tenant can only ever see its own
 * series; null = all organizations (platform admins).
 */
@Service
@RequiredArgsConstructor
public class LiveMetricsService {

    private static final String COUNT = "gateway_requests_seconds_count";
    private static final String BUCKET = "gateway_requests_seconds_bucket";
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration STEP = Duration.ofSeconds(15);

    private final PrometheusClient prometheus;

    public List<LiveRouteResponse> routes(Long organizationId) {
        String by = "organization, route";
        Map<String, Double> rate = byRoute(prometheus.query(sum(by, COUNT, organizationId, "")));
        Map<String, Double> errors = byRoute(prometheus.query(sum(by, COUNT, organizationId, ",status=~\"5..\"")));
        Map<String, Double> limited = byRoute(prometheus.query(sum(by, COUNT, organizationId, ",status=\"429\"")));
        Map<String, Double> p50 = byRoute(prometheus.query(quantile(0.50, by, organizationId)));
        Map<String, Double> p95 = byRoute(prometheus.query(quantile(0.95, by, organizationId)));
        Map<String, Double> p99 = byRoute(prometheus.query(quantile(0.99, by, organizationId)));

        List<LiveRouteResponse> routes = new ArrayList<>();
        rate.forEach((key, requestsPerSecond) -> {
            String[] organizationAndRoute = key.split("\u0000", 2);
            routes.add(new LiveRouteResponse(organizationAndRoute[0], organizationAndRoute[1], requestsPerSecond,
                    requestsPerSecond == 0 ? 0 : errors.getOrDefault(key, 0.0) / requestsPerSecond,
                    millis(p50.get(key)), millis(p95.get(key)), millis(p99.get(key)), limited.getOrDefault(key, 0.0)));
        });
        routes.sort(Comparator.comparingDouble(LiveRouteResponse::requestsPerSecond).reversed());
        return routes;
    }

    public List<LivePointResponse> timeseries(Long organizationId) {
        List<Point> rate = prometheus.range(sum("", COUNT, organizationId, ""), WINDOW, STEP);
        Map<Instant, Double> errors = byTime(prometheus.range(sum("", COUNT, organizationId, ",status=~\"5..\""), WINDOW, STEP));
        Map<Instant, Double> p95 = byTime(prometheus.range(quantile(0.95, "", organizationId), WINDOW, STEP));
        return rate.stream()
                .map(point -> new LivePointResponse(point.timestamp(), point.value(),
                        point.value() == 0 ? 0 : errors.getOrDefault(point.timestamp(), 0.0) / point.value(),
                        millis(p95.get(point.timestamp()))))
                .toList();
    }

    // organization_id is a Long from the token or the platform admin's choice: nothing a caller writes reaches PromQL
    private static String selector(Long organizationId, String extra) {
        String organization = organizationId == null ? "organization_id!=\"none\"" : "organization_id=\"" + organizationId + "\"";
        return "{" + organization + extra + "}";
    }

    private static String sum(String by, String metric, Long organizationId, String extra) {
        return "sum by (" + by + ") (rate(" + metric + selector(organizationId, extra) + "[1m]))";
    }

    private static String quantile(double q, String by, Long organizationId) {
        String groups = by.isEmpty() ? "le" : by + ", le";
        return "histogram_quantile(" + q + ", sum by (" + groups + ") (rate(" + BUCKET + selector(organizationId, "") + "[1m])))";
    }

    private static Map<String, Double> byRoute(List<Sample> samples) {
        Map<String, Double> values = new HashMap<>();
        samples.forEach(s -> values.put(s.labels().get("organization") + "\u0000" + s.labels().get("route"), s.value()));
        return values;
    }

    private static Map<Instant, Double> byTime(List<Point> points) {
        return points.stream().collect(Collectors.toMap(Point::timestamp, Point::value, (a, b) -> a));
    }

    private static long millis(Double seconds) {
        return seconds == null ? 0 : Math.round(seconds * 1000);
    }
}
