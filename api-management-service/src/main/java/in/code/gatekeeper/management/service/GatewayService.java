package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.responseDto.GatewayOrganizationResponse;
import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GatewayService {

    private final OrganizationRepository organizationRepository;
    private final ApiRouteRepository apiRouteRepository;

    // EVERY organization, including suspended and deleted ones (with no routes), so a resync can tell
    // "suspended" from "unchanged". All reads run in one transaction, so they see the same data.
    @Transactional(readOnly = true)
    public List<GatewayOrganizationResponse> getRouteSnapshot() {
        Map<Long, List<ApiRoute>> routesByOrganization = apiRouteRepository.findAllForGatewaySnapshot().stream()
                .collect(Collectors.groupingBy(route -> route.getOrganization().getId()));

        return organizationRepository.findAll(Sort.by("name")).stream()
                .map(organization -> GatewayOrganizationResponse.from(organization,
                        routesByOrganization.getOrDefault(organization.getId(), List.of())))
                .toList();
    }
}
