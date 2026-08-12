package com.wangbin.ai.module.infra.framework.file.core.client;

import cn.hutool.core.io.IoUtil;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件客户端
 *
 * @author 芋道源码
 */
public interface FileClient {

    /**
     * 获得客户端编号
     *
     * @return 客户端编号
     */
    Long getId();

    /**
     * 上传文件
     *
     * @param content 文件流
     * @param path    相对路径
     * @return 完整路径，即 HTTP 访问地址
     * @throws Exception 上传文件时，抛出 Exception 异常
     */
    String upload(byte[] content, String path, String type) throws Exception;

    /**
     * 流式上传文件。默认实现保持旧 Provider 兼容；支持流式的 Provider 应覆盖该方法。
     *
     * @param inputStream 文件流
     * @param contentLength 文件大小
     * @param path 相对路径
     * @param type MIME 类型
     * @return 完整路径，即 HTTP 访问地址
     */
    default String upload(InputStream inputStream, long contentLength, String path, String type) throws Exception {
        return upload(IoUtil.readBytes(inputStream), path, type);
    }

    /**
     * Whether this client can upload from an {@link InputStream} without buffering the full file in heap memory.
     * Default is conservative because the compatibility implementation reads the whole stream into {@code byte[]}.
     */
    default boolean supportsStreamingUpload() {
        return false;
    }

    /**
     * 删除文件
     *
     * @param path 相对路径
     * @throws Exception 删除文件时，抛出 Exception 异常
     */
    void delete(String path) throws Exception;

    /**
     * 获得文件的内容
     *
     * @param path 相对路径
     * @return 文件的内容
     */
    byte[] getContent(String path) throws Exception;

    /**
     * 将文件内容写入输出流。默认实现保持旧 Provider 兼容；支持流式的 Provider 应覆盖该方法。
     *
     * @param path 相对路径
     * @param outputStream 输出流
     */
    default void writeContent(String path, OutputStream outputStream) throws Exception {
        byte[] content = getContent(path);
        if (content != null) {
            IoUtil.write(outputStream, false, content);
        }
    }

    /**
     * Whether this client can write content to an {@link OutputStream} without loading the full object into heap memory.
     * Default is conservative because the compatibility implementation delegates to {@link #getContent(String)}.
     */
    default boolean supportsStreamingDownload() {
        return false;
    }

    // ========== 文件签名，目前仅 S3 支持 ==========

    /**
     * 获得文件预签名地址，用于上传
     *
     * @param path 相对路径
     * @return 文件预签名地址
     */
    default String presignPutUrl(String path) {
        throw new UnsupportedOperationException("不支持的操作");
    }

    /**
     * 生成文件预签名地址，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    default String presignGetUrl(String url, Integer expirationSeconds) {
        throw new UnsupportedOperationException("不支持的操作");
    }

}
