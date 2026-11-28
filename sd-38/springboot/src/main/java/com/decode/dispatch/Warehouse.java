package com.decode.dispatch;

import java.util.ArrayList;
import java.util.List;

/** SOLUTION, receiving side: remember the highest term, reject orders from an older leader. */
public class Warehouse {
    private long highestTerm = -1;
    private final List<String> packed = new ArrayList<>();

    public synchronized String accept(long term, String order) {
        if (term < highestTerm) {
            return order + " term " + term + " -> REJECTED (old leader, highest term " + highestTerm + ")";
        }
        highestTerm = term;
        packed.add(order);
        return order + " term " + term + " -> accepted";
    }

    public synchronized List<String> packed() { return List.copyOf(packed); }
}
