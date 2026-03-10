<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-auth-channel-webui" />

<div class="page-body">
<main class="main-section"><div class="content auth-channels-content">
    <h2 class="queue-title">Web UI Channel</h2>

    <section class="auth-channel-card" aria-labelledby="auth-channel-web-ui-title">
        <div class="auth-card-header">
            <h3 id="auth-channel-web-ui-title" class="auth-coin-name">Web UI Channel</h3>
            <span class="auth-chip <#if (webUi.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                ${(webUi.enabled!false)?string('enabled', 'disabled')}
            </span>
        </div>
        <div class="auth-kv-grid">
            <div class="auth-kv-item">
                <span class="auth-kv-label">password login</span>
                <span class="mono auth-kv-value">${(webUi.passwordProtectionEnabled!false)?string('enabled', 'disabled')}</span>
            </div>
            <div class="auth-kv-item">
                <span class="auth-kv-label">password file</span>
                <span class="mono auth-kv-value">${webUi.passwordFile!'-'}</span>
            </div>
        </div>
    </section>

    <section class="auth-card settings-section" data-section="web-ui" style="margin-top:1rem">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Settings</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/web-ui">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Password Protection</label>
                    <label class="settings-toggle"><input type="checkbox" name="password-protection.enabled"<#if settings.passwordProtectionEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Auto-Reload Templates</label>
                    <label class="settings-toggle"><input type="checkbox" name="auto-reload.enabled"<#if settings.autoReloadEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Assets Auto-Reload</label>
                    <label class="settings-toggle"><input type="checkbox" name="auto-reload.assets-enabled"<#if settings.assetsAutoReloadEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#if (webUi.passwordProtectionEnabled!false)>
        <section class="auth-channel-card" aria-labelledby="password-rotate-title">
            <div class="auth-card-header">
                <h3 id="password-rotate-title" class="auth-coin-name">Password Rotation</h3>
            </div>
            <#if revealedPassword?has_content>
                <p class="api-keys-info">Your new password has been generated. Copy it now &mdash; the cleartext password will not be shown again.</p>
                <div class="setup-password-row">
                    <code class="setup-password-display" id="rotated-password-value">${revealedPassword}</code>
                    <button class="setup-copy-button" type="button" onclick="navigator.clipboard.writeText(document.getElementById('rotated-password-value').textContent).then(function(){var b=this;b.textContent='Copied';setTimeout(function(){b.textContent='Copy'},1500)}.bind(this))">Copy</button>
                </div>
                <p class="api-keys-info">All other sessions have been invalidated. Use the new password to log in.</p>
                <a href="/auth_channels/web-ui" class="api-keys-back-link">&larr; Back to Web UI</a>
            <#else>
                <p class="api-keys-warn">Rotating the password will invalidate all active sessions. The new cleartext password will only be shown once.</p>
                <form method="post" action="/auth_channels/web-ui/rotate-password">
                    <button class="login-button" type="submit">Rotate Password</button>
                </form>
            </#if>
        </section>
    </#if>
</div></main>

<@m.settingsScript />
<@m.footer />
</div>
</@layout.page>
