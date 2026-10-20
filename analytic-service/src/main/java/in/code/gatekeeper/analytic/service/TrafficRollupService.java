package in.code.gatekeeper.analytic.service;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.document.TrafficMinuteDocument;
import com.mongodb.client.model.ReplaceOptions;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Keeps traffic_minutes up to date (decision #73), so the dashboards never compute over raw events. Every few
 * seconds it finds the minutes that got newly stored events (late ones from a Kafka backlog included) and recomputes
 * those minutes completely from traffic_events. Recomputing instead of incrementing means a rerun, a restart or a
 * redelivered event can never count twice. The first run ever backfills every stored event.
 */
@Slf4j
@Service
public class TrafficRollupService {

    private static final String STATE_COLLECTION = "rollup_state";
    private static final String STATE_ID = "traffic_minutes";
    private static final long MINUTE_MS = 60_000;
    // an event saved just before a run may become visible just after it: re-read this much
    private static final Duration OVERLAP = Duration.ofSeconds(2);
    private static final Duration BACKFILL_CHUNK = Duration.ofHours(6);

    private final MongoTemplate mongoTemplate;
    private final String eventsCollection;
    private final String minutesCollection;

    public TrafficRollupService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.eventsCollection = mongoTemplate.getCollectionName(TrafficEventDocument.class);
        this.minutesCollection = mongoTemplate.getCollectionName(TrafficMinuteDocument.class);
    }

    @Scheduled(initialDelayString = "${analytics.rollup.interval-ms}",
            fixedDelayString = "${analytics.rollup.interval-ms}")
    public void rollUp() {
        try {
            Instant until = Instant.now();
            Instant processedUntil = processedUntil();
            if (processedUntil == null) {
                backfill(until);
            } else {
                recompute(touchedMinutes(processedUntil.minus(OVERLAP), until));
            }
            saveProcessedUntil(until);
        } catch (RuntimeException e) {
            // the next run starts from the same point, nothing is lost
            log.warn("Traffic rollup failed: {}", e.getMessage());
        }
    }

    private List<Long> touchedMinutes(Instant after, Instant until) {
        List<Long> minutes = new ArrayList<>();
        mongoTemplate.getCollection(eventsCollection).aggregate(TrafficAggregations.touchedMinutes(after, until))
                .forEach(row -> minutes.add(row.get("_id", Number.class).longValue()));
        return minutes;
    }

    // Consecutive minutes are recomputed in one pass
    private void recompute(List<Long> minutes) {
        List<Long> sorted = minutes.stream().sorted().toList();
        int i = 0;
        while (i < sorted.size()) {
            long start = sorted.get(i);
            long end = start;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == end + MINUTE_MS) {
                end = sorted.get(++i);
            }
            merge(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end + MINUTE_MS));
            i++;
        }
    }

    private void backfill(Instant until) {
        TrafficEventDocument oldest = mongoTemplate.findOne(new Query().with(Sort.by("timestamp")).limit(1),
                TrafficEventDocument.class);
        if (oldest == null) {
            return;
        }
        long startMs = oldest.getTimestamp().toEpochMilli() / MINUTE_MS * MINUTE_MS;
        log.info("Backfilling traffic_minutes from {}", Instant.ofEpochMilli(startMs));
        for (Instant from = Instant.ofEpochMilli(startMs); from.isBefore(until); from = from.plus(BACKFILL_CHUNK)) {
            merge(from, from.plus(BACKFILL_CHUNK));
        }
        log.info("Backfill of traffic_minutes done");
    }

    private void merge(Instant from, Instant to) {
        mongoTemplate.getCollection(eventsCollection)
                .aggregate(TrafficAggregations.rollup(from, to, minutesCollection))
                .toCollection();
    }

    private Instant processedUntil() {
        Document state = mongoTemplate.getCollection(STATE_COLLECTION).find(new Document("_id", STATE_ID)).first();
        return state == null ? null : state.getDate("processedUntil").toInstant();
    }

    private void saveProcessedUntil(Instant until) {
        mongoTemplate.getCollection(STATE_COLLECTION).replaceOne(new Document("_id", STATE_ID),
                new Document("_id", STATE_ID).append("processedUntil", Date.from(until)),
                new ReplaceOptions().upsert(true));
    }
}
