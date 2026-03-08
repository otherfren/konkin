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
        <#assign authChannelSubPages = ["auth_channel_webui"]>
        <#assign isAuthChannelSubActive = authChannelSubPages?seq_contains(activePage)>
        <#if activePage == "auth_channels"><span class="menu-active">auth channels</span><#else><a href="${authChannelsPath}"<#if isAuthChannelSubActive> class="menu-group-active"</#if>>auth channels</a></#if>
        <#if activePage == "auth_channel_webui"><span class="menu-active menu-sub">web ui</span><#else><a href="/auth_channels/web-ui" class="menu-sub">web ui</a></#if>
        <#if activePage == "api_keys"><span class="menu-active">api<#if restApiKeyMissing> <span class="menu-warn">&#9888;</span></#if></span><#else><a href="${apiKeysPath}">api<#if restApiKeyMissing> <span class="menu-warn">&#9888;</span></#if></a></#if>
        <#if telegramPageAvailable>
            <#if activePage == "telegram"><span class="menu-active">telegram</span><#else><a href="${telegramPath}">telegram</a></#if>
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
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</div>
</@layout.page>
