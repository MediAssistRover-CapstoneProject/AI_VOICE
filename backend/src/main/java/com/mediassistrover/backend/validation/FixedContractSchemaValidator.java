package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mediassistrover.backend.exception.BusinessRuleException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Evaluator for the fixed, bundled contract vocabulary. No remote refs or arbitrary user schemas. */
final class FixedContractSchemaValidator {
    private final Map<String, JsonNode> documents = new LinkedHashMap<>();
    private static final Set<String> VOCABULARY = Set.of("$ref", "$id", "$schema", "$comment", "$defs", "title", "description",
            "default", "x-provenance", "type", "const", "enum", "not", "allOf", "anyOf", "oneOf", "if", "then", "else",
            "required", "properties", "additionalProperties", "minProperties", "propertyNames", "items", "minItems", "maxItems",
            "minLength", "maxLength", "pattern", "format", "minimum", "maximum", "exclusiveMinimum", "prefixItems", "uniqueItems", "contentEncoding");

    FixedContractSchemaValidator() {
        ObjectMapper mapper = new ObjectMapper();
        for (String name : new String[]{"common.schema.json", "pi-step-outputs.schema.json", "be-step-contracts.json", "staff-step-response.schema.json",
                "pi-step-inputs.schema.json", "mqtt-command.schema.json", "mqtt-pi-message.schema.json", "mqtt-be-response.schema.json", "mapping-live.schema.json"}) {
            try (var stream = getClass().getResourceAsStream("/contracts/mar-step-2/" + name)) {
                if (stream == null) throw new IllegalStateException("Missing bundled contract: " + name);
                documents.put(name, mapper.readTree(stream));
            } catch (IOException ex) { throw new IllegalStateException("Cannot read bundled contract: " + name, ex); }
        }
    }

    void validate(String document, String definition, JsonNode value) {
        check(documents.get(document).at("/$defs/" + definition), value, document, "$", 0);
    }
    void validateRoot(String document, JsonNode value) {
        check(documents.get(document),value,document,"$",0);
    }

