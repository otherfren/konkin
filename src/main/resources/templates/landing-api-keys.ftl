<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-api-keys" />

<div class="page-body">
<main class="main-section"><div class="content auth-channels-content">
    <h2 class="queue-title">REST API Channel</h2>

    <section class="auth-channel-card" aria-labelledby="auth-channel-rest-api-title">
        <div class="auth-card-header">
            <h3 id="auth-channel-rest-api-title" class="auth-coin-name">REST API Channel</h3>
            <span class="auth-chip <#if (restApi.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                ${(restApi.enabled!false)?string('enabled', 'disabled')}
            </span>
        </div>
        <div class="auth-kv-grid">
            <div class="auth-kv-item">
                <span class="auth-kv-label">health endpoint</span>
                <span class="mono auth-kv-value">${restApi.healthPath!'-'}</span>
            </div>
            <div class="auth-kv-item">
                <span class="auth-kv-label">api-key header</span>
                <span class="mono auth-kv-value">${restApi.apiKeyHeader!'-'}</span>
            </div>
            <div class="auth-kv-item">
                <span class="auth-kv-label">protected scope</span>
                <span class="mono auth-kv-value">${restApi.protectedScope!'-'}</span>
            </div>
            <div class="auth-kv-item">
                <span class="auth-kv-label">api-key protection</span>
                <span class="mono auth-kv-value">${(restApi.apiKeyProtectionEnabled!false)?string('enabled', 'disabled')}</span>
            </div>
            <div class="auth-kv-item">
                <span class="auth-kv-label">secret file</span>
                <span class="mono auth-kv-value">${restApi.secretFile!'-'}</span>
            </div>
        </div>
    </section>

    <section class="auth-card settings-section" data-section="rest-api" style="margin-top:1rem">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Settings</h3>
            <span class="settings-expand-hint">click to expand</span><span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/rest-api">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if settings.restApiEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <section class="auth-channel-card" aria-labelledby="api-key-mgmt-title" style="text-align: center;">
        <div class="auth-card-header">
            <h3 id="api-key-mgmt-title" class="auth-coin-name">API Key Management</h3>
        </div>
        <#if !restApiEnabled>
            <p class="api-keys-info">The REST API is disabled. Enable it in <code>config.toml</code> under <code>[rest-api]</code> with <code>enabled = true</code>.</p>
        <#elseif revealedApiKey?has_content>
            <p class="api-keys-info">Your API key has been generated. Copy it now &mdash; the cleartext key will not be shown again.</p>
            <p class="api-keys-info">Key hash stored in <code>${secretFilePath}</code>.</p>
            <div class="setup-password-row">
                <code class="setup-password-display" id="api-key-value">${revealedApiKey}</code>
                <button class="setup-copy-button" type="button" onclick="navigator.clipboard.writeText(document.getElementById('api-key-value').textContent).then(function(){var b=this;b.textContent='Copied';setTimeout(function(){b.textContent='Copy'},1500)}.bind(this))">Copy</button>
            </div>
            <a href="${apiKeysPath}" class="api-keys-back-link">&larr; Back to API Keys</a>
        <#elseif !hasApiKey>
            <p class="api-keys-info">No API key has been configured yet. The key will be stored in <code>${secretFilePath}</code>.</p>
            <p class="api-keys-warn">The cleartext key will only be shown once after creation. Make sure to copy it.</p>
            <form method="post" action="/auth_channels/api_keys/rotate">
                <button class="login-button" type="submit">Create API Key</button>
            </form>
        <#else>
            <p class="api-keys-info">An API key is configured. Secret file: <code>${secretFilePath}</code>.</p>
            <p class="api-keys-warn">Rotating the key will invalidate the current one. The new cleartext key will only be shown once.</p>
            <form method="post" action="/auth_channels/api_keys/rotate">
                <button class="login-button" type="submit">Rotate API Key</button>
            </form>
        </#if>
    </section>
</div></main>

<@m.settingsScript />
<@m.footer />
</div>
</@layout.page>
