package p1.component.agent.task.context;

public final class TaskSupervisorPromptRenderer {

    public String render(String userQuestion,
                         String agentRequest,
                         String currentInstruction,
                         String blackboardReadonlyView) {
        return """
                %s
                <blackboard_readonly>
                %s
                </blackboard_readonly>
                %s
                """.formatted(
                TaskPromptSupport.renderTaskGoal(userQuestion, agentRequest),
                blackboardReadonlyView,
                TaskPromptSupport.renderCurrentInstructionSection(agentRequest, currentInstruction)
        );
    }
}
