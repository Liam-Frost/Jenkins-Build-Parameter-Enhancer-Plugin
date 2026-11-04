package io.jenkins.plugins.bpe.discovery;

import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares a saved layout against the parameters the job defines now. */
public final class ChangeDetector {
    private ChangeDetector() {}

    /**
     * @param added parameters that did not exist when the layout was saved
     * @param removed parameters referenced by the layout that no longer exist
     * @param typeChanged parameters whose definition class changed since the layout was saved
     * @param unassigned current parameters not placed in any group (rendered in the fallback group)
     */
    public record Changes(List<String> added, List<String> removed, List<String> typeChanged, List<String> unassigned) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && typeChanged.isEmpty();
        }
    }

    public static Changes detect(FormLayout layout, List<DiscoveredParameter> current) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> typeChanged = new ArrayList<>();
        List<String> unassigned = new ArrayList<>();
        Set<String> currentNames = new HashSet<>();
        for (DiscoveredParameter p : current) {
            currentNames.add(p.name());
        }
        if (layout == null) {
            for (DiscoveredParameter p : current) {
                unassigned.add(p.name());
            }
            return new Changes(added, removed, typeChanged, unassigned);
        }
        Map<String, String> known = layout.getKnownParameters();
        Set<String> placed = new HashSet<>();
        for (GroupLayout g : layout.getGroups()) {
            placed.addAll(g.getFields());
        }
        for (DiscoveredParameter p : current) {
            String knownType = known.get(p.name());
            if (knownType == null) {
                added.add(p.name());
            } else if (!knownType.equals(p.typeClass())) {
                typeChanged.add(p.name());
            }
            if (!placed.contains(p.name())) {
                unassigned.add(p.name());
            }
        }
        Set<String> referenced = new HashSet<>(known.keySet());
        referenced.addAll(placed);
        for (String name : referenced) {
            if (!currentNames.contains(name)) {
                removed.add(name);
            }
        }
        removed.sort(null);
        return new Changes(added, removed, typeChanged, unassigned);
    }

    public static Map<String, String> snapshot(List<DiscoveredParameter> current) {
        Map<String, String> m = new LinkedHashMap<>();
        for (DiscoveredParameter p : current) {
            m.put(p.name(), p.typeClass());
        }
        return m;
    }
}
