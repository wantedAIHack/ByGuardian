package nextvisit.api.llm;

public interface LlmClient {
    String complete(QuestionRewritePrompt.Prompt prompt);
}
