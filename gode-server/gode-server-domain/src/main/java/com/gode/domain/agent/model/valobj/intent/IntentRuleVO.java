package com.gode.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentRuleVO {

    private IntentTypeEnumVO intent;

    private List<String> keywords;

    private List<String> patterns;

}