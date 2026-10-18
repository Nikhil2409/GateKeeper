package in.code.gatekeeper.analytic.service;

import in.code.gatekeeper.analytic.document.OrganizationDocument;
import in.code.gatekeeper.analytic.document.OrganizationDocument.RouteLimit;
import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.dto.responseDto.IdempotencyStatsResponse;
import in.code.gatekeeper.analytic.dto.responseDto.IdempotencyStatsResponse.RecentOutcome;
import in.code.gatekeeper.analytic.dto.responseDto.RateLimitUsageResponse;
import in.code.gatekeeper.analytic.repository.OrganizationRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static in.code.gatekeeper.analytic.service.TrafficAggregations.match;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * The traffic-policy views: how close each limited route is to its rate limit, and what idempotency did.
 * Everything is derived from the gateway's traffic events (this service never reads the gateway's Redis), so the
 * numbers trail the gateway by the event delivery delay.
 */
@Service
public class PolicyStatsService {

    private static final int RECENT_OUTCOMES = 20;
    private static final List<String> NOTABLE_OUTCOMES = List.of("REPLAYED", "IN_PROGRESS_CONFLICT", "BODY_MISMATCH");

    private final MongoTemplate mongoTemplate;
    private final OrganizationRepository organizationRepository;
    private final Duration idempotencyTtl;

    public PolicyStatsService(MongoTemplate mongoTemplate, OrganizationRepository organizationRepository,
                              @Value("${analytics.idempotency.ttl}") Duration idempotencyTtl) {
        this.mongoTemplate = mongoTemplate;
        this.organizationRepository = organizationRepository;
        this.idempotencyTtl = idempotencyTtl;
    }

    // Every route of an active organization that has a limit, closest to its limit first
    public List<RateLimitUsageResponse> rateLimits(Long organizationId) {
        Instant now = Instant.now();
        Instant windowStart = Instant.ofEpochSecond(now.getEpochSecond() / 60 * 60);   // the gateway's 1-minute window
        Map<String, Long> used = countByRoute(match(windowStart, windowStart.plusSeconds(60), organizationId), null);
        Map<String, Long> rejected = countByRoute(match(now.minus(Duration.ofHours(1)), now.plusSeconds(1),
                organizationId), new Document("$match", new Document("status", 429)));

        List<RateLimitUsageResponse> rows = new ArrayList<>();
        for (OrganizationDocument organization : organizations(organizationId)) {
            for (RouteLimit route : organization.getRoutes()) {
                if (route.requestsPerMinute() == null) {
                    continue;
                }
                String key = organization.getId() + "/" + route.routeId();
                rows.add(new RateLimitUsageResponse(organization.getSlug(), route.routeId(), route.requestsPerMinute(),
                        used.getOrDefault(key, 0L), rejected.getOrDefault(key, 0L)));
            }
        }
        rows.sort(Comparator.comparingDouble((RateLimitUsageResponse row) ->
                (double) row.usedThisWindow() / row.limitPerMinute()).reversed());
        return rows;
    }

    public IdempotencyStatsResponse idempotency(Long organizationId) {
        Instant now = Instant.now();
        Instant hourAgo = now.minus(Duration.ofHours(1));

        Map<String, Long> lastHour = new HashMap<>();
        run(List.of(match(hourAgo, now.plusSeconds(1), organizationId),
                new Document("$match", new Document("idempotencyOutcome", new Document("$in", NOTABLE_OUTCOMES))),
                new Document("$group", new Document("_id", "$idempotencyOutcome").append("count", new Document("$sum", 1)))))
                .forEach(row -> lastHour.put(row.getString("_id"), row.get("count", Number.class).longValue()));

        // approximate: every execution stored a response, minus the few released after a 5xx
        long storedKeys = mongoTemplate.count(new Query(scoped(organizationId,
                where("timestamp").gte(now.minus(idempotencyTtl)).and("idempotencyOutcome").is("EXECUTED"))),
                TrafficEventDocument.class);

        Query recentQuery = new Query(scoped(organizationId,
                where("timestamp").gte(hourAgo).and("idempotencyOutcome").in(NOTABLE_OUTCOMES)))
                .with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .limit(RECENT_OUTCOMES);
        List<RecentOutcome> recent = mongoTemplate.find(recentQuery, TrafficEventDocument.class).stream()
                .map(event -> new RecentOutcome(event.getIdempotencyKey(), event.getOrganizationSlug(),
                        event.getRouteId(), event.getIdempotencyOutcome(), event.getTimestamp()))
                .toList();

        return new IdempotencyStatsResponse(idempotencyTtl.toSeconds(), storedKeys,
                lastHour.getOrDefault("REPLAYED", 0L), lastHour.getOrDefault("IN_PROGRESS_CONFLICT", 0L),
                lastHour.getOrDefault("BODY_MISMATCH", 0L), recent);
    }

    private List<OrganizationDocument> organizations(Long organizationId) {
        List<OrganizationDocument> organizations = organizationId == null
                ? organizationRepository.findAll()
                : organizationRepository.findById(organizationId).map(List::of).orElse(List.of());
        return organizations.stream().filter(OrganizationDocument::isActive).toList();
    }

    // Events per route, keyed "organizationId/routeId"; extraStage narrows the events further (may be null)
    private Map<String, Long> countByRoute(Document matchStage, Document extraStage) {
        List<Document> pipeline = new ArrayList<>(List.of(matchStage));
        if (extraStage != null) {
            pipeline.add(extraStage);
        }
        pipeline.add(new Document("$group", new Document("_id", new Document("organizationId", "$organizationId")
                .append("routeId", "$routeId")).append("count", new Document("$sum", 1))));

        Map<String, Long> counts = new HashMap<>();
        for (Document row : run(pipeline)) {
            Document key = row.get("_id", Document.class);
            Number id = key.get("organizationId", Number.class);
            if (id != null && key.getString("routeId") != null) {
                counts.put(id.longValue() + "/" + key.getString("routeId"), row.get("count", Number.class).longValue());
            }
        }
        return counts;
    }

    private static Criteria scoped(Long organizationId, Criteria criteria) {
        return organizationId == null ? criteria : criteria.and("organizationId").is(organizationId);
    }

    private List<Document> run(List<Document> pipeline) {
        return mongoTemplate.getCollection(mongoTemplate.getCollectionName(TrafficEventDocument.class))
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }
}
