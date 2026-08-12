package com.wangbin.ai.module.infra.api.file;

import com.wangbin.ai.module.infra.service.file.FileService;
import com.wangbin.ai.framework.common.util.object.BeanUtils;
import com.wangbin.ai.module.infra.api.file.dto.FileClientCapabilityRespDTO;
import com.wangbin.ai.module.infra.api.file.dto.FileRespDTO;
import com.wangbin.ai.module.infra.api.file.dto.FileUploadReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.OutputStream;

/**
 * 文件 API 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class FileApiImpl implements FileApi {

    @Resource
    private FileService fileService;

    @Override
    public String createFile(byte[] content, String name, String directory, String type) {
        return fileService.createFile(content, name, directory, type);
    }

    @Override
    public FileRespDTO createFile(FileUploadReqDTO reqDTO) {
        return BeanUtils.toBean(fileService.createFile(reqDTO), FileRespDTO.class);
    }

    @Override
    public FileRespDTO getFile(Long id) {
        return BeanUtils.toBean(fileService.getFile(id), FileRespDTO.class);
    }

    @Override
    public void writeFileContent(Long configId, String path, OutputStream outputStream) throws Exception {
        fileService.writeFileContent(configId, path, outputStream);
    }

    @Override
    public FileClientCapabilityRespDTO getFileClientCapability(Long configId) {
        return fileService.getFileClientCapability(configId);
    }

    @Override
    public void deleteFile(Long id) throws Exception {
        fileService.deleteFile(id);
    }

    @Override
    public void deleteFileIfExists(Long id) throws Exception {
        fileService.deleteFileIfExists(id);
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        return fileService.presignGetUrl(url, expirationSeconds);
    }

}
