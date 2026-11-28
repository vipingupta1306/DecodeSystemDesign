package com.decode.dispatch.problem;

import java.util.Collections;
import java.util.Set;

/**
 * PROBLEM (our own election, the "bully" idea): the highest ID I can see is the leader.
 * During a network split each side sees a different set -> two leaders (split brain).
 */
public final class HighestIdElection {
    private HighestIdElection() {}

    public static int leader(Set<Integer> serversICanSee) {
        return Collections.max(serversICanSee);
    }
}
