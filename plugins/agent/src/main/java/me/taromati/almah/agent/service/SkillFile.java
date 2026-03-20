package me.taromati.almah.agent.service;

import java.util.List;
import java.util.Map;

/**
 * 스킬 파일 파싱 결과.
 * agent-data/skills/{name}/SKILL.md 에서 frontmatter를 파싱한 결과입니다.
 *
 * @param name        스킬 이름 (frontmatter name 필드)
 * @param description 스킬 설명 (frontmatter description 필드, 없으면 null)
 * @param active      활성 여부 (frontmatter active 필드, 기본 false)
 * @param tools       스킬에서 사용하는 도구 목록 (frontmatter tools 필드, 없으면 빈 리스트)
 * @param content     frontmatter 제거 후 마크다운 본문
 * @param os          지원 OS 목록 (darwin, linux, windows). 빈 리스트이면 OS 무관
 * @param requires    필수 바이너리/환경변수 요구사항
 * @param mcpServer   의존하는 MCP 서버 이름 (없으면 null)
 * @param env         환경변수 매핑 (key→value 또는 key→${config:...})
 * @param install     설치 스펙 목록 (requires 미충족 시 사용)
 */
public record SkillFile(
        String name,
        String description,
        boolean active,
        List<String> tools,
        String content,
        List<String> os,
        Requires requires,
        String mcpServer,
        Map<String, String> env,
        List<InstallSpec> install
) {
    /**
     * 필수 요구사항 (바이너리 + 환경변수).
     *
     * @param bins 필수 바이너리 목록 (which/where로 검증)
     * @param env  필수 환경변수 목록
     */
    public record Requires(List<String> bins, List<String> env) {
        public Requires {
            bins = bins != null ? List.copyOf(bins) : List.of();
            env = env != null ? List.copyOf(env) : List.of();
        }
    }

    /**
     * 설치 스펙.
     *
     * @param kind    설치 방식 (brew, apt, npm 등)
     * @param formula 패키지 이름
     * @param bins    설치 후 사용 가능한 바이너리 목록
     * @param label   표시용 라벨
     */
    public record InstallSpec(String kind, String formula, List<String> bins, String label) {
        public InstallSpec {
            bins = bins != null ? List.copyOf(bins) : List.of();
        }
    }

    public SkillFile {
        tools = tools != null ? List.copyOf(tools) : List.of();
        os = os != null ? List.copyOf(os) : List.of();
        env = env != null ? Map.copyOf(env) : Map.of();
        install = install != null ? List.copyOf(install) : List.of();
    }
}
