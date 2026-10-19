package com.gode.domain.agent.model.valobj.mistake;

import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MistakeBookPageVO {
    private List<LeetcodeMistakeEntity> records;
    private Long total;
    private Integer page;
    private Integer pageSize;
    private Long totalPages;
}
