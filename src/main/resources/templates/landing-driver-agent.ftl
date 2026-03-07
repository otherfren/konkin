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
    <input type="checkbox" id="menu-toggle-driver-agent" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-driver-agent" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "history"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
        <#if activePage == "wallets"><span class="menu-active">wallets</span><#else><a href="${walletsPath}">wallets</a></#if>
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
<main class="main-section"><div class="content auth-channels-content">
    <h2 class="queue-title">Driver Agent</h2>
    <p class="auth-channels-subtitle">Runtime overview of the driver agent endpoint that initiates wallet requests and queues them.</p>

    <#assign driverAgentEndpoint = (driverAgent.driverAgent!{})>
    <#assign authMethod = (driverAgent.authMethod!{})>
    <#assign mcpRegistration = (driverAgent.mcpRegistration!{})>

    <section class="auth-card" aria-labelledby="driver-agent-title">
        <div class="auth-card-header">
            <h3 id="driver-agent-title" class="auth-coin-name">Driver Agent Endpoint</h3>
            <span class="auth-chip <#if (driverAgentEndpoint.configured!false) && (driverAgentEndpoint.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                <#if (driverAgentEndpoint.configured!false)>
                    ${(driverAgentEndpoint.enabled!false)?string('enabled', 'disabled')}
                <#else>
                    not configured
                </#if>
            </span>
        </div>

        <#if !(driverAgentEndpoint.configured!false)>
            <p class="telegram-empty">No driver agent configured.</p>
        <#else>
            <table class="queue-table auth-channel-table">
                <thead>
                <tr>
                    <th>Agent</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Bind</th>
                    <th>Port</th>
                    <th>Health Endpoint</th>
                    <th>OAuth Token Endpoint</th>
                    <th>MCP SSE Endpoint</th>
                    <th>Secret File</th>
                </tr>
                </thead>
                <tbody>
                <tr>
                    <td class="mono">${driverAgentEndpoint.name!'-'}</td>
                    <td class="mono">${driverAgentEndpoint.type!'-'}</td>
                    <td>
                        <span class="auth-channel-status <#if (driverAgentEndpoint.enabled!false)>auth-channel-status-approved<#else>auth-channel-status-pending</#if>">
                            ${(driverAgentEndpoint.enabled!false)?string('enabled', 'disabled')}
                        </span>
                    </td>
                    <td class="mono">${driverAgentEndpoint.bind!'-'}</td>
                    <td class="mono">${driverAgentEndpoint.port!'-'}</td>
                    <td class="mono">${driverAgentEndpoint.healthPath!'-'}</td>
                    <td class="mono">${driverAgentEndpoint.oauthTokenPath!'-'}</td>
                    <td class="mono">${driverAgentEndpoint.ssePath!'-'}</td>
                    <td class="mono">${driverAgentEndpoint.secretFile!'-'}</td>
                </tr>
                </tbody>
            </table>
        </#if>
    </section>

    <div class="driver-panels-grid">
        <section class="auth-overview-panel" aria-labelledby="driver-mcp-registration-title">
            <h3 id="driver-mcp-registration-title" class="auth-section-title">MCP Registration</h3>
            <p class="driver-panel-copy">README flow: issue bearer token, register SSE endpoint in Claude Code, then load the driver skill instructions.</p>
            <div class="auth-kv-grid">
                <div class="auth-kv-item">
                    <span class="auth-kv-label">SSE endpoint</span>
                    <span class="mono auth-kv-value">${mcpRegistration.sseEndpoint!'-'}</span>
                </div>
                <div class="auth-kv-item">
                    <span class="auth-kv-label">Skill instructions</span>
                    <span class="mono auth-kv-value"><a target="_blank" href="https://konkin.io/${mcpRegistration.skillPath!'-'}">${mcpRegistration.skillPath!'-'}</a></span>
                </div>
            </div>

            <#if (mcpRegistration.enabled!false)>
                <div class="driver-command-block">
                    <span class="auth-kv-label">1) Get bearer token</span>
                    <pre class="driver-command"><code>${(mcpRegistration.tokenCommand!'-')}</code></pre>
                </div>
                <#assign agentCommands = mcpRegistration.agentCommands![]>
                <#if agentCommands?has_content>
                    <div class="driver-command-block">
                        <div class="driver-agent-select-row">
                            <span class="auth-kv-label">Agent</span>
                            <select id="driver-agent-select" class="driver-agent-select" aria-label="Select agent">
                                <#list agentCommands as agent>
                                    <option value="${agent.id}">${agent.label}</option>
                                </#list>
                            </select>
                        </div>
                    </div>
                    <#list agentCommands as agent>
                        <div class="driver-agent-commands" data-agent-id="${agent.id}"<#if !agent?is_first> style="display:none"</#if>>
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
                    <script>
                        (function() {
                            var sel = document.getElementById('driver-agent-select');
                            if (!sel) return;
                            sel.addEventListener('change', function() {
                                var blocks = document.querySelectorAll('.driver-agent-commands');
                                for (var i = 0; i < blocks.length; i++) {
                                    blocks[i].style.display = blocks[i].getAttribute('data-agent-id') === sel.value ? '' : 'none';
                                }
                            });
                        })();
                    </script>
                </#if>
            <#else>
                <p class="telegram-empty">Enable the driver agent to render ready-to-run token and MCP registration commands.</p>
            </#if>
            <p class="driver-panel-copy" style="margin-top:1.2em;font-size:.92em;opacity:.75">Authentication uses OAuth 2.0 client credentials: obtain a bearer token via the token endpoint using your client ID and secret, then pass it as an Authorization header when connecting to the MCP SSE endpoint.</p>
        </section>
    </div>
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</div>
</@layout.page>
