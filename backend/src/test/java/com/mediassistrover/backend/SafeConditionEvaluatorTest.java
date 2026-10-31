package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.validation.SafeConditionEvaluator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SafeConditionEvaluatorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final SafeConditionEvaluator evaluator=new SafeConditionEvaluator();
    @Test void numericEqualityIsIndependentOfJsonNumberRepresentation() throws Exception {
        assertThat(evaluator.matches(json.readTree("{\"vars\":{\"count\":1}}"),json.readTree("{\"path\":\"/vars/count\",\"operator\":\"EQ\",\"value\":1.0}"))).isTrue();
    }
    @Test void missingFieldHasDefinedExistsAndNullSemantics() throws Exception {
        assertThat(evaluator.matches(json.readTree("{}"),json.readTree("{\"path\":\"/vars/missing\",\"operator\":\"EXISTS\"}"))).isFalse();
        assertThat(evaluator.matches(json.readTree("{}"),json.readTree("{\"path\":\"/vars/missing\",\"operator\":\"EQ\",\"value\":null}"))).isTrue();
    }
    @Test void refusesNumericComparisonOfMissingFieldRatherThanCoercingToZero() throws Exception {
        var condition=json.readTree("{\"path\":\"/vars/missing\",\"operator\":\"LT\",\"value\":1}");
        assertThatThrownBy(()->evaluator.matches(json.createObjectNode(),condition)).hasMessageContaining("numeric");
    }
    @Test void refusesExecutableOrUnknownExpressionSyntax() throws Exception {
        var condition=json.readTree("{\"path\":\"/vars/count\",\"operator\":\"EXEC\",\"value\":\"1+1\"}");
        assertThatThrownBy(()->evaluator.matches(json.createObjectNode(),condition)).hasMessageContaining("SAFE_EXPR");
        assertThatThrownBy(()->evaluator.matches(json.createObjectNode(),json.readTree("{\"path\":\"/vars/count\",\"operator\":\"EXISTS\",\"script\":\"arbitrary code\"}"))).hasMessageContaining("Unknown condition field");
    }
}