    private void check(JsonNode schema, JsonNode n, String document, String path, int depth) {
        if (schema == null || schema.isMissingNode()) throw new IllegalStateException("Missing contract definition at " + path);
        if (depth > 64) invalid(path, "exceeds contract nesting limit");
        if (schema.isBoolean()) { if (!schema.booleanValue()) invalid(path, "is forbidden"); return; }
        schema.fieldNames().forEachRemaining(key -> { if (!VOCABULARY.contains(key)) throw new IllegalStateException("Unsupported fixed contract keyword: " + key); });
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").textValue();
            String[] parts = ref.split("#", 2);
            String target = parts[0].isEmpty() ? document : parts[0];
            if (!documents.containsKey(target) || parts.length != 2 || !parts[1].startsWith("/")) throw new IllegalStateException("Non-local contract ref: " + ref);
            check(documents.get(target).at(parts[1]), n, target, path, depth + 1);
        }
        if (schema.has("type")) {
            boolean matches = switch (schema.get("type").asText()) {
                case "object" -> n.isObject(); case "array" -> n.isArray(); case "string" -> n.isTextual();
                case "number" -> n.isNumber(); case "integer" -> n.isIntegralNumber();
                case "boolean" -> n.isBoolean(); case "null" -> n.isNull();
                default -> throw new IllegalStateException("Unsupported fixed contract type");
            };
            if (!matches) invalid(path, "must be " + schema.get("type").asText());
        }
        if (schema.has("const") && !equivalent(schema.get("const"), n)) invalid(path, "does not match contract constant");
        if (schema.has("enum")) {
            boolean match = false; for (JsonNode option : schema.get("enum")) match |= equivalent(option, n);
            if (!match) invalid(path, "has an unsupported value");
        }
        if (schema.has("not") && matches(schema.get("not"), n, document, path, depth)) invalid(path, "matches a forbidden shape");
        for (JsonNode child : schema.path("allOf")) check(child, n, document, path, depth + 1);
        for (String combinator : new String[]{"oneOf", "anyOf"}) if (schema.has(combinator)) {
            int count = 0;
            for (JsonNode child : schema.get(combinator)) if (matches(child, n, document, path, depth)) count++;
            if (count == 0 || "oneOf".equals(combinator) && count != 1) invalid(path, "does not match " + combinator + " contract variants");
        }
        if (schema.has("if")) {
            String branch = matches(schema.get("if"), n, document, path, depth) ? "then" : "else";
            if (schema.has(branch)) check(schema.get(branch), n, document, path, depth + 1);
        }
        if (n.isObject()) {
            for (JsonNode field : schema.path("required")) if (!n.has(field.asText())) invalid(path + "." + field.asText(), "is required");
            if (schema.has("minProperties") && n.size() < schema.get("minProperties").intValue()) invalid(path, "has too few fields");
            var names = n.fieldNames();
            while (names.hasNext()) {
                String field = names.next(); JsonNode child = schema.path("properties").get(field);
                if (child != null) check(child, n.get(field), document, path + "." + field, depth + 1);
                else if (schema.has("additionalProperties")) check(schema.get("additionalProperties"), n.get(field), document, path + "." + field, depth + 1);
                if (schema.has("propertyNames")) check(schema.get("propertyNames"), TextNode.valueOf(field), document, path + "." + field, depth + 1);
            }
        }
        if (n.isArray()) {
            if (schema.has("minItems") && n.size() < schema.get("minItems").intValue()) invalid(path, "has too few items");
            if (schema.has("maxItems") && n.size() > schema.get("maxItems").intValue()) invalid(path, "has too many items");
            int prefix=schema.path("prefixItems").size();
            for (int i=0;i<Math.min(prefix,n.size());i++) check(schema.path("prefixItems").get(i),n.get(i),document,path+"["+i+"]",depth+1);
            if (schema.has("items")) for (int i = prefix; i < n.size(); i++) check(schema.get("items"), n.get(i), document, path + "[" + i + "]", depth + 1);
            if (schema.path("uniqueItems").asBoolean()) for (int i=0;i<n.size();i++) for (int j=0;j<i;j++)
                if (equivalent(n.get(i),n.get(j))) invalid(path,"has duplicate items");
        }
        if (n.isTextual()) {
            if (schema.has("minLength") && n.textValue().codePointCount(0, n.textValue().length()) < schema.get("minLength").intValue()) invalid(path, "is too short");
            if (schema.has("maxLength") && n.textValue().codePointCount(0,n.textValue().length())>schema.get("maxLength").intValue()) invalid(path,"is too long");
            if (schema.has("pattern") && !java.util.regex.Pattern.compile(schema.get("pattern").asText()).matcher(n.asText()).find()) invalid(path, "has invalid format");
            if (schema.has("format")) try {
                switch (schema.get("format").asText()) {
                    case "uuid" -> { if (!UUID.fromString(n.asText()).toString().equalsIgnoreCase(n.asText())) throw new IllegalArgumentException(); }
                    case "date-time" -> java.time.OffsetDateTime.parse(n.asText());
                    default -> throw new IllegalStateException("Unsupported contract format");
                }
            } catch (IllegalArgumentException | java.time.DateTimeException ex) { invalid(path, "has invalid " + schema.get("format").asText() + " format"); }
        }
        if (n.isNumber()) {
            if (!Double.isFinite(n.doubleValue())) invalid(path, "must be finite");
            if (schema.has("minimum") && n.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0
                    || schema.has("maximum") && n.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0
                    || schema.has("exclusiveMinimum") && n.decimalValue().compareTo(schema.get("exclusiveMinimum").decimalValue()) <= 0)
                invalid(path, "is outside contract range");
        }
    }
    private boolean matches(JsonNode schema, JsonNode n, String doc, String path, int depth) {
        try { check(schema, n, doc, path, depth + 1); return true; }
        catch (BusinessRuleException ex) { return false; }
    }
    private boolean equivalent(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) == 0 : a.equals(b);
    }
    private void invalid(String path, String reason) { throw new BusinessRuleException("Invalid Step result schema 3: " + path + " " + reason); }
}
