package nextvisit.api.llm;

public class LlmClientException extends RuntimeException {
    private final LlmFailureCode code;

    public LlmClientException(LlmFailureCode code) {
        super(code.name());
        this.code = code;
    }

    public LlmClientException(LlmFailureCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public LlmFailureCode code() {
        return code;
    }
}
