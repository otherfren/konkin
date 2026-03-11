<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-driver-agent" />

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
            <span class="auth-chip <#if (driverAgentEndpoint.configured!false) && !(driverAgentWarn!false)>auth-chip-on<#else>auth-chip-off</#if>">
                    <#if (driverAgentEndpoint.configured!false)>
                        <#if (driverAgentWarn!false)>
                            not connected
                        <#else>
                            running
                        </#if>
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
                        <span class="auth-channel-status <#if (driverAgentEndpoint.configured!false) && !(driverAgentWarn!false)>auth-channel-status-approved<#else>auth-channel-status-pending</#if>">
                            <#if (driverAgentWarn!false)>not connected<#else>running</#if>
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

    <#if driverSettings??>
    <section class="auth-card settings-section" data-section="driver-agent" style="margin-top:1rem">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Settings</h3>
            <span class="settings-expand-hint">click to expand</span><span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/agents/primary">
                <div class="settings-field">
                    <label class="settings-label">Bind <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="bind" value="${driverSettings.bind!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Port <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="number" class="settings-input" name="port" value="${driverSettings.port?c}" min="1" max="65535" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>
    </#if>

    <#if (driverAgentEndpoint.configured!false)>
    <section class="auth-card" style="margin-top:1rem">
        <div class="auth-card-header">
            <h3 class="auth-coin-name">Client Secret</h3>
        </div>
        <p class="auth-channels-subtitle">Rotate the OAuth client-secret stored in the driver agent secret file. Takes effect immediately &mdash; existing tokens are revoked.</p>
        <#if revealedClientSecret?has_content>
            <div style="margin-top:.8rem;text-align:center">
                <div class="setup-password-row" style="justify-content:center;margin-bottom:.6rem">
                    <code class="setup-password-display" id="rotated-secret-value" style="color:var(--accent);font-weight:bold">${revealedClientSecret}</code>
                    <button class="setup-copy-button" type="button" onclick="navigator.clipboard.writeText(document.getElementById('rotated-secret-value').textContent)">Copy</button>
                </div>
                <p class="auth-channels-subtitle" style="margin:0">Copy the secret now &mdash; it will not be shown again.</p>
            </div>
        <#else>
            <div style="margin-top:.8rem;text-align:center">
                <form method="post" action="/driver_agent/rotate-secret" id="rotate-secret-form">
                    <button class="login-button" type="button" id="rotate-secret-btn">Rotate Client Secret</button>
                </form>
            </div>
        </#if>
    </section>
    </#if>

    <script>
    (function() {
        var btn = document.getElementById('rotate-secret-btn');
        if (btn) {
            btn.addEventListener('click', function() {
                var confirmFn = window.confirmModal && window.confirmModal['driver-confirm-modal'];
                if (confirmFn) {
                    confirmFn(
                        'Rotate Client Secret',
                        'This will generate a new client-secret, update the secret file, and revoke existing tokens. Continue?',
                        'Rotate'
                    ).then(function(ok) { if (ok) document.getElementById('rotate-secret-form').submit(); });
                }
            });
        }
    })();
    </script>

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

<@m.settingsScript />
<@m.confirmModal id="driver-confirm-modal" />
<script>
(function() {
    var newSecret = document.getElementById('rotated-secret-value');
    if (!newSecret) return;
    var secret = newSecret.textContent;
    var codeBlocks = document.querySelectorAll('.driver-command code');
    for (var i = 0; i < codeBlocks.length; i++) {
        var text = codeBlocks[i].textContent;
        if (text.indexOf('YOUR_SECRET') !== -1) {
            codeBlocks[i].textContent = text.replace('YOUR_SECRET', secret);
        }
    }
})();
</script>
<@m.footer />
</div>
</@layout.page>
