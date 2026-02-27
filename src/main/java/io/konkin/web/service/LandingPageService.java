package io.konkin.web.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Renders landing-related pages from FreeMarker templates.
 */
public class LandingPageService {

    private static final String MAIN_TEMPLATE_NAME = "landing.ftl";
    private static final String LOGIN_TEMPLATE_NAME = "landing-login.ftl";
    private static final String AUDIT_LOG_TEMPLATE_NAME = "landing-log.ftl";
    private static final String TELEGRAM_TEMPLATE_NAME = "landing-telegram.ftl";
    private static final String AUTH_DEFINITIONS_TEMPLATE_NAME = "landing-auth-definitions.ftl";

    private final Configuration freemarker;
    private final String staticHostedPath;
    private final AtomicLong staticAssetsVersion;
    private final boolean telegramEnabled;

    public LandingPageService(
            Path templateDirectory,
            String staticHostedPath,
            boolean autoReloadEnabled,
            boolean telegramEnabled
    ) {
        this.staticHostedPath = staticHostedPath;
        this.staticAssetsVersion = new AtomicLong(1L);
        this.telegramEnabled = telegramEnabled;

        this.freemarker = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarker.setDefaultEncoding("UTF-8");
        this.freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.freemarker.setLogTemplateExceptions(false);
        this.freemarker.setWrapUncheckedExceptions(true);
        this.freemarker.setFallbackOnNullLoopVariable(false);
        this.freemarker.setTemplateUpdateDelayMilliseconds(autoReloadEnabled ? 0 : 60_000);

        try {
            this.freemarker.setDirectoryForTemplateLoading(templateDirectory.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to configure template directory: " + templateDirectory, e);
        }
    }

    public String renderLanding(boolean showLogout, String activePage) {
        return renderLanding(showLogout, activePage, "", false, "", List.of(), List.of(), List.of(), Map.of(), List.of(), Map.of(), List.of(), Map.of());
    }

    public String renderLanding(
            boolean showLogout,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft,
            List<Map<String, String>> telegramChatRequests,
            List<Map<String, String>> telegramApprovedChats,
            List<Map<String, Object>> queueRows,
            Map<String, Object> queuePage,
            List<Map<String, Object>> auditRows,
            Map<String, Object> auditPage,
            List<Map<String, Object>> logQueueRows,
            Map<String, Object> logQueuePage
    ) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/log");
        model.put("telegramPath", "/telegram");
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("authDefinitionsPath", "/auth_definitions");
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", activePage);
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("telegramNotice", telegramNotice == null ? "" : telegramNotice);
        model.put("telegramNoticeError", telegramNoticeError);
        model.put("telegramDraft", telegramDraft == null ? "" : telegramDraft);
        model.put("telegramChatRequests", telegramChatRequests == null ? List.of() : telegramChatRequests);
        model.put("telegramApprovedChats", telegramApprovedChats == null ? List.of() : telegramApprovedChats);
        model.put("queueRows", queueRows == null ? List.of() : queueRows);
        model.put("queuePage", queuePage == null ? Map.of() : queuePage);
        model.put("auditRows", auditRows == null ? List.of() : auditRows);
        model.put("auditPage", auditPage == null ? Map.of() : auditPage);
        model.put("logQueueRows", logQueueRows == null ? List.of() : logQueueRows);
        model.put("logQueuePage", logQueuePage == null ? Map.of() : logQueuePage);

        String selectedTemplate;
        if ("log".equals(activePage)) {
            selectedTemplate = AUDIT_LOG_TEMPLATE_NAME;
        } else if ("telegram".equals(activePage)) {
            selectedTemplate = TELEGRAM_TEMPLATE_NAME;
        } else {
            selectedTemplate = MAIN_TEMPLATE_NAME;
        }

        return renderTemplate(selectedTemplate, model);
    }

    public String renderAuthDefinitions(boolean showLogout, Map<String, Object> authDefinitions) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/log");
        model.put("telegramPath", "/telegram");
        model.put("authDefinitionsPath", "/auth_definitions");
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "auth_definitions");
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("authDefinitions", authDefinitions == null ? Map.of() : authDefinitions);

        return renderTemplate(AUTH_DEFINITIONS_TEMPLATE_NAME, model);
    }

    public String renderLogin(boolean invalidPassword) {
        Map<String, Object> model = Map.of(
                "assetsPath", staticHostedPath,
                "assetsVersion", staticAssetsVersion.get(),
                "title", "KONKIN Login",
                "invalidPassword", invalidPassword
        );

        return renderTemplate(LOGIN_TEMPLATE_NAME, model);
    }

    public void clearTemplateCache() {
        freemarker.clearTemplateCache();
    }

    public void markStaticAssetsChanged() {
        staticAssetsVersion.incrementAndGet();
    }

    private String renderTemplate(String template, Map<String, Object> model) {
        try {
            Template pageTemplate = freemarker.getTemplate(template);
            StringWriter out = new StringWriter();
            pageTemplate.process(model, out);
            return out.toString();
        } catch (IOException | TemplateException e) {
            throw new IllegalStateException("Failed to render landing template: " + template, e);
        }
    }
}
