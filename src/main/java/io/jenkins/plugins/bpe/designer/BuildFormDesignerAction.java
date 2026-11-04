package io.jenkins.plugins.bpe.designer;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.User;
import io.jenkins.plugins.bpe.BuildParameterEnhancerConfiguration;
import io.jenkins.plugins.bpe.FormDesignStore;
import io.jenkins.plugins.bpe.I18n;
import io.jenkins.plugins.bpe.autolayout.AutoLayoutGenerator;
import io.jenkins.plugins.bpe.autolayout.GroupingRule;
import io.jenkins.plugins.bpe.discovery.ChangeDetector;
import io.jenkins.plugins.bpe.discovery.DiscoveredParameter;
import io.jenkins.plugins.bpe.discovery.ParameterDiscovery;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.FormLayout;
import io.jenkins.plugins.bpe.model.LayoutCodec;
import io.jenkins.plugins.bpe.model.LayoutValidationException;
import io.jenkins.plugins.bpe.render.RenderModel;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

/**
 * "Design Build Form": the administrator UI and its JSON API, mounted at {@code <job>/build-parameter-enhancer/}.
 *
 * <p>Every entry point requires {@link Item#CONFIGURE} on the job. {@link #getTarget()} enforces it for the whole
 * URL space, and each web method checks again.
 */
public class BuildFormDesignerAction implements Action, StaplerProxy {
    private static final Logger LOGGER = Logger.getLogger(BuildFormDesignerAction.class.getName());
    public static final String URL_NAME = "build-parameter-enhancer";
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final Job<?, ?> job;

    public BuildFormDesignerAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    @Override
    public String getIconFileName() {
        if (!BuildParameterEnhancerConfiguration.isActive() || !job.hasPermission(Item.CONFIGURE)) {
            return null;
        }
        return "symbol-parameters";
    }

