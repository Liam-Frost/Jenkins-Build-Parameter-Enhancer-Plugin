package io.jenkins.plugins.bpe.designer;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.TransientActionFactory;
import org.jenkinsci.Symbol;

/** Adds "Design Build Form" to every job that can be built with parameters. */
@Extension
@Symbol("buildFormDesigner")
public class BuildFormDesignerActionFactory extends TransientActionFactory<Job> {

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public Collection<? extends Action> createFor(Job target) {
        if (!(target instanceof ParameterizedJobMixIn.ParameterizedJob)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new BuildFormDesignerAction(target));
    }
}
