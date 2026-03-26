package me.taromati.almah.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스킬 env 필드 해석 + exec 프로세스 환경변수 주입용 Map 생성.
 * <p>
 * {@code ${config:key}} 패턴은 Spring Environment에서 해석.
 * 리터럴 값은 그대로 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class SkillEnvInjector {

    private static final Pattern CONFIG_REF = Pattern.compile("\\$\\{config:([^}]+)}");

    private final Environment environment;

    /**
     * 활성 스킬 목록의 env 필드를 모두 병합하여 환경변수 Map 생성.
     *
     * @param activeSkills 활성 스킬 목록
     * @return exec ProcessBuilder에 주입할 환경변수 (key→resolved value)
     */
    public Map<String, String> resolveEnv(List<SkillFile> activeSkills) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (SkillFile skill : activeSkills) {
            if (skill.env().isEmpty()) continue;
            for (var entry : skill.env().entrySet()) {
                String resolved = resolveValue(entry.getValue());
                if (resolved != null) {
                    merged.put(entry.getKey(), resolved);
                } else {
                    log.debug("[SkillEnvInjector] Could not resolve env '{}' for skill '{}'",
                            entry.getKey(), skill.name());
                }
            }
        }
        return merged;
    }

    /**
     * 스킬의 env 매핑에서 해당 키가 해석 가능한지 확인.
     * System.getenv()에 없더라도 ${config:...} 매핑으로 해석 가능하면 true.
     */
    public boolean canResolveEnv(SkillFile skill, String envKey) {
        if (skill.env().isEmpty()) return false;
        String template = skill.env().get(envKey);
        if (template == null) return false;
        return resolveValue(template) != null;
    }

    /**
     * 값 해석: ${config:key} 패턴이면 Spring Environment에서 조회, 아니면 리터럴.
     */
    private String resolveValue(String value) {
        if (value == null) return null;

        Matcher m = CONFIG_REF.matcher(value);
        if (m.matches()) {
            String configKey = m.group(1);
            String resolved = environment.getProperty(configKey);
            if (resolved == null) {
                log.debug("[SkillEnvInjector] Config key not found: {}", configKey);
            }
            return resolved;
        }

        // 부분 치환: ${config:key} 여러 개 포함 가능
        if (value.contains("${config:")) {
            StringBuffer sb = new StringBuffer();
            Matcher partial = CONFIG_REF.matcher(value);
            while (partial.find()) {
                String configKey = partial.group(1);
                String resolved = environment.getProperty(configKey);
                partial.appendReplacement(sb, resolved != null ? Matcher.quoteReplacement(resolved) : "");
            }
            partial.appendTail(sb);
            return sb.toString();
        }

        return value;
    }
}
