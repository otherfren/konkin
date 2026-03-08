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
    <input type="checkbox" id="menu-toggle-auth-channels" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-auth-channels" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
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
    <h2 class="queue-title">Auth Channels</h2>
    <p class="auth-channels-subtitle">Runtime overview of web-ui, rest-api, telegram users, and auth-agent channels.</p>

    <#assign restApi = (authChannels.restApi!{})>
    <#assign telegramEnabled = (authChannels.telegramEnabled!false)>
    <#assign telegramUsers = (authChannels.telegramUsers![])>
    <#assign authAgents = (authChannels.authAgents![])>

    <div class="auth-channels-grid">
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
        <p class="driver-panel-copy auth-channels-copy">Names and usernames come from secret file <span class="mono">telegram.secret</span>.</p>

        <#if !telegramEnabled>
            <p class="telegram-empty">Telegram is globally disabled.</p>
        <#elseif telegramUsers?size == 0>
            <p class="telegram-empty">No Telegram users discovered or approved yet.</p>
        <#else>
            <table class="queue-table auth-channel-table">
                <thead>
                <tr>
                    <th>Status</th>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Chat ID</th>
                    <th>Username</th>
                    <th>Title</th>
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
                        <td><span class="auth-channel-name">${(user.chatDisplayName!'-')}</span></td>
                        <td>${(user.chatType!'unknown')}</td>
                        <td>
                            <span class="auth-channel-secret-wrap">
                                <span class="mono auth-secret-value" data-secret-value="${(user.chatId!'-')}" data-masked="true">***</span>
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
                                    <span class="mono auth-secret-value" data-secret-value="@${(user.chatUsername!'')}" data-masked="true">***</span>
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
                        <td>${(user.chatTitle!'-')}</td>
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
        <p class="driver-panel-copy auth-channels-copy">Each enabled secondary agent exposes an auth endpoint and contributes a channel id. Use these channel ids in coin auth settings via <span class="mono">mcp-auth-channels</span>.</p>

        <#if authAgents?size == 0>
            <p class="telegram-empty">No auth agent channels configured.</p>
        <#else>
            <table class="queue-table auth-channel-table">
                <thead>
                <tr>
                    <th>Agent</th>
                    <th>Auth Channel ID</th>
                    <th>Status</th>
                    <th>Bind</th>
                    <th>Port</th>
                    <th>Health Endpoint</th>
                    <th>OAuth Token Endpoint</th>
                    <th>Secret File</th>
                </tr>
                </thead>
                <tbody>
                <#list authAgents as agent>
                    <tr>
                        <td class="mono">${agent.name!'-'}</td>
                        <td class="mono">${agent.authChannelId!'-'}</td>
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

            <div class="auth-agent-hints">
                <section class="auth-overview-panel" aria-labelledby="auth-agent-reference-title">
                    <h3 id="auth-agent-reference-title" class="auth-section-title">Reference format</h3>
                    <p class="driver-panel-copy">If your model is dumb: <a target="_blank" href="https://konkin.io/documents/SKILL-auth-agent.md">documents/SKILL-auth-agent.md</a></p>
                </section>
                <section class="auth-overview-panel" aria-labelledby="auth-agent-runtime-title">
                    <h3 id="auth-agent-runtime-title" class="auth-section-title">Runtime checks</h3>
                    <p class="driver-panel-copy">Use <span class="mono">/health</span> for liveness and <span class="mono">/oauth/token</span> to issue bearer tokens consumed by MCP auth tools.</p>
                </section>
            </div>
        </#if>
    </section>

    <#assign authAgentMcpRegistrations = (authChannels.authAgentMcpRegistrations![])>
    <#if authAgentMcpRegistrations?size gt 0>
    <section class="auth-card" aria-labelledby="auth-agent-mcp-registration-title">
        <div class="auth-card-header">
            <h3 id="auth-agent-mcp-registration-title" class="auth-coin-name">Auth Agent MCP Registration</h3>
            <span class="auth-chip auth-chip-on">${authAgentMcpRegistrations?size} agent<#if authAgentMcpRegistrations?size gt 1>s</#if></span>
        </div>
        <p class="driver-panel-copy auth-channels-copy">Issue a bearer token per auth agent, then register the SSE endpoint in your MCP client. Load the auth skill instructions afterwards.</p>

        <#list authAgentMcpRegistrations as reg>
            <div class="auth-agent-mcp-block" aria-labelledby="auth-mcp-agent-${reg?index}">
                <h4 id="auth-mcp-agent-${reg?index}" class="auth-section-title">${(reg.agentName!'-')}</h4>
                <div class="auth-kv-grid">
                    <div class="auth-kv-item">
                        <span class="auth-kv-label">SSE endpoint</span>
                        <span class="mono auth-kv-value">${reg.sseEndpoint!'-'}</span>
                    </div>
                    <div class="auth-kv-item">
                        <span class="auth-kv-label">Skill instructions</span>
                        <span class="mono auth-kv-value"><a target="_blank" href="https://konkin.io/${reg.skillPath!'-'}">${reg.skillPath!'-'}</a></span>
                    </div>
                </div>

                <#if (reg.enabled!false)>
                    <div class="driver-command-block">
                        <span class="auth-kv-label">1) Get bearer token</span>
                        <pre class="driver-command"><code>${(reg.tokenCommand!'-')}</code></pre>
                    </div>
                    <#assign regAgentCommands = reg.agentCommands![]>
                    <#if regAgentCommands?has_content>
                        <div class="driver-command-block">
                            <div class="driver-agent-select-row">
                                <span class="auth-kv-label">Agent</span>
                                <select class="driver-agent-select auth-agent-mcp-select" data-reg-index="${reg?index}" aria-label="Select MCP client for ${(reg.agentName!'')}">
                                    <#list regAgentCommands as agent>
                                        <option value="${agent.id}">${agent.label}</option>
                                    </#list>
                                </select>
                            </div>
                        </div>
                        <#list regAgentCommands as agent>
                            <div class="auth-agent-mcp-commands" data-reg-index="${reg?index}" data-agent-id="${agent.id}"<#if !agent?is_first> style="display:none"</#if>>
                                <div class="driver-command-block">
                                    <span class="auth-kv-label">2) Register MCP server</span>
                                    <pre class="driver-command"><code>${(agent.registerCommand!'-')}</code></pre>
                                </div>
                                <div class="driver-command-block">
                                    <span class="auth-kv-label">3) Verify registration</span>
                                    <pre class="driver-command"><code>${(agent.verifyCommand!'-')}</code></pre>
                                </div>
                            </div>
                        </#list>
                    </#if>
                <#else>
                    <p class="telegram-empty">Enable this auth agent to render ready-to-run token and MCP registration commands.</p>
                </#if>
            </div>
            <#if reg?has_next><hr class="auth-agent-mcp-separator"></#if>
        </#list>
    </section>
    </#if>
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

<script>
(() => {
    const selects = document.querySelectorAll('.auth-agent-mcp-select');
    for (const sel of selects) {
        sel.addEventListener('change', () => {
            const regIndex = sel.dataset.regIndex;
            const blocks = document.querySelectorAll('.auth-agent-mcp-commands[data-reg-index="' + regIndex + '"]');
            for (const block of blocks) {
                block.style.display = block.getAttribute('data-agent-id') === sel.value ? '' : 'none';
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
</div>
</@layout.page>
