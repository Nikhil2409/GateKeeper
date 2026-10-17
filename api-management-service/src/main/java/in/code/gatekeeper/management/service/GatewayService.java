package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.responseDto.GatewayRouteResponse;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GatewayService {

    private final ApiRouteRepository apiRouteRepository;

    // Every active route of every active, non-suspended organization
    @Transactional(readOnly = true)
    public List<GatewayRouteResponse> getRouteSnapshot() {
        return apiRouteRepository.findAllForGatewaySnapshot().stream()
                .map(GatewayRouteResponse::from)
                .toList();
    }
}
