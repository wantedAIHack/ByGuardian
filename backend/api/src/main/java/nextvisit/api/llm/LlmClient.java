package nextvisit.api.llm;

public interface LlmClient {
    String complete(LlmPrompt prompt);
}
