package com.wangbin.ai.agent.daemon.cloud.controlplane;

import com.wangbin.ai.agent.daemon.state.DeviceCredentialState;
import com.wangbin.ai.agent.daemon.artifact.ArtifactPrepareUploadRequest;
import com.wangbin.ai.agent.daemon.artifact.ArtifactPrepareUploadResponse;
import com.wangbin.ai.agent.daemon.artifact.ArtifactReportFailureRequest;

import java.nio.file.Path;

public interface ControlPlaneClient {

    PairDeviceResponse pair(String controlPlaneUrl, PairDeviceRequest request);

    RelayTicketResponse createDeviceRelayTicket(DeviceCredentialState credential);

    default RegisterProjectResponse registerProject(DeviceCredentialState credential, RegisterProjectRequest request) {
        throw new UnsupportedOperationException("project register is not implemented");
    }

    default void reportRuntime(DeviceCredentialState credential, RuntimeReportRequest request) {
        throw new UnsupportedOperationException("runtime report is not implemented");
    }

    default ArtifactPrepareUploadResponse prepareArtifactUpload(DeviceCredentialState credential,
                                                                ArtifactPrepareUploadRequest request) {
        throw new UnsupportedOperationException("artifact upload prepare is not implemented");
    }

    default void uploadArtifact(DeviceCredentialState credential, String artifactId, String uploadTicket,
                                Path file, String contentType, long contentLength) {
        throw new UnsupportedOperationException("artifact upload is not implemented");
    }

    default void reportArtifactFailure(DeviceCredentialState credential, ArtifactReportFailureRequest request) {
        throw new UnsupportedOperationException("artifact failure report is not implemented");
    }
}
