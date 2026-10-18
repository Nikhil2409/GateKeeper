package in.code.gatekeeper.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * direction: REQUEST (to the upstream) or RESPONSE (to the client). action: ADD, SET or REMOVE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeaderRule(String direction, String action, String headerName, String headerValue) {
}
