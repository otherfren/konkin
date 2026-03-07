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
        <#if activePage == "auth_channels"><span class="menu-active">auth channels</span><#else><a href="${authChannelsPath}">auth channels</a></#if>
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
<main class="main-section"><div class="content auth-definitions-content">
    <h2 class="queue-title">Wallets</h2>
    <p class="auth-definitions-subtitle">Overview of configured wallets and auth channels.</p>

    <#assign configuredAuthChannels = (wallets.configuredAuthChannels![])>

    <section class="auth-overview-panel" aria-labelledby="auth-overview-title">
        <h3 id="auth-overview-title" class="auth-section-title">Auth channel configured</h3>
        <div class="auth-chip-row">
            <#list configuredAuthChannels as channel>
                <span class="auth-chip <#if (channel.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                    ${(channel.name!'-')}: <strong>${(channel.enabled!false)?string('enabled', 'disabled')}</strong>
                </span>
            </#list>
        </div>
    </section>

    <#assign coins = (wallets.coins![])>
    <#if coins?size == 0>
        <section class="auth-card auth-card-empty">
            <p class="telegram-empty">No wallet configuration available.</p>
        </section>
    <#else>
        <#list coins as coin>
            <#assign channels = (coin.channels!{})>
            <section class="auth-card" aria-labelledby="auth-coin-${coin?index}">
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
                        <a href="/wallets/${coin.coin!''}" class="auth-coin-link">${(coin.coin!'-')?upper_case}</a>
                    </h3>
                    <#if (coin.disconnected!false)>
                        <span class="auth-chip auth-chip-warn">disconnected</span>
                    <#else>
                        <span class="auth-chip auth-chip-on">
                            coin: enabled
                        </span>
                    </#if>
                </div>
                <section class="auth-meta-item auth-compact-block" aria-label="Status">
                    <span class="mono auth-meta-value auth-inline-meta">${coin.connectionStatus!'unknown'} · last life-sign: ${coin.lastLifeSign!'unknown'}</span>
                </section>
                <div class="auth-channel-grid auth-channel-grid-compact">
                    <span class="auth-channel-badge <#if (channels.webUi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">web-ui <strong>${(channels.webUi!false)?string('on', 'off')}</strong></span>
                    <span class="auth-channel-badge <#if (channels.restApi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">rest-api <strong>${(channels.restApi!false)?string('on', 'off')}</strong></span>
                    <span class="auth-channel-badge <#if (channels.telegram!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">telegram <strong>${(channels.telegram!false)?string('on', 'off')}</strong></span>
                </div>
            </section>
        </#list>
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
