<#import "layout.ftl" as layout>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo.png")
>
<header class="topbar">
    <a href="/" class="brand">
        <img src="${assetsPath}/img/logo.png?v=${assetsVersion}" alt="KONKIN logo" class="brand-logo">
        <span class="brand-name">${title}</span>
    </a>
    <input type="checkbox" id="menu-toggle-auth-definitions" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-auth-definitions" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "log"><span class="menu-active">audit</span><#else><a href="${auditLogPath}">audit</a></#if>
        <#if activePage == "auth_definitions"><span class="menu-active">auth defs</span><#else><a href="${authDefinitionsPath}">auth defs</a></#if>
        <#if telegramPageAvailable>
            <#if activePage == "telegram"><span class="menu-active">telegram</span><#else><a href="${telegramPath}">telegram</a></#if>
        </#if>
        <#if showLogout>
            <form method="post" action="/logout" class="logout-form">
                <button type="submit" class="logout-btn">logout</button>
            </form>
        </#if>
    </nav>
</header>

<main class="main-section"><div class="content auth-definitions-content">
    <h2 class="queue-title">Auth Definitions</h2>
    <p class="auth-definitions-subtitle">Read-only view of config.toml</p>

    <#assign webUiEnabled = (authDefinitions.webUiEnabled!false)>
    <#assign telegramEnabled = (authDefinitions.telegramEnabled!false)>

    <section class="auth-overview-panel" aria-labelledby="auth-overview-title">
        <h3 id="auth-overview-title" class="auth-section-title">Global Channel Availability</h3>
        <div class="auth-chip-row">
            <span class="auth-chip <#if webUiEnabled>auth-chip-on<#else>auth-chip-off</#if>">
                web-ui: <strong>${webUiEnabled?string('enabled', 'disabled')}</strong>
            </span>
            <span class="auth-chip <#if telegramEnabled>auth-chip-on<#else>auth-chip-off</#if>">
                telegram: <strong>${telegramEnabled?string('enabled', 'disabled')}</strong>
            </span>
        </div>
    </section>

    <#assign coins = (authDefinitions.coins![])>
    <#if coins?size == 0>
        <section class="auth-card auth-card-empty">
            <p class="telegram-empty">No coin auth definitions available.</p>
        </section>
    <#else>
        <#list coins as coin>
            <#assign channels = (coin.channels!{})>
            <#assign channelWarnings = (coin.channelWarnings![])>
            <#assign autoAcceptRules = (coin.autoAcceptRules![])>
            <#assign autoDenyRules = (coin.autoDenyRules![])>

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
                        <span>${(coin.coin!'-')?upper_case}</span>
                    </h3>
                    <span class="auth-chip <#if (coin.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                        coin: ${(coin.enabled!false)?string('enabled', 'disabled')}
                    </span>
                </div>

                <#assign mcpAuthChannels = (coin.mcpAuthChannels![])>
                <div class="auth-meta-grid">
                    <section class="auth-meta-item auth-mcp-panel" aria-labelledby="auth-mcp-${coin?index}">
                        <h4 id="auth-mcp-${coin?index}" class="auth-meta-label auth-mcp-title">MCP auth channels</h4>
                        <#if mcpAuthChannels?size == 0>
                            <span class="mono auth-meta-value">-</span>
                        <#else>
                            <div class="auth-mcp-list">
                                <#list mcpAuthChannels as mcpValue>
                                    <div class="auth-mcp-item">
                                        <span class="mono auth-meta-value auth-secret-value" data-secret-value="${mcpValue?html}" data-masked="true">***</span>
                                        <button
                                            type="button"
                                            class="auth-secret-toggle"
                                            aria-label="Reveal MCP value"
                                            title="Reveal secret"
                                        >
                                            <span aria-hidden="true">👁</span>
                                        </button>
                                    </div>
                                </#list>
                            </div>
                        </#if>
                    </section>
                </div>

                <div class="auth-channel-grid">
                    <span class="auth-channel-badge <#if (channels.webUi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">
                        web-ui <strong>${(channels.webUi!false)?string('on', 'off')}</strong>
                    </span>
                    <span class="auth-channel-badge <#if (channels.restApi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">
                        rest-api <strong>${(channels.restApi!false)?string('on', 'off')}</strong>
                    </span>
                    <span class="auth-channel-badge <#if (channels.telegram!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">
                        telegram <strong>${(channels.telegram!false)?string('on', 'off')}</strong>
                    </span>
                </div>

                <#if channelWarnings?size gt 0>
                    <ul class="auth-warning-list">
                        <#list channelWarnings as warning>
                            <li>${warning?html}</li>
                        </#list>
                    </ul>
                </#if>

                <div class="auth-rules-grid">
                    <section class="auth-rule-block auth-rule-block-accept" aria-labelledby="auth-accept-${coin?index}">
                        <h4 id="auth-accept-${coin?index}" class="auth-rule-title">Auto Accept</h4>
                        <#if autoAcceptRules?size == 0>
                            <p class="auth-empty-rule">No auto-accept rules configured.</p>
                        <#else>
                            <table class="queue-table auth-rule-table">
                                <thead>
                                    <tr>
                                        <th>#</th>
                                        <th>Rule</th>
                                        <th>Amount</th>
                                        <th>Time window</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <#list autoAcceptRules as rule>
                                        <tr>
                                            <td class="mono">${rule.index!'-'}</td>
                                            <td><span class="auth-rule-type auth-rule-type-accept">${rule.typeLabel!rule.type!'-'}</span></td>
                                            <td class="mono">${rule.value!'-'}</td>
                                            <td class="mono">${rule.period!'-'}</td>
                                        </tr>
                                    </#list>
                                </tbody>
                            </table>
                        </#if>
                    </section>

                    <section class="auth-rule-block auth-rule-block-deny" aria-labelledby="auth-deny-${coin?index}">
                        <h4 id="auth-deny-${coin?index}" class="auth-rule-title">Auto Deny</h4>
                        <#if autoDenyRules?size == 0>
                            <p class="auth-empty-rule">No auto-deny rules configured.</p>
                        <#else>
                            <table class="queue-table auth-rule-table">
                                <thead>
                                    <tr>
                                        <th>#</th>
                                        <th>Rule</th>
                                        <th>Amount</th>
                                        <th>Time window</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <#list autoDenyRules as rule>
                                        <tr>
                                            <td class="mono">${rule.index!'-'}</td>
                                            <td><span class="auth-rule-type auth-rule-type-deny">${rule.typeLabel!rule.type!'-'}</span></td>
                                            <td class="mono">${rule.value!'-'}</td>
                                            <td class="mono">${rule.period!'-'}</td>
                                        </tr>
                                    </#list>
                                </tbody>
                            </table>
                        </#if>
                    </section>
                </div>
            </section>
        </#list>
    </#if>
</div></main>

<script>
(() => {
    const secretValues = document.querySelectorAll('.auth-secret-value[data-secret-value]');

    for (const valueEl of secretValues) {
        const container = valueEl.closest('.auth-mcp-item, .auth-secret');
        const toggle = container ? container.querySelector('.auth-secret-toggle') : null;
        if (!toggle) {
            continue;
        }

        toggle.addEventListener('click', () => {
            const masked = valueEl.dataset.masked !== 'false';
            if (masked) {
                valueEl.textContent = valueEl.dataset.secretValue || '-';
                valueEl.dataset.masked = 'false';
                toggle.setAttribute('aria-label', 'Hide MCP value');
                toggle.setAttribute('title', 'Hide secret');
                toggle.classList.add('is-revealed');
            } else {
                valueEl.textContent = '***';
                valueEl.dataset.masked = 'true';
                toggle.setAttribute('aria-label', 'Reveal MCP value');
                toggle.setAttribute('title', 'Reveal secret');
                toggle.classList.remove('is-revealed');
            }
        });
    }
})();
</script>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
