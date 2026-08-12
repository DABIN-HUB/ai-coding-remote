package com.wangbin.ai.module.agent.controller.admin.change;

import com.wangbin.ai.framework.common.pojo.CommonResult;
import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.module.agent.controller.admin.change.vo.*;
import com.wangbin.ai.module.agent.service.change.AgentChangeSetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.wangbin.ai.framework.common.pojo.CommonResult.success;
import static com.wangbin.ai.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - Agent ChangeSet")
@RestController
@RequestMapping("/agent/changeSet")
@Validated
@RequiredArgsConstructor
public class AgentChangeSetController {

    private final AgentChangeSetService changeSetService;

    @GetMapping("/page")
    @Operation(summary = "获取当前用户 ChangeSet 分页")
    @PreAuthorize("@ss.hasPermission('agent:changeSet:query')")
    public CommonResult<PageResult<AgentChangeSetRespVO>> getChangeSetPage(
            @Valid AgentChangeSetPageReqVO reqVO) {
        return success(changeSetService.getChangeSetPage(reqVO, getLoginUserId()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取当前用户 ChangeSet 详情")
    @Parameter(name = "changeSetId", description = "变更集业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:changeSet:query')")
    public CommonResult<AgentChangeSetDetailRespVO> getChangeSet(
            @RequestParam("changeSetId") String changeSetId) {
        return success(changeSetService.getChangeSet(changeSetId, getLoginUserId()));
    }

    @GetMapping("/getByCommand")
    @Operation(summary = "按 Command 获取当前用户 ChangeSet")
    @Parameter(name = "commandId", description = "Command 业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:changeSet:query')")
    public CommonResult<AgentChangeSetDetailRespVO> getChangeSetByCommand(
            @RequestParam("commandId") String commandId) {
        return success(changeSetService.getChangeSetByCommand(commandId, getLoginUserId()));
    }

    @GetMapping("/fileList")
    @Operation(summary = "获取 ChangeSet 文件列表")
    @Parameter(name = "changeSetId", description = "变更集业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:changeSet:query')")
    public CommonResult<List<AgentFileChangeRespVO>> getFileList(
            @RequestParam("changeSetId") String changeSetId) {
        return success(changeSetService.getFileList(changeSetId, getLoginUserId()));
    }

    @GetMapping("/getFileChange")
    @Operation(summary = "获取单文件变更详情")
    @Parameter(name = "fileChangeId", description = "文件变更业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:changeSet:query')")
    public CommonResult<AgentFileChangeRespVO> getFileChange(
            @RequestParam("fileChangeId") String fileChangeId) {
        return success(changeSetService.getFileChange(fileChangeId, getLoginUserId()));
    }

    @GetMapping("/getDiff")
    @Operation(summary = "获取 ChangeSet Unified Diff")
    @Parameter(name = "changeSetId", description = "变更集业务编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:changeSet:query')")
    public CommonResult<AgentChangeSetDiffRespVO> getDiff(@RequestParam("changeSetId") String changeSetId) {
        return success(changeSetService.getDiff(changeSetId, getLoginUserId()));
    }
}
