package com.gode.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEventDTO {

    private String type;      // agent_start、chunk、done、error
    private String agentName; // problemAnalyzer 等
    private String content;   // chunk 才有实际文本

}