    @Override
    public String getDisplayName() {
        return Messages.BuildFormDesignerAction_DisplayName();
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @Override
    public Object getTarget() {
        job.checkPermission(Item.CONFIGURE);
        return this;
    }

    // ---------------------------------------------------------------- views

    public String getJobDisplayName() {
        return job.getFullDisplayName();
    }

    /** The designer's interface text in the page's language, read by the bundle from the mount element. */
    public String getI18nJson() {
        return I18n.strings("designer.").toString();
    }

    public boolean isDesignEnabled() {
        return FormDesignStore.load(job).isEnabled();
    }

    public boolean hasParameters() {
        return !ParameterDiscovery.definitions(job).isEmpty();
    }

    /** URL of the job's native build form, used by the "Open build page" link. */
    public String getBuildUrl() {
        return job.getUrl() + "build";
    }

    // ------------------------------------------------------------------ API

    /** Everything the designer needs: discovered parameters, draft, published layout and pending changes. */
    @GET
    public void doState(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        List<DiscoveredParameter> params = ParameterDiscovery.discover(job);
        FormDesign design = FormDesignStore.load(job);
        FormLayout working = design.getWorkingLayout();

        JSONObject o = new JSONObject();
        o.put("jobName", job.getFullName());
        o.put("jobDisplayName", job.getFullDisplayName());
        o.put("buildUrl", req.getContextPath() + "/" + job.getUrl() + "build");
        o.put("globallyEnabled", BuildParameterEnhancerConfiguration.isActive());
        o.put("enabled", design.isEnabled());
        o.put("hasDraft", design.getDraft() != null);
        o.put("draftSavedAt", design.getDraftSavedAt());
        o.put("draftSavedBy", design.getDraftSavedBy() == null ? "" : design.getDraftSavedBy());
        o.put("schemaVersion", FormLayout.SCHEMA_VERSION);
        o.put("otherGroupTitleDefault", RenderModel.defaultOtherTitle());

        JSONArray ps = new JSONArray();
        for (DiscoveredParameter p : params) {
            JSONObject po = new JSONObject();
            po.put("name", p.name());
            po.put("typeClass", p.typeClass());
            po.put("type", p.typeKey());
            po.put("typeLabel", p.typeLabel());
            po.put("description", p.descriptionText() == null ? "" : p.descriptionText());
            po.put("defaultValue", p.defaultValue() == null ? "" : p.defaultValue());
            po.put("hasDefault", p.defaultValue() != null);
            po.put("choices", p.choices() == null ? new JSONArray() : JSONArray.fromObject(p.choices()));
            po.put("sensitive", p.sensitive());
            po.put("managed", p.isManaged());
            ps.add(po);
        }
        o.put("parameters", ps);
        o.put("layout", working == null ? null : LayoutCodec.toJson(working));
        o.put(
                "publishedLayout",
                design.getPublishedLayout() == null ? null : LayoutCodec.toJson(design.getPublishedLayout()));
        if (design.getPublished() != null) {
            o.put("publishedAt", design.getPublished().getPublishedAt());
            o.put(
                    "publishedBy",
                    design.getPublished().getPublishedBy() == null
                            ? ""
                            : design.getPublished().getPublishedBy());
        }
        JSONArray history = new JSONArray();
        for (int i = 0; i < design.getHistory().size(); i++) {
            FormDesign.Revision r = design.getHistory().get(i);
            JSONObject ro = new JSONObject();
            ro.put("index", i);
            ro.put("publishedAt", r.getPublishedAt());
            ro.put("publishedBy", r.getPublishedBy() == null ? "" : r.getPublishedBy());
            ro.put(
                    "groups",
                    r.getLayout() == null ? 0 : r.getLayout().getGroups().size());
            history.add(ro);
        }
        o.put("history", history);

        ChangeDetector.Changes changes = ChangeDetector.detect(working, params);
        JSONObject co = new JSONObject();
        co.put("added", JSONArray.fromObject(changes.added()));
        co.put("removed", JSONArray.fromObject(changes.removed()));
        co.put("typeChanged", JSONArray.fromObject(changes.typeChanged()));
        co.put("unassigned", JSONArray.fromObject(changes.unassigned()));
        o.put("changes", co);
        o.put(
                "rules",
                JSONArray.fromObject(BuildParameterEnhancerConfiguration.get().getRulesJson()));
        writeJson(rsp, 200, o);
    }

    @POST
    public void doSaveDraft(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        FormLayout layout;
        try {
            layout = parseLayout(req);
        } catch (LayoutValidationException e) {
            writeErrors(rsp, e);
            return;
        }
        FormDesign design = FormDesignStore.load(job);
        design.saveDraft(layout, currentUser());
        FormDesignStore.save(job, design);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("savedAt", design.getDraftSavedAt());
        writeJson(rsp, 200, o);
    }

    @POST
    public void doPublish(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        FormLayout layout;
        try {
            layout = parseLayout(req);
        } catch (LayoutValidationException e) {
            writeErrors(rsp, e);
            return;
        }
        FormDesign design = FormDesignStore.load(job);
        design.publish(layout, currentUser());
        design.setEnabled(true);
        FormDesignStore.save(job, design);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("publishedAt", design.getPublished().getPublishedAt());
        writeJson(rsp, 200, o);
    }

    /** Throws away the draft; the designer falls back to the published layout. */
    @POST
    public void doDiscardDraft(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        FormDesign design = FormDesignStore.load(job);
        design.discardDraft();
        FormDesignStore.save(job, design);
        writeOk(rsp);
    }

    /** Restores the previously published layout. */
    @POST
    public void doRollback(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        FormDesign design = FormDesignStore.load(job);
        if (!design.rollback()) {
            writeError(rsp, 409, I18n.text("server.noHistory"));
            return;
        }
        FormDesignStore.save(job, design);
        writeOk(rsp);
    }

    @POST
    public void doSetEnabled(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        JSONObject body = readJson(req);
        boolean enabled = body.optBoolean("enabled", false);
        FormDesign design = FormDesignStore.load(job);
        design.setEnabled(enabled);
        FormDesignStore.save(job, design);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("enabled", design.isEnabled());
        writeJson(rsp, 200, o);
    }

    /**
     * Auto Generate Layout. Returns a suggested layout without saving anything, so the administrator can review
     * and adjust it before saving.
     */
    @POST
    public void doAutoGenerate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        JSONObject body = readJson(req);
        List<DiscoveredParameter> params = ParameterDiscovery.discover(job);
        List<GroupingRule> rules;
        try {
            Object raw = body.opt("rules");
            rules = raw instanceof JSONArray
                    ? GroupingRule.parseList((JSONArray) raw)
                    : BuildParameterEnhancerConfiguration.get().getRules();
        } catch (JSONException | IllegalArgumentException e) {
            writeError(rsp, 400, I18n.text("server.invalidRules", e.getMessage()));
            return;
        }
        boolean merge = "merge".equals(body.optString("mode", "replace"));
        FormLayout base = null;
        if (merge) {
            Object raw = body.opt("layout");
            if (raw instanceof JSONObject) {
                try {
                    base = LayoutCodec.fromJson((JSONObject) raw);
                } catch (LayoutValidationException e) {
                    writeErrors(rsp, e);
                    return;
                }
            }
            if (base == null) {
                base = FormDesignStore.load(job).getWorkingLayout();
            }
        }
        FormLayout generated = base != null
                ? AutoLayoutGenerator.merge(base, params, rules)
                : AutoLayoutGenerator.generate(params, rules);
        generated.setKnownParameters(ChangeDetector.snapshot(params));
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("layout", LayoutCodec.toJson(generated));
        writeJson(rsp, 200, o);
    }

