package io.jenkins.plugins.bpe.api;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.TransientActionFactory;

/** Mounts the preset API at {@code <job>/bpe-presets/} on every job that can be built with parameters. */
@Extension
public class PresetApiFactory extends TransientActionFactory<Job> {

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public Collection<? extends Action> createFor(Job target) {
        if (!(target instanceof ParameterizedJobMixIn.ParameterizedJob)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new PresetApiAction(target));
    }
}
