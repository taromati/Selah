package me.taromati.almah.agent.task;

import java.util.Map;
import java.util.Set;

/**
 * Pure logic: validates state transitions for task items.
 * No Spring dependencies.
 */
public final class TaskStatusMachine {

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            TaskStatus.PENDING, Set.of(TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED),
            TaskStatus.IN_PROGRESS, Set.of(TaskStatus.PENDING, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.WAITING_APPROVAL, TaskStatus.CANCELLED),
            TaskStatus.WAITING_APPROVAL, Set.of(TaskStatus.PENDING, TaskStatus.FAILED, TaskStatus.CANCELLED),
            TaskStatus.FAILED, Set.of(TaskStatus.PENDING),
            TaskStatus.COMPLETED, Set.of(),
            TaskStatus.CANCELLED, Set.of()
    );

    private TaskStatusMachine() {}

    public static boolean canTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static void validate(String from, String to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Invalid status transition: " + from + " → " + to);
        }
    }
}
