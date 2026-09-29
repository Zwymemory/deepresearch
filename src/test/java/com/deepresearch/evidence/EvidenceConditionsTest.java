package com.deepresearch.evidence;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceConditionsTest {
    @Test void missingUniqueAndAmbiguousDeclarationsRemainDistinct() {
        assertThat(EvidenceService.conditionDeclaration("Limit: 20").state()).isEqualTo(EvidenceService.ConditionState.MISSING);
        var unique = EvidenceService.conditionDeclaration("Document conditions: mode=legacy\n\nLimit: 20");
        assertThat(unique.state()).isEqualTo(EvidenceService.ConditionState.DECLARED);
        assertThat(unique.values()).containsExactly("mode=legacy");
        var conflict = "Document conditions: mode=legacy\nDocument conditions: mode=general\nLimit: 20";
        assertThat(EvidenceService.conditionDeclaration(conflict).state()).isEqualTo(EvidenceService.ConditionState.AMBIGUOUS);
        assertThat(EvidenceService.declaredConditions(conflict)).isNotEqualTo(EvidenceService.declaredConditions("Limit: 20"));
    }
    @Test void repeatedSameDeclarationDoesNotInventConflictButEmptyDeclarationIsAmbiguous() {
        assertThat(EvidenceService.conditionDeclaration("Document conditions: mode=legacy\nDocument conditions: mode=legacy").state())
                .isEqualTo(EvidenceService.ConditionState.DECLARED);
        assertThat(EvidenceService.conditionDeclaration("Document conditions: \nLimit: 20").state())
                .isEqualTo(EvidenceService.ConditionState.AMBIGUOUS);
        assertThat(EvidenceService.conditionDeclaration("Document conditions: " + "a".repeat(1001)).state())
                .isEqualTo(EvidenceService.ConditionState.AMBIGUOUS);
    }
    @Test void aUniqueFreeFormDeclarationIsRetainedWithoutInventingCategoricalExclusion() {
        assertThat(EvidenceService.declaredConditions("Document conditions: General usage\nLimit: 20"))
                .isEqualTo(List.of("General usage"));
    }
}
