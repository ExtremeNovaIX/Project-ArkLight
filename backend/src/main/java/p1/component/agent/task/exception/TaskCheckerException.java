package p1.component.agent.task.exception;

public class TaskCheckerException extends RuntimeException {

    public TaskCheckerException(String message) {
        super(message);
    }

    public TaskCheckerException(String message, Throwable cause) {
        super(message, cause);
    }
}
