package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.ai.agent.contract.protocol.AgentHttpHeaders;
import com.wangbin.ai.agent.daemon.artifact.ArtifactPrepareUploadRequest;
import com.wangbin.ai.agent.daemon.artifact.ArtifactPrepareUploadResponse;
import com.wangbin.ai.agent.daemon.artifact.ArtifactReportFailureRequest;
import com.wangbin.ai.agent.daemon.exception.AgentConnectionException;
import com.wangbin.ai.agent.daemon.exception.AgentProtocolException;
import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class HttpControlPlaneClient implements ControlPlaneClient {

    private static final String PAIR_DEVICE_PATH = "/agent/device/pair";
    private static final String CREATE_RELAY_TICKET_PATH = "/agent/device/createRelayTicket";
    private static final String REGISTER_PROJECT_PATH = "/agent/project/register";
    private static final String REPORT_RUNTIME_PATH = "/agent/runtime/report";
    private static final String PREPARE_ARTIFACT_UPLOAD_PATH = "/agent/artifact/prepareUpload";
    private static final String UPLOAD_ARTIFACT_PATH = "/agent/artifact/upload";
    private static final String REPORT_ARTIFACT_FAILURE_PATH = "/agent/artifact/reportFailure";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpControlPlaneClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request) {
        return post(controlPlaneUrl + PAIR_DEVICE_PATH, request, PairDeviceResponse.class);
    }

    @Override
    public RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(credential.getControlPlaneUrl() + CREATE_RELAY_TICKET_PATH))
                    .header(AgentHttpHeaders.TENANT_ID, String.valueOf(credential.getTenantId()))
                    .header(AgentHttpHeaders.CREDENTIAL_ID, credential.getCredentialId())
                    .header(AgentHttpHeaders.CREDENTIAL_SECRET, credential.getCredentialSecret())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return send(request, RelayTicketResponse.class);
        } catch (Exception ex) {
            throw new AgentConnectionException("failed to create relay ticket", ex);
        }
    }

    @Override
    public RegisterProjectResponse registerProject(DeviceCredentialState credential, RegisterProjectRequest request) {
        return postWithCredential(credential, REGISTER_PROJECT_PATH, request, RegisterProjectResponse.class);
    }

    @Override
    public void reportRuntime(DeviceCredentialState credential, RuntimeReportRequest request) {
        postWithCredential(credential, REPORT_RUNTIME_PATH, request, Object.class);
    }

    @Override
    public ArtifactPrepareUploadResponse prepareArtifactUpload(DeviceCredentialState credential,
                                                               ArtifactPrepareUploadRequest request) {
        return postWithCredential(credential, PREPARE_ARTIFACT_UPLOAD_PATH, request,
                ArtifactPrepareUploadResponse.class);
    }

    @Override
    public void uploadArtifact(DeviceCredentialState credential, String artifactId, String uploadTicket,
                               Path file, String contentType, long contentLength) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(credential.getControlPlaneUrl() + UPLOAD_ARTIFACT_PATH))
                    .header(HEADER_CONTENT_TYPE, contentType == null || contentType.isBlank()
                            ? CONTENT_TYPE_OCTET_STREAM : contentType)
                    .header(AgentHttpHeaders.ARTIFACT_UPLOAD_TICKET, uploadTicket)
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
                        try {
                            return Files.newInputStream(file);
                        } catch (Exception ex) {
                            throw new AgentConnectionException("failed to open artifact file stream", ex);
                        }
                    }))
                    .build();
            send(request, Object.class);
        } catch (Exception ex) {
            throw new AgentConnectionException("failed to upload artifact", ex);
        }
    }

    @Override
    public void reportArtifactFailure(DeviceCredentialState credential, ArtifactReportFailureRequest request) {
        postWithCredential(credential, REPORT_ARTIFACT_FAILURE_PATH, request, Object.class);
    }

    private <T> T post(String url, Object body, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return send(request, responseType);
        } catch (Exception ex) {
            throw new AgentConnectionException("failed to call control plane", ex);
        }
    }

    private <T> T postWithCredential(DeviceCredentialState credential, String path, Object body, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(credential.getControlPlaneUrl() + path))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .header(AgentHttpHeaders.TENANT_ID, String.valueOf(credential.getTenantId()))
                    .header(AgentHttpHeaders.CREDENTIAL_ID, credential.getCredentialId())
                    .header(AgentHttpHeaders.CREDENTIAL_SECRET, credential.getCredentialSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return send(request, responseType);
        } catch (Exception ex) {
            throw new AgentConnectionException("failed to call credential control plane API", ex);
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
