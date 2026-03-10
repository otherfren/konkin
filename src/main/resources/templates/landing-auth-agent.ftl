<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-auth-agent" />

<#assign hasToken = (settings.hasToken!false)>
<div class="page-body">
<main class="main-section"><div class="content auth-channels-content">
    <h2 class="queue-title">${agentName}</h2>
    <p class="auth-channels-subtitle">Runtime overview of the <span class="mono">${agentName}</span> auth agent channel.</p>

    <section class="auth-card" aria-labelledby="agent-endpoint-title">
        <div class="auth-card-header">
            <h3 id="agent-endpoint-title" class="auth-coin-name">Agent Endpoint</h3>
            <span class="auth-chip <#if hasToken>auth-chip-on<#else>auth-chip-warn</#if>">
                <#if hasToken>authenticated<#else>not connected</#if>
            </span>
        </div>

        <table class="queue-table auth-channel-table">
            <thead>
            <tr>
                <th style="white-space:nowrap">Auth Channel ID</th>
                <th style="white-space:nowrap">Last Lifesign</th>
                <th style="white-space:nowrap">Status</th>
                <th style="white-space:nowrap">Bind</th>
                <th style="white-space:nowrap">Port</th>
                <th style="white-space:nowrap">Health Endpoint</th>
                <th style="white-space:nowrap">OAuth Token Endpoint</th>
                <th style="white-space:nowrap">SSE Endpoint</th>
                <th style="white-space:nowrap">Secret File</th>
            </tr>
            </thead>
            <tbody>
            <tr>
                <td class="mono">${agent.authChannelId!'-'}</td>
                <td class="mono" style="white-space:nowrap"><#if (settings.lastActivity!'')?has_content>${settings.lastActivity}<#else>-</#if></td>
                <td>
                    <span class="auth-channel-status <#if hasToken>auth-channel-status-approved<#else>auth-channel-status-warn</#if>">
                        <#if hasToken>authenticated<#else>not connected</#if>
                    </span>
                </td>
                <td class="mono">${agent.bind!'-'}</td>
                <td class="mono">${agent.port!'-'}</td>
                <td class="mono">${agent.healthPath!'-'}</td>
                <td class="mono">${agent.oauthTokenPath!'-'}</td>
                <td class="mono">${agent.ssePath!'-'}</td>
                <td class="mono">${agent.secretFile!'-'}</td>
            </tr>
            </tbody>
        </table>
    </section>

    <#if hasToken>
    <section class="auth-card" style="margin-top:1rem">
        <div class="auth-card-header">
            <h3 class="auth-coin-name">Agent Token</h3>
            <span class="auth-chip auth-chip-on">active</span>
        </div>
        <p class="driver-panel-copy auth-channels-copy">An MCP client has connected using a bearer token issued for this agent. Revoking the token will disconnect the client.</p>
        <div class="settings-actions">
            <button type="button" class="login-button" id="agent-revoke-btn" style="background:#c0392b">Remove Agent Token</button>
            <span class="settings-status" id="agent-revoke-status"></span>
        </div>
    </section>
    </#if>

    <#if settings??>
    <section class="auth-card settings-section" data-section="agents-secondary-${agentName}" style="margin-top:1rem">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Settings</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/agents/${agentName}">
                <div class="settings-field">
                    <label class="settings-label">Bind Address</label>
                    <input type="text" class="settings-input" name="bind" value="${settings.bind!'127.0.0.1'}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Port</label>
                    <input type="number" class="settings-input" name="port" value="${(settings.port!0)?c}" min="1" max="65535" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>
    </#if>

    <#if mcpRegistration??>
    <section class="auth-card" aria-labelledby="auth-agent-mcp-registration-title" style="margin-top:1rem">
        <div class="auth-card-header">
            <h3 id="auth-agent-mcp-registration-title" class="auth-coin-name">MCP Registration</h3>
            <span class="auth-chip <#if hasToken>auth-chip-on<#else>auth-chip-off</#if>">
                <#if hasToken>connected<#else>no token</#if>
            </span>
        </div>
        <p class="driver-panel-copy auth-channels-copy">Issue a bearer token for this auth agent, then register the SSE endpoint in your MCP client. Load the auth skill instructions afterwards.</p>

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

        <div class="driver-command-block">
            <span class="auth-kv-label">1) Get bearer token</span>
            <pre class="driver-command"><code>${(mcpRegistration.tokenCommand!'-')}</code></pre>
        </div>
        <#assign regAgentCommands = mcpRegistration.agentCommands![]>
        <#if regAgentCommands?has_content>
            <div class="driver-command-block">
                <div class="driver-agent-select-row">
                    <span class="auth-kv-label">Agent</span>
                    <select class="driver-agent-select auth-agent-mcp-select" data-reg-index="0" aria-label="Select MCP client for ${agentName}">
                        <#list regAgentCommands as ac>
                            <option value="${ac.id}">${ac.label}</option>
                        </#list>
                    </select>
                </div>
            </div>
            <#list regAgentCommands as ac>
                <div class="auth-agent-mcp-commands" data-reg-index="0" data-agent-id="${ac.id}"<#if !ac?is_first> style="display:none"</#if>>
                    <div class="driver-command-block">
                        <span class="auth-kv-label">2) Register MCP server</span>
                        <pre class="driver-command"><code>${(ac.registerCommand!'-')}</code></pre>
                    </div>
                    <div class="driver-command-block">
                        <span class="auth-kv-label">3) Verify registration</span>
                        <pre class="driver-command"><code>${(ac.verifyCommand!'-')}</code></pre>
                    </div>
                </div>
            </#list>
        </#if>
    </section>
    </#if>


</div></main>

<@m.settingsScript />
<@m.agentSelectScript />
<@m.confirmModal id="agent-confirm-modal" />

<script>
(() => {
    const confirmFn = window.confirmModal['agent-confirm-modal'];

    <#if hasToken>
    const revokeBtn = document.getElementById('agent-revoke-btn');
    const revokeStatus = document.getElementById('agent-revoke-status');
    if (revokeBtn) {
        revokeBtn.addEventListener('click', async () => {
            const confirmed = await confirmFn('Remove Agent Token', 'This will revoke the bearer token for ${agentName} and disconnect the MCP client.', 'revoke');
            if (!confirmed) return;
            revokeBtn.disabled = true;
            if (revokeStatus) { revokeStatus.textContent = 'revoking...'; revokeStatus.className = 'settings-status'; }
            try {
                const resp = await fetch('/settings/agents/${agentName}/revoke-token', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: '{}'
                });
                const result = await resp.json();
                if (result.success) {
                    if (revokeStatus) { revokeStatus.textContent = 'revoked — reloading...'; revokeStatus.className = 'settings-status settings-status-ok'; }
                    setTimeout(() => location.reload(), 600);
                } else {
                    if (revokeStatus) { revokeStatus.textContent = result.errorMessage || 'error'; revokeStatus.className = 'settings-status settings-status-error'; }
                    revokeBtn.disabled = false;
                }
            } catch (err) {
                if (revokeStatus) { revokeStatus.textContent = 'network error'; revokeStatus.className = 'settings-status settings-status-error'; }
                revokeBtn.disabled = false;
            }
        });
    }
    </#if>
})();
</script>

<@m.footer />
</div>
</@layout.page>
