package nextvisit.api.snapshots;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotRepository extends JpaRepository<Snapshot, UUID> {
    List<Snapshot> findByCaseIdOrderByWeekAsc(UUID caseId);
    Optional<Snapshot> findByCaseIdAndWeek(UUID caseId, int week);
    Optional<Snapshot> findFirstByCaseIdAndWeekLessThanOrderByWeekDesc(UUID caseId, int week);
    Optional<Snapshot> findFirstByCaseIdOrderByWeekDesc(UUID caseId);
}
