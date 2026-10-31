package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.JobNavigationSelection;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface JobNavigationSelectionRepository extends JpaRepository<JobNavigationSelection,UUID> {
    Optional<JobNavigationSelection> findBySelectorJobStepJobStepId(UUID id);
    Optional<JobNavigationSelection> findByMoveJobStepJobStepId(UUID id);
    @Query("select s from JobNavigationSelection s where s.selectorJobStep.jobTask.job.jobId=:job and s.endpointGroup.endpointGroupId=:group order by s.selectorJobStep.executionSequenceNo desc")
    List<JobNavigationSelection> history(@Param("job") UUID job,@Param("group") UUID group);
}
