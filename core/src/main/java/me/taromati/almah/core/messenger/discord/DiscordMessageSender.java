package me.taromati.almah.core.messenger.discord;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.messenger.MessageSplitter;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DiscordMessageSender {

    private DiscordMessageSender() {}

    private static final int DISCORD_MAX_MESSAGE_LENGTH = 2000;

    public static void sendText(MessageChannel channel, String message) {
        if (channel == null || message == null || message.isEmpty()) return;

        List<String> chunks = MessageSplitter.split(message, DISCORD_MAX_MESSAGE_LENGTH);
        for (String chunk : chunks) {
            channel.sendMessage(chunk).queue();
        }
    }

    public static void sendWithImages(MessageChannel channel, String message, List<byte[]> images) {
        if (channel == null) return;

        List<FileUpload> uploads = prepareFileUploads(images);
        if (uploads.isEmpty()) return;

        if (message == null || message.isEmpty()) {
            channel.sendFiles(uploads).queue();
            return;
        }

        List<String> chunks = MessageSplitter.split(message, DISCORD_MAX_MESSAGE_LENGTH);
        for (int i = 0; i < chunks.size() - 1; i++) {
            channel.sendMessage(chunks.get(i)).queue();
        }
        channel.sendMessage(chunks.getLast()).addFiles(uploads).queue();
    }

    private static List<FileUpload> prepareFileUploads(List<byte[]> images) {
        List<FileUpload> uploads = new ArrayList<>();
        int index = 1;
        for (byte[] imageData : images) {
            uploads.add(FileUpload.fromData(imageData, "image_" + index + ".png"));
            index++;
        }
        return uploads;
    }
}
