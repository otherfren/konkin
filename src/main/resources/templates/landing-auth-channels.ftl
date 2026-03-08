<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-auth-channels" />

<div class="page-body">
<main class="main-section"><div class="content auth-channels-content">
    <h2 class="queue-title">Auth Channels</h2>
    <p class="auth-channels-subtitle">Runtime overview of web-ui, rest-api, telegram users, and auth-agent channels.</p>

    <#assign configuredAuthChannels = (authChannels.configuredAuthChannels![])>

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

    <#assign telegramEnabled = (authChannels.telegramEnabled!false)>
    <#assign telegramUsers = (authChannels.telegramUsers![])>
    <#assign authAgents = (authChannels.authAgents![])>

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

<@m.secretToggleScript />
<@m.agentSelectScript />

<@m.footer />
</div>
</@layout.page>
