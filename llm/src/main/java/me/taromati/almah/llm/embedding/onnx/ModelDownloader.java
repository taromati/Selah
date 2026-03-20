package me.taromati.almah.llm.embedding.onnx;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.llm.embedding.EmbeddingProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/**
 * Hugging Face Hub에서 모델 파일을 다운로드하고 캐시한다.
 */
@Slf4j
public class ModelDownloader {

    private static final String HF_BASE_URL = "https://huggingface.co";
    private final EmbeddingProperties.OnnxConfig config;

    public ModelDownloader(EmbeddingProperties.OnnxConfig config) {
        this.config = config;
    }

    /**
     * 모델 디렉토리가 유효한지 확인하고, 없으면 다운로드.
     * @return 모델 파일들이 있는 디렉토리 경로
     */
    public Path ensureModel() {
        Path cacheDir = resolveCacheDir();
        Path modelDir = cacheDir.resolve(sanitizeDirName(config.getModelRepo()));
        Path versionFile = modelDir.resolve("version.txt");

        // 버전 확인 (S06)
        if (Files.exists(modelDir) && Files.exists(versionFile)) {
            String cached = readVersion(versionFile);
            if (config.getModelVersion().equals(cached)) {
                Path modelPath = modelDir.resolve(config.getModelFile());
                Path tokenizerPath = modelDir.resolve(config.getTokenizerFile());
                if (Files.exists(modelPath) && Files.exists(tokenizerPath)) {
                    log.debug("[ModelDownloader] 캐시된 모델 사용: {}", modelDir);
                    return modelDir;
                }
            }
            // 버전 불일치 또는 파일 누락 → 재다운로드
            log.info("[ModelDownloader] 모델 버전 변경 감지 또는 파일 누락: {} → {}",
                    cached, config.getModelVersion());
            deleteDirectory(modelDir);
        } else if (Files.exists(modelDir)) {
            // version.txt 누락 → 재다운로드
            deleteDirectory(modelDir);
        }

        // 다운로드
        try {
            Files.createDirectories(modelDir);
            downloadFile(config.getModelRepo(), config.getModelFile(), modelDir);
            downloadFile(config.getTokenizerRepo(), config.getTokenizerFile(), modelDir);
            writeVersion(versionFile, config.getModelVersion());
        } catch (Exception e) {
            // 부분 다운로드 정리
            deleteDirectory(modelDir);
            throw new RuntimeException("모델 다운로드 실패. 수동 설치: " + HF_BASE_URL + "/" + config.getModelRepo(), e);
        }

        return modelDir;
    }

    private void downloadFile(String repo, String filename, Path targetDir) {
        String url = HF_BASE_URL + "/" + repo + "/resolve/main/" + filename;
        Path targetPath = targetDir.resolve(filename);
        Path tmpPath = targetDir.resolve(filename + ".tmp");

        // 서브디렉토리가 포함된 경우 (예: onnx/model.onnx) 부모 디렉토리 생성
        try { Files.createDirectories(targetPath.getParent()); } catch (IOException ignored) {}

        log.info("[ModelDownloader] 다운로드: {} → {}", url, targetPath);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + " - " + url);
            }

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (contentLength > 0) {
                log.info("[ModelDownloader] 파일 크기: {}MB", contentLength / (1024 * 1024));
            }

            // 임시 파일에 다운로드 후 rename (부분 다운로드 방지)
            try (InputStream is = response.body()) {
                Files.copy(is, tmpPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 완료 후 rename
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[ModelDownloader] 다운로드 완료: {}", targetPath);

        } catch (IOException | InterruptedException e) {
            // 임시 파일 정리
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("파일 다운로드 실패: " + url, e);
        }
    }

    Path resolveCacheDir() {
        if (config.getModelCacheDir() != null && !config.getModelCacheDir().isBlank()) {
            return Path.of(config.getModelCacheDir());
        }
        // 기본값: ~/.selah/models/
        return Path.of(System.getProperty("user.home"), ".selah", "models");
    }

    static String sanitizeDirName(String repoName) {
        return repoName.replace('/', '_').replace('\\', '_');
    }

    private String readVersion(Path versionFile) {
        try {
            return Files.readString(versionFile).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void writeVersion(Path versionFile, String version) {
        try {
            Files.writeString(versionFile, version);
        } catch (IOException e) {
            log.warn("[ModelDownloader] version.txt 쓰기 실패: {}", e.getMessage());
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[ModelDownloader] 디렉토리 삭제 실패: {}", e.getMessage());
        }
    }
}
