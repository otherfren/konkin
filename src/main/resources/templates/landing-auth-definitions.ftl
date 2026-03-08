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
    <input type="checkbox" id="menu-toggle-wallets" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-wallets" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
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
<main class="main-section"><div class="content auth-definitions-content">
    <h2 class="queue-title">Wallets</h2>
    <p class="auth-definitions-subtitle">Overview of configured wallets.</p>

    <#assign coins = (wallets.coins![])>
    <#list coins as coin>
        <#assign channels = (coin.channels!{})>
        <section class="auth-card<#if !(coin.enabled!false)> auth-card-disabled</#if>" aria-labelledby="auth-coin-${coin?index}">
            <div class="auth-card-header">
                <h3 id="auth-coin-${coin?index}" class="auth-coin-name auth-coin-title">
                    <#if (coin.coinIconName!'')?has_content>
                        <img
                            class="coin-icon auth-coin-icon"
                            src="${assetsPath}/img/${coin.coinIconName}.svg?v=${assetsVersion}"
                            alt="${coin.coin!'coin'} icon"
                            title="${coin.coin!'-'}"
                        >
                    </#if>
                    <#if (coin.enabled!false)>
                        <a href="/wallets/${coin.coin!''}" class="auth-coin-link">${(coin.coin!'-')?upper_case}</a>
                    <#else>
                        <span>${(coin.coin!'-')?upper_case}</span>
                    </#if>
                </h3>
                <#if (coin.enabled!false)>
                    <#if (coin.disconnected!false)>
                        <span class="auth-chip auth-chip-warn">disconnected</span>
                    <#else>
                        <span class="auth-chip auth-chip-on">enabled</span>
                    </#if>
                <#else>
                    <span class="auth-chip auth-chip-off">disabled</span>
                </#if>
            </div>
            <section class="auth-meta-item auth-compact-block" aria-label="Config">
                <h4 class="auth-meta-label">config.toml</h4>
                <span class="mono auth-meta-value auth-inline-meta"><code>[${coin.configSection!''}]</code> &mdash; enabled = ${(coin.enabled!false)?string('true', 'false')}</span>
                <h4 class="auth-meta-label">Secret files</h4>
                <span class="mono auth-meta-value auth-inline-meta">${coin.daemonConfigKey!''} = ${coin.daemonSecretFile!'-'}</span>
                <span class="mono auth-meta-value auth-inline-meta">${coin.walletConfigKey!''} = ${coin.walletSecretFile!'-'}</span>
            </section>
            <#if (coin.enabled!false)>
                <section class="auth-meta-item auth-compact-block" aria-label="Status">
                    <span class="mono auth-meta-value auth-inline-meta">${coin.connectionStatus!'unknown'} · last life-sign: ${coin.lastLifeSign!'unknown'}</span>
                </section>
                <div class="auth-channel-grid auth-channel-grid-compact">
                    <span class="auth-channel-badge <#if (channels.webUi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">web-ui <strong>${(channels.webUi!false)?string('on', 'off')}</strong></span>
                    <span class="auth-channel-badge <#if (channels.restApi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">rest-api <strong>${(channels.restApi!false)?string('on', 'off')}</strong></span>
                    <span class="auth-channel-badge <#if (channels.telegram!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">telegram <strong>${(channels.telegram!false)?string('on', 'off')}</strong></span>
                </div>
            </#if>
        </section>
    </#list>
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</div>
</@layout.page>
