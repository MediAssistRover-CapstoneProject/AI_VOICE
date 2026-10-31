package com.mediassistrover.backend.validation;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

@Component
public class MqttContractValidator {
    private final FixedContractSchemaValidator schemas=new FixedContractSchemaValidator();
    public void live(JsonNode node) { schemas.validateRoot("mapping-live.schema.json",node); }
    public void inbound(JsonNode node) { schemas.validateRoot("mqtt-pi-message.schema.json",node); }
    public void command(JsonNode node) {
        schemas.validateRoot("mqtt-command.schema.json",node);
        rejectUnresolvedReferences(node,"$");
    }
    public void response(JsonNode node) { schemas.validateRoot("mqtt-be-response.schema.json",node); }

    private void rejectUnresolvedReferences(JsonNode node,String path) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                throw new BusinessRuleException("Invalid MQTT command: unresolved reference at " + path + ".$ref");
            }
            node.fields().forEachRemaining(field -> rejectUnresolvedReferences(field.getValue(),path+"."+field.getKey()));
        } else if (node.isArray()) {
            for (int i=0;i<node.size();i++) rejectUnresolvedReferences(node.get(i),path+"["+i+"]");
        }
    }
}
