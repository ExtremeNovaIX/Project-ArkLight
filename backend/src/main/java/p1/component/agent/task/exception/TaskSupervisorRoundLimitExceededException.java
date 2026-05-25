package p1.component.agent.task.exception;

public final class TaskSupervisorRoundLimitExceededException extends RuntimeException {

    private final String taskRunId;
    private final int maxRounds;
    private final String toolName;

    public TaskSupervisorRoundLimitExceededException(String taskRunId, int maxRounds, String toolName) {
        super("TaskSupervisor tool round limit exceeded");
        this.taskRunId = taskRunId;
        this.maxRounds = maxRounds;
        this.toolName = toolName;
    }

    public String taskRunId() {
        return taskRunId;
    }

    public int maxRounds() {
        return maxRounds;
    }

    public String toolName() {
        return toolName;
    }
}
