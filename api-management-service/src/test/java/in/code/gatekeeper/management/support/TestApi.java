package in.code.gatekeeper.management.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The service's HTTP API as a test sees it, plus the setup steps an admin would take. The database is shared by all
 * tests of a run, so every organization gets a unique slug.
 */
public class TestApi {

    public static final String PLATFORM_ADMIN = "platform-admin";
    public static final String PLATFORM_PASSWORD = "admin-pass-123";
    public static final String TENANT_PASSWORD = "tenant-pass-123";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public TestApi(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public record Response(int status, JsonNode body) {
    }

    // An organization with its tenant admin, as the platform admin creates them
    public record Tenant(long organizationId, String slug, long adminId, String adminUsername, String token) {
    }

    public Response call(String method, String path, String token, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body().isBlank() ? null : JSON.readTree(response.body());
            return new Response(response.statusCode(), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public Response get(String path, String token) {
        return call("GET", path, token, null);
    }

    public Response login(String username, String password) {
        return call("POST", "/auth/login", null, Map.of("username", username, "password", password));
    }

    public String token(String username, String password) {
        Response response = login(username, password);
        if (response.status() != 200) {
            throw new IllegalStateException("login of " + username + " failed: " + response);
        }
        return response.body().get("accessToken").asString();
    }

    public String platformToken() {
        return token(PLATFORM_ADMIN, PLATFORM_PASSWORD);
    }

    public Tenant createTenant(String prefix) {
        String platform = platformToken();
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Response organization = call("POST", "/api/platform/organizations", platform,
                Map.of("name", slug, "slug", slug));
        expect(201, organization);
        long organizationId = organization.body().get("id").asLong();
        String username = slug + "-admin";
        Response admin = call("POST", "/api/platform/tenant-admins", platform,
                Map.of("username", username, "password", TENANT_PASSWORD, "organizationId", organizationId));
        expect(201, admin);
        return new Tenant(organizationId, slug, admin.body().get("id").asLong(), username,
                token(username, TENANT_PASSWORD));
    }

    public static Map<String, Object> route(String routeId, String path, String targetBaseUrl) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("routeId", routeId);
        route.put("name", routeId);
        route.put("pathPattern", path);
        route.put("targetBaseUrl", targetBaseUrl);
        route.put("targetPathPrefix", "");
        route.put("allowedMethods", List.of("GET"));
        route.put("requestsPerMinute", 60);
        route.put("responseTimeoutMs", 2000);
        route.put("idempotencyEnabled", false);
        route.put("headerRules", List.of());
        route.put("allowedHeaders", List.of());
        return route;
    }

    public Response createRoute(Tenant tenant, Map<String, Object> route) {
        return expect(201, call("POST", "/api/tenant/routes", tenant.token(), route));
    }

    public static Response expect(int status, Response response) {
        if (response.status() != status) {
            throw new AssertionError("expected " + status + " but got " + response);
        }
        return response;
    }
}
