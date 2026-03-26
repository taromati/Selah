package me.taromati.almah.web.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.response.RootResponse;
import me.taromati.almah.web.setup.dto.*;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupApiController {

    private final SetupConfigService configService;
    private final SetupValidationService validationService;
    private final SetupDoctorService doctorService;
    private final SystemStatusBuilder statusBuilder;
    private final TelegramChatDetector chatDetector;

    // ── Status ──

    @GetMapping("/status")
    public RootResponse<SystemStatus> status() {
        return RootResponse.ok(statusBuilder.build());
    }

    // ── Config CRUD ──

    @GetMapping("/config")
    public RootResponse<SetupConfigDto> getConfig() {
        return RootResponse.ok(configService.readConfig());
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/config")
    public RootResponse<ConfigSaveResult> saveConfig(@RequestBody SetupConfigDto dto) {
        try {
            ConfigSaveResult result = configService.saveConfig(dto);
            return RootResponse.ok(result);
        } catch (IllegalArgumentException e) {
            return (RootResponse) RootResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[Setup] config 저장 실패", e);
            return (RootResponse) RootResponse.fail("설정 저장 실패: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/restart")
    public RootResponse<RestartResult> restart() {
        RestartResult result = configService.restart();
        if (!result.restarting()) {
            return (RootResponse) RootResponse.fail(result.message());
        }
        return RootResponse.ok(result);
    }

    // ── Validation ──

    @PostMapping("/messenger/discord/validate")
    public RootResponse<DiscordValidation> validateDiscord(@RequestBody TokenRequest req) {
        return RootResponse.ok(validationService.validateDiscordToken(req.token()));
    }

    @PostMapping("/messenger/telegram/validate")
    public RootResponse<TelegramValidation> validateTelegram(@RequestBody TokenRequest req) {
        return RootResponse.ok(validationService.validateTelegramToken(req.token()));
    }

    @PostMapping("/messenger/telegram/detect-chat")
    public RootResponse<Void> startChatDetection(@RequestBody TokenRequest req) {
        try {
            chatDetector.startDetection(req.token());
            return RootResponse.ok();
        } catch (IllegalStateException e) {
            return RootResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/messenger/telegram/detect-chat/status")
    public RootResponse<ChatDetectionStatus> chatDetectionStatus() {
        return RootResponse.ok(chatDetector.getStatus());
    }

    @PostMapping("/llm/test")
    public RootResponse<ConnectionTest> testLlm(@RequestBody LlmTestRequest req) {
        return RootResponse.ok(validationService.testLlmConnection(
                req.provider(), req.apiKey(), req.baseUrl(), req.cliPath()));
    }

    @GetMapping("/embedding/onnx-status")
    public RootResponse<OnnxStatus> onnxStatus() {
        return RootResponse.ok(validationService.getOnnxStatus());
    }

    @PostMapping("/embedding/test")
    public RootResponse<ConnectionTest> testEmbedding(@RequestBody EmbeddingTestRequest req) {
        return RootResponse.ok(validationService.testEmbeddingConnection(
                req.apiKey(), req.baseUrl(), req.model()));
    }

    @PostMapping("/searxng/test")
    public RootResponse<ConnectionTest> testSearxng(@RequestBody UrlRequest req) {
        return RootResponse.ok(validationService.testSearxng(req.url()));
    }

    @PostMapping("/doctor")
    public RootResponse<DoctorResult> runDoctor() {
        return RootResponse.ok(doctorService.runDoctor());
    }
}
