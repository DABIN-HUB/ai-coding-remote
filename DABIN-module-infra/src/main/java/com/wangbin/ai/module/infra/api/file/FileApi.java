package com.wangbin.ai.module.infra.api.file;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.Valid;

import com.wangbin.ai.module.infra.api.file.dto.FileRespDTO;
import com.wangbin.ai.module.infra.api.file.dto.FileUploadReqDTO;

import java.io.OutputStream;

/**
 * 文件 API 接口
 *
 * @author 芋道源码
 */
public interface FileApi {

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @return 文件路径
     */
    default String createFile(byte[] content) {
        return createFile(content, null, null, null);
    }

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @return 文件路径
     */
    default String createFile(byte[] content, String name) {
        return createFile(content, name, null, null);
    }

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件路径
     */
    String createFile(@NotEmpty(message = "文件内容不能为空") byte[] content,
                      String name, String directory, String type);

    /**
     * 流式保存文件，并返回已有统一文件记录。
     *
     * @param reqDTO 上传请求
     * @return 文件记录
     */
    FileRespDTO createFile(@Valid FileUploadReqDTO reqDTO);

    /**
     * 获得已有统一文件记录。
     *
     * @param id 文件记录编号
     * @return 文件记录
     */
    FileRespDTO getFile(Long id);

    /**
     * 将文件内容写入输出流。
     *
     * @param configId 文件配置编号
     * @param path 文件存储相对路径
     * @param outputStream 输出流
     */
    void writeFileContent(Long configId, String path, OutputStream outputStream) throws Exception;

    /**
     * 删除已有统一文件记录和底层对象。
     *
     * @param id 文件记录编号
     */
    void deleteFile(Long id) throws Exception;

    /**
     * 生成文件预签名地址，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    String presignGetUrl(@NotEmpty(message = "URL 不能为空") String url,
                         Integer expirationSeconds);

}
