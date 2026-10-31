package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.JobEvent;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
public interface JobEventRepository extends JpaRepository<JobEvent,UUID> {
    boolean existsByProducerSessionIdAndSequenceNo(UUID sessionId,long sequenceNo);
    Page<JobEvent> findByJobJobId(UUID jobId,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from JobEvent e where e.job.jobId=:jobId and e.eventType=:eventType and e.correlationKey=:correlation and e.consumedAt is null and e.occurredAt>=:notBefore order by e.occurredAt,e.persistedAt")
    List<JobEvent> lockUnconsumed(@Param("jobId") UUID jobId,@Param("eventType") String eventType,@Param("correlation") String correlation,@Param("notBefore") Instant notBefore);
}
