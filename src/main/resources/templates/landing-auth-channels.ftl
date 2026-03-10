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

    <#assign authAgents = (authChannels.authAgents![])>

    <section class="auth-card" aria-labelledby="auth-channel-secondary-title">
        <div class="auth-card-header">
            <h3 id="auth-channel-secondary-title" class="auth-coin-name">Auth Agent Bot Channels</h3>
            <span class="auth-chip <#if authAgents?size gt 0>auth-chip-on<#else>auth-chip-off</#if>">
                ${authAgents?size} configured
            </span>
        </div>
        <p class="driver-panel-copy auth-channels-copy">Each secondary agent exposes an auth endpoint and contributes a channel id. Use these channel ids in coin auth settings via <span class="mono">mcp-auth-channels</span>.</p>

        <#if authAgents?size == 0>
            <p class="telegram-empty">No auth agent channels configured.</p>
        <#else>
            <table class="queue-table auth-channel-table">
                <thead>
                <tr>
                    <th style="white-space:nowrap">Agent</th>
                    <th style="white-space:nowrap">Last Lifesign</th>
                    <th style="white-space:nowrap">Status</th>
                    <th style="white-space:nowrap">Bind</th>
                    <th style="white-space:nowrap">Port</th>
                    <th style="white-space:nowrap">Health Endpoint</th>
                    <th style="white-space:nowrap">OAuth Token Endpoint</th>
                    <th style="white-space:nowrap">Secret File</th>
                </tr>
                </thead>
                <tbody>
                <#list authAgents as agent>
                    <tr>
                        <td class="mono">${agent.name!'-'}</td>
                        <td class="mono" style="white-space:nowrap"><#if (agent.lastActivity!'')?has_content>${agent.lastActivity}<#else>-</#if></td>
                        <td>
                            <span class="auth-channel-status <#if (agent.connected!false)>auth-channel-status-approved<#else>auth-channel-status-warn</#if>">
                                ${(agent.connected!false)?string('authenticated', 'not connected')}
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

<@m.footer />
</div>
</@layout.page>
