package in.code.gatekeeper.management.controller;

import in.code.gatekeeper.management.dto.responseDto.HealthResponse;
import in.code.gatekeeper.management.service.HealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/health")
public class HealthController {
    private HealthService healthService;

    public HealthController(HealthService healthservice) {
        this.healthService = healthservice;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        String dbstatus = healthService.getDBStatus();
        return ResponseEntity.ok(new HealthResponse("UP",dbstatus, LocalDateTime.now()));
    }
}