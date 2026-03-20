package me.taromati.almah.core.util;

import org.slf4j.MDC;

/**
 * 플러그인별 로그 라우팅을 위한 MDC 유틸리티.
 * MDC "plugin" 값을 설정하면 logback의 MdcPluginFilter가 플러그인별 로그 파일에 자동 라우팅합니다.
 */
public final class PluginMdc {

    private static final String KEY = "plugin";

    private PluginMdc() {}

    public static void set(String plugin) {
        MDC.put(KEY, plugin);
    }

    public static void clear() {
        MDC.remove(KEY);
    }
}
