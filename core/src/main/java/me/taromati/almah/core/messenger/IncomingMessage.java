package me.taromati.almah.core.messenger;

import java.util.List;

public record IncomingMessage(
        ChannelRef channel,
        String authorId,
        String authorName,
        boolean authorIsBot,
        String content,
        boolean mentionsBot,
        List<Attachment> attachments
) {
    public record Attachment(
            String fileName,
            String contentType,
            boolean isImage,
            byte[] data
    ) {}
}
