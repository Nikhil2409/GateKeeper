package in.code.gatekeeper.analytic.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * One organization + route + minute of traffic, computed from traffic_events by TrafficRollupService (decision #73).
 * The dashboards read these instead of raw events, so a query covers one document per route-minute, not one per
 * request. Written only by the rollup's $merge and read only by aggregations (TrafficAggregations), so this class
 * just declares the collection and its indexes. Fields: requests, s2xx, s4xx, s5xx, rateLimited, replays,
 * conflicts, mismatches, executed and the latency histogram h0, h1 ... (LatencyHistogram).
 */
@Document("traffic_minutes")
@CompoundIndex(name = "org_minute", def = "{'organizationId': 1, 'minute': 1}")
public class TrafficMinuteDocument {

    // {organizationId, routeId, minute}
    @Id
    private Map<String, Object> id;

    @Indexed(name = "expire_after_15_days", expireAfter = "15d")
    private Instant minute;

    private Long organizationId;
}
