package com.wangbin.ai.module.agent.controller.admin.artifact;

import com.wangbin.ai.agent.contract.protocol.AgentHttpHeaders;
import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.common.util.http.HttpUtils;
import com.wangbin.ai.framework.web.core.util.WebFrameworkUtils;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPageReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactPrepareUploadRespVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactReportFailureReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRequestFileReqVO;
import com.wangbin.ai.module.agent.controller.admin.artifact.vo.AgentArtifactRespVO;
import com.wangbin.ai.module.agent.service.artifact.AgentArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import static com.wangbin.ai.framework.common.pojo.CommonResult.success;
import static com.wangbin.ai.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - Agent Artifact")
@RestController
@RequestMapping("/agent/artifact")
@Validated
@RequiredArgsConstructor
public class AgentArtifactController {

    private static final String DOWNLOAD_FILENAME = "artifact.bin";

    private final AgentArtifactService artifactService;

    @PostMapping("/requestFile")
    @Operation(summary = "请求获取 ChangeSet 中的文件 Artifact")
    @PreAuthorize("@ss.hasPermission('agent:artifact:create')")
    public CommonResult<AgentArtifactRespVO> requestFile(@Valid @RequestBody AgentArtifactRequestFileReqVO reqVO) {
        return success(artifactService.requestFile(reqVO, getLoginUserId()));
    }

    @GetMapping("/page")
    @Operation(summary = "获取当前用户 Artifact 分页")
    @PreAuthorize("@ss.hasPermission('agent:artifact:query')")
    public CommonResult<PageResult<AgentArtifactRespVO>> getArtifactPage(@Valid AgentArtifactPageReqVO reqVO) {
        return success(artifactService.getArtifactPage(reqVO, getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取当前用户 Artifact 详情")
    @Parameter(name = "artifactId", description = "Artifact 业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:artifact:query')")
    public CommonResult<AgentArtifactRespVO> getArtifact(@RequestParam("artifactId") String artifactId) {
        return success(artifactService.getArtifact(artifactId, getLoginUserId()));
    }

    @GetMapping("/download")
    @Operation(summary = "鉴权下载当前用户 Artifact")
    @Parameter(name = "artifactId", description = "Artifact 业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:artifact:download')")
    public void download(@RequestParam("artifactId") String artifactId, HttpServletResponse response)
            throws Exception {
        AgentArtifactRespVO artifact = artifactService.getArtifact(artifactId, getLoginUserId());
        response.setContentType(artifact.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : artifact.getContentType());
        if (artifact.getFileSize() != null) {
            response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(artifact.getFileSize()));
        }
        if (artifact.getSha256() != null) {
            response.setHeader(HttpHeaders.ETAG, "\"" + artifact.getSha256() + "\"");
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(artifact.getFileName()));
        artifactService.download(artifactId, getLoginUserId(), response.getOutputStream());
    }

    @PostMapping("/prepareUpload")
    @Operation(summary = "Daemon 使用设备凭证准备 Artifact 上传")
    @PermitAll
    public CommonResult<AgentArtifactPrepareUploadRespVO> prepareUpload(
            @Parameter(description = "设备凭证公开编号", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_ID) String credentialId,
            @Parameter(description = "设备凭证明文密钥", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_SECRET) String credentialSecret,
            @Parameter(description = "租户编号 Header，复用 RuoYi tenant-id", required = true)
            @RequestHeader(WebFrameworkUtils.HEADER_TENANT_ID) Long tenantId,
            @Valid @RequestBody AgentArtifactPrepareUploadReqVO reqVO) {
        return success(artifactService.prepareUpload(tenantId, credentialId, credentialSecret, reqVO));
    }

    @PostMapping("/upload")
    @Operation(summary = "Daemon 使用一次性 Ticket 流式上传 Artifact")
    @PermitAll
    public CommonResult<AgentArtifactRespVO> upload(
            @Parameter(description = "一次性 Artifact 上传 Ticket", required = true)
            @RequestHeader(AgentHttpHeaders.ARTIFACT_UPLOAD_TICKET) String uploadTicket,
            HttpServletRequest request) throws Exception {
        return success(artifactService.upload(uploadTicket, request.getInputStream(), request.getContentLengthLong()));
    }

    @PostMapping("/reportFailure")
    @Operation(summary = "Daemon 使用设备凭证上报 Artifact 上传失败")
    @PermitAll
    public CommonResult<Boolean> reportFailure(
            @Parameter(description = "设备凭证公开编号", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_ID) String credentialId,
            @Parameter(description = "设备凭证明文密钥", required = true)
            @RequestHeader(AgentHttpHeaders.CREDENTIAL_SECRET) String credentialSecret,
            @Parameter(description = "租户编号 Header，复用 RuoYi tenant-id", required = true)
            @RequestHeader(WebFrameworkUtils.HEADER_TENANT_ID) Long tenantId,
            @Valid @RequestBody AgentArtifactReportFailureReqVO reqVO) {
        artifactService.reportFailure(tenantId, credentialId, credentialSecret, reqVO);
        return success(true);
    }

    private String contentDisposition(String fileName) throws IOException {
        String safeName = safeFileName(fileName);
        return "attachment;filename=\"" + fallbackFileName(safeName) + "\";filename*=UTF-8''"
                + HttpUtils.encodeUrlPathSegment(safeName);
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return DOWNLOAD_FILENAME;
        }
        String normalized = fileName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.isBlank() ? DOWNLOAD_FILENAME : name;
    }

    private String fallbackFileName(String fileName) {
        StringBuilder result = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char ch = fileName.charAt(i);
            if (ch == '"' || ch == '\\') {
                result.append('\\').append(ch);
            } else if (ch >= 0x20 && ch <= 0x7E) {
                result.append(ch);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }
}
