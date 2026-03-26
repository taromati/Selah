package me.taromati.almah.core.util;

import java.time.ZoneId;

/**
 * 프로젝트 전역 시간 상수
 */
public final class TimeConstants {

    /** 시스템 기본 타임존 */
    public static final ZoneId KST = ZoneId.systemDefault();

    private TimeConstants() {}
}
