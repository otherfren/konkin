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
    <input type="checkbox" id="menu-toggle-auth-channels" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-auth-channels" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
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

<main class="main-section"><div class="content auth-channels-content">
    <h2 class="queue-title">Auth Channels</h2>
    <p class="auth-channels-subtitle">Runtime overview of web-ui, rest-api, telegram users, and auth-agent channels.</p>

    <#assign webUi = (authChannels.webUi!{})>
    <#assign restApi = (authChannels.restApi!{})>
    <#assign telegramEnabled = (authChannels.telegramEnabled!false)>
    <#assign telegramUsers = (authChannels.telegramUsers![])>
    <#assign authAgents = (authChannels.authAgents![])>

    <div class="auth-channels-grid">
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
    </div>

    <section class="auth-card" aria-labelledby="auth-channel-telegram-title">
        <div class="auth-card-header">
            <h3 id="auth-channel-telegram-title" class="auth-coin-name">Telegram Connected Users</h3>
            <span class="auth-chip <#if telegramEnabled>auth-chip-on<#else>auth-chip-off</#if>">
                ${telegramEnabled?string('enabled', 'disabled')}
            </span>
        </div>

        <#if !telegramEnabled>
            <p class="telegram-empty">Telegram is globally disabled.</p>
        <#elseif telegramUsers?size == 0>
            <p class="telegram-empty">No Telegram users discovered or approved yet.</p>
        <#else>
            <table class="queue-table auth-channel-table">
                <thead>
                <tr>
                    <th>Status</th>
                    <th>Chat ID</th>
                    <th>Username</th>
                </tr>
                </thead>
                <tbody>
                <#list telegramUsers as user>
                    <tr>
                        <td>
                            <span class="auth-channel-status <#if (user.approved!false)>auth-channel-status-approved<#else>auth-channel-status-pending</#if>">
                                <#if (user.approved!false)>approved<#else>discovered</#if>
                            </span>
                        </td>
                        <td>
                            <span class="auth-channel-secret-wrap">
                                <span class="mono auth-secret-value" data-secret-value="${(user.chatId!'-')?html}" data-masked="true">***</span>
                                <button
                                    type="button"
                                    class="auth-secret-toggle"
                                    aria-label="Reveal Telegram identifier"
                                    title="Reveal identifier"
                                >
                                    <span aria-hidden="true">👁</span>
                                </button>
                            </span>
                        </td>
                        <td>
                            <#if (user.chatUsername!'')?has_content>
                                <span class="auth-channel-secret-wrap">
                                    <span class="mono auth-secret-value" data-secret-value="@${(user.chatUsername!'')?html}" data-masked="true">***</span>
                                    <button
                                        type="button"
                                        class="auth-secret-toggle"
                                        aria-label="Reveal Telegram identifier"
                                        title="Reveal identifier"
                                    >
                                        <span aria-hidden="true">👁</span>
                                    </button>
                                </span>
                            <#else>
                                <span class="mono">-</span>
                            </#if>
                        </td>
                    </tr>
                </#list>
                </tbody>
            </table>
        </#if>
    </section>

    <section class="auth-card" aria-labelledby="auth-channel-secondary-title">
        <div class="auth-card-header">
            <h3 id="auth-channel-secondary-title" class="auth-coin-name">Auth Agent Bot Channels</h3>
            <span class="auth-chip <#if authAgents?size gt 0>auth-chip-on<#else>auth-chip-off</#if>">
                ${authAgents?size} configured
            </span>
        </div>

        <#if authAgents?size == 0>
            <p class="telegram-empty">No auth agent channels configured.</p>
        <#else>
            <table class="queue-table auth-channel-table">
                <thead>
                <tr>
                    <th>Agent</th>
                    <th>Status</th>
                    <th>Bind</th>
                    <th>Port</th>
                    <th>Health</th>
                    <th>OAuth Token</th>
                    <th>Secret File</th>
                </tr>
                </thead>
                <tbody>
                <#list authAgents as agent>
                    <tr>
                        <td class="mono">${agent.name!'-'}</td>
                        <td>
                            <span class="auth-channel-status <#if (agent.enabled!false)>auth-channel-status-approved<#else>auth-channel-status-pending</#if>">
                                ${(agent.enabled!false)?string('enabled', 'disabled')}
                            </span>
                        </td>
                        <td class="mono">${agent.bind!'-'}</td>
                        <td class="mono">${agent.port!'-'}</td>
                        <td class="mono">${agent.healthPath!'-'}</td>
                        <td class="mono">${agent.oauthTokenPath!'-'}</td>
                        <td class="mono">${agent.secretFile!'-'}</td>
                    </tr>
                </#list>
                </tbody>
            </table>
        </#if>
    </section>
</div></main>

<script>
(() => {
    const secretValues = document.querySelectorAll('.auth-secret-value[data-secret-value]');

    for (const valueEl of secretValues) {
        const container = valueEl.closest('.auth-channel-secret-wrap, .auth-mcp-item, .auth-secret');
        const toggle = container ? container.querySelector('.auth-secret-toggle') : null;
        if (!toggle) {
            continue;
        }

        toggle.addEventListener('click', () => {
            const masked = valueEl.dataset.masked !== 'false';
            if (masked) {
                valueEl.textContent = valueEl.dataset.secretValue || '-';
                valueEl.dataset.masked = 'false';
                toggle.setAttribute('aria-label', 'Hide Telegram identifier');
                toggle.setAttribute('title', 'Hide identifier');
                toggle.classList.add('is-revealed');
            } else {
                valueEl.textContent = '***';
                valueEl.dataset.masked = 'true';
                toggle.setAttribute('aria-label', 'Reveal Telegram identifier');
                toggle.setAttribute('title', 'Reveal identifier');
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
