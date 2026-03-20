package me.taromati.almah.llm.embedding.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import me.taromati.almah.llm.embedding.EmbeddingProperties;
import me.taromati.almah.llm.embedding.EmbeddingService;
import ai.onnxruntime.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.*;

/**
 * ONNX Runtime + DJL HuggingFace Tokenizer를 사용한 로컬 임베딩 구현체.
 * EmbeddingAutoConfiguration의 @Bean에서만 등록 — @Service 미사용.
 * <p>
 * 초기화 실패 시 initialized=false, embed()→null, embedBatch()→빈 리스트 (graceful degradation).
 */
@Slf4j
public class OnnxEmbeddingService implements EmbeddingService {

    private final EmbeddingProperties.OnnxConfig config;
    private final int configuredDimensions;

    // 초기화 성공 시에만 non-null
    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private int outputDimension;

    /** 초기화 성공 여부. false면 embed()는 null을 반환한다. */
    private boolean initialized = false;

    public OnnxEmbeddingService(EmbeddingProperties properties) {
        this.config = properties.getOnnx();
        this.configuredDimensions = properties.getDimensions();

        try {
            // 1. 모델 다운로드 (필요 시)
            ModelDownloader downloader = new ModelDownloader(config);
            Path modelDir = downloader.ensureModel();

            // 2. ONNX Runtime 초기화
            // OrtEnvironment는 JVM 전역 싱글턴 — close()에서 해제하지 않는다
            this.environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            if (config.getIntraOpThreads() > 0) {
                opts.setIntraOpNumThreads(config.getIntraOpThreads());
            }
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = environment.createSession(
                    modelDir.resolve(config.getModelFile()).toString(), opts);

            // 3. 토크나이저 초기화
            this.tokenizer = HuggingFaceTokenizer.newInstance(
                    modelDir.resolve(config.getTokenizerFile()),
                    Map.of("padding", "true",
                            "truncation", "true",
                            "maxLength", String.valueOf(config.getMaxLength())));

            // 4. 출력 차원 확인
            this.outputDimension = resolveOutputDimension();

            // 5. 차원 호환성 검증 (S07)
            if (configuredDimensions != outputDimension) {
                log.warn("[OnnxEmbeddingService] 임베딩 차원 불일치: 설정={}, 모델={}. "
                                + "기존 벡터 데이터와 호환되지 않을 수 있습니다.",
                        configuredDimensions, outputDimension);
            }

            // 6. Warm-up (첫 추론 그래프 캐싱)
            warmUp();

            this.initialized = true;
        } catch (Exception e) {
            log.error("[OnnxEmbeddingService] 초기화 실패 — degraded 모드로 동작합니다. "
                    + "임베딩 없이 BM25 키워드 검색만 사용됩니다. 원인: {}", e.getMessage());
        }
    }

    @Override
    public float[] embed(String text) {
        if (!initialized) return null;

        try {
            List<float[]> result = embedBatchInternal(List.of(text));
            return result.isEmpty() ? null : result.getFirst();
        } catch (Exception e) {
            log.error("[OnnxEmbeddingService] 임베딩 실패: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (!initialized) return List.of();

        List<float[]> results = new ArrayList<>();
        int batchSize = config.getBatchSize();

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            try {
                results.addAll(embedBatchInternal(batch));
            } catch (Exception e) {
                log.error("[OnnxEmbeddingService] 배치 임베딩 실패: {}", e.getMessage());
                return List.of();
            }
        }
        return results;
    }

    private List<float[]> embedBatchInternal(List<String> texts) throws OrtException {
        // 1. 배치 토큰화
        Encoding[] encodings = tokenizer.batchEncode(texts);

        // 2. 패딩된 텐서 구성
        int batchSz = encodings.length;
        int maxLen = Arrays.stream(encodings)
                .mapToInt(e -> e.getIds().length)
                .max().orElse(0);

        long[][] inputIds = new long[batchSz][maxLen];
        long[][] attentionMask = new long[batchSz][maxLen];
        long[][] tokenTypeIds = new long[batchSz][maxLen];

        for (int i = 0; i < batchSz; i++) {
            long[] ids = encodings[i].getIds();
            long[] mask = encodings[i].getAttentionMask();
            long[] types = encodings[i].getTypeIds();
            System.arraycopy(ids, 0, inputIds[i], 0, ids.length);
            System.arraycopy(mask, 0, attentionMask[i], 0, mask.length);
            System.arraycopy(types, 0, tokenTypeIds[i], 0, types.length);
        }

        // 3. ONNX 추론
        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(environment, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask);
             OnnxTensor typesTensor = OnnxTensor.createTensor(environment, tokenTypeIds)) {

            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", maskTensor,
                    "token_type_ids", typesTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] output = (float[][][]) result.get(0).getValue();

                // 4. 각 배치 항목에 대해 Mean Pooling + L2 정규화
                List<float[]> embeddings = new ArrayList<>();
                for (int i = 0; i < batchSz; i++) {
                    embeddings.add(meanPooling(output[i], attentionMask[i]));
                }
                return embeddings;
            }
        }
    }

    /**
     * 단일 샘플의 hidden state에 대해 attention mask 기반 Mean Pooling 수행.
     */
    private float[] meanPooling(float[][] singleSample, long[] attentionMask) {
        int seqLen = singleSample.length;
        int hiddenSize = singleSample[0].length;
        float[] result = new float[hiddenSize];
        float tokenCount = 0;

        for (int i = 0; i < seqLen; i++) {
            if (attentionMask[i] == 1) {
                for (int j = 0; j < hiddenSize; j++) {
                    result[j] += singleSample[i][j];
                }
                tokenCount++;
            }
        }

        if (tokenCount > 0) {
            for (int j = 0; j < hiddenSize; j++) {
                result[j] /= tokenCount;
            }
        }

        return l2Normalize(result);
    }

    /**
     * L2 정규화. 영벡터 시 영벡터 반환.
     */
    private float[] l2Normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) return vector;

        norm = (float) Math.sqrt(norm);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    /**
     * 모델 출력 차원을 더미 추론으로 확인.
     */
    private int resolveOutputDimension() {
        try {
            Encoding encoding = tokenizer.encode("dim");
            long[][] ids = {encoding.getIds()};
            long[][] mask = {encoding.getAttentionMask()};
            long[][] types = {encoding.getTypeIds()};

            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(environment, ids);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
                 OnnxTensor typesTensor = OnnxTensor.createTensor(environment, types)) {

                Map<String, OnnxTensor> inputs = Map.of(
                        "input_ids", inputIdsTensor,
                        "attention_mask", maskTensor,
                        "token_type_ids", typesTensor);

                try (OrtSession.Result result = session.run(inputs)) {
                    float[][][] output = (float[][][]) result.get(0).getValue();
                    return output[0][0].length;
                }
            }
        } catch (Exception e) {
            log.warn("[OnnxEmbeddingService] 출력 차원 확인 실패, 기본값 사용: {}", configuredDimensions);
            return configuredDimensions;
        }
    }

    private void warmUp() {
        try {
            embed("warm-up");
            log.info("[OnnxEmbeddingService] 초기화 완료 (모델: {}, 차원: {})",
                    config.getModelRepo(), outputDimension);
        } catch (Exception e) {
            log.warn("[OnnxEmbeddingService] Warm-up 실패: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (session != null) session.close();
            if (tokenizer != null) tokenizer.close();
            // OrtEnvironment는 JVM 전역 싱글턴이므로 close하지 않는다.
        } catch (Exception e) {
            log.debug("[OnnxEmbeddingService] 리소스 해제 중 오류: {}", e.getMessage());
        }
    }
}
