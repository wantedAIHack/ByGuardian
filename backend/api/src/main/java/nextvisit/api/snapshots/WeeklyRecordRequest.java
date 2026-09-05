package nextvisit.api.snapshots;

import java.util.List;
import java.util.Map;

/** README §9 화면 2. noChange면 changedItems는 비어 있어야 하고, 아니면 하나 이상. */
public record WeeklyRecordRequest(
    boolean noChange,
    Map<String, ItemInput> changedItems,
    Map<String, List<String>> painSignal,
    Integer sleep,
    FreeNoteInput freeNote
) {}
