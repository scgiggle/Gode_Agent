package com.gode.api.dto;

import lombok.Data;

/** 开始复习、标记掌握、删除错题共用的身份及错题 ID 请求。 */
@Data
public class MistakeReviewRequestDTO {
    private String userId;
    private Long mistakeId;
}
