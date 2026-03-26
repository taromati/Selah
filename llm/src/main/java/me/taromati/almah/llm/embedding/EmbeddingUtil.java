package me.taromati.almah.llm.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 임베딩 관련 유틸리티 (프로바이더 무관).
 * 코사인 유사도 계산 및 float[]/byte[] 변환.
 */
public final class EmbeddingUtil {

    private EmbeddingUtil() {}

    /**
     * 코사인 유사도 계산.
     * 길이 불일치, null, 영벡터 시 0.0 반환.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * float[] 배열을 byte[]로 변환 (저장용).
     */
    public static byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * byte[] 배열을 float[]로 변환 (로드용).
     */
    public static float[] bytesToFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
}
