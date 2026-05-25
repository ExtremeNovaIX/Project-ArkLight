package p1.component.agent.task.state;

import p1.component.agent.task.exception.TaskSupervisorRoundLimitExceededException;

public final class TaskSupervisorRoundBudget {

    private final String taskRunId;
    private final int maxRounds;
    private int usedRounds;

    public TaskSupervisorRoundBudget(String taskRunId, int maxRounds) {
        this.taskRunId = taskRunId;
        this.maxRounds = maxRounds;
    }

    public synchronized int claimNextRound(String toolName) {
        if (usedRounds >= maxRounds) {
            throw new TaskSupervisorRoundLimitExceededException(taskRunId, maxRounds, toolName);
        }
        usedRounds++;
        return usedRounds;
    }

    public synchronized int usedRounds() {
        return usedRounds;
    }

    public int maxRounds() {
        return maxRounds;
    }
}
