package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.RobotCommandOutbox;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface RobotCommandOutboxRepository extends JpaRepository<RobotCommandOutbox,UUID> {
    boolean existsByJobId(UUID jobId);
    @Query("select count(c)>0 from RobotCommandOutbox c where c.jobId=:jobId and " +
            "(c.state in ('PENDING','PUBLISHING','PUBLISHED') or (c.interruptionReason is not null and " +
            "(c.physicalStopConfirmed is null or c.physicalStopConfirmed=false)))")
    boolean existsUnreconciledByJobId(@Param("jobId") UUID jobId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from RobotCommandOutbox c where c.commandId=:id")
    Optional<RobotCommandOutbox> lock(@Param("id") UUID id);
    Optional<RobotCommandOutbox> findByJobStepIdAndCommandType(UUID stepId,String type);
    default Optional<RobotCommandOutbox> findByJobStepId(UUID stepId) {return findByJobStepIdAndCommandType(stepId,"EXECUTE_STEP");}
    List<RobotCommandOutbox> findByJobIdOrderByCreatedAt(UUID jobId);
    List<RobotCommandOutbox> findByRobotIdAndStateIn(UUID robot,Collection<String> states);
    @Query("select c.commandId from RobotCommandOutbox c where c.state in ('PENDING','PUBLISHING','PUBLISHED') order by c.createdAt")
    List<UUID> pending(Pageable page);
}
