package io.konkin.web.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Renders landing-related pages from FreeMarker templates.
 */
public class LandingPageService {

    private static final String LOGIN_TEMPLATE_NAME = "landing-login.ftl";

    private final Configuration freemarker;
    private final String templateName;
    private final String staticHostedPath;
    private final AtomicLong staticAssetsVersion;

    public LandingPageService(Path templateDirectory, String templateName, String staticHostedPath, boolean autoReloadEnabled) {
        this.templateName = templateName;
        this.staticHostedPath = staticHostedPath;
        this.staticAssetsVersion = new AtomicLong(1L);

        this.freemarker = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarker.setDefaultEncoding("UTF-8");
        this.freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.freemarker.setLogTemplateExceptions(false);
        this.freemarker.setWrapUncheckedExceptions(true);
        this.freemarker.setFallbackOnNullLoopVariable(false);
        this.freemarker.setTemplateUpdateDelayMilliseconds(autoReloadEnabled ? 500 : 60_000);

        try {
            this.freemarker.setDirectoryForTemplateLoading(templateDirectory.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to configure template directory: " + templateDirectory, e);
        }
    }

    public String renderLanding(boolean showLogout) {
        Map<String, Object> model = Map.of(
                "assetsPath", staticHostedPath,
                "assetsVersion", staticAssetsVersion.get(),
                "queuePath", "/api/v1/auth_queue",
                "githubPath", "#",
                "title", "KONKIN.io",
                "showLogout", showLogout,
                "activePage", "queue"
        );

        return renderTemplate(templateName, model);
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
