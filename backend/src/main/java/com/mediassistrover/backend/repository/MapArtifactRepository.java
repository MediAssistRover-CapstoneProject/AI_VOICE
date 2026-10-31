package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.MapArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface MapArtifactRepository extends JpaRepository<MapArtifact,UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from MapArtifact a where a.artifactId=:id")
    java.util.Optional<MapArtifact> lock(@org.springframework.data.repository.query.Param("id") UUID id);
}
