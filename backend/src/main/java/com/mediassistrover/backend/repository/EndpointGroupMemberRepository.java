package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.EndpointGroupMember;
import com.mediassistrover.backend.entity.EndpointGroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EndpointGroupMemberRepository extends JpaRepository<EndpointGroupMember, EndpointGroupMemberId> {
    boolean existsByEndpointGroupEndpointGroupIdAndSequenceNo(UUID groupId, int sequenceNo);
    List<EndpointGroupMember> findByEndpointGroupEndpointGroupIdOrderBySequenceNo(UUID groupId);
    @Query("""
            select m from EndpointGroupMember m
            where m.endpointGroup.endpointGroupId = :groupId and m.endpointGroup.active = true
              and m.active = true and m.endpoint.active = true and m.endpoint.publicVisible = true
              and m.endpoint.map = m.endpointGroup.map
            """)
    Page<EndpointGroupMember> findPublicMembers(@Param("groupId") UUID groupId, Pageable pageable);
}
