package in.code.gatekeeper.analytic.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One tenant request as reported by the gateway. The id is the gateway's request id, so a Kafka redelivery of the
 * same event overwrites instead of counting twice. Documents expire after 15 days: enough for the 7-day range and
 * the 7 days before it (the deltas).
 *
 * Field names are used as strings in the aggregation pipelines (TrafficAggregations), keep them in sync.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Document("traffic_events")
@CompoundIndex(name = "org_time", def = "{'organizationId': 1, 'timestamp': -1}")
public class TrafficEventDocument {

    @Id
    private String id;

    @Indexed(name = "correlation_id")
    private String correlationId;

    @Indexed(name = "expire_after_15_days", expireAfter = "15d")
    private Instant timestamp;

    private String gatewayInstanceId;
    private Long organizationId;
    private String organizationSlug;
    private String routeId;
    private String method;
    private String path;
    private int status;
    private long latencyMs;
    private String clientIp;
    private String rejectReason;
    private String idempotencyOutcome;
    private String idempotencyKey;
}
