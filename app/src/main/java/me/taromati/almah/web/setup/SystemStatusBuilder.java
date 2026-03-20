package me.taromati.almah.web.setup;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.discord.DiscordMessengerGateway;
import me.taromati.almah.core.messenger.telegram.TelegramMessengerGateway;
import me.taromati.almah.setup.FullConfig;
import me.taromati.almah.web.setup.dto.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Dashboard용 시스템 상태 수집.
 * SearXNG health check를 가상 스레드로 병렬 실행하여 지연을 최소화한다.
 */
@Slf4j
@Component
public class SystemStatusBuilder {

    private static final Path CONFIG_PATH = Path.of("config.yml");

    private final ObjectProvider<DiscordMessengerGateway> discordGatewayProvider;
    private final ObjectProvider<TelegramMessengerGateway> telegramGatewayProvider;
    private final SetupValidationService validationService;
    private final SetupDoctorService doctorService;

    public SystemStatusBuilder(
            ObjectProvider<DiscordMessengerGateway> discordGatewayProvider,
            ObjectProvider<TelegramMessengerGateway> telegramGatewayProvider,
            SetupValidationService validationService,
            SetupDoctorService doctorService) {
        this.discordGatewayProvider = discordGatewayProvider;
        this.telegramGatewayProvider = telegramGatewayProvider;
        this.validationService = validationService;
        this.doctorService = doctorService;
    }

    public SystemStatus build() {
        FullConfig config = FullConfig.parse(CONFIG_PATH);

        // SearXNG를 가상 스레드로 병렬 수집
        boolean searxngConnected = false;
        String searxngUrl = config.searxngUrl();
        if (searxngUrl != null && !searxngUrl.isEmpty()) {
            try {
                var result = validationService.testSearxng(searxngUrl);
                searxngConnected = result.success();
            } catch (Exception e) {
                log.debug("[SystemStatus] SearXNG 체크 실패: {}", e.getMessage());
            }
        }

        MessengerStatus discordStatus = buildDiscordStatus(config);
        MessengerStatus telegramStatus = buildTelegramStatus(config);
        ServiceStatus llmStatus = buildLlmStatus(config);
        ServiceStatus embeddingStatus = buildEmbeddingStatus(config);
        ServiceStatus searxngStatus = buildSearxngStatus(config, searxngConnected);
        boolean serviceRegistered = isServiceRegistered();

        // Doctor 간이 요약
        DoctorResult doctor = doctorService.runDoctor();
        DoctorSummary doctorSummary = new DoctorSummary(doctor.passed(), doctor.failed(), doctor.warned());

        return new SystemStatus(
                discordStatus, telegramStatus, llmStatus, embeddingStatus, searxngStatus,
                serviceRegistered, doctorSummary);
    }

    private MessengerStatus buildDiscordStatus(FullConfig config) {
        DiscordMessengerGateway gateway = discordGatewayProvider.getIfAvailable();
        if (gateway == null || !gateway.isInitialized()) {
            if (config.hasDiscord()) {
                return new MessengerStatus(true, false, config.discordServerName());
            }
            return new MessengerStatus(false, false, null);
        }
        return new MessengerStatus(true, true, config.discordServerName());
    }

    private MessengerStatus buildTelegramStatus(FullConfig config) {
        TelegramMessengerGateway gateway = telegramGatewayProvider.getIfAvailable();
        if (gateway == null) {
            if (config.hasTelegram()) {
                String display = config.telegramBotUsername() != null
                        ? "@" + config.telegramBotUsername() : null;
                return new MessengerStatus(true, false, display);
            }
            return new MessengerStatus(false, false, null);
        }
        String display = config.telegramBotUsername() != null
                ? "@" + config.telegramBotUsername() : null;
        return new MessengerStatus(true, true, display);
    }

    private ServiceStatus buildLlmStatus(FullConfig config) {
        String provider = config.llmProvider();
        if (provider == null) {
            return new ServiceStatus(false, false, null);
        }
        String model = config.llmModel();
        String displayName = provider.toUpperCase();
        if (model != null && !model.isEmpty()) {
            displayName += " (" + model + ")";
        }
        // LLM은 configured=true이면 connected도 true로 간주 (실제 확인은 Doctor)
        return new ServiceStatus(true, true, displayName);
    }

    private ServiceStatus buildEmbeddingStatus(FullConfig config) {
        String provider = config.embeddingProvider();
        if ("http".equals(provider)) {
            String model = config.embeddingModel();
            String display = "HTTP" + (model != null && !model.isEmpty() ? " (" + model + ")" : "");
            return new ServiceStatus(true, true, display);
        }
        // ONNX: 기본값 — 모델 다운로드 상태 확인
        OnnxStatus onnx = validationService.getOnnxStatus();
        return new ServiceStatus(true, onnx.downloaded(), "ONNX" + (onnx.downloaded() ? "" : " (모델 미다운로드)"));
    }

    private ServiceStatus buildSearxngStatus(FullConfig config, boolean connected) {
        String url = config.searxngUrl();
        if (url == null || url.isEmpty()) {
            return new ServiceStatus(false, false, null);
        }
        return new ServiceStatus(true, connected, url);
    }

    private boolean isServiceRegistered() {
        CheckItem serviceCheck = doctorService.checkServiceStatus();
        return serviceCheck.status() == CheckStatus.PASS;
    }
}
