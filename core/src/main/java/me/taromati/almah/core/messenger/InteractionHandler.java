package me.taromati.almah.core.messenger;

public interface InteractionHandler {

    String getActionIdPrefix();

    void handle(ActionEvent event, InteractionResponder responder);
}
