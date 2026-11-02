package org.bitwisemadness.tensuraxaerointegration.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillGateTest {

    private static final String MAGIC_SENSE = "tensura:magic_sense";
    private static final String UNIVERSAL_PERCEPTION = "tensura:universal_perception";

    /** A player whose held, mastered and toggled skills are given independently. */
    private static SkillGate.SkillQuery player(Set<String> held, Set<String> mastered, Set<String> toggled) {
        return new SkillGate.SkillQuery() {
            @Override
            public boolean has(String skillId) {
                return held.contains(skillId);
            }

            @Override
            public boolean mastered(String skillId) {
                return mastered.contains(skillId);
            }

            @Override
            public boolean toggled(String skillId) {
                return toggled.contains(skillId);
            }
        };
    }

    private static SkillGate gate(SkillGate.Requirement requirement, String... skills) {
        return new SkillGate(true, requirement, List.of(skills));
    }

    @Test
    void a_disabled_gate_lets_everyone_through() {
        SkillGate.SkillQuery nobody = player(Set.of(), Set.of(), Set.of());
        assertTrue(SkillGate.disabled().allows(nobody));
        assertTrue(new SkillGate(false, SkillGate.Requirement.MASTERED, List.of(MAGIC_SENSE)).allows(nobody));
    }

    @Test
    void a_gate_that_names_no_skill_lets_everyone_through() {
        assertTrue(gate(SkillGate.Requirement.MASTERED).allows(player(Set.of(), Set.of(), Set.of())));
    }

    @Test
    void has_only_needs_the_skill_learned() {
        SkillGate query = gate(SkillGate.Requirement.HAS, MAGIC_SENSE);
        assertTrue(query.allows(player(Set.of(MAGIC_SENSE), Set.of(), Set.of())));
        assertFalse(query.allows(player(Set.of(), Set.of(), Set.of())));
    }

    @Test
    void mastered_needs_mastery_not_merely_the_skill() {
        SkillGate query = gate(SkillGate.Requirement.MASTERED, MAGIC_SENSE);
        assertTrue(query.allows(player(Set.of(MAGIC_SENSE), Set.of(MAGIC_SENSE), Set.of())));
        assertFalse(query.allows(player(Set.of(MAGIC_SENSE), Set.of(), Set.of())));
    }

    @Test
    void toggled_needs_the_toggle() {
        SkillGate query = gate(SkillGate.Requirement.TOGGLED, MAGIC_SENSE);
        assertTrue(query.allows(player(Set.of(MAGIC_SENSE), Set.of(MAGIC_SENSE), Set.of(MAGIC_SENSE))));
        assertFalse(query.allows(player(Set.of(MAGIC_SENSE), Set.of(MAGIC_SENSE), Set.of())));
    }

    @Test
    void any_listed_skill_satisfies_the_gate() {
        SkillGate query = gate(SkillGate.Requirement.MASTERED, MAGIC_SENSE, UNIVERSAL_PERCEPTION);
        assertTrue(query.allows(player(Set.of(), Set.of(UNIVERSAL_PERCEPTION), Set.of())));
        assertFalse(query.allows(player(Set.of(MAGIC_SENSE), Set.of(), Set.of())));
    }

    @Test
    void an_unknown_skill_id_is_simply_not_held() {
        SkillGate query = gate(SkillGate.Requirement.HAS, "tensura:does_not_exist");
        assertFalse(query.allows(player(Set.of(MAGIC_SENSE), Set.of(), Set.of())));
    }

    @Test
    void a_gate_needs_a_requirement() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillGate(true, null, List.of(MAGIC_SENSE)));
    }
}
