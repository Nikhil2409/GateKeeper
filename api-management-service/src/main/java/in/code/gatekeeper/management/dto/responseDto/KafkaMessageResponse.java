package in.code.gatekeeper.management.dto.responseDto;

import java.time.Instant;

// One message as stored in a topic; value is the raw JSON, cut after a few KB (truncated tells when)
public record KafkaMessageResponse(int partition, long offset, Instant timestamp, String key, String value,
                                   boolean truncated) {
}
