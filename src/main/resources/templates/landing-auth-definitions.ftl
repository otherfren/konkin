<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-wallets" />

<div class="page-body">
<main class="main-section"><div class="content auth-definitions-content">
    <h2 class="queue-title">Wallets</h2>
    <p class="auth-definitions-subtitle">Overview of configured wallets. Auth channels are editable inline.</p>

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
                        <form method="post" action="/wallets/reconnect" class="reconnect-form">
                            <input type="hidden" name="coin" value="${(coin.coin!'')}">
                            <button type="submit" class="reconnect-btn">reconnect</button>
                        </form>
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

<@m.footer />
</div>

</@layout.page>
