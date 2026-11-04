package io.jenkins.plugins.bpe.api;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import io.jenkins.plugins.bpe.FormDesignStore;
import io.jenkins.plugins.bpe.I18n;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.discovery.ParameterDiscovery;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.ParameterPreset;
import io.jenkins.plugins.bpe.user.ParameterPresetUserProperty;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

/**
 * Manages parameter presets for a specific job. Mounted at {@code <job>/bpe-presets/} by {@link PresetApiFactory}.
 * Hidden action: has no icon or display name, only an API.
 */
public class PresetApiAction implements Action {
    private static final int MAX_BODY = 256 * 1024;
    private static final int MAX_PRESETS = 100;
    private static final int MAX_NAME = 120;
    private static final int MAX_DESC = 300;

    private final Job<?, ?> job;

    public PresetApiAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "bpe-presets";
    }

    /** Lists all presets visible to the current user: global + personal. */
    @GET
    public void doList(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.READ);
        writeJson(rsp, 200, listPresetsForUser());
    }

    @POST
    public void doSave(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JSONObject body = readJson(req);
        boolean isGlobal = body.optBoolean("isGlobal", false);
        if (isGlobal) {
            job.checkPermission(Item.CONFIGURE);
        } else {
            job.checkPermission(Item.BUILD);
        }

        String name = body.optString("name", "").trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            writeError(rsp, 400, I18n.text("server.preset.nameRequired", MAX_NAME));
            return;
        }

        String description = body.optString("description", "").trim();
        if (description.length() > MAX_DESC) {
            writeError(rsp, 400, I18n.text("server.preset.descriptionTooLong", MAX_DESC));
            return;
        }

        JSONObject valuesJson = body.optJSONObject("values");
        Map<String, String> values = sanitizeValues(valuesJson);
        if (values.isEmpty()) {
            writeError(rsp, 400, I18n.text("server.preset.noValues"));
            return;
        }

        ParameterPreset preset = new ParameterPreset();
        preset.setId(UUID.randomUUID().toString());
        preset.setName(name);
        preset.setDescription(description.isEmpty() ? null : description);
        preset.setValues(values);
        preset.setCreatedBy(currentUserId());
        long now = System.currentTimeMillis();
        preset.setCreatedAt(now);
        preset.setUpdatedAt(now);

        if (isGlobal) {
            FormDesign design = FormDesignStore.load(job);
            if (design.getGlobalPresets().size() >= MAX_PRESETS) {
                writeError(rsp, 400, I18n.text("server.preset.tooManyGlobal", MAX_PRESETS));
                return;
            }
            design.getGlobalPresets().add(preset);
            FormDesignStore.save(job, design);
        } else {
            User user = User.current();
            if (user == null) {
                writeError(rsp, 403, I18n.text("server.preset.loginRequired"));
                return;
            }
            ParameterPresetUserProperty prop = ParameterPresetUserProperty.of(user);
            synchronized (prop) {
                List<ParameterPreset> list = prop.presetsFor(job.getFullName());
                if (list.size() >= MAX_PRESETS) {
                    writeError(rsp, 400, I18n.text("server.preset.tooManyPersonal", MAX_PRESETS));
                    return;
                }
                list.add(preset);
            }
            user.save();
        }

        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("preset", presetToJson(preset, isGlobal));
        writeJson(rsp, 200, resp);
    }

    @POST
    public void doUpdate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JSONObject body = readJson(req);
        String presetId = body.optString("presetId");
        boolean isGlobal = body.optBoolean("isGlobal", false);

        if (isGlobal) {
            job.checkPermission(Item.CONFIGURE);
            FormDesign design = FormDesignStore.load(job);
            ParameterPreset preset = findById(design.getGlobalPresets(), presetId);
            if (preset == null) {
                writeError(rsp, 404, I18n.text("server.preset.notFound"));
                return;
            }
            applyUpdates(preset, body);
            FormDesignStore.save(job, design);
        } else {
            job.checkPermission(Item.BUILD);
            User user = User.current();
            if (user == null) {
                writeError(rsp, 403, I18n.text("server.preset.loginRequired"));
                return;
            }
            ParameterPresetUserProperty prop = ParameterPresetUserProperty.of(user);
            synchronized (prop) {
                ParameterPreset preset = findById(prop.presetsFor(job.getFullName()), presetId);
                if (preset == null) {
                    writeError(rsp, 404, I18n.text("server.preset.notFound"));
                    return;
                }
                applyUpdates(preset, body);
            }
            user.save();
        }

        writeOk(rsp);
    }

    @POST
    public void doDelete(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JSONObject body = readJson(req);
        String presetId = body.optString("presetId");
        boolean isGlobal = body.optBoolean("isGlobal", false);

        if (isGlobal) {
            job.checkPermission(Item.CONFIGURE);
            FormDesign design = FormDesignStore.load(job);
            boolean removed = design.getGlobalPresets().removeIf(p -> presetId.equals(p.getId()));
            if (!removed) {
                writeError(rsp, 404, I18n.text("server.preset.notFound"));
                return;
            }
            FormDesignStore.save(job, design);
        } else {
            job.checkPermission(Item.BUILD);
            User user = User.current();
            if (user == null) {
                writeError(rsp, 403, I18n.text("server.preset.loginRequired"));
                return;
            }
            ParameterPresetUserProperty prop = ParameterPresetUserProperty.of(user);
            synchronized (prop) {
                boolean removed = prop.presetsFor(job.getFullName()).removeIf(p -> presetId.equals(p.getId()));
                if (!removed) {
                    writeError(rsp, 404, I18n.text("server.preset.notFound"));
                    return;
                }
                prop.prune(job.getFullName());
            }
            user.save();
        }

        writeOk(rsp);
    }

    /** @return a JSON object with ok=true and a presets array. */
    public JSONObject listPresetsForUser() {
        List<JSONObject> list = new ArrayList<>();
        FormDesign design = FormDesignStore.load(job);
        boolean canSaveGlobal = job.hasPermission(Item.CONFIGURE);
        for (ParameterPreset p : design.getGlobalPresets()) {
            JSONObject o = presetToJson(p, true);
            o.put("canEdit", canSaveGlobal);
            list.add(o);
        }
        User user = User.current();
        // getProperty, not of(): listing runs on every build page view and must never write the user's config.
        ParameterPresetUserProperty prop = user == null ? null : user.getProperty(ParameterPresetUserProperty.class);
        if (prop != null) {
            for (ParameterPreset p : prop.snapshot(job.getFullName())) {
                JSONObject o = presetToJson(p, false);
                o.put("canEdit", true);
                list.add(o);
            }
        }
        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("presets", JSONArray.fromObject(list));
        resp.put("canSavePersonal", job.hasPermission(Item.BUILD));
        resp.put("canSaveGlobal", canSaveGlobal);
        return resp;
    }

    private void applyUpdates(ParameterPreset preset, JSONObject body) {
        if (body.has("name")) {
            String n = body.optString("name", "").trim();
            if (!n.isEmpty() && n.length() <= MAX_NAME) {
                preset.setName(n);
            }
        }
        if (body.has("description")) {
            String d = body.optString("description", "").trim();
            if (d.length() <= MAX_DESC) {
                preset.setDescription(d.isEmpty() ? null : d);
            }
        }
        if (body.has("values")) {
            Map<String, String> values = sanitizeValues(body.optJSONObject("values"));
            if (!values.isEmpty()) {
                preset.setValues(values);
            }
        }
        preset.setUpdatedAt(System.currentTimeMillis());
    }

    private Map<String, String> sanitizeValues(JSONObject valuesJson) {
        if (valuesJson == null) {
            return Map.of();
        }
        List<DiscoveredParameter> params = ParameterDiscovery.discover(job);
        Set<String> allowed = new HashSet<>();
        for (DiscoveredParameter p : params) {
            if (!p.sensitive()) {
                allowed.add(p.name());
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Object key : valuesJson.keySet()) {
            String pname = String.valueOf(key);
            if (allowed.contains(pname)) {
                values.put(pname, valuesJson.optString(pname, ""));
            }
        }
        return values;
    }

    private ParameterPreset findById(List<ParameterPreset> list, String id) {
        for (ParameterPreset p : list) {
            if (id.equals(p.getId())) {
                return p;
            }
        }
        return null;
    }

    private JSONObject presetToJson(ParameterPreset p, boolean isGlobal) {
        JSONObject o = new JSONObject();
        o.put("id", p.getId());
        o.put("name", p.getName());
        o.put("description", p.getDescription() == null ? "" : p.getDescription());
        JSONObject values = new JSONObject();
        values.putAll(p.getValues());
        o.put("values", values);
        o.put("createdBy", p.getCreatedBy() == null ? "" : p.getCreatedBy());
        o.put("createdAt", p.getCreatedAt());
        o.put("updatedAt", p.getUpdatedAt());
        o.put("isGlobal", isGlobal);
        return o;
    }

    private String currentUserId() {
        User u = User.current();
        return u == null ? "anonymous" : u.getId();
    }

    private JSONObject readJson(StaplerRequest2 req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IOException("Request body too large");
            }
            String s = new String(bytes, StandardCharsets.UTF_8).strip();
            if (s.isEmpty()) {
                return new JSONObject();
            }
            // Throws JSONException for anything that is not a JSON object, such as an array or a number.
            return JSONObject.fromObject(s);
        } catch (JSONException e) {
            throw new IOException("Request body is not valid JSON", e);
        }
    }

    private void writeOk(StaplerResponse2 rsp) throws IOException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        writeJson(rsp, 200, o);
    }

    private void writeError(StaplerResponse2 rsp, int status, String message) throws IOException {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("message", message);
        writeJson(rsp, status, o);
    }

    private void writeJson(StaplerResponse2 rsp, int status, JSONObject body) throws IOException {
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-store");
        rsp.getWriter().write(body.toString());
    }
}
