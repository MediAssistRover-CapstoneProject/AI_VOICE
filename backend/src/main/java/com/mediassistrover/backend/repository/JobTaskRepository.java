package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.JobTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface JobTaskRepository extends JpaRepository<JobTask,UUID> {
    List<JobTask> findByJobJobIdOrderBySequenceNoAsc(UUID jobId);
}
