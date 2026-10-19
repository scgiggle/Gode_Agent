package com.gode.domain.agent.service.suggestion;

/** 一次运行业务与独立 Runner 两组测试；使用本地模拟模型。 */
public class ChatSuggestionSmokeTest {
    public static void main(String[] args) throws Exception {
        ChatSuggestionServiceSmokeTest.main(args);
        ChatSuggestionRunnerSmokeTest.main(args);
    }
}
