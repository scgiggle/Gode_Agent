package com.gode.domain.agent.service.intent.classifier;

import com.gode.domain.agent.model.valobj.intent.ConversationContextVO;
import com.gode.domain.agent.model.valobj.intent.IntentResultVO;
import com.gode.domain.agent.model.valobj.intent.IntentTypeEnumVO;


/**
 * 意图分类器接口
 */
public interface IIntentClassifier {

    IntentResultVO classify(String message, ConversationContextVO context);

}
