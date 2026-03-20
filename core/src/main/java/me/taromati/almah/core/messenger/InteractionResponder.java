package me.taromati.almah.core.messenger;

public interface InteractionResponder {

    void replyEphemeral(String text);

    void editMessage(String newText);

    void removeComponents();
}
