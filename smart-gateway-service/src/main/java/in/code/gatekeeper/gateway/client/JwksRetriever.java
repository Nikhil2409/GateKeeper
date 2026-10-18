package in.code.gatekeeper.gateway.client;

import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import in.code.gatekeeper.gateway.check.UpstreamTargetGuard;
import in.code.gatekeeper.gateway.exception.GatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Downloads an organization's JWKS. The URL is tenant-controlled, so it gets the same treatment as an upstream:
 * the host is checked by UpstreamTargetGuard on every fetch, redirects are never followed, and time and size are
 * capped, so a JWKS URL can't be used to reach internal services or tie up the gateway.
 */
@Slf4j
@Component
public class JwksRetriever implements ResourceRetriever {

    private static final int MAX_BYTES = 64 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final UpstreamTargetGuard targetGuard;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public JwksRetriever(UpstreamTargetGuard targetGuard) {
        this.targetGuard = targetGuard;
    }

    @Override
    public Resource retrieveResource(URL url) throws IOException {
        URI uri;
        try {
            uri = url.toURI();
            targetGuard.check(uri);
        } catch (URISyntaxException | GatewayException e) {
            throw new IOException("JWKS URL not allowed: " + url.getHost(), e);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("JWKS fetch interrupted", e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("JWKS fetch from " + url.getHost() + " returned " + response.statusCode());
            }
            byte[] body = in.readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                throw new IOException("JWKS from " + url.getHost() + " is larger than " + MAX_BYTES + " bytes");
            }
            return new Resource(new String(body, StandardCharsets.UTF_8),
                    response.headers().firstValue("content-type").orElse(null));
        }
    }
}
