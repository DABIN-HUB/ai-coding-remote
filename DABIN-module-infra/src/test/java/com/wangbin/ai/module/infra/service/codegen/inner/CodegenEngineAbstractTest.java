package com.wangbin.ai.module.infra.service.codegen.inner;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.wangbin.ai.framework.common.util.json.JsonUtils;
import com.wangbin.ai.framework.test.core.ut.BaseMockitoUnitTest;
import com.wangbin.ai.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import com.wangbin.ai.module.infra.dal.dataobject.codegen.CodegenTableDO;
import com.wangbin.ai.module.infra.enums.codegen.CodegenVOTypeEnum;
import com.wangbin.ai.module.infra.framework.codegen.config.CodegenProperties;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared unit-test base for code generation snapshot tests.
 */
public abstract class CodegenEngineAbstractTest extends BaseMockitoUnitTest {

    private String resourcesPath = "";

    @InjectMocks
    protected CodegenEngine codegenEngine;

    @Spy
    protected CodegenProperties codegenProperties = new CodegenProperties()
            .setBasePackage("com.wangbin.ai")
            .setVoType(CodegenVOTypeEnum.VO.getType())
            .setDeleteBatchEnable(true)
            .setUnitTestEnable(true)
            .setImportEnable(false);

    @BeforeEach
    public void setUp() {
        codegenEngine.setJakartaEnable(true);
        codegenEngine.initGlobalBindingMap();
        String absolutePath = FileUtil.getAbsolutePath("application-unit-test.yaml");
        resourcesPath = absolutePath.split("/target")[0] + "/src/test/resources/codegen/";
    }

    protected static CodegenTableDO getTable(String name) {
        String content = ResourceUtil.readUtf8Str("codegen/table/" + name + ".json");
        return JsonUtils.parseObject(content, "table", CodegenTableDO.class);
    }

    protected static List<CodegenColumnDO> getColumnList(String name) {
        String content = ResourceUtil.readUtf8Str("codegen/table/" + name + ".json");
        List<CodegenColumnDO> list = JsonUtils.parseArray(content, "columns", CodegenColumnDO.class);
        list.forEach(column -> {
            if (column.getNullable() == null) {
                column.setNullable(false);
            }
            if (column.getCreateOperation() == null) {
                column.setCreateOperation(false);
            }
            if (column.getUpdateOperation() == null) {
                column.setUpdateOperation(false);
            }
            if (column.getListOperation() == null) {
                column.setListOperation(false);
            }
            if (column.getListOperationResult() == null) {
                column.setListOperationResult(false);
            }
        });
        return list;
    }

    private static final boolean REGENERATE = Boolean.parseBoolean(System.getProperty("codegen.regenerate", "false"));

    @SuppressWarnings("rawtypes")
    protected void assertResult(Map<String, String> result, String path) {
        if (REGENERATE) {
            writeResult(result, resourcesPath + path);
            return;
        }
        String assertContent = ResourceUtil.readUtf8Str("codegen/" + path + "/assert.json");
        List<HashMap> asserts = JsonUtils.parseArray(assertContent, HashMap.class);
        Set<String> expectedFiles = asserts.stream()
                .map(m -> normalizeFilePath((String) m.get("filePath")))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> normalizedResult = new HashMap<>();
        result.forEach((filePath, content) -> normalizedResult.put(normalizeFilePath(filePath), content));
        assertEquals(expectedFiles, normalizedResult.keySet(), "generated file set mismatch");
        asserts.forEach(assertMap -> {
            String contentPath = (String) assertMap.get("contentPath");
            String filePath = normalizeFilePath((String) assertMap.get("filePath"));
            String expected = normalizeLineEndings(ResourceUtil.readUtf8Str("codegen/" + path + "/" + contentPath));
            String actual = normalizedResult.get(filePath);
            assertEquals(expected, normalizeLineEndings(actual), filePath + " mismatch");
        });
    }

    private static String normalizeFilePath(String filePath) {
        if (filePath == null) {
            return null;
        }
        return filePath.replace('\\', '/')
                .replace("src/main/java/com.wangbin.ai/", "src/main/java/com/wangbin/ai/")
                .replace("src/test/java/com.wangbin.ai/", "src/test/java/com/wangbin/ai/");
    }

    private static String normalizeLineEndings(String content) {
        if (content == null) {
            return null;
        }
        content = content.replace("\r\n", "\n").replace('\r', '\n');
        return content.replaceAll("\\n+\\z", "");
    }

    protected void writeFile(Map<String, String> result, String path) {
        String[] paths = result.keySet().toArray(new String[0]);
        ByteArrayInputStream[] ins = result.values().stream().map(IoUtil::toUtf8Stream).toArray(ByteArrayInputStream[]::new);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ZipUtil.zip(outputStream, paths, ins);
        FileUtil.writeBytes(outputStream.toByteArray(), path);
    }

    protected void writeResult(Map<String, String> result, String basePath) {
        List<Map<String, String>> asserts = new ArrayList<>();
        Set<String> usedContentPaths = new LinkedHashSet<>();
        result.forEach((filePath, fileContent) -> {
            String lastFilePath = StrUtil.subAfter(filePath, '/', true);
            String ext = StrUtil.subAfter(lastFilePath, '.', true);
            String name = StrUtil.subBefore(lastFilePath, '.', true);
            String parentPath = StrUtil.subBefore(filePath, '/' + lastFilePath, true);
            String parentDir = StrUtil.subAfter(parentPath, '/', true);
            String contentPath = "index".equals(name) && ("form".equals(parentDir) || "detail".equals(parentDir))
                    ? ext + '/' + parentDir + '/' + name : ext + '/' + name;
            if (usedContentPaths.contains(contentPath)) {
                String grandParentDir = StrUtil.subAfter(StrUtil.subBefore(parentPath, '/' + parentDir, true), '/', true);
                contentPath = ext + '/' + grandParentDir + '/' + parentDir + '/' + name;
                for (int i = 2; usedContentPaths.contains(contentPath); i++) {
                    contentPath = ext + '/' + grandParentDir + '/' + parentDir + '/' + name + '-' + i;
                }
            }
            usedContentPaths.add(contentPath);
            asserts.add(MapUtil.<String, String>builder().put("filePath", filePath)
                    .put("contentPath", contentPath).build());
            FileUtil.writeUtf8String(fileContent, basePath + "/" + contentPath);
        });
        FileUtil.writeUtf8String(JsonUtils.toJsonPrettyString(asserts), basePath + "/assert.json");
    }
}
