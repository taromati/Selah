package me.taromati.almah.agent.db.repository;

import me.taromati.almah.agent.db.entity.AgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, String> {
    List<AgentMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    int countBySessionId(String sessionId);

    @Modifying
    @Query("DELETE FROM AgentMessageEntity m WHERE m.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);

    @Modifying
    @Query("""
            DELETE FROM AgentMessageEntity m WHERE m.sessionId = :sessionId
            AND m.id NOT IN (
                SELECT m2.id FROM AgentMessageEntity m2
                WHERE m2.sessionId = :sessionId
                ORDER BY m2.createdAt DESC LIMIT :keepCount
            )
            """)
    int deleteOldMessages(@Param("sessionId") String sessionId, @Param("keepCount") int keepCount);

    /**
     * 마지막 assistant 메시지 이후의 user 메시지 삭제
     * (LLM 호출 실패 시 미응답 user 메시지 정리용)
     */
    @Modifying
    @Query("""
            DELETE FROM AgentMessageEntity m WHERE m.sessionId = :sessionId
            AND m.role = 'user'
            AND m.createdAt > (
                SELECT COALESCE(MAX(m2.createdAt), '1970-01-01')
                FROM AgentMessageEntity m2
                WHERE m2.sessionId = :sessionId AND m2.role = 'assistant'
            )
            """)
    int deleteUnansweredUserMessages(@Param("sessionId") String sessionId);
}
