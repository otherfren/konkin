<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-settings" />

<div class="page-body">
<main class="main-section"><div class="content settings-content">
    <h2 class="queue-title">Settings</h2>
    <p class="auth-definitions-subtitle">Edit configuration. Changes are saved to config.toml.</p>

    <#-- ── Server ── -->
    <section class="auth-card settings-section" data-section="server">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Server</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/server">
                <div class="settings-field">
                    <label class="settings-label">Host <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="host" value="${settings.serverHost!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Port <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="number" class="settings-input" name="port" value="${settings.serverPort?c}" min="1" max="65535" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Log Level</label>
                    <select class="settings-input settings-select" name="log-level">
                        <#list ["trace","debug","info","warn","error"] as lvl>
                            <option value="${lvl}"<#if (settings.logLevel!'info') == lvl> selected</#if>>${lvl}</option>
                        </#list>
                    </select>
                </div>
                <div class="settings-field">
                    <label class="settings-label">Log File</label>
                    <input type="text" class="settings-input" name="log-file" value="${settings.logFile!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Log Rotate Max Size (MB)</label>
                    <input type="number" class="settings-input" name="log-rotate-max-size-mb" value="${settings.logRotateMaxSizeMb?c}" min="0" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Secrets Dir <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="secrets-dir" value="${settings.secretsDir!''}" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#-- ── Database ── -->
    <section class="auth-card settings-section" data-section="database">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Database</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/database">
                <div class="settings-field">
                    <label class="settings-label">URL <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="url" value="${settings.dbUrl!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">User <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="user" value="${settings.dbUser!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Password <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="password" class="settings-input" name="password" value="${settings.dbPassword!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Pool Size <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="number" class="settings-input" name="pool-size" value="${settings.dbPoolSize?c}" min="1" max="100" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#-- ── Web UI ── -->
    <section class="auth-card settings-section" data-section="web-ui">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Web UI</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/web-ui">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Password Protection</label>
                    <label class="settings-toggle"><input type="checkbox" name="password-protection.enabled"<#if settings.passwordProtectionEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Auto-Reload Templates</label>
                    <label class="settings-toggle"><input type="checkbox" name="auto-reload"<#if settings.autoReloadEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Assets Auto-Reload</label>
                    <label class="settings-toggle"><input type="checkbox" name="assets-auto-reload"<#if settings.assetsAutoReloadEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#-- ── REST API ── -->
    <section class="auth-card settings-section" data-section="rest-api">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">REST API</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/rest-api">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if settings.restApiEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#-- ── Telegram ── -->
    <section class="auth-card settings-section" data-section="telegram">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Telegram</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/telegram">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if settings.telegramEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field">
                    <label class="settings-label">API Base URL <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="api-base-url" value="${settings.telegramApiBaseUrl!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Auto-Deny Timeout</label>
                    <input type="text" class="settings-input" name="auto-deny-timeout" value="${settings.telegramAutoDenyTimeout!''}" placeholder="e.g. 5m, 1h" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#-- ── Agents ── -->
    <#if settings.primaryAgent??>
    <section class="auth-card settings-section" data-section="agent-primary">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Agent: primary</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/agents/primary">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if settings.primaryAgent.enabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field">
                    <label class="settings-label">Bind <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="bind" value="${settings.primaryAgent.bind!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Port <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="number" class="settings-input" name="port" value="${settings.primaryAgent.port?c}" min="1" max="65535" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>
    </#if>

    <#list (settings.secondaryAgents!{}) as agentName, agent>
    <section class="auth-card settings-section" data-section="agent-${agentName}">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Agent: ${agentName}</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/agents/${agentName}">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if agent.enabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field">
                    <label class="settings-label">Bind <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="text" class="settings-input" name="bind" value="${agent.bind!''}" />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Port <span class="settings-restart" title="Requires restart">&#128274;</span></label>
                    <input type="number" class="settings-input" name="port" value="${agent.port?c}" min="1" max="65535" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>
    </#list>

    <#-- ── Debug ── -->
    <section class="auth-card settings-section" data-section="debug">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Debug</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-debug-warn">Do not enable debug mode in a production environment. It exposes test wallets, seeds fake data, and weakens security assumptions.</div>
            <div class="settings-form" data-endpoint="/settings/debug">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if settings.debugEnabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Seed Fake Data</label>
                    <label class="settings-toggle"><input type="checkbox" name="seed-fake-data"<#if settings.debugSeedFakeData!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>

    <#-- ── Coins ── -->
    <#list (settings.coins!{}) as coinName, coin>
    <section class="auth-card settings-section" data-section="coin-${coinName}">
        <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
            <h3 class="auth-coin-name">Coin: ${coinName?upper_case}</h3>
            <span class="settings-toggle-icon">&#9654;</span>
        </div>
        <div class="settings-card-body" hidden>
            <div class="settings-form" data-endpoint="/settings/coins/${coinName}">
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Enabled</label>
                    <label class="settings-toggle"><input type="checkbox" name="enabled"<#if coin.enabled!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <h4 class="settings-subsection-title">Auth Channels</h4>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Web UI</label>
                    <label class="settings-toggle"><input type="checkbox" name="auth.web-ui"<#if coin.authWebUi!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">REST API</label>
                    <label class="settings-toggle"><input type="checkbox" name="auth.rest-api"<#if coin.authRestApi!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field settings-field-toggle">
                    <label class="settings-label">Telegram</label>
                    <label class="settings-toggle"><input type="checkbox" name="auth.telegram"<#if coin.authTelegram!false> checked</#if> /><span class="settings-toggle-slider"></span></label>
                </div>
                <div class="settings-field">
                    <label class="settings-label">Min Approvals Required</label>
                    <input type="number" class="settings-input" name="auth.min-approvals-required" value="${coin.minApprovalsRequired?c}" min="1" max="10" />
                </div>
                <div class="settings-actions">
                    <button type="button" class="settings-save-btn">Save</button>
                    <span class="settings-status"></span>
                </div>
            </div>
        </div>
    </section>
    </#list>

</div></main>

<@m.footer />
</div>

<@m.settingsScript />
</@layout.page>
