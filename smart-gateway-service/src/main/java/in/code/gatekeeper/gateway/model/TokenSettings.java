package in.code.gatekeeper.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How to verify an organization's user tokens: signed by a key from jwksUri, issued by issuer, meant for audience.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenSettings(String issuer, String jwksUri, String audience) {
}
