<#import "layout.ftl" as layout>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<aside class="sidebar">
    <a href="/" class="brand">
        <img src="${assetsPath}/img/logo_v2_small_trans.png?v=${assetsVersion}" alt="KONKIN logo" class="brand-logo">
    </a>
    <input type="checkbox" id="menu-toggle-api-keys" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-api-keys" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "history"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
        <#assign walletPages = enabledCoins?map(c -> "wallet_" + c)>
        <#assign isWalletSubActive = walletPages?seq_contains(activePage)>
        <#if activePage == "wallets"><span class="menu-active">wallets</span><#else><a href="${walletsPath}"<#if isWalletSubActive> class="menu-group-active"</#if>>wallets</a></#if>
        <#list enabledCoins as ec>
            <#if activePage == "wallet_" + ec><span class="menu-active menu-sub">${ec}</span><#else><a href="/wallets/${ec}" class="menu-sub">${ec}</a></#if>
        </#list>
        <#if activePage == "driver_agent"><span class="menu-active">driver agent</span><#else><a href="${driverAgentPath}">driver agent</a></#if>
        <#assign authChannelSubPages = ["auth_channel_webui", "auth_channel_api_keys", "auth_channel_telegram"]>
        <#assign isAuthChannelSubActive = authChannelSubPages?seq_contains(activePage)>
        <#if activePage == "auth_channels"><span class="menu-active">auth channels</span><#else><a href="${authChannelsPath}"<#if isAuthChannelSubActive> class="menu-group-active"</#if>>auth channels</a></#if>
        <#if activePage == "auth_channel_webui"><span class="menu-active menu-sub">web ui</span><#else><a href="/auth_channels/web-ui" class="menu-sub">web ui</a></#if>
        <#if activePage == "auth_channel_api_keys"><span class="menu-active menu-sub">api keys<#if restApiKeyMissing> <span class="menu-warn">&#9888;</span></#if></span><#else><a href="${apiKeysPath}" class="menu-sub">api keys<#if restApiKeyMissing> <span class="menu-warn">&#9888;</span></#if></a></#if>
        <#if telegramPageAvailable>
            <#if activePage == "auth_channel_telegram"><span class="menu-active menu-sub">telegram</span><#else><a href="${telegramPath}" class="menu-sub">telegram</a></#if>
        </#if>
        <#if showLogout>
            <form method="post" action="/logout" class="logout-form">
                <button type="submit" class="logout-btn">logout</button>
            </form>
        </#if>
    </nav>
</aside>

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
</div>
</@layout.page>
