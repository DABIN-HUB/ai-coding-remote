package com.wangbin.ai.module.infra.framework.file.core.client;

import com.wangbin.ai.module.infra.framework.file.core.client.db.DBFileClient;
import com.wangbin.ai.module.infra.framework.file.core.client.db.DBFileClientConfig;
import com.wangbin.ai.module.infra.framework.file.core.client.ftp.FtpFileClient;
import com.wangbin.ai.module.infra.framework.file.core.client.ftp.FtpFileClientConfig;
import com.wangbin.ai.module.infra.framework.file.core.client.local.LocalFileClient;
import com.wangbin.ai.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.wangbin.ai.module.infra.framework.file.core.client.s3.S3FileClient;
import com.wangbin.ai.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.wangbin.ai.module.infra.framework.file.core.client.sftp.SftpFileClient;
import com.wangbin.ai.module.infra.framework.file.core.client.sftp.SftpFileClientConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileClientCapabilitiesTest {

    @Test
    void s3LocalFtpSftpDeclareStreamingCapabilities() {
        assertStreaming(new S3FileClient(1L, new S3FileClientConfig()));
        assertStreaming(new LocalFileClient(2L, new LocalFileClientConfig()));
        assertStreaming(new FtpFileClient(3L, new FtpFileClientConfig()));
        assertStreaming(new SftpFileClient(4L, new SftpFileClientConfig()));
    }

    @Test
    void dbFileClientKeepsNonStreamingDefaults() {
        DBFileClient client = new DBFileClient(5L, new DBFileClientConfig());

        assertThat(client.supportsStreamingUpload()).isFalse();
        assertThat(client.supportsStreamingDownload()).isFalse();
    }

    private void assertStreaming(FileClient client) {
        assertThat(client.supportsStreamingUpload()).isTrue();
        assertThat(client.supportsStreamingDownload()).isTrue();
    }
}
