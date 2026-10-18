package in.code.gatekeeper.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * api-management-ui shows gateway health on the platform overview by calling /_gateway/health and /_gateway/status
 * straight from the browser (api-management itself never calls the gateway, #38). Only those read-only endpoints
 * get CORS, only for GET and the configured origins; tenant traffic is not affected.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${gateway.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/_gateway/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET");
    }
}
