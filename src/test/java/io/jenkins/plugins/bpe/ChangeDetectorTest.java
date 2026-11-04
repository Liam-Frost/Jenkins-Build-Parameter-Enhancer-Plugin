package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bpe.discovery.ChangeDetector;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeDetectorTest {

    private static DiscoveredParameter p(String name, String type) {
        return new DiscoveredParameter(name, type, "string", "String", null, null, null, null, null, false);
    }

    private static FormLayout layoutWith(List<String> placed, List<DiscoveredParameter> known) {
        FormLayout l = new FormLayout();
        GroupLayout g = new GroupLayout("g", "G");
        g.setFields(placed);
        l.getGroups().add(g);
        l.setKnownParameters(ChangeDetector.snapshot(known));
        return l;
    }

    @Test
    void withoutALayoutEverythingIsUnassignedAndNothingIsNew() {
        var changes = ChangeDetector.detect(null, List.of(p("A", "T"), p("B", "T")));
        assertEquals(List.of("A", "B"), changes.unassigned());
        assertTrue(changes.added().isEmpty());
        assertTrue(changes.isEmpty());
    }

    @Test
    void detectsAddedRemovedAndTypeChanged() {
        List<DiscoveredParameter> before = List.of(p("A", "T1"), p("B", "T1"));
        FormLayout l = layoutWith(List.of("A", "B"), before);
        List<DiscoveredParameter> now = List.of(p("A", "T2"), p("C", "T1"));

        var changes = ChangeDetector.detect(l, now);
        assertEquals(List.of("C"), changes.added());
        assertEquals(List.of("B"), changes.removed());
        assertEquals(List.of("A"), changes.typeChanged());
        assertEquals(List.of("C"), changes.unassigned(), "the new parameter is not in any group yet");
        assertTrue(!changes.isEmpty());
    }

    @Test
    void aParameterOutsideEveryGroupIsUnassignedButNotNew() {
        List<DiscoveredParameter> params = List.of(p("A", "T"), p("B", "T"));
        FormLayout l = layoutWith(List.of("A"), params);
        var changes = ChangeDetector.detect(l, params);
        assertEquals(List.of("B"), changes.unassigned());
        assertTrue(changes.added().isEmpty());
        assertTrue(changes.isEmpty(), "being unassigned is not a pending change");
    }
}
