package me.taromati.almah.llm.embedding;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "llm.embedding")
public class EmbeddingProperties {
    // 기존
    private String baseUrl;
    private String apiKey;
    private String model = "intfloat/multilingual-e5-small";
    private int dimensions = 384;

    // 신규
    private String provider;  // "onnx" | "http" — null이면 @PostConstruct에서 자동 확정
    private OnnxConfig onnx = new OnnxConfig();

    /**
     * provider 미설정 시 baseUrl 유무로 확정.
     * EmbeddingAutoConfiguration의 @Bean 메서드에서 getProvider()로 읽어 분기한다.
     */
    @PostConstruct
    void resolveProvider() {
        if (provider != null) return;
        provider = (baseUrl != null && !baseUrl.isBlank()) ? "http" : "onnx";
    }

    @Getter
    @Setter
    public static class OnnxConfig {
        private String modelCacheDir;   // 기본값: ~/.selah/models/
        private String modelRepo = "deepfile/multilingual-e5-small-onnx-qint8";
        private String modelFile;       // null = 플랫폼 자동 감지

        /**
         * modelFile이 미설정이면 OS/Arch에 맞는 ONNX 파일을 자동 선택한다.
         */
        public String getModelFile() {
            if (modelFile != null) return modelFile;
            String arch = System.getProperty("os.arch", "");
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "onnx/model_qint8_arm64.onnx";
            }
            return "onnx/model_quint8_avx2.onnx";
        }
        private String tokenizerRepo = "intfloat/multilingual-e5-small";
        private String tokenizerFile = "tokenizer.json";
        private String modelVersion = "qint8-v1";
        private int maxLength = 512;
        private int batchSize = 16;
        private int intraOpThreads = 0;  // 0 = ONNX Runtime 자동 감지
    }
}
