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
    <input type="checkbox" id="menu-toggle-wallets" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-wallets" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "log"><span class="menu-active">audit</span><#else><a href="${auditLogPath}">audit</a></#if>
        <#if activePage == "wallets"><span class="menu-active">wallets</span><#else><a href="${walletsPath}">wallets</a></#if>
        <#if activePage == "driver_agent"><span class="menu-active">driver agent</span><#else><a href="${driverAgentPath}">driver agent</a></#if>
        <#if activePage == "auth_channels"><span class="menu-active">auth channels</span><#else><a href="${authChannelsPath}">auth channels</a></#if>
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
    <h2 class="queue-title">Wallets</h2>
    <p class="auth-definitions-subtitle">Compact wallet overview from config.toml and runtime fallbacks.</p>

    <#assign configuredAuthChannels = (wallets.configuredAuthChannels![])>

    <section class="auth-overview-panel" aria-labelledby="auth-overview-title">
        <h3 id="auth-overview-title" class="auth-section-title">Auth channel configured</h3>
        <div class="auth-chip-row">
            <#list configuredAuthChannels as channel>
                <span class="auth-chip <#if (channel.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                    ${(channel.name!'-')?html}: <strong>${(channel.enabled!false)?string('enabled', 'disabled')}</strong>
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
            <#assign channelWarnings = (coin.channelWarnings![])>
            <#assign autoAcceptRules = (coin.autoAcceptRules![])>
            <#assign autoDenyRules = (coin.autoDenyRules![])>
            <#assign verificationAgents = (coin.verificationAgents![])>

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

                <div class="auth-meta-grid">
                    <section class="auth-meta-item" aria-label="Connection status">
                        <h4 class="auth-meta-label">Connection status</h4>
                        <span class="mono auth-meta-value auth-inline-meta">${coin.connectionStatus!'unknown'} · last life-sign: ${coin.lastLifeSign!'unknown'}</span>
                    </section>
                    <section class="auth-meta-item" aria-label="Secret file locations">
                        <h4 class="auth-meta-label">Secret file locations</h4>
                        <span class="mono auth-meta-value auth-inline-meta">daemon: ${coin.daemonSecretFile!'unknown'} · wallet: ${coin.walletSecretFile!'unknown'}</span>
                    </section>
                    <section class="auth-meta-item" aria-label="Wallet balance">
                        <h4 class="auth-meta-label">Balance</h4>
                        <span class="auth-secret">
                            <span class="mono auth-meta-value auth-secret-value" data-secret-value="${(coin.maskedBalance!'unknown')?html}" data-masked="true">***</span>
                            <button
                                type="button"
                                class="auth-secret-toggle"
                                aria-label="Reveal wallet balance"
                                title="Reveal balance"
                            >
                                <span aria-hidden="true">👁</span>
                            </button>
                        </span>
                    </section>
                </div>

                <section class="auth-meta-item auth-compact-block" aria-label="Active auth channels">
                    <h4 class="auth-meta-label">Active auth channels</h4>
                    <div class="auth-channel-grid auth-channel-grid-compact">
                        <span class="auth-channel-badge <#if (channels.webUi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">web-ui <strong>${(channels.webUi!false)?string('on', 'off')}</strong></span>
                        <span class="auth-channel-badge <#if (channels.restApi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">rest-api <strong>${(channels.restApi!false)?string('on', 'off')}</strong></span>
                        <span class="auth-channel-badge <#if (channels.telegram!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">telegram <strong>${(channels.telegram!false)?string('on', 'off')}</strong></span>
                    </div>
                </section>

                <div class="auth-rules-grid">
                    <section class="auth-rule-block auth-rule-block-accept" aria-labelledby="auth-accept-${coin?index}">
                        <h4 id="auth-accept-${coin?index}" class="auth-rule-title">Auto Apply</h4>
                        <#if autoAcceptRules?size == 0>
                            <p class="auth-empty-rule">No auto-apply rules configured.</p>
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

                <section class="auth-meta-item auth-compact-block" aria-label="Verification agents">
                    <h4 class="auth-meta-label">Verification agents</h4>
                    <#if verificationAgents?size == 0>
                        <span class="mono auth-meta-value">none</span>
                    <#else>
                        <div class="auth-chip-row auth-chip-row-tight">
                            <#list verificationAgents as agent>
                                <span class="auth-chip <#if (agent.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                                    ${(agent.name!'unknown')?html} @ ${(agent.connectUrl!'unknown')?html}:${(agent.port!'unknown')?html}
                                </span>
                            </#list>
                        </div>
                    </#if>
                </section>

                <section class="auth-meta-item auth-compact-block" aria-label="Quorum and veto channels">
                    <h4 class="auth-meta-label">Quorum</h4>
                    <span class="mono auth-meta-value auth-inline-meta">${coin.quorumLine!'unknown'}</span>
                    <h4 class="auth-meta-label">Veto channels</h4>
                    <span class="mono auth-meta-value auth-inline-meta">${coin.vetoChannelsLine!'none'}</span>
                </section>

                <#if channelWarnings?size gt 0>
                    <ul class="auth-warning-list">
                        <#list channelWarnings as warning>
                            <li>${warning?html}</li>
                        </#list>
                    </ul>
                </#if>
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
                const revealLabel = toggle.getAttribute('aria-label') || 'Reveal secret';
                const hideLabel = revealLabel.replace('Reveal', 'Hide');
                const revealTitle = toggle.getAttribute('title') || 'Reveal secret';
                const hideTitle = revealTitle.replace('Reveal', 'Hide');
                toggle.setAttribute('aria-label', hideLabel);
                toggle.setAttribute('title', hideTitle);
                toggle.classList.add('is-revealed');
            } else {
                valueEl.textContent = '***';
                valueEl.dataset.masked = 'true';
                const revealLabel = toggle.getAttribute('aria-label') || 'Reveal secret';
                const hideLabel = revealLabel.replace('Reveal', 'Hide');
                toggle.setAttribute('aria-label', hideLabel.startsWith('Hide') ? hideLabel.replace('Hide', 'Reveal') : 'Reveal secret');
                const revealTitle = toggle.getAttribute('title') || 'Reveal secret';
                const hideTitle = revealTitle.replace('Reveal', 'Hide');
                toggle.setAttribute('title', hideTitle.startsWith('Hide') ? hideTitle.replace('Hide', 'Reveal') : 'Reveal secret');
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
