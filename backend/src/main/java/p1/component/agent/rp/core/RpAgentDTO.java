package p1.component.agent.rp.core;

import jdk.jfr.Description;
import lombok.Data;

@Data
public class RpAgentDTO {

    @Description("""
            在进行任何角色回复之前，你【必须】先在这里进行思考，并严格按照以下格式输出：
   
            1. 提取用户提到的专有名词/事件：[列出名词]
            2. 检查这些名词是否在当前的 summary 或 role 设定中明确存在？[是/否]
            3. 结论：是否需要调用 tool？[如果第2步有"否"，必须回答"是"并立即停止思考调用工具]

            【角色沉浸要求】在你的思考过程（think）中，请遵守以下规则：
            1. 请以角色第一人称进行内心独白，用括号包裹内心活动，例如"（心想：……）"或"(内心OS：……)"
            2. 用第一人称描写角色的内心感受，例如"我心想""我觉得""我暗自"等
            3. 思考内容应沉浸在角色中，通过内心独白分析剧情和规划回复
            """)
    public String think;

    @Description("你实际上回复给用户的内容")
    public String body;
}
