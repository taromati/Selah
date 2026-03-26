package me.taromati.almah.core.messenger.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Document;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.response.GetFileResponse;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.*;
import me.taromati.almah.core.messenger.telegram.config.TelegramConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramPluginRouter {

    private final TelegramBot bot;
    private final List<PluginListener> listeners;
    private final Environment environment;
    private final String botUsername;
    private final Map<String, String> chatIdToChannelName;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramPluginRouter(@org.springframework.lang.Nullable TelegramBot bot,
                                 TelegramConfigProperties config,
                                 List<PluginListener> listeners, Environment environment) {
        this.bot = bot;
        this.listeners = listeners;
        this.environment = environment;
        this.botUsername = config.getBotUsername();
        this.chatIdToChannelName = config.getChannelMappings().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a, b) -> a));
    }

    public void processUpdate(Update update) {
        if (update.message() == null) return;

        var message = update.message();
        var from = message.from();
        if (from == null) return;
        if (from.isBot()) return;

        String chatId = String.valueOf(message.chat().id());
        String channelName = chatIdToChannelName.get(chatId);
        if (channelName == null) return;

        String text = message.text() != null ? message.text() : "";
        boolean mentionsBot = botUsername != null && text.contains("@" + botUsername);

        for (PluginListener listener : listeners) {
            if (channelName.equals(resolveChannel(listener.getPluginName()))) {
                List<IncomingMessage.Attachment> attachments = List.of();
                if (listener.needsAttachments()) {
                    attachments = downloadAttachments(message);
                }

                IncomingMessage msg = new IncomingMessage(
                        ChannelRef.of(MessengerPlatform.TELEGRAM, chatId),
                        String.valueOf(from.id()),
                        from.firstName(),
                        from.isBot(),
                        text,
                        mentionsBot,
                        attachments
                );
                listener.onMessage(msg);
            }
        }
    }

    private List<IncomingMessage.Attachment> downloadAttachments(com.pengrad.telegrambot.model.Message message) {
        if (bot == null) return List.of();

        List<IncomingMessage.Attachment> result = new ArrayList<>();

        // 사진 처리 (가장 큰 해상도 선택)
        PhotoSize[] photos = message.photo();
        if (photos != null && photos.length > 0) {
            PhotoSize largest = photos[photos.length - 1];
            byte[] data = downloadFile(largest.fileId());
            if (data != null) {
                result.add(new IncomingMessage.Attachment(
                        "photo.jpg", "image/jpeg", true, data));
            }
        }

        // 문서 처리
        Document document = message.document();
        if (document != null) {
            byte[] data = downloadFile(document.fileId());
            if (data != null) {
                boolean isImage = document.mimeType() != null && document.mimeType().startsWith("image/");
                result.add(new IncomingMessage.Attachment(
                        document.fileName(), document.mimeType(), isImage, data));
            }
        }

        return result;
    }

    private byte[] downloadFile(String fileId) {
        try {
            GetFileResponse fileResponse = bot.execute(new GetFile(fileId));
            if (!fileResponse.isOk()) {
                log.warn("[TelegramPluginRouter] 파일 정보 조회 실패: {}", fileId);
                return null;
            }
            String fullPath = bot.getFullFilePath(fileResponse.file());
            try (InputStream is = URI.create(fullPath).toURL().openStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("[TelegramPluginRouter] 파일 다운로드 실패: {}", fileId, e);
            return null;
        }
    }

    private String resolveChannel(String pluginName) {
        String defaultChannel = environment.getProperty("plugins.notification-channel", "bot");
        return environment.getProperty(
                "plugins." + pluginName + ".channel-name",
                defaultChannel);
    }
}
