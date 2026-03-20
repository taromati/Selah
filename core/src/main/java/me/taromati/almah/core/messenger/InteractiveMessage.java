package me.taromati.almah.core.messenger;

import java.util.List;

public record InteractiveMessage(String text, List<Action> actions) {

    public record Action(String id, String label, ActionStyle style) {}

    public enum ActionStyle {
        PRIMARY,
        SUCCESS,
        DANGER
    }
}
