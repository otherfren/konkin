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
    <input type="checkbox" id="menu-toggle-auth-channel-webui" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-auth-channel-webui" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
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
        <span class="app-version">${appVersion}</span>
    </nav>
</aside>

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

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</div>
</@layout.page>
