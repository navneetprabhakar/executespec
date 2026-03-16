package com.navneet.executespec.deployment.analyzer;

import com.navneet.executespec.deployment.analyzer.InfrastructureManifest.StorageRequirement;
import com.navneet.executespec.deployment.analyzer.InfrastructureManifest.StorageRequirement.StorageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Detects object storage dependencies (GCS, S3) and local filesystem write patterns.
 */
@Component
@Slf4j
public class StorageDetector {

    // GCS
    private static final Pattern GCS_MAVEN = Pattern.compile(
            "google-cloud-storage|spring-cloud-gcp-storage");
    private static final Pattern GCS_NPM = Pattern.compile(
            "\"@google-cloud/storage\"");
    private static final Pattern GCS_PYTHON = Pattern.compile(
            "google-cloud-storage");

    // S3 / generic object storage
    private static final Pattern S3_MAVEN = Pattern.compile(
            "aws-java-sdk-s3|software\\.amazon\\.awssdk.*s3|minio");
    private static final Pattern S3_NPM = Pattern.compile(
            "\"(@aws-sdk/client-s3|aws-sdk|minio)\"");
    private static final Pattern S3_PYTHON = Pattern.compile(
            "boto3|minio");

    // File upload patterns (indicates local filesystem writes)
    private static final Pattern MULTIPART_JAVA = Pattern.compile(
            "MultipartFile|@RequestParam.*multipart|spring\\.servlet\\.multipart");
    private static final Pattern MULTIPART_NODE = Pattern.compile(
            "\"(multer|formidable|busboy)\"");
    private static final Pattern MULTIPART_PYTHON = Pattern.compile(
            "UploadFile|FileUpload|django\\.core\\.files");

    public StorageRequirement detect(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;

        StorageRequirement result = detectInDir(workspace);
        if (result != null) return result;

        for (String subDir : new String[]{"backend", "server", "api", "app"}) {
            Path sub = workspace.resolve(subDir);
            if (Files.isDirectory(sub)) {
                result = detectInDir(sub);
                if (result != null) {
                    log.info("Storage detected in subdirectory {}: {}", subDir, result.type());
                    return result;
                }
            }
        }
        return null;
    }

    private StorageRequirement detectInDir(Path dir) {
        // Maven / Gradle
        for (String file : new String[]{"pom.xml", "build.gradle", "build.gradle.kts"}) {
            String content = readSafe(dir.resolve(file));
            if (content != null) {
                if (GCS_MAVEN.matcher(content).find())
                    return new StorageRequirement(StorageType.GCS, "GCS dependency in " + file);
                if (S3_MAVEN.matcher(content).find())
                    return new StorageRequirement(StorageType.S3, "S3/Minio dependency in " + file);
            }
        }

        // Node
        String pkg = readSafe(dir.resolve("package.json"));
        if (pkg != null) {
            if (GCS_NPM.matcher(pkg).find())
                return new StorageRequirement(StorageType.GCS, "GCS dependency in package.json");
            if (S3_NPM.matcher(pkg).find())
                return new StorageRequirement(StorageType.S3, "S3/Minio dependency in package.json");
            if (MULTIPART_NODE.matcher(pkg).find())
                return new StorageRequirement(StorageType.LOCAL_FILESYSTEM, "File upload library in package.json");
        }

        // Python
        for (String file : new String[]{"requirements.txt", "pyproject.toml", "Pipfile"}) {
            String content = readSafe(dir.resolve(file));
            if (content != null) {
                if (GCS_PYTHON.matcher(content).find())
                    return new StorageRequirement(StorageType.GCS, "GCS dependency in " + file);
                if (S3_PYTHON.matcher(content).find())
                    return new StorageRequirement(StorageType.S3, "S3/Minio dependency in " + file);
            }
        }

        // Spring multipart config
        for (String cfg : new String[]{
                "src/main/resources/application.yml",
                "src/main/resources/application.yaml",
                "src/main/resources/application.properties"}) {
            String content = readSafe(dir.resolve(cfg));
            if (content != null && MULTIPART_JAVA.matcher(content).find()) {
                return new StorageRequirement(StorageType.LOCAL_FILESYSTEM, "Multipart config in " + cfg);
            }
        }

        return null;
    }

    private String readSafe(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            return content.length() > 50_000 ? content.substring(0, 50_000) : content;
        } catch (IOException e) {
            return null;
        }
    }
}
