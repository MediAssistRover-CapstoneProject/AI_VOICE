package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.dto.alert.response.AlertResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AlertServiceImpl implements AlertService {
    private final AlertRepository alertRepository; private final AlertRecipientRepository recipientRepository;
    private final AccountRepository accountRepository; private final AuditService auditService;
    private final ObjectMapper objectMapper; private final Clock clock;
    private final RuntimeStepCompletionService completionService;
    private final JobRepository jobs; private final WorkflowEventService workflowEvents;
    private final StaffOperationalScopePolicy scopePolicy;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    @Override @Transactional public Alert createSystemAlert(String key,String type,AlertSeverity severity,String message,JsonNode data,EmergencyIncident incident,ServiceRequest request,JobStep step,Zone zone,boolean requireAck,AlertAckPolicy policy,Instant expiresAt){
        return createSystemAlert(key,type,severity,message,data,incident,request,step,zone,requireAck,policy,expiresAt,null);
    }
    @Override @Transactional public Alert createSystemAlert(String key,String type,AlertSeverity severity,String message,JsonNode data,EmergencyIncident incident,ServiceRequest request,JobStep step,Zone zone,boolean requireAck,AlertAckPolicy policy,Instant expiresAt,JsonNode requested){
        Alert found=alertRepository.findByOperationKey(key).orElse(null);if(found!=null)return found;Instant now=Instant.now(clock);
        List<Account> eligible=accountRepository.findByStatusAndRoleInOrderByAccountCodeAsc(AccountStatus.ACTIVE,List.of(Role.ADMIN,Role.STAFF));List<Account> recipients=resolveRecipients(eligible,requested);if(recipients.isEmpty())throw new BusinessRuleException("NOTIFY has no active eligible recipient");
        Alert alert=alertRepository.saveAndFlush(Alert.builder().incident(incident).jobStep(step).serviceRequest(request).zone(zone).notificationType(type).severity(severity).status(AlertStatus.OPEN).message(message).dataJson(data==null?objectMapper.createObjectNode():data.deepCopy()).requestedRecipientsJson(objectMapper.valueToTree(recipients.stream().map(Account::getAccountId).toList())).requireAck(requireAck).ackPolicy(policy).operationKey(key).createdAt(now).expiresAt(expiresAt).build());
        for(Account account:recipients)recipientRepository.save(AlertRecipient.builder().alert(alert).account(account).channel(AlertChannel.WEB).recipientAddressSnapshot(account.getUsername()).resolvedRoleSnapshot(account.getRole().name()).deliveryStatus(AlertDeliveryStatus.PENDING).createdAt(now).build());
        return alert;
    }
    @Override @Transactional public AlertResponse get(UUID id,AccountPrincipal actor){requireCurrentActor(actor);Alert alert=lockForMutation(id);scopePolicy.requireAlertAccess(alert,actor);markDelivered(alert,actor);return response(alert,actor);}
    @Override @Transactional public Page<AlertResponse> list(AlertStatus status,AlertSeverity severity,String type,Instant from,Instant to,AccountPrincipal actor,Pageable pageable){
        requireCurrentActor(actor);validateWindow(from,to);String normalized=normalize(type);
        if(actor.role()==Role.ADMIN)return alertRepository.search(status,severity,normalized,from,to,pageable).map(a->response(a,actor));
        List<Alert> scoped=alertRepository.search(status,severity,normalized,from,to,Pageable.unpaged(pageable.getSort())).getContent().stream()
                .filter(a->scopePolicy.canAccessAlert(a,actor)).toList();
        int start=(int)Math.min(pageable.getOffset(),scoped.size());int end=Math.min(start+pageable.getPageSize(),scoped.size());
        List<AlertResponse> content=scoped.subList(start,end).stream().map(a->{Alert current=lockForMutation(a.getAlertId());markDelivered(current,actor);return response(current,actor);}).toList();
        return new PageImpl<>(content,pageable,scoped.size());
    }
    @Override @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public AlertResponse acknowledge(UUID id,AccountPrincipal actor,RequestMetadata md){Account account=requireCurrentActor(actor);Alert alert=lockForMutation(id);Instant now=Instant.now(clock);if(!alert.isRequireAck())throw new BusinessRuleException("Alert does not require acknowledgement");if(alert.getExpiresAt()!=null&&!alert.getExpiresAt().isAfter(now))throw new BusinessRuleException("Alert acknowledgement window has expired");if(alert.getStatus()==AlertStatus.RESOLVED||alert.getStatus()==AlertStatus.CLOSED)throw new BusinessRuleException("Resolved or closed Alert cannot be acknowledged");Optional<AlertRecipient> assigned=recipientRepository.lockRecipient(id,actor.accountId());if(assigned.isEmpty()){if(actor.role()!=Role.STAFF)throw new org.springframework.security.access.AccessDeniedException("Alert is not assigned to this account or an authorized Staff delegate");scopePolicy.requireAlertAccess(alert,actor);}AlertRecipient recipient=assigned.orElseGet(()->recipientRepository.saveAndFlush(AlertRecipient.builder().alert(alert).account(account).channel(AlertChannel.WEB).recipientAddressSnapshot(account.getUsername()).resolvedRoleSnapshot(account.getRole().name()).deliveryStatus(AlertDeliveryStatus.DELIVERED).sentAt(now).deliveredAt(now).createdAt(now).build()));deliver(recipient,now);boolean newAck=recipient.getAcknowledgedAt()==null;if(newAck)recipient.setAcknowledgedAt(now);long acked=recipientRepository.countByAlertAlertIdAndAcknowledgedAtIsNotNull(id);long total=recipientRepository.countByAlertAlertId(id);boolean becameAcknowledged=alert.getStatus()!=AlertStatus.ACKNOWLEDGED&&(alert.getAckPolicy()==AlertAckPolicy.ANY||acked>=total);if(becameAcknowledged){alert.setStatus(AlertStatus.ACKNOWLEDGED);if(alert.getJobStep()!=null && alert.getJobStep().getJobTask().getJob().getSchemaVersion()==3)workflowEvents.publishAlertAcknowledged(alert,account,now,acked,total);else completeNotify(alert,true);}if(newAck)audit(actor,"ALERT_ACKNOWLEDGED",alert,md);return response(alert,actor);}
    @Override @Transactional public AlertResponse resolve(UUID id,AccountPrincipal actor,RequestMetadata md){requireCurrentActor(actor);Alert alert=lockForMutation(id);scopePolicy.requireAlertAccess(alert,actor);if(alert.getStatus()!=AlertStatus.ACKNOWLEDGED)throw new BusinessRuleException("Only ACKNOWLEDGED Alert can be resolved");if(alert.getIncident()!=null&&!Set.of(EmergencyIncidentStatus.RESOLVED,EmergencyIncidentStatus.CLOSED,EmergencyIncidentStatus.FALSE_ALARM).contains(alert.getIncident().getStatus()))throw new BusinessRuleException("Resolve the linked incident before resolving its Alert");alert.setStatus(AlertStatus.RESOLVED);audit(actor,"ALERT_RESOLVED",alert,md);return response(alert,actor);}
    @Override @Transactional public AlertResponse close(UUID id,AccountPrincipal actor,RequestMetadata md){requireCurrentActor(actor);Alert alert=lockForMutation(id);scopePolicy.requireAlertAccess(alert,actor);if(alert.getStatus()!=AlertStatus.RESOLVED)throw new BusinessRuleException("Only RESOLVED Alert can be closed");if(alert.getIncident()!=null&&!Set.of(EmergencyIncidentStatus.CLOSED,EmergencyIncidentStatus.FALSE_ALARM).contains(alert.getIncident().getStatus()))throw new BusinessRuleException("Close the linked incident before closing its Alert");alert.setStatus(AlertStatus.CLOSED);audit(actor,"ALERT_CLOSED",alert,md);return response(alert,actor);}
    private AlertResponse response(Alert a,AccountPrincipal actor){List<RecipientResponse> recipients=recipientRepository.findByAlertAlertIdOrderByCreatedAtAsc(a.getAlertId()).stream().filter(r->actor.role()==Role.ADMIN||r.getAccount().getAccountId().equals(actor.accountId())).map(r->new RecipientResponse(r.getAlertRecipientId(),r.getAccount().getAccountId(),r.getChannel(),r.getDeliveryStatus(),r.getSentAt(),r.getDeliveredAt(),r.getAcknowledgedAt(),r.getErrorCode())).toList();return new AlertResponse(a.getAlertId(),a.getIncident()==null?null:a.getIncident().getIncidentId(),a.getJobStep()==null?null:a.getJobStep().getJobStepId(),a.getServiceRequest()==null?null:a.getServiceRequest().getServiceRequestId(),a.getZone()==null?null:a.getZone().getZoneId(),a.getNotificationType(),a.getSeverity(),a.getStatus(),a.getMessage(),a.getDataJson(),a.isRequireAck(),a.getAckPolicy(),a.getCreatedAt(),a.getExpiresAt(),a.getRowVersion(),recipients);}
    private void markDelivered(Alert alert,AccountPrincipal actor){recipientRepository.findByAlertAlertIdAndAccountAccountId(alert.getAlertId(),actor.accountId()).ifPresent(r->{deliver(r,Instant.now(clock));if(!alert.isRequireAck())completeNotify(alert,false);});}
    private void deliver(AlertRecipient r,Instant now){if(r.getDeliveryStatus()==AlertDeliveryStatus.PENDING){r.setDeliveryStatus(AlertDeliveryStatus.DELIVERED);r.setSentAt(now);r.setDeliveredAt(now);}}
    private void completeNotify(Alert alert,boolean acked){if(alert.getJobStep()==null || alert.getJobStep().getJobTask().getJob().getSchemaVersion()==3)return;long delivered=recipientRepository.countByAlertAlertIdAndDeliveryStatus(alert.getAlertId(),AlertDeliveryStatus.DELIVERED);if(delivered==0)return;var result=objectMapper.createObjectNode().put("notification_id",alert.getAlertId().toString()).put("delivered",true).put("acked",acked);completionService.complete(alert.getJobStep(),result,null,JobEventProducerType.SERVER,acked?"NOTIFY_ACK_COMPLETED":"NOTIFY_DELIVERED",null);}
    private List<Account> resolveRecipients(List<Account> eligible,JsonNode requested){if(requested==null||!requested.isArray()||requested.isEmpty())return eligible;Set<String> selectors=new HashSet<>();requested.forEach(n->{if(n.isTextual())selectors.add(n.asText());});return eligible.stream().filter(a->selectors.contains(a.getAccountId().toString())||selectors.contains(a.getRole().name())).toList();}
    private void validateWindow(Instant from,Instant to){if(from!=null&&to!=null&&!from.isBefore(to))throw new BusinessRuleException("from must be before to");}
    private String normalize(String value){return value==null||value.isBlank()?null:value.trim();}
    private void audit(AccountPrincipal p,String action,Alert a,RequestMetadata md){Account actor=accountRepository.findById(p.accountId()).orElseThrow();auditService.record(actor,action,"Alert",a.getAlertId(),null,Map.of("status",a.getStatus().name()),md);}
    private Alert lockForMutation(UUID id){
        alertRepository.findJobId(id).ifPresent(jobId->jobs.lockById(jobId).orElseThrow());
        Alert alert=alertRepository.lockById(id).orElseThrow(()->new ResourceNotFoundException("Alert not found: "+id));
        em.refresh(alert,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);return alert;
    }
    private Account requireCurrentActor(AccountPrincipal actor){
        Account account=accountRepository.findById(actor.accountId()).orElseThrow(()->new org.springframework.security.access.AccessDeniedException("Account unavailable"));
        if(account.getStatus()!=AccountStatus.ACTIVE || account.getRole()!=actor.role() || account.getTokenVersion()!=actor.tokenVersion()
                || !Set.of(Role.ADMIN,Role.STAFF).contains(account.getRole()))throw new org.springframework.security.access.AccessDeniedException("Alert requires an active STAFF/ADMIN account");
        return account;
    }
    private Alert alert(UUID id){return alertRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Alert not found: "+id));}
}
