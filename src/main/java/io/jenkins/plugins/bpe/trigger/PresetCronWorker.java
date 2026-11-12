package io.jenkins.plugins.bpe.trigger;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.PeriodicWork;
import hudson.triggers.Trigger;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Runs every minute to check preset cron schedules. Mimics the behavior of Jenkins' built-in Trigger.Cron.
 */
@Extension
public class PresetCronWorker extends PeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(PresetCronWorker.class.getName());
    private transient long lastMinute;

    @Override
    public long getRecurrencePeriod() {
        return MIN;
    }

    @Override
    protected void doRun() {
        long now = System.currentTimeMillis();
        long currentMinute = now / 60000;
        long previousMinute = lastMinute != 0 ? lastMinute : currentMinute - 1;
        lastMinute = currentMinute;

        // Check each minute we missed
        for (long m = previousMinute + 1; m <= currentMinute; m++) {
            Calendar cal = new GregorianCalendar();
            cal.setTimeInMillis(m * 60000);
            checkAllJobs(cal);
        }
    }

    private void checkAllJobs(Calendar cal) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        for (Job<?, ?> job : jenkins.allItems(Job.class)) {
            PresetCronTrigger trigger = getTrigger(job);
            if (trigger != null) {
                trigger.checkDue(cal);
            }
        }
    }

    private PresetCronTrigger getTrigger(Job<?, ?> job) {
        if (job instanceof jenkins.model.ParameterizedJobMixIn.ParameterizedJob) {
            jenkins.model.ParameterizedJobMixIn.ParameterizedJob<?, ?> pjob =
                    (jenkins.model.ParameterizedJobMixIn.ParameterizedJob<?, ?>) job;
            for (Trigger<?> t : pjob.getTriggers().values()) {
                if (t instanceof PresetCronTrigger) {
                    return (PresetCronTrigger) t;
                }
            }
        }
        return null;
    }
}
