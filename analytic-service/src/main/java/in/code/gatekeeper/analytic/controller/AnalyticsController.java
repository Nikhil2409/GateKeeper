package in.code.gatekeeper.analytic.controller;

import in.code.gatekeeper.analytic.dto.responseDto.OrganizationStatsResponse;
import in.code.gatekeeper.analytic.dto.responseDto.RequestLogPageResponse;
import in.code.gatekeeper.analytic.dto.responseDto.RouteStatsResponse;
import in.code.gatekeeper.analytic.dto.responseDto.SummaryResponse;
import in.code.gatekeeper.analytic.dto.responseDto.TimeBucketResponse;
import in.code.gatekeeper.analytic.enums.TimeRange;
import in.code.gatekeeper.analytic.security.AnalyticsUser;
import in.code.gatekeeper.analytic.service.RequestLogService;
import in.code.gatekeeper.analytic.service.TrafficStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only analytics for analytics-ui. range: 1h, 24h (default) or 7d. org: an organization id; platform admins may
 * pass it to narrow down (no org = all), tenant admins are always scoped to their own whatever they pass.
 * Shapes match analytics-ui/src/api/mocks.js, so the UI switches over by turning mock data off.
 */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final TrafficStatsService trafficStatsService;
    private final RequestLogService requestLogService;

    @GetMapping("/summary")
    public ResponseEntity<SummaryResponse> summary(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(required = false) String range,
                                                   @RequestParam(required = false) Long org) {
        return ResponseEntity.ok(trafficStatsService.summary(TimeRange.from(range), AnalyticsUser.from(jwt).scope(org)));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<List<TimeBucketResponse>> timeseries(@AuthenticationPrincipal Jwt jwt,
                                                               @RequestParam(required = false) String range,
                                                               @RequestParam(required = false) Long org) {
        return ResponseEntity.ok(trafficStatsService.timeseries(TimeRange.from(range),
                AnalyticsUser.from(jwt).scope(org)));
    }

    @GetMapping("/routes")
    public ResponseEntity<List<RouteStatsResponse>> routes(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestParam(required = false) String range,
                                                           @RequestParam(required = false) Long org) {
        return ResponseEntity.ok(trafficStatsService.routes(TimeRange.from(range), AnalyticsUser.from(jwt).scope(org)));
    }

    // Platform admins only (SecurityConfig)
    @GetMapping("/organizations")
    public ResponseEntity<List<OrganizationStatsResponse>> organizations(@RequestParam(required = false) String range) {
        return ResponseEntity.ok(trafficStatsService.organizations(TimeRange.from(range)));
    }

    @GetMapping("/requests")
    public ResponseEntity<RequestLogPageResponse> requests(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestParam(required = false) String range,
                                                           @RequestParam(required = false) Long org,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(requestLogService.requests(TimeRange.from(range), AnalyticsUser.from(jwt).scope(org),
                status, q, page, size));
    }
}
