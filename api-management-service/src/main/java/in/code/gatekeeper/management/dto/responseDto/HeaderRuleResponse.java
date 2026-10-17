package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.RouteHeaderRule;
import in.code.gatekeeper.management.enums.HeaderAction;
import in.code.gatekeeper.management.enums.HeaderDirection;

public record HeaderRuleResponse(HeaderDirection direction, HeaderAction action, String headerName,
                                 String headerValue) {

    public static HeaderRuleResponse from(RouteHeaderRule rule) {
        return new HeaderRuleResponse(rule.getDirection(), rule.getAction(), rule.getHeaderName(),
                rule.getHeaderValue());
    }
}