    /**
     * Renders the real parameter controls with a candidate layout applied, for the designer's Preview pane. The
     * rendered form cannot start a build: its action points at {@link #doBuild} which always fails, and the
     * enhancer blocks submission in preview mode.
     */
    @POST
    public void doPreview(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        job.checkPermission(Item.CONFIGURE);
        FormLayout layout;
        String raw = req.getParameter("layout");
        try {
            layout = raw != null && !raw.isBlank()
                    ? LayoutCodec.fromJson(JSONObject.fromObject(raw))
                    : firstNonNull(FormDesignStore.load(job).getWorkingLayout(), new FormLayout());
        } catch (LayoutValidationException | JSONException e) {
            LOGGER.log(Level.FINE, "Invalid preview layout", e);
            layout = new FormLayout();
        }
        List<DiscoveredParameter> params = ParameterDiscovery.discover(job);
        JSONObject preview = RenderModel.build(layout, params, true);
        preview.put("i18n", I18n.strings("build."));
        String config = preview.toString();
        req.setAttribute("bpeConfig", config);
        req.getView(this, "previewPage.jelly").forward(req, rsp);
    }

    /** Sink for the preview form's action: a preview must never schedule a build. */
    public void doBuild(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        rsp.sendError(400, I18n.text("server.previewOnly"));
    }

    public ParametersDefinitionProperty getParametersDefinitionProperty() {
        return job.getProperty(ParametersDefinitionProperty.class);
    }

    // -------------------------------------------------------------- helpers

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    private static String currentUser() {
        User u = User.current();
        return u == null ? "unknown" : u.getId();
    }

    private FormLayout parseLayout(StaplerRequest2 req) throws IOException, LayoutValidationException {
        JSONObject body = readJson(req);
        Object raw = body.opt("layout");
        if (!(raw instanceof JSONObject)) {
            throw new LayoutValidationException(List.of("Request is missing the 'layout' object"));
        }
        FormLayout layout = LayoutCodec.fromJson((JSONObject) raw);
        // The parameter snapshot is always taken from the job, never from the client.
        layout.setKnownParameters(ChangeDetector.snapshot(ParameterDiscovery.discover(job)));
        return layout;
    }

    private static JSONObject readJson(StaplerRequest2 req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IOException("Request body is too large");
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

    private static void writeJson(StaplerResponse2 rsp, int status, JSONObject body) throws IOException {
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-store");
        rsp.getWriter().write(body.toString());
    }

    private static void writeOk(StaplerResponse2 rsp) throws IOException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        writeJson(rsp, 200, o);
    }

    private static void writeError(StaplerResponse2 rsp, int status, String message) throws IOException {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("message", message);
        writeJson(rsp, status, o);
    }

    private static void writeErrors(StaplerResponse2 rsp, LayoutValidationException e) throws IOException {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("message", I18n.text("server.layoutInvalid"));
        o.put("errors", JSONArray.fromObject(e.getErrors()));
        writeJson(rsp, 400, o);
    }
}
