package com.gode.api.dto;

import lombok.Data;

import java.util.List;

/** 错题本分页结果。 */
@Data
public class MistakeBookPageResponseDTO {
    private List<LeetcodeMistakeDTO> records;
    private Long total;
    private Integer page;
    private Integer pageSize;
    private Long totalPages;
}
