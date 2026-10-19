package com.gode.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentHistoryEntryVO {

    private IntentTypeEnumVO intent;

    private double confidence;

    private long timestamp;

}
