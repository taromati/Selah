package me.taromati.almah.core.messenger;

public interface PluginListener {

    String getPluginName();

    void onMessage(IncomingMessage message);

    default boolean needsAttachments() {
        return false;
    }
}
