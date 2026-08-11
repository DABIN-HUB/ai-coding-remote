package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class HttpControlPlaneClient implements ControlPlaneClient {

    private static final String HEADER_TENANT_ID = "tenant-id";
    private static final String HEADER_CREDENTIAL_ID = "X-Agent-Credential-Id";
    private static final String HEADER_CREDENTIAL_SECRET = "X-Agent-Credential-Secret";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpControlPlaneClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request) {
        return post(controlPlaneUrl + "/agent/device/pair", request, PairDeviceResponse.class);
    }

    @Override
    public RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(credential.getControlPlaneUrl() + "/agent/device/createRelayTicket"))
                    .header(HEADER_TENANT_ID, String.valueOf(credential.getTenantId()))
                    .header(HEADER_CREDENTIAL_ID, credential.getCredentialId())
                    .header(HEADER_CREDENTIAL_SECRET, credential.getCredentialSecret())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return send(request, RelayTicketResponse.class);
        } catch (Exception ex) {
            throw new AgentConnectionException("failed to create relay ticket", ex);
        }
    }

    private <T> T post(String url, Object body, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return send(request, responseType);
        } catch (Exception ex) {
            throw new AgentConnectionException("failed to call control plane", ex);
        }
    }

    private <T> T send(HttpRequest request, Class<T> responseType) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AgentProtocolException("control plane HTTP status " + response.statusCode());
        }
        JavaType type = objectMapper.getTypeFactory().constructParametricType(ControlPlaneResult.class,
                responseType);
        ControlPlaneResult<T> result = objectMapper.readValue(response.body(), type);
        if (result.code() == null || result.code() != 0) {
            throw new AgentProtocolException("control plane returned error code " + result.code());
        }
        return result.data();
    }
}
