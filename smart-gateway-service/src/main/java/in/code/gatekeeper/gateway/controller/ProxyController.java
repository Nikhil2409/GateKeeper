package in.code.gatekeeper.gateway.controller;

import in.code.gatekeeper.gateway.service.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Every tenant request: /{organizationSlug}/{route path}, any method. The gateway's own /_gateway endpoints are more
 * specific mappings and win. The pipeline itself is in ProxyService.
 */
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;

    @RequestMapping("/{slug}/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response);
    }
}
