package nextvisit.api.llm;

/** 모델에 보내는 한 번의 요청. system 한 개와 user 한 개로 끝난다. */
public record LlmPrompt(String systemMessage, String userMessage) {}
