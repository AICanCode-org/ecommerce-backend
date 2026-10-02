package org.aicancode.shopflow.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.Principal;

/** Users created directly in the store for service-level tests: people.get("ada") -> Principal. */
public final class People {

    private People() {
    }

    public static Map<String, Principal> create(InMemoryStore store, MutableClock clock, String... nameRolePairs) {
        Map<String, Principal> people = new LinkedHashMap<>();
        for (int i = 0; i < nameRolePairs.length; i += 2) {
            String name = nameRolePairs[i];
            String role = nameRolePairs[i + 1];
            String display = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            long id = store.insertUser(name + "@shopflow.test", "x", display, role, clock.instant()).id();
            people.put(name, new Principal(id, role));
        }
        return people;
    }
}
