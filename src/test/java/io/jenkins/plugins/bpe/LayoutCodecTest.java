package io.jenkins.plugins.bpe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bpe.model.FieldLayout;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.GroupLayout;
import io.jenkins.plugins.bpe.model.LayoutCodec;
import io.jenkins.plugins.bpe.model.LayoutValidationException;
import java.util.List;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

/** The codec must round-trip and must reject anything a client could get wrong or abuse. */
class LayoutCodecTest {

    private static FormLayout sample() {
        FormLayout l = new FormLayout();
        GroupLayout g = new GroupLayout("source", "源码版本");
        g.setDescription("Branches");
        g.setCollapsed(true);
        g.setColumns(2);
        g.setFields(List.of("A_BRANCH", "A_COMMIT"));
        l.getGroups().add(g);
        FieldLayout f = new FieldLayout();
        f.setLabel("Branch");
        f.setShortDescription("Which branch");
        f.setHelpText("line1\nline2");
        f.setWidth("full");
        f.setLinkWithNext(true);
        f.setShowHelpIcon(false);
        l.getFields().put("A_BRANCH", f);
        l.setOtherGroupTitle("Rest");
        return l;
    }

    @Test
    void roundTrips() throws Exception {
        FormLayout in = sample();
        FormLayout out = LayoutCodec.fromJson(LayoutCodec.toJson(in));
        assertEquals(1, out.getGroups().size());
        GroupLayout g = out.getGroups().get(0);
        assertEquals("source", g.getId());
        assertEquals("源码版本", g.getTitle());
        assertEquals("Branches", g.getDescription());
        assertTrue(g.isCollapsed());
        assertEquals(List.of("A_BRANCH", "A_COMMIT"), g.getFields());
        FieldLayout f = out.getField("A_BRANCH");
        assertEquals("Branch", f.getLabel());
        assertEquals("Which branch", f.getShortDescription());
        assertEquals("line1\nline2", f.getHelpText());
        assertEquals("full", f.getWidth());
        assertTrue(f.isLinkWithNext());
        assertFalse(f.isShowHelpIcon());
        assertEquals("Rest", out.getOtherGroupTitle());
    }

    @Test
    void roundTripsTheSubGroupPosition() throws Exception {
        FormLayout in = sample();
        GroupLayout sub = new GroupLayout("sub", "Sub");
        sub.setParentId("source");
        sub.setPosition(1);
        in.getGroups().add(sub);
        GroupLayout last = new GroupLayout("last", "Last");
        last.setParentId("source");
        in.getGroups().add(last);
        FormLayout out = LayoutCodec.fromJson(LayoutCodec.toJson(in));
        assertEquals(Integer.valueOf(1), out.getGroups().get(1).getPosition());
        assertEquals(null, out.getGroups().get(2).getPosition());
    }

    @Test
    void ignoresAPositionOnATopLevelGroupAndRejectsANegativeOne() {
        JSONObject top = JSONObject.fromObject("{'schemaVersion':1,'groups':[{'id':'a','title':'A','position':2}]}");
        assertEquals(
                null,
                assertDoesNotThrow(() -> LayoutCodec.fromJson(top))
                        .getGroups()
                        .get(0)
                        .getPosition());
        JSONObject negative = JSONObject.fromObject(
                "{'schemaVersion':1,'groups':[{'id':'a','title':'A'},{'id':'b','title':'B','parentId':'a','position':-1}]}");
        assertThrows(LayoutValidationException.class, () -> LayoutCodec.fromJson(negative));
    }

    @Test
    void rejectsAParameterPlacedTwice() {
        JSONObject o = JSONObject.fromObject(
                "{'schemaVersion':1,'groups':[{'id':'a','title':'A','fields':['X']},{'id':'b','title':'B','fields':['X']}]}");
        LayoutValidationException e = assertThrows(LayoutValidationException.class, () -> LayoutCodec.fromJson(o));
        assertTrue(e.getMessage().contains("more than once"), e.getMessage());
    }

    @Test
    void rejectsDuplicateAndInvalidGroupIds() {
        JSONObject dup =
                JSONObject.fromObject("{'schemaVersion':1,'groups':[{'id':'a','title':'A'},{'id':'a','title':'B'}]}");
        assertTrue(assertThrows(LayoutValidationException.class, () -> LayoutCodec.fromJson(dup))
                .getMessage()
                .contains("Duplicate group id"));

        JSONObject bad = JSONObject.fromObject("{'schemaVersion':1,'groups':[{'id':'a b/../c','title':'A'}]}");
        assertTrue(assertThrows(LayoutValidationException.class, () -> LayoutCodec.fromJson(bad))
                .getMessage()
                .contains("invalid"));
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        JSONObject o = JSONObject.fromObject("{'schemaVersion':99,'groups':[]}");
        assertTrue(assertThrows(LayoutValidationException.class, () -> LayoutCodec.fromJson(o))
                .getMessage()
                .contains("Unsupported schemaVersion"));
    }

    @Test
    void rejectsOverlongText() {
        String long401 = "x".repeat(401);
        JSONObject o = JSONObject.fromObject(
                "{'schemaVersion':1,'groups':[],'fields':{'X':{'shortDescription':'" + long401 + "'}}}");
        assertTrue(assertThrows(LayoutValidationException.class, () -> LayoutCodec.fromJson(o))
                .getMessage()
                .contains("longer than"));
    }

    @Test
    void ignoresClientSuppliedKnownParameters() throws Exception {
        JSONObject o = JSONObject.fromObject("{'schemaVersion':1,'groups':[],'knownParameters':{'X':'Y'}}");
        assertTrue(LayoutCodec.fromJson(o).getKnownParameters().isEmpty());
    }

    @Test
    void normalisesUnknownWidthsAndColumns() throws Exception {
        JSONObject o = JSONObject.fromObject(
                "{'schemaVersion':1,'groups':[{'id':'a','title':'A','columns':7}],'fields':{'X':{'width':'huge'}}}");
        FormLayout l = LayoutCodec.fromJson(o);
        assertEquals(2, l.getGroups().get(0).getColumns());
        assertEquals("auto", l.getField("X").getWidth());
    }
}
