package in.code.gatekeeper.analytic.service;

import in.code.gatekeeper.analytic.document.OrganizationDocument;
import in.code.gatekeeper.analytic.document.TrafficMinuteDocument;
import in.code.gatekeeper.analytic.dto.responseDto.OrganizationStatsResponse;
import in.code.gatekeeper.analytic.dto.responseDto.RouteStatsResponse;
import in.code.gatekeeper.analytic.dto.responseDto.SummaryResponse;
import in.code.gatekeeper.analytic.dto.responseDto.TimeBucketResponse;
import in.code.gatekeeper.analytic.enums.TimeRange;
import in.code.gatekeeper.analytic.enums.TimeRange.Window;
import in.code.gatekeeper.analytic.repository.OrganizationRepository;
import in.code.gatekeeper.analytic.service.TrafficAggregations.Stats;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static in.code.gatekeeper.analytic.service.TrafficAggregations.bucketStart;
import static in.code.gatekeeper.analytic.service.TrafficAggregations.match;
import static in.code.gatekeeper.analytic.service.TrafficAggregations.statsGroup;

/**
 * The dashboard, routes and organizations views, summed from the per-minute rollups (traffic_minutes, at most a
 * few seconds behind, decision #73). organizationId null = every organization (platform admins); callers pass the
 * already-scoped id (AnalyticsUser.scope).
 */
@Service
@RequiredArgsConstructor
public class TrafficStatsService {

    private static final int ROUTE_TREND_SLICES = 12;
    private static final Document HAS_ROUTE = new Document("$match", new Document("routeId", new Document("$ne", null)));
    private static final Document HAS_ORGANIZATION =
            new Document("$match", new Document("organizationId", new Document("$ne", null)));

    private final MongoTemplate mongoTemplate;
    private final OrganizationRepository organizationRepository;

    public List<TimeBucketResponse> timeseries(TimeRange range, Long organizationId) {
        return timeseries(range, range.window(Instant.now()), organizationId);
    }

    public SummaryResponse summary(TimeRange range, Long organizationId) {
        Window current = range.window(Instant.now());
        Stats now = total(current, organizationId);
        Stats before = total(range.previous(current), organizationId);
        List<TimeBucketResponse> buckets = timeseries(range, current, organizationId);

        return new SummaryResponse(range.code(), now.requests(), now.errorRate(), now.serverErrorRate(), now.p50(),
                now.p95(), now.rateLimited(), now.replays(),
                new SummaryResponse.Deltas(change(now.requests(), before.requests()),
                        change(now.errorRate(), before.errorRate()), change(now.p95(), before.p95()),
                        change(now.rateLimited(), before.rateLimited()), change(now.replays(), before.replays())),
                new SummaryResponse.Trends(
                        buckets.stream().map(TimeBucketResponse::requests).toList(),
                        buckets.stream().map(TimeBucketResponse::errorRate).toList(),
                        buckets.stream().map(TimeBucketResponse::p95).toList(),
                        buckets.stream().map(TimeBucketResponse::rateLimited).toList(),
                        buckets.stream().map(TimeBucketResponse::replays).toList()));
    }

    // Busiest routes first; events that never matched a route (404s on unknown paths) are left out
    public List<RouteStatsResponse> routes(TimeRange range, Long organizationId) {
        Window window = range.window(Instant.now());
        Document routeKey = new Document("organizationSlug", "$organizationSlug").append("routeId", "$routeId");

        List<Document> stats = run(List.of(match(window.from(), window.to(), organizationId), HAS_ROUTE,
                statsGroup(routeKey)));
        Map<String, long[]> trends = routeTrends(window, organizationId);

        return stats.stream()
                .map(group -> {
                    Document key = group.get("_id", Document.class);
                    Stats s = Stats.of(group);
                    long[] trend = trends.getOrDefault(routeKey(key), new long[ROUTE_TREND_SLICES]);
                    return new RouteStatsResponse(key.getString("organizationSlug"), key.getString("routeId"),
                            s.requests(), s.errorRate(), s.p50(), s.p95(), s.rateLimited(), s.replays(),
                            Arrays.stream(trend).boxed().toList());
                })
                .sorted(Comparator.comparingLong(RouteStatsResponse::requests).reversed())
                .toList();
    }

