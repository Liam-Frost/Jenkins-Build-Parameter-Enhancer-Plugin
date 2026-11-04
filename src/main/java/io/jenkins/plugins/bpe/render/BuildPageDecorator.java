package io.jenkins.plugins.bpe.render;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.PageDecorator;
import io.jenkins.plugins.bpe.BuildParameterEnhancerConfiguration;
import io.jenkins.plugins.bpe.FormDesignStore;
import io.jenkins.plugins.bpe.I18n;
import io.jenkins.plugins.bpe.api.PresetApiAction;
import io.jenkins.plugins.bpe.discovery.ParameterDiscovery;
import io.jenkins.plugins.bpe.model.FormDesign;
import io.jenkins.plugins.bpe.model.FormLayout;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Injects the progressive-enhancement script into Jenkins' own "Build with Parameters" page.
 *
 * <p>Core owns that view ({@code hudson/model/ParametersDefinitionProperty/index.jelly}) and a plugin cannot
 * replace it, so the page is rendered by Jenkins as usual and the script regroups the existing parameter controls
 * on the client. The native controls, their ids, event handlers and the form's submit behaviour are preserved;
 * if the script does not run, the page stays exactly as Jenkins rendered it.
 *
 * <p>{@link #getBootData()} returns {@code null} on every page that is not an enabled job's build form, so no
 * work and no payload are added anywhere else.
 */
@Extension
public class BuildPageDecorator extends PageDecorator {
    private static final Logger LOGGER = Logger.getLogger(BuildPageDecorator.class.getName());

    /** Query parameter that forces the native page, e.g. {@code /job/x/build?bpe=classic}. */
    public static final String OPT_OUT = "bpe";

    public static final String OPT_OUT_VALUE = "classic";

    /** @return the JSON configuration for the enhancer, or {@code null} if this page must not be enhanced. */
    public String getBootData() {
        try {
            if (!BuildParameterEnhancerConfiguration.isActive()) {
                return null;
            }
            StaplerRequest2 req = Stapler.getCurrentRequest2();
            if (req == null || !"GET".equalsIgnoreCase(req.getMethod())) {
                return null;
            }
            if (OPT_OUT_VALUE.equals(req.getParameter(OPT_OUT))) {
                return null;
            }
            String uri = req.getRequestURI();
            if (uri == null || !(uri.endsWith("/build") || uri.endsWith("/buildWithParameters"))) {
                return null;
            }
            Job<?, ?> job = req.findAncestorObject(Job.class);
            if (job == null || !(job instanceof ParameterizedJobMixIn.ParameterizedJob)) {
                return null;
            }
            // The page itself is already permission-checked by core; this keeps the payload out of pages a user
            // may not act on at all.
            if (!job.hasPermission(Item.READ)) {
                return null;
            }
            FormDesign design = FormDesignStore.load(job);
            FormLayout layout = design.getPublishedLayout();
            if (!design.isEnabled() || layout == null) {
                return null;
            }
            var params = ParameterDiscovery.discover(job);
            if (params.isEmpty()) {
                return null;
            }
            net.sf.json.JSONObject result = RenderModel.build(layout, params, false);
            result.put("i18n", I18n.strings("build."));
            // Embed presets; best-effort — render works without them if this fails (e.g., in tests or on error).
            try {
                PresetApiAction api = new PresetApiAction(job);
                net.sf.json.JSONObject presetsResp = api.listPresetsForUser();
                result.put("presets", presetsResp.get("presets"));
                result.put("presetsUrl", req.getContextPath() + "/" + job.getUrl() + "bpe-presets");
                boolean authenticated = hudson.model.User.current() != null;
                result.put("canSavePresets", authenticated && job.hasPermission(Item.BUILD));
                result.put("canSaveGlobalPresets", authenticated && job.hasPermission(Item.CONFIGURE));
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.FINE, "Could not load presets for build page", e);
            }
            return result.toString();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not prepare the enhanced build form; using the native page", e);
            return null;
        }
    }

    /** Resource base of the plugin's static files, e.g. {@code /jenkins/static/<hash>/plugin/build-parameter-enhancer}. */
    public String getResourceBase() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String contextPath = req != null ? req.getContextPath() : "";
        return contextPath + hudson.Functions.getResourcePath() + "/plugin/build-parameter-enhancer";
    }
}
