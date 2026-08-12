package com.wangbin.ai.module.infra.framework.file.core.local;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.IdUtil;
import com.wangbin.ai.framework.common.exception.ServiceException;
import com.wangbin.ai.module.infra.framework.file.core.client.local.LocalFileClient;
import com.wangbin.ai.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.wangbin.ai.framework.test.core.util.RandomUtils.randomString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LocalFileClientTest {

    @Test
    public void testUpload_success() throws IOException {
        Path tempDir = Files.createTempDirectory("local-file-client-");
        LocalFileClient client = createClient(tempDir);
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        String path = "avatar/test.txt";

        try {
            String url = client.upload(content, path, "text/plain");

            assertEquals("http://127.0.0.1:48080/admin-api/infra/file/0/get/avatar/test.txt", url);
            assertArrayEquals(content, FileUtil.readBytes(tempDir.resolve(path).toFile()));
            assertArrayEquals(content, client.getContent(path));

            client.delete(path);
            assertFalse(FileUtil.exist(tempDir.resolve(path).toFile()));
        } finally {
            FileUtil.del(tempDir.toFile());
        }
    }

    @Test
    public void testUpload_encodeUrlPath() throws IOException {
        Path tempDir = Files.createTempDirectory("local-file-client-");
        LocalFileClient client = createClient(tempDir);
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        String path = "avatar/中文 100%+文件.txt";

        try {
            String url = client.upload(content, path, "text/plain");

            assertEquals("http://127.0.0.1:48080/admin-api/infra/file/0/get/avatar/%E4%B8%AD%E6%96%87%20100%25+%E6%96%87%E4%BB%B6.txt", url);
            assertArrayEquals(content, FileUtil.readBytes(tempDir.resolve(path).toFile()));
        } finally {
            FileUtil.del(tempDir.toFile());
        }
    }

    @Test
    public void testUpload_pathInvalid() throws IOException {
        Path tempDir = Files.createTempDirectory("local-file-client-");
        LocalFileClient client = createClient(tempDir);
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);

        try {
            assertThrows(ServiceException.class, () -> client.upload(content, "../test.txt", "text/plain"));
            assertFalse(FileUtil.exist(tempDir.getParent().resolve("test.txt").toFile()));
        } finally {
            FileUtil.del(tempDir.toFile());
        }
    }

    @Test
    @Disabled
    public void test() {
        LocalFileClientConfig config = new LocalFileClientConfig();
        config.setDomain("http://127.0.0.1:48080");
        config.setBasePath("/Users/yunai/file_test");
        LocalFileClient client = new LocalFileClient(0L, config);
        client.init();
        String path = IdUtil.fastSimpleUUID() + ".jpg";
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        String fullPath = client.upload(content, path, "image/jpeg");
        System.out.println("visit url: " + fullPath);
        client.delete(path);
    }

    @Test
    @Disabled
    public void testGetContent_notFound() {
        LocalFileClientConfig config = new LocalFileClientConfig();
        config.setDomain("http://127.0.0.1:48080");
        config.setBasePath("/Users/yunai/file_test");
        LocalFileClient client = new LocalFileClient(0L, config);
        client.init();
        byte[] content = client.getContent(randomString());
        System.out.println(content);
    }

    private LocalFileClient createClient(Path tempDir) {
        LocalFileClientConfig config = new LocalFileClientConfig();
        config.setDomain("http://127.0.0.1:48080");
        config.setBasePath(tempDir.toAbsolutePath().toString());
        LocalFileClient client = new LocalFileClient(0L, config);
        client.init();
        return client;
    }
}
