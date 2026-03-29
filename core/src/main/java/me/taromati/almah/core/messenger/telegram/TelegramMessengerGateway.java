package me.taromati.almah.core.messenger.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.*;
import me.taromati.almah.core.messenger.telegram.config.TelegramConfigProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramMessengerGateway implements MessengerGateway {

    private static final int TELEGRAM_MAX_MESSAGE_LENGTH = 4096;

    private final TelegramBot bot;
    private final TelegramConfigProperties config;
    private final ObjectProvider<TelegramInteractionRouter> interactionRouterProvider;

    public TelegramMessengerGateway(TelegramBot bot, TelegramConfigProperties config,
                                     ObjectProvider<TelegramInteractionRouter> interactionRouterProvider) {
        this.bot = bot;
        this.config = config;
        this.interactionRouterProvider = interactionRouterProvider;
    }

    @Override
    public MessengerPlatform getPlatform() {
        return MessengerPlatform.TELEGRAM;
    }

    @Override
    public void sendText(ChannelRef channel, String message) {
        if (message == null || message.isEmpty()) return;
        List<String> chunks = MessageSplitter.split(message, TELEGRAM_MAX_MESSAGE_LENGTH);
        for (String chunk : chunks) {
            bot.execute(new SendMessage(channel.channelId(), chunk));
        }
    }

    @Override
    public void sendWithImages(ChannelRef channel, String message, List<byte[]> images) {
        if (images == null || images.isEmpty()) return;

        if (message != null && !message.isEmpty()) {
            List<String> chunks = MessageSplitter.split(message, TELEGRAM_MAX_MESSAGE_LENGTH);
            for (int i = 0; i < chunks.size() - 1; i++) {
                bot.execute(new SendMessage(channel.channelId(), chunks.get(i)));
            }
            // 마지막 청크와 첫 번째 이미지를 함께
            bot.execute(new SendPhoto(channel.channelId(), images.getFirst()).caption(chunks.getLast()));
        } else {
            bot.execute(new SendPhoto(channel.channelId(), images.getFirst()));
        }

        // 추가 이미지
        for (int i = 1; i < images.size(); i++) {
            bot.execute(new SendPhoto(channel.channelId(), images.get(i)));
        }
    }

    @Override
    public void sendWithFiles(ChannelRef channel, String message, List<FileData> files) {
        if (files == null || files.isEmpty()) return;
        if (message != null && !message.isEmpty()) {
            bot.execute(new SendMessage(channel.channelId(), message));
        }
        for (FileData file : files) {
            bot.execute(new SendDocument(channel.channelId(), file.data()).fileName(file.fileName()));
        }
    }

    @Override
    public void sendDirectMessage(String userId, String message) {
        bot.execute(new SendMessage(userId, message));
    }

    @Override
    public ChannelRef resolveChannel(String channelName) {
        Map<String, String> mappings = config.getChannelMappings();
        String chatId = mappings.get(channelName);
        if (chatId == null) return null;
        return ChannelRef.of(MessengerPlatform.TELEGRAM, chatId);
    }

    @Override
    public TypingHandle startTyping(ChannelRef channel) {
        return new TelegramTypingHandle(bot, channel.channelId());
    }

    @Override
    public InteractiveMessageHandle sendInteractive(ChannelRef channel, InteractiveMessage interactiveMsg) {
        InlineKeyboardButton[] buttons = interactiveMsg.actions().stream()
                .map(a -> new InlineKeyboardButton(a.label()).callbackData(a.id()))
                .toArray(InlineKeyboardButton[]::new);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(buttons);
        SendResponse response = bot.execute(
                new SendMessage(channel.channelId(), interactiveMsg.text()).replyMarkup(markup));

        if (response.isOk() && response.message() != null) {
            int messageId = response.message().messageId();
            TelegramInteractionRouter router = interactionRouterProvider.getIfAvailable();
            return new TelegramInteractiveMessage(
                    channel.channelId(), messageId, bot, router);
        }
        return null;
    }

    @Override
    public String sendTextAndGetId(ChannelRef channel, String message) {
        if (message == null || message.isEmpty()) return null;
        SendResponse response = bot.execute(new SendMessage(channel.channelId(), message));
        if (response.isOk() && response.message() != null) {
            return String.valueOf(response.message().messageId());
        }
        return null;
    }

    @Override
    public void editMessage(ChannelRef channel, String messageId, String newText) {
        try {
            bot.execute(new EditMessageText(channel.channelId(), Integer.parseInt(messageId), newText)
                    .replyMarkup(new InlineKeyboardMarkup()));  // 빈 마크업 = 버튼 제거
        } catch (Exception e) {
            log.warn("[Telegram] editMessage 실패: {}", e.getMessage());
        }
    }
}
