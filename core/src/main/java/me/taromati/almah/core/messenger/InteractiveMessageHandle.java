package me.taromati.almah.core.messenger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public interface InteractiveMessageHandle {

    ActionEvent waitForAction(long timeout, TimeUnit unit);

    void editText(String newText);

    CompletableFuture<ActionEvent> getFuture();
}
