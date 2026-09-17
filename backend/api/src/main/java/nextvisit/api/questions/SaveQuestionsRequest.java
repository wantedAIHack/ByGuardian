package nextvisit.api.questions;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SaveQuestionsRequest(@NotNull List<Item> items) {
    public record Item(String id, String sentence) {}
}
