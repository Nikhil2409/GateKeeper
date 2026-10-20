package in.code.gatekeeper.analytic.service;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.dto.responseDto.RequestLogPageResponse;
import in.code.gatekeeper.analytic.enums.TimeRange;
import in.code.gatekeeper.analytic.enums.TimeRange.Window;
import in.code.gatekeeper.analytic.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * The request log: individual events, newest first, filtered by range, organization, status class (2xx, 4xx, ...)
 * and a free-text search over path, route, client IP, exact status and exact request or correlation id.
 */
@Service
@RequiredArgsConstructor
public class RequestLogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final Pattern STATUS_CLASS = Pattern.compile("[1-5]xx");

    private final MongoTemplate mongoTemplate;

    public RequestLogPageResponse requests(TimeRange range, Long organizationId, String statusClass, String search,
                                           int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Window window = range.window(Instant.now());
        List<Criteria> filters = new ArrayList<>();
        filters.add(where("timestamp").gte(window.from()).lt(window.to()));
        if (organizationId != null) {
            filters.add(where("organizationId").is(organizationId));
        }
        if (statusClass != null && !statusClass.isBlank()) {
            if (!STATUS_CLASS.matcher(statusClass).matches()) {
                throw ApiException.badRequest("status must look like 2xx, 4xx or 5xx");
            }
            int from = (statusClass.charAt(0) - '0') * 100;
            filters.add(where("status").gte(from).lt(from + 100));
        }
        if (search != null && !search.isBlank()) {
            filters.add(searchFilter(search.trim()));
        }

        Query query = new Query(new Criteria().andOperator(filters));
        long total = mongoTemplate.count(query, TrafficEventDocument.class);
        query.with(Sort.by(Sort.Direction.DESC, "timestamp")).skip((long) page * size).limit(size);
        List<RequestLogPageResponse.Entry> items = mongoTemplate.find(query, TrafficEventDocument.class).stream()
                .map(RequestLogPageResponse.Entry::from)
                .toList();
        return new RequestLogPageResponse(total, page, size, items);
    }

    // The text is matched literally (quoted), never as a regular expression the caller controls
    private static Criteria searchFilter(String search) {
        if (search.length() > MAX_SEARCH_LENGTH) {
            throw ApiException.badRequest("search is longer than " + MAX_SEARCH_LENGTH + " characters");
        }
        String literal = Pattern.quote(search);
        List<Criteria> anyOf = new ArrayList<>(List.of(where("path").regex(literal, "i"),
                where("routeId").regex(literal, "i"), where("clientIp").regex(literal, "i"),
                where("_id").is(search), where("correlationId").is(search)));
        if (search.matches("\\d{3}")) {
            anyOf.add(where("status").is(Integer.parseInt(search)));
        }
        return new Criteria().orOperator(anyOf);
    }
}
