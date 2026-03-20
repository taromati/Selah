package me.taromati.almah.web.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.ActionEvent;
import me.taromati.almah.core.messenger.InteractionHandler;
import me.taromati.almah.core.messenger.InteractionResponder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 웹 인증 버튼 핸들러. Discord/Telegram 양쪽의 InteractionRouter에서 자동 주입됨.
 * action ID prefix: "auth-"
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web.auth.enabled", havingValue = "true")
public class WebAuthInteractionHandler implements InteractionHandler {

    private final WebAuthService webAuthService;

    @Override
    public String getActionIdPrefix() {
        return "auth-";
    }

    @Override
    public void handle(ActionEvent event, InteractionResponder responder) {
        String actionId = event.actionId();

        if (actionId.startsWith("auth-approve-")) {
            String token = actionId.substring("auth-approve-".length());
            if (webAuthService.approve(token)) {
                responder.editMessage("승인됨");
            } else {
                responder.replyEphemeral("이미 처리되었거나 만료된 요청입니다.");
            }
        } else if (actionId.startsWith("auth-deny-")) {
            String token = actionId.substring("auth-deny-".length());
            if (webAuthService.deny(token)) {
                responder.editMessage("거부됨");
                responder.removeComponents();
            } else {
                responder.replyEphemeral("이미 처리되었거나 만료된 요청입니다.");
            }
        } else if (actionId.startsWith("auth-revoke-")) {
            String token = actionId.substring("auth-revoke-".length());
            String result = webAuthService.revokeApproval(token);
            if (result != null) {
                responder.editMessage(result);
                responder.removeComponents();
            } else {
                responder.replyEphemeral("이미 만료되었거나 취소할 수 없는 요청입니다.");
            }
        } else if (actionId.startsWith("auth-unblock-")) {
            String ip = actionId.substring("auth-unblock-".length());
            webAuthService.unblockIp(ip);
            responder.editMessage("IP 해제됨: " + ip);
            responder.removeComponents();
        }
    }
}
