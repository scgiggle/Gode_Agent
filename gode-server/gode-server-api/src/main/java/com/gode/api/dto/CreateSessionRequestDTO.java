package com.gode.api.dto;

import lombok.Data;

@Data
public class CreateSessionRequestDTO {

    private String agentId;

    private String userId;

    /** 可选标题，不绑定任何题目。 */
    private String title;

}
