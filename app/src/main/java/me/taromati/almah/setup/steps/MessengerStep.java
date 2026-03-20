package me.taromati.almah.setup.steps;

import me.taromati.almah.setup.ConfigGenerator;
import me.taromati.almah.setup.ConsoleUi;
import me.taromati.almah.setup.ExistingConfig;

import java.util.List;

/**
 * 메신저 선택 단계. Discord/Telegram/양쪽 선택 → 해당 설정 단계 분기.
 */
public class MessengerStep {

    public static boolean run(ConsoleUi ui, ConfigGenerator config, ExistingConfig existing) {
        // 기존 설정 감지 (S05)
        if (existing != null && existing.hasAnyMessenger()) {
            showExistingConfig(ui, existing);

            int action = ui.choose("메신저 설정을 어떻게 하시겠습니까?", List.of(
                    "변경 안 함 (현재 설정 유지)",
                    "기존 유지 + 메신저 추가",
                    "처음부터 다시 설정"
            ));

            if (action == 0) return false;

            if (action == 1) {
                // 변경 안 함 — 기존 설정 그대로 복사
                existing.copyMessengerTo(config);
                return true;
            }

            if (action == 2) {
                // 기존 유지 + 추가 — 기존 설정 복사 후 미설정 메신저만 설정
                existing.copyMessengerTo(config);
                if (!existing.hasDiscord() && !existing.hasTelegram()) {
                    // 둘 다 없으면 "처음부터"와 동일
                    return runFullSelection(ui, config);
                } else if (!existing.hasDiscord()) {
                    ui.info("Discord를 추가합니다.");
                    DiscordTokenStep.run(ui, config);
                } else if (!existing.hasTelegram()) {
                    ui.info("Telegram을 추가합니다.");
                    TelegramTokenStep.run(ui, config);
                } else {
                    ui.info("Discord, Telegram 모두 설정되어 있습니다. "
                            + "변경하려면 '처음부터 다시'를 선택해주세요.");
                }
                return true;
            }

            // action == 3: 처음부터 다시 — 아래로 진행
        }

        return runFullSelection(ui, config);
    }

    private static boolean runFullSelection(ConsoleUi ui, ConfigGenerator config) {
        int choice = ui.choose("사용할 메신저를 선택하세요:", List.of(
                "Discord",
                "Telegram",
                "둘 다"
        ));

        if (choice == 0) return false;

        boolean setupDiscord = (choice == 1 || choice == 3);
        boolean setupTelegram = (choice == 2 || choice == 3);

        if (setupDiscord) {
            DiscordTokenStep.run(ui, config);
        }
        if (setupTelegram) {
            TelegramTokenStep.run(ui, config);
        }

        // 선택하지 않은 메신저 비활성화
        if (!setupDiscord) {
            config.discordEnabled(false);
        }
        if (!setupTelegram) {
            config.telegramEnabled(false);
        }
        return true;
    }

    private static void showExistingConfig(ConsoleUi ui, ExistingConfig existing) {
        ui.info("현재 설정:");
        ui.info("  Discord: " + (existing.hasDiscord()
                ? "설정됨 (서버: " + existing.discordServerName() + ")"
                : "미설정"));
        ui.info("  Telegram: " + (existing.hasTelegram()
                ? "설정됨 (@" + existing.telegramBotUsername() + ")"
                : "미설정"));
    }
}
