package com.gode.domain.agent.model.valobj.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilestoneVO {

    private Type type;
    private String content;
    private long timestamp;

    public enum Type {
        TASK_CHANGE,
        TASK_COMPLETE,
        USER_CORRECTION,
        ERROR,
        DECISION,
        FILE_SWITCH
    }

}
