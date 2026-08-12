package com.wangbin.ai.agent.daemon.adapter.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.ai.agent.contract.enums.PermissionDecision;
import org.springframework.stereotype.Component;

@Component
public class CodexPermissionDecisionMapper {

    private static final String WIRE_ACCEPT = "accept";
    private static final String WIRE_ACCEPT_FOR_SESSION = "acceptForSession";
    private static final String WIRE_DECLINE = "decline";
    private static final String WIRE_CANCEL = "cancel";

    private final ObjectMapper objectMapper;

    public CodexPermissionDecisionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toWireValue(PermissionDecision decision) {
        return switch (decision) {
            case APPROVED -> WIRE_ACCEPT;
            case APPROVED_FOR_SESSION -> WIRE_ACCEPT_FOR_SESSION;
            case REJECTED -> WIRE_DECLINE;
            case CANCELLED -> WIRE_CANCEL;
        };
    }

    public PermissionDecision fromWireValue(String value) {
        if (WIRE_ACCEPT.equals(value)) {
            return PermissionDecision.APPROVED;
        }
        if (WIRE_ACCEPT_FOR_SESSION.equals(value)) {
            return PermissionDecision.APPROVED_FOR_SESSION;
        }
        if (WIRE_DECLINE.equals(value)) {
            return PermissionDecision.REJECTED;
        }
        if (WIRE_CANCEL.equals(value)) {
            return PermissionDecision.CANCELLED;
        }
        return null;
    }

    public ObjectNode responseResult(PermissionDecision decision) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("decision", toWireValue(decision));
        return result;
    }
}