    // Busiest organizations first; names come from the configuration stream (slug when not received yet)
    public List<OrganizationStatsResponse> organizations(TimeRange range) {
        Window window = range.window(Instant.now());
        Document organizationKey = new Document("organizationId", "$organizationId")
                .append("organizationSlug", "$organizationSlug");
        Map<Long, OrganizationDocument> organizations = organizationRepository.findAll().stream()
                .collect(Collectors.toMap(OrganizationDocument::getId, Function.identity()));

        return run(List.of(match(window.from(), window.to(), null), HAS_ORGANIZATION, statsGroup(organizationKey)))
                .stream()
                .map(group -> {
                    Document key = group.get("_id", Document.class);
                    Long id = key.get("organizationId", Number.class).longValue();
                    String slug = key.getString("organizationSlug");
                    OrganizationDocument organization = organizations.get(id);
                    Stats s = Stats.of(group);
                    return new OrganizationStatsResponse(id, slug,
                            organization != null && organization.getName() != null ? organization.getName() : slug,
                            s.requests(), s.errorRate(), s.serverErrorRate(), s.p50(), s.p95(), s.rateLimited(),
                            s.replays());
                })
                .sorted(Comparator.comparingLong(OrganizationStatsResponse::requests).reversed())
                .toList();
    }

    private List<TimeBucketResponse> timeseries(TimeRange range, Window window, Long organizationId) {
        long stepMs = range.step().toMillis();
        Map<Long, Stats> byBucket = new HashMap<>();
        run(List.of(match(window.from(), window.to(), organizationId), statsGroup(bucketStart(stepMs))))
                .forEach(group -> byBucket.put(group.get("_id", Number.class).longValue(), Stats.of(group)));

        // every bucket of the range, empty ones included, so charts always get the same number of points
        List<TimeBucketResponse> rows = new ArrayList<>(range.buckets());
        for (int i = 0; i < range.buckets(); i++) {
            long start = window.from().toEpochMilli() + i * stepMs;
            rows.add(TimeBucketResponse.from(Instant.ofEpochMilli(start), byBucket.getOrDefault(start, Stats.EMPTY)));
        }
        return rows;
    }

    private Stats total(Window window, Long organizationId) {
        List<Document> groups = run(List.of(match(window.from(), window.to(), organizationId), statsGroup(null)));
        return groups.isEmpty() ? Stats.EMPTY : Stats.of(groups.getFirst());
    }

    // Requests per route in 12 equal slices of the window, keyed "slug/routeId"
    private Map<String, long[]> routeTrends(Window window, Long organizationId) {
        long fromMs = window.from().toEpochMilli();
        long sliceMs = (window.to().toEpochMilli() - fromMs) / ROUTE_TREND_SLICES;
        Document slice = new Document("$toInt", new Document("$floor", new Document("$divide", List.of(
                new Document("$subtract", List.of(new Document("$toLong", "$minute"), fromMs)), sliceMs))));
        Document group = new Document("$group", new Document("_id", new Document("organizationSlug", "$organizationSlug")
                .append("routeId", "$routeId").append("slice", slice))
                .append("requests", TrafficAggregations.sum("requests")));

        Map<String, long[]> trends = new HashMap<>();
        for (Document row : run(List.of(match(window.from(), window.to(), organizationId), HAS_ROUTE, group))) {
            Document key = row.get("_id", Document.class);
            int index = Math.min(key.get("slice", Number.class).intValue(), ROUTE_TREND_SLICES - 1);
            trends.computeIfAbsent(routeKey(key), k -> new long[ROUTE_TREND_SLICES])[index] +=
                    row.get("requests", Number.class).longValue();
        }
        return trends;
    }

    private static String routeKey(Document key) {
        return key.getString("organizationSlug") + "/" + key.getString("routeId");
    }

    // Relative change as a fraction; null when there is no previous value to compare with
    private static Double change(double current, double previous) {
        return previous == 0 ? null : (current - previous) / previous;
    }

    private List<Document> run(List<Document> pipeline) {
        return mongoTemplate.getCollection(mongoTemplate.getCollectionName(TrafficMinuteDocument.class))
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }
}
