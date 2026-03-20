package me.taromati.almah.llm.embedding;

import java.util.List;

/**
 * 임베딩 서비스 인터페이스 (공유 인프라).
 * 구현체: HttpEmbeddingService (OpenAI 호환 API), OnnxEmbeddingService (ONNX Runtime 로컬 추론)
 */
public interface EmbeddingService {

    /**
     * 단일 텍스트 임베딩 생성.
     * @return 임베딩 벡터. 실패 시 null.
     */
    float[] embed(String text);

    /**
     * 배치 임베딩 생성.
     * @return 임베딩 벡터 목록. 실패 시 빈 리스트.
     */
    List<float[]> embedBatch(List<String> texts);
}
