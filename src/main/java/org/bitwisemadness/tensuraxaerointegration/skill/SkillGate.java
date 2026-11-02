package org.bitwisemadness.tensuraxaerointegration.skill;

import java.util.List;

/**
 * The rule for who may sense concentrations: one or more skills, and how far one
 * of them must be developed.
 *
 * <p>Any listed skill satisfies the gate. It is a policy value rather than a
 * check, so the same rule is built from server config, enforced on the server,
 * and sent to the client - both sides then reach the same verdict against the
 * same player.</p>
 *
 * <p>Player skills are synced to the owning client, so the client can evaluate
 * its own gate without a round trip; the server is still the enforcement point,
 * because it is the side that decides what is transmitted.</p>
 *
 * @param enabled     whether the gate applies at all
 * @param requirement how far a listed skill must be developed
 * @param skills      skill ids, any one of which grants access
 */
public record SkillGate(boolean enabled, Requirement requirement, List<String> skills) {

    /** How far a gated skill must be developed. */
    public enum Requirement {
        /** The skill is learned, whatever its mastery. */
        HAS,
        /** The skill is mastered. */
        MASTERED,
        /** The skill is currently toggled on. */
        TOGGLED
    }

    /** A player's skill state, supplied by whichever side is evaluating the gate. */
    public interface SkillQuery {

        boolean has(String skillId);

        boolean mastered(String skillId);

        boolean toggled(String skillId);
    }

    public SkillGate {
        if (requirement == null) {
            throw new IllegalArgumentException("a skill gate needs a requirement");
        }
        skills = List.copyOf(skills);
    }

    /** The gate that lets everyone through; the config default. */
    public static SkillGate disabled() {
        return new SkillGate(false, Requirement.MASTERED, List.of());
    }

    /**
     * Whether a player may sense under this gate.
     *
     * <p>An enabled gate with no skills lets everyone through: an empty list is a
     * misconfiguration, and locking every player out of a server over one would be
     * the worse failure.</p>
     */
    public boolean allows(SkillQuery query) {
        if (!this.enabled || this.skills.isEmpty()) {
            return true;
        }
        for (String skill : this.skills) {
            boolean satisfied = switch (this.requirement) {
                case HAS -> query.has(skill);
                case MASTERED -> query.mastered(skill);
                case TOGGLED -> query.toggled(skill);
            };
            if (satisfied) {
                return true;
            }
        }
        return false;
    }
}
