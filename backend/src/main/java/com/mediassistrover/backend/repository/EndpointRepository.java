package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Endpoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.mediassistrover.backend.enums.EndpointType;

import java.util.UUID;
import java.util.List;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    boolean existsByMapMapIdAndEndpointCodeIgnoreCase(UUID mapId, String endpointCode);
    Page<Endpoint> findByActiveTrueAndPublicVisibleTrue(Pageable pageable);
    List<Endpoint> findByMapMapIdOrderByEndpointCodeAsc(UUID mapId);
    List<Endpoint> findByZoneZoneId(UUID zoneId);
    @Query("""
            select e from Endpoint e where e.active = true and e.publicVisible = true
              and (:mapId is null or e.map.mapId = :mapId)
              and (:roomId is null or e.room.roomId = :roomId)
              and (:endpointType is null or e.endpointType = :endpointType)
              and (:accessible is null or e.accessible = :accessible)
              and (:q is null or lower(e.endpointName) like lower(concat('%', :q, '%'))
                   or lower(e.endpointCode) like lower(concat('%', :q, '%')))
            """)
    Page<Endpoint> searchPublic(@Param("mapId") UUID mapId, @Param("roomId") UUID roomId,
            @Param("endpointType") EndpointType endpointType, @Param("accessible") Boolean accessible,
            @Param("q") String q, Pageable pageable);
    java.util.Optional<Endpoint> findByEndpointIdAndActiveTrueAndPublicVisibleTrue(UUID id);
}
