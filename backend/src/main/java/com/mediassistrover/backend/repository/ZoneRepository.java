package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Zone;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.mediassistrover.backend.enums.ZoneType;

import java.util.UUID;

public interface ZoneRepository extends JpaRepository<Zone, UUID> {
    java.util.List<Zone> findByMapMapIdOrderByZoneCodeAsc(UUID mapId);
    boolean existsByMapMapIdAndZoneCodeIgnoreCase(UUID mapId, String zoneCode);
    Page<Zone> findByActiveTrueAndPublicVisibleTrue(Pageable pageable);
    @Query("""
            select z from Zone z where z.active = true and z.publicVisible = true
              and (:mapId is null or z.map.mapId = :mapId)
              and (:zoneType is null or z.zoneType = :zoneType)
              and (:q is null or lower(z.zoneName) like lower(concat('%', :q, '%'))
                   or lower(z.zoneCode) like lower(concat('%', :q, '%')))
            """)
    Page<Zone> searchPublic(@Param("mapId") UUID mapId, @Param("zoneType") ZoneType zoneType,
                            @Param("q") String q, Pageable pageable);
    java.util.Optional<Zone> findByZoneIdAndActiveTrueAndPublicVisibleTrue(UUID id);
}
