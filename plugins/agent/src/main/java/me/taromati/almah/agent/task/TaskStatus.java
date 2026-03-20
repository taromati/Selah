package me.taromati.almah.agent.task;

public final class TaskStatus {
    public static final String PENDING = "PENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String CANCELLED = "CANCELLED";

    private TaskStatus() {}

    public static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
