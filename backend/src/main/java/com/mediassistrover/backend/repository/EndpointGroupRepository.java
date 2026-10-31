package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.EndpointGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EndpointGroupRepository extends JpaRepository<EndpointGroup, UUID> {
    boolean existsByMapMapIdAndGroupCodeIgnoreCase(UUID mapId, String groupCode);
    @Query("""
            select g from EndpointGroup g where g.active = true
              and (:mapId is null or g.map.mapId = :mapId)
              and (:q is null or lower(g.groupName) like lower(concat('%', :q, '%'))
                   or lower(g.groupCode) like lower(concat('%', :q, '%')))
              and exists (select m.id from EndpointGroupMember m
                          where m.endpointGroup = g and m.active = true
                            and m.endpoint.active = true and m.endpoint.publicVisible = true
                            and m.endpoint.map = g.map)
            """)
    Page<EndpointGroup> searchPublic(@Param("mapId") UUID mapId, @Param("q") String q, Pageable pageable);
    @Query("""
            select g from EndpointGroup g where g.endpointGroupId = :id and g.active = true
              and exists (select m.id from EndpointGroupMember m
                          where m.endpointGroup = g and m.active = true
                            and m.endpoint.active = true and m.endpoint.publicVisible = true
                            and m.endpoint.map = g.map)
            """)
    java.util.Optional<EndpointGroup> findPublicById(@Param("id") UUID id);
}
