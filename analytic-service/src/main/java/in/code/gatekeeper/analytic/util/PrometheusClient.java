package in.code.gatekeeper.analytic.util;

import in.code.gatekeeper.analytic.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus' HTTP query API. Queries are built by LiveMetricsService only, never taken from a caller.
 */
@Component
public class PrometheusClient {

    public record Sample(Map<String, String> labels, double value) {
    }

    public record Point(Instant timestamp, double value) {
    }

    private final RestClient restClient;

    public PrometheusClient(@Value("${analytics.prometheus.url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    // One value per series, now
    public List<Sample> query(String promQl) {
        JsonNode result = call("/api/v1/query?query={q}", promQl);
        List<Sample> samples = new ArrayList<>();
        for (JsonNode series : result) {
            samples.add(new Sample(labels(series), number(series.path("value").path(1).asString())));
        }
        return samples;
    }

    // The first series' values from start to now, one every step (empty when there was no traffic)
    public List<Point> range(String promQl, Duration window, Duration step) {
        Instant end = Instant.now();
        JsonNode result = call("/api/v1/query_range?query={q}&start={s}&end={e}&step={st}", promQl,
                end.minus(window).getEpochSecond(), end.getEpochSecond(), step.toSeconds());
        List<Point> points = new ArrayList<>();
        if (result.isEmpty()) {
            return points;
        }
        for (JsonNode value : result.get(0).path("values")) {
            points.add(new Point(Instant.ofEpochSecond(value.path(0).asLong()), number(value.path(1).asString())));
        }
        return points;
    }

    private JsonNode call(String uri, Object... variables) {
        try {
            JsonNode body = restClient.get().uri(uri, variables).retrieve().body(JsonNode.class);
            if (body == null || !"success".equals(body.path("status").asString())) {
                throw ApiException.unavailable("Live metrics are not available right now");
            }
            return body.path("data").path("result");
        } catch (RestClientException e) {
            throw ApiException.unavailable("Live metrics are not available right now");
        }
    }

    private static Map<String, String> labels(JsonNode series) {
        Map<String, String> labels = new LinkedHashMap<>();
        series.path("metric").properties().forEach(entry -> labels.put(entry.getKey(), entry.getValue().asString()));
        return labels;
    }

    // NaN (no requests in the window, so no rate or quantile) becomes 0
    private static double number(String value) {
        double number = Double.parseDouble(value);
        return Double.isFinite(number) ? number : 0;
    }
}
