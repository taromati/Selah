package me.taromati.almah.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * logback Filter: MDC "plugin" 값이 설정된 pluginName과 일치하면 ACCEPT, 아니면 DENY.
 * logback-spring.xml에서 appender별로 사용합니다.
 *
 * <pre>{@code
 * <appender name="AGENT_FILE" class="...RollingFileAppender">
 *     <filter class="me.taromati.almah.core.logging.MdcPluginFilter">
 *         <pluginName>agent</pluginName>
 *     </filter>
 *     ...
 * </appender>
 * }</pre>
 */
public class MdcPluginFilter extends Filter<ILoggingEvent> {

    private static final String MDC_KEY = "plugin";

    private String pluginName;

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String mdcValue = event.getMDCPropertyMap().get(MDC_KEY);
        if (pluginName != null && pluginName.equals(mdcValue)) {
            return FilterReply.ACCEPT;
        }
        return FilterReply.DENY;
    }
}
