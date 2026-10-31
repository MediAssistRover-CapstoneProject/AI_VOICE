package com.mediassistrover.backend.service.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.service.ServerStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

@Component @RequiredArgsConstructor
public class SetContextStepHandler implements ServerStepHandler {
    private final ObjectMapper objectMapper;
    @Override public boolean supports(JobStep step){return StepType.SET_CONTEXT.name().equals(step.getStepTypeSnapshot());}
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){
        Job job=step.getJobTask().getJob();ObjectNode before=job.getContextJson()!=null&&job.getContextJson().isObject()?((ObjectNode)job.getContextJson()).deepCopy():objectMapper.createObjectNode();
        ObjectNode after=before.deepCopy(), values=objectMapper.createObjectNode();ArrayNode changed=objectMapper.createArrayNode();Set<String> seen=new HashSet<>();JsonNode operations=inputs.path("operations");
        if(!operations.isArray()||operations.isEmpty())throw new BusinessRuleException("SET_CONTEXT requires operations");
        for(JsonNode op:operations){String path=op.path("path").asText();if(!path.matches("^/vars/[A-Za-z][A-Za-z0-9_]*$"))throw new BusinessRuleException("SET_CONTEXT may update only /vars/<identifier>");String key=path.substring(6);if(!seen.add(key))throw new BusinessRuleException("SET_CONTEXT cannot assign the same variable twice");JsonNode value=op.get("value");String action=op.path("operation").asText("SET");JsonNode assigned;
            if(job.getSchemaVersion()==3 && Set.of("mapping_session_id","artifact_id").contains(key))throw new BusinessRuleException("Mapping identities are allocated by BE and cannot be changed by SET_CONTEXT");
            if("INCREMENT".equals(action)){JsonNode current=before.get(key);if(current==null||!current.isNumber()||value==null||!value.isNumber())throw new BusinessRuleException("INCREMENT requires numeric existing and assigned values");assigned=DecimalNode.valueOf(new BigDecimal(current.asText()).add(new BigDecimal(value.asText())));}
            else if("SET".equals(action))assigned=value==null?NullNode.instance:value.deepCopy();else throw new BusinessRuleException("Unsupported context operation: "+action);
            after.set(key,assigned);values.set(key,assigned.deepCopy());changed.add(key);
        }
        job.setContextJson(after);ObjectNode result=objectMapper.createObjectNode();if(job.getSchemaVersion()==3) result.set("updated",values); else {result.set("changed_keys",changed);result.set("values",values);}return new ServerStepExecution(result,true);
    }
}
