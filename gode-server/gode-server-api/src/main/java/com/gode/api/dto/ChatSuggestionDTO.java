package com.gode.api.dto;

import lombok.Data;

/** label 用于按钮展示，prompt 用于点击后填入输入框；不是自动执行的指令。 */
@Data
public class ChatSuggestionDTO {
    private String label;
    private String prompt;
}
