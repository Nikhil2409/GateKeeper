package in.code.gatekeeper.gateway.client;

import in.code.gatekeeper.gateway.exception.GatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends the request to the upstream with one shared JDK HttpClient. Redirects are never followed: a 3xx goes back to
 * the caller as it is, otherwise an upstream could redirect the gateway to a host it must never call.
 * The route's responseTimeoutMs bounds the wait for the response headers; the body is then streamed.
 */
@Slf4j
@Component
public class UpstreamClient {

    private final HttpClient httpClient;

    public UpstreamClient(@Value("${gateway.upstream.connect-timeout-ms}") long connectTimeoutMs) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpResponse<InputStream> send(String method, URI target, Map<String, List<String>> headers, byte[] body,
                                          int responseTimeoutMs) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(responseTimeoutMs))
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));

        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            log.warn("Connect timeout to {}", target.getHost());
            throw GatewayException.badGateway("Upstream service is not reachable");
        } catch (HttpTimeoutException e) {
            log.warn("No response from {} within {} ms", target.getHost(), responseTimeoutMs);
            throw GatewayException.gatewayTimeout();
        } catch (IOException e) {
            log.warn("Upstream call to {} failed: {}", target.getHost(), e.toString());
            throw GatewayException.badGateway("Upstream service is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayException.badGateway("Upstream call was interrupted");
        }
    }
}
