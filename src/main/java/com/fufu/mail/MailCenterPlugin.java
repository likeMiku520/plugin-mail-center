package com.fufu.mail;

import com.fufu.mail.model.MailSendLog;
import com.fufu.mail.model.MailTemplate;
import com.fufu.mail.model.MailTrigger;
import com.fufu.mail.service.LinkApplicationWatcher;
import com.fufu.mail.service.LinkTemplatesProvisioner;
import com.fufu.mail.service.MailLogService;
import com.fufu.mail.service.MailTriggerPoller;
import com.fufu.mail.service.SystemTemplates;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Plugin entry point. Registers the three custom models, starts the workers that keep
 * the record count bounded and the automatic rules running, and hooks the optional
 * links plugin so friend link decisions get answered.
 */
@Component
public class MailCenterPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final MailTriggerPoller triggerPoller;
    private final MailLogService logService;
    private final LinkApplicationWatcher linkWatcher;
    private final LinkTemplatesProvisioner linkProvisioner;
    private final SystemTemplates systemTemplates;

    public MailCenterPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                            MailTriggerPoller triggerPoller, MailLogService logService,
                            LinkApplicationWatcher linkWatcher,
                            LinkTemplatesProvisioner linkProvisioner,
                            SystemTemplates systemTemplates) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.triggerPoller = triggerPoller;
        this.logService = logService;
        this.linkWatcher = linkWatcher;
        this.linkProvisioner = linkProvisioner;
        this.systemTemplates = systemTemplates;
    }

    @Override
    public void start() {
        schemeManager.register(MailTemplate.class);
        schemeManager.register(MailSendLog.class);
        schemeManager.register(MailTrigger.class);
        logService.start();
        triggerPoller.start();
        systemTemplates.provision();
        linkProvisioner.provision();
        linkWatcher.start();
    }

    @Override
    public void stop() {
        linkWatcher.stop();
        triggerPoller.stop();
        logService.stop();
        schemeManager.unregister(Scheme.buildFromType(MailTrigger.class));
        schemeManager.unregister(Scheme.buildFromType(MailSendLog.class));
        schemeManager.unregister(Scheme.buildFromType(MailTemplate.class));
    }
}