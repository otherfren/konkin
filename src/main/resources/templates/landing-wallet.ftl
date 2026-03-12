<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-wallet" />

<div class="page-body">
<main class="main-section"><div class="content auth-definitions-content">
    <#assign coin = (walletData.coin!{})>
    <#assign configuredAuthChannels = (walletData.configuredAuthChannels![])>
    <#assign channels = (coin.channels!{})>
    <#assign channelWarnings = (coin.channelWarnings![])>
    <#assign autoAcceptRules = (coin.autoAcceptRules![])>
    <#assign autoDenyRules = (coin.autoDenyRules![])>
    <#assign verificationAgents = (coin.verificationAgents![])>

    <section class="auth-card" aria-labelledby="auth-coin-0">
        <div class="auth-card-header">
            <h3 id="auth-coin-0" class="auth-coin-name auth-coin-title">
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
            <#if (coin.disconnected!false)>
                <span class="auth-chip auth-chip-warn">disconnected</span>
                <form method="post" action="/wallets/reconnect" class="reconnect-form">
                    <input type="hidden" name="coin" value="${(coin.coin!'')}">
                    <button type="submit" class="reconnect-btn">reconnect</button>
                </form>
            <#else>
                <span class="auth-chip auth-chip-on">
                    coin: enabled
                </span>
            </#if>
            <#if (coin.connectionError!'')?has_content>
                <div class="auth-warning-list" style="margin-top:0.5rem;width:100%">
                    <li>${coin.connectionError}</li>
                </div>
            </#if>
        </div>
        <section class="auth-card" style="margin-top:12px" aria-label="Connection and secrets">
            <h4 class="auth-section-title">Connection &amp; Secrets</h4>
            <div class="settings-form" style="margin-top:10px">
                <div class="settings-field">
                    <label class="settings-label">Status</label>
                    <input type="text" class="settings-input mono" value="${coin.connectionStatus!'unknown'}" readonly />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Last life-sign</label>
                    <input type="text" class="settings-input mono" value="${coin.lastLifeSign!'unknown'}" readonly />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Daemon secret</label>
                    <input type="text" class="settings-input mono" value="${coin.daemonSecretFile!'unknown'}" readonly />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Wallet secret</label>
                    <input type="text" class="settings-input mono" value="${coin.walletSecretFile!'unknown'}" readonly />
                </div>
                <div class="settings-field">
                    <label class="settings-label">Balance</label>
                    <span class="auth-secret" style="flex:1">
                        <input type="text" class="settings-input mono auth-secret-value" value="***" data-secret-value="${(coin.maskedBalance!'unknown')}" data-masked="true" readonly />
                        <button
                            type="button"
                            class="auth-secret-toggle"
                            aria-label="Reveal wallet balance"
                            title="Reveal balance"
                        >
                            <span aria-hidden="true">&#x1F441;</span>
                        </button>
                    </span>
                </div>
            </div>
        </section>

        <#-- Connection wizard — works without JS via <details> + form POST -->
        <#assign coinId = (coin.coin!'unknown')>
        <#assign configured = (coin.configured!false)>
        <#assign maskedCfg = (coin.maskedConfig!{})>
        <#assign defaultRpcPort = "8332">
        <#if coinId == "litecoin"><#assign defaultRpcPort = "9332"></#if>

        <section class="auth-card settings-section" data-section="connection" style="margin-top:1rem">
            <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
                <h3 class="auth-coin-name"><#if configured>Edit Connection<#else>Configure Connection</#if></h3>
                <span class="settings-expand-hint">click to expand</span><span class="settings-toggle-icon">&#9654;</span>
            </div>
            <div class="settings-card-body" hidden>
                <form class="wizard-panel" data-coin="${coinId}" data-mode="${configured?string('edit', 'add')}" method="post" action="/wallets/${coinId}/save-connection">
                    <div class="settings-form">
                    <#if coinId == "bitcoin" || coinId == "litecoin">
                        <div class="settings-field">
                            <label class="settings-label">RPC Host</label>
                            <input type="text" class="settings-input" name="rpcHost" value="<#if configured && maskedCfg?has_content && maskedCfg.rpcEndpoint??>${maskedCfg.rpcEndpoint?split(':')[0]!'127.0.0.1'}<#else>127.0.0.1</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">RPC Port</label>
                            <input type="text" class="settings-input" name="rpcPort" value="<#if configured && maskedCfg?has_content && maskedCfg.rpcEndpoint?? && (maskedCfg.rpcEndpoint?split(':')?size > 1)>${maskedCfg.rpcEndpoint?split(':')[1]}<#else>${defaultRpcPort}</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">RPC User</label>
                            <input type="text" class="settings-input" name="rpcUser" value="<#if configured && maskedCfg?has_content>${maskedCfg.rpcUser!''}</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">RPC Password</label>
                            <input type="password" class="settings-input" name="rpcPassword" placeholder="${configured?string('unchanged', '')}"<#if !configured> required</#if> />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">Wallet Name</label>
                            <input type="text" class="settings-input" name="walletName" value="<#if configured && maskedCfg?has_content>${maskedCfg.walletName!''}</#if>" placeholder="optional" />
                        </div>
                    <#elseif coinId == "monero">
                        <h4 class="settings-subsection-title">Daemon RPC</h4>
                        <div class="settings-field">
                            <label class="settings-label">Host</label>
                            <input type="text" class="settings-input" name="daemonHost" value="<#if configured && maskedCfg?has_content && maskedCfg.daemonEndpoint??>${maskedCfg.daemonEndpoint?split(':')[0]!'127.0.0.1'}<#else>127.0.0.1</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">Port</label>
                            <input type="text" class="settings-input" name="daemonPort" value="<#if configured && maskedCfg?has_content && maskedCfg.daemonEndpoint?? && (maskedCfg.daemonEndpoint?split(':')?size > 1)>${maskedCfg.daemonEndpoint?split(':')[1]}<#else>18081</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">User</label>
                            <input type="text" class="settings-input" name="daemonUser" placeholder="optional" />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">Password</label>
                            <input type="password" class="settings-input" name="daemonPassword" placeholder="${configured?string('unchanged', 'optional')}" />
                        </div>
                        <h4 class="settings-subsection-title">Wallet RPC</h4>
                        <div class="settings-field">
                            <label class="settings-label">Host</label>
                            <input type="text" class="settings-input" name="walletRpcHost" value="<#if configured && maskedCfg?has_content && maskedCfg.walletRpcEndpoint??>${maskedCfg.walletRpcEndpoint?split(':')[0]!'127.0.0.1'}<#else>127.0.0.1</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">Port</label>
                            <input type="text" class="settings-input" name="walletRpcPort" value="<#if configured && maskedCfg?has_content && maskedCfg.walletRpcEndpoint?? && (maskedCfg.walletRpcEndpoint?split(':')?size > 1)>${maskedCfg.walletRpcEndpoint?split(':')[1]}<#else>18083</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">User</label>
                            <input type="text" class="settings-input" name="walletRpcUser" value="<#if configured && maskedCfg?has_content>${maskedCfg.walletRpcUser!''}</#if>" required />
                        </div>
                        <div class="settings-field">
                            <label class="settings-label">Password</label>
                            <input type="password" class="settings-input" name="walletRpcPassword" placeholder="${configured?string('unchanged', '')}"<#if !configured> required</#if> />
                        </div>
                    </#if>
                    <div class="settings-actions">
                        <button type="button" class="wizard-test-btn">Test Connection</button>
                        <div class="wizard-test-result"></div>
                        <button type="submit" class="wizard-save-btn">Save &amp; Enable</button>
                    </div>
                    </div>
                </form>
            </div>
        </section>

        <section class="auth-card settings-section" data-section="coin-settings" style="margin-top:1rem">
            <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
                <h3 class="auth-coin-name">Settings</h3>
                <span class="settings-expand-hint">click to expand</span><span class="settings-toggle-icon">&#9654;</span>
            </div>
            <div class="settings-card-body" hidden>
                <div class="settings-form" data-endpoint="/settings/coins/${coin.coin!''}">
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
                        <input type="number" class="settings-input" name="auth.min-approvals-required" value="${(coin.minApprovalsRequired!1)?c}" min="1" max="10" />
                    </div>
                    <h4 class="settings-subsection-title">Veto Channels</h4>
                    <div class="settings-field veto-channels-field">
                        <div class="veto-channels-list">
                            <#assign vetoList = (coin.vetoChannels![])>
                            <#assign vetoOptions = (coin.vetoChannelOptions![])>
                            <#list vetoList as vc>
                            <div class="veto-channel-row">
                                <select class="settings-input settings-select veto-channel-input">
                                    <#list vetoOptions as opt>
                                    <option value="${opt}"<#if opt == vc> selected</#if>>${opt}</option>
                                    </#list>
                                </select>
                                <button type="button" class="rule-remove-btn" title="Remove">&times;</button>
                            </div>
                            </#list>
                        </div>
                        <button type="button" class="rule-add-btn veto-add-btn">+ add veto channel</button>
                    </div>
                    <h4 class="settings-subsection-title">MCP Auth Channels</h4>
                    <div class="settings-field mcp-auth-channels-field">
                        <#assign mcpAuthList = (coin.mcpAuthChannels![])>
                        <#assign mcpAuthOptions = (coin.mcpAuthChannelOptions![])>
                        <div class="mcp-auth-channels-list">
                            <#list mcpAuthList as mac>
                            <div class="mcp-auth-channel-row">
                                <select class="settings-input settings-select mcp-auth-channel-input">
                                    <#list mcpAuthOptions as opt>
                                    <option value="${opt}"<#if opt == mac> selected</#if>>${opt}</option>
                                    </#list>
                                </select>
                                <button type="button" class="rule-remove-btn mcp-auth-remove-btn" title="Remove">&times;</button>
                            </div>
                            </#list>
                        </div>
                        <button type="button" class="rule-add-btn mcp-auth-add-btn">+ add mcp auth channel</button>
                        <#if mcpAuthOptions?size == 0>
                            <p class="settings-hint">No secondary agents configured.</p>
                        </#if>
                    </div>
                    <noscript>
                        <form method="POST" action="/wallets/${coin.coin!''}/mcp-auth-channels" class="mcp-auth-noscript-form" style="margin-top:0.5rem">
                            <#list mcpAuthList as mac>
                                <input type="hidden" name="channel" value="${mac}" />
                            </#list>
                            <div class="settings-field">
                                <label class="settings-label">Add channel</label>
                                <select name="channel" class="settings-input settings-select">
                                    <option value="">— none —</option>
                                    <#list mcpAuthOptions as opt>
                                    <option value="${opt}">${opt}</option>
                                    </#list>
                                </select>
                            </div>
                            <button type="submit" class="settings-save-btn">Save MCP Auth Channels</button>
                        </form>
                    </noscript>
                    <div class="settings-actions">
                        <button type="button" class="settings-save-btn coin-settings-save-btn">Save</button>
                        <span class="settings-status"></span>
                    </div>
                </div>
            </div>
        </section>

        <div class="deposit-quorum-row">
            <#if !(coin.disconnected!true)>
            <section class="deposit-address-panel" aria-label="Deposit address">
                <h4 class="auth-meta-label">Deposit address</h4>
                <#assign lastAddr = (coin.lastDepositAddress!'')>
                <#if lastAddr?has_content>
                    <div class="deposit-address-display">
                        <textarea class="deposit-address-textarea" readonly rows="2" id="deposit-addr-0">${lastAddr}</textarea>
                        <button
                            type="button"
                            class="deposit-copy-btn"
                            data-copy-target="deposit-addr-0"
                            aria-label="Copy deposit address to clipboard"
                            title="Copy to clipboard"
                        >
                            <span class="deposit-copy-label">copy</span>
                        </button>
                    </div>
                <#else>
                    <p class="deposit-address-empty mono">No deposit address generated yet.</p>
                </#if>
                <form method="post" action="/wallets/generate-address" class="deposit-generate-form">
                    <input type="hidden" name="coin" value="${(coin.coin!'')}">
                    <button type="submit" class="deposit-generate-btn">Generate new address</button>
                </form>
            </section>
            </#if>

            <section class="quorum-panel" aria-label="Quorum and veto channels">
                <h4 class="auth-meta-label">Quorum</h4>
                <span class="mono auth-meta-value auth-inline-meta">${coin.quorumLine!'unknown'}</span>
                <h4 class="auth-meta-label">Veto channels</h4>
                <span class="mono auth-meta-value auth-inline-meta">${coin.vetoChannelsLine!'none'}</span>
            </section>
        </div>

        <#if !(coin.disconnected!true)>
        <#assign txList = (coin.transactions![])>
        <section class="incoming-tx-panel" aria-label="Transactions">
            <h4 class="auth-meta-label">Transactions</h4>
            <#if txList?size == 0>
                <p class="deposit-address-empty mono">No recent transactions.</p>
            <#else>
                <table class="queue-table incoming-tx-table" id="tx-table-0">
                    <thead>
                        <tr>
                            <th>TxID</th>
                            <th>Direction</th>
                            <th>Amount</th>
                            <th>Confirmations</th>
                            <th>Status</th>
                            <th>Time</th>
                        </tr>
                    </thead>
                    <tbody>
                        <#list txList as tx>
                            <tr class="tx-row <#if !(tx.confirmed!false)>incoming-tx-unconfirmed</#if>" data-epoch="${(tx.epochMillis!0)?c}">
                                <td class="mono" title="${(tx.txId!'-')}">${(tx.txIdShort!'-')}</td>
                                <td><span class="tx-direction tx-direction-${(tx.direction!'incoming')}">${(tx.direction!'incoming')}</span></td>
                                <td class="mono">${(tx.amount!'-')}</td>
                                <td class="mono">${(tx.confirmations!'0')}</td>
                                <td><span class="incoming-tx-status <#if (tx.confirmed!false)>incoming-tx-confirmed-badge<#else>incoming-tx-unconfirmed-badge</#if>">${(tx.confirmed!false)?string('confirmed', 'unconfirmed')}</span></td>
                                <td class="mono">${(tx.timestamp!'-')}</td>
                            </tr>
                        </#list>
                    </tbody>
                </table>
                <div class="tx-paging" data-table="tx-table-0" <#if (txList?size lte 10)>style="display:none"</#if>>
                    <button type="button" class="tx-paging-btn tx-paging-prev" disabled>&larr; prev</button>
                    <span class="tx-paging-info mono">page 1 / ${((txList?size + 9) / 10)?int}</span>
                    <button type="button" class="tx-paging-btn tx-paging-next" <#if (txList?size lte 10)>disabled</#if>>next &rarr;</button>
                </div>
            </#if>
        </section>
        </#if>

        <section class="auth-meta-item auth-compact-block" aria-label="Auth channels">
            <h4 class="auth-meta-label">Auth channels</h4>
            <div class="auth-channel-grid auth-channel-grid-compact">
                <span class="auth-channel-badge <#if (channels.webUi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">web-ui <strong>${(channels.webUi!false)?string('on', 'off')}</strong></span>
                <span class="auth-channel-badge <#if (channels.restApi!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">rest-api <strong>${(channels.restApi!false)?string('on', 'off')}</strong></span>
                <span class="auth-channel-badge <#if (channels.telegram!false)>auth-channel-enabled<#else>auth-channel-disabled</#if>">telegram <strong>${(channels.telegram!false)?string('on', 'off')}</strong></span>
            </div>
            <#if verificationAgents?size gt 0>
                <div class="auth-chip-row auth-chip-row-tight">
                    <#list verificationAgents as agent>
                        <#if (agent.enabled!false)>
                            <span class="auth-chip auth-chip-on">
                                ${(agent.name!'unknown')} @ ${(agent.connectUrl!'unknown')}:${(agent.port!'unknown')}
                            </span>
                        <#else>
                            <span class="auth-chip auth-chip-warn">
                                ${(agent.name!'unknown')} — no mcp auth channel configured
                            </span>
                        </#if>
                    </#list>
                </div>
            </#if>
        </section>

        <#assign emptySlots = 2>
        <div class="auth-rules-grid">
            <section class="auth-rule-block auth-rule-block-accept" aria-labelledby="auth-accept-0">
                <h4 id="auth-accept-0" class="auth-rule-title">Auto Apply</h4>
                <form method="POST" action="/wallets/${coin.coin!''}/rules">
                    <input type="hidden" name="rule-key" value="auth.auto-accept" />
                    <table class="queue-table auth-rule-table rule-form-table">
                        <thead>
                            <tr>
                                <th>Rule</th>
                                <th>Amount</th>
                                <th>Time window</th>
                            </tr>
                        </thead>
                        <tbody>
                            <#list autoAcceptRules as rule>
                                <tr>
                                    <td>
                                        <select class="settings-input" name="type">
                                            <option value="value-lt"<#if (rule.type!'') == 'value-lt'> selected</#if>>amount &lt;</option>
                                            <option value="value-gt"<#if (rule.type!'') == 'value-gt'> selected</#if>>amount &gt;</option>
                                            <option value="cumulated-value-lt"<#if (rule.type!'') == 'cumulated-value-lt'> selected</#if>>sum in window &lt;</option>
                                            <option value="cumulated-value-gt"<#if (rule.type!'') == 'cumulated-value-gt'> selected</#if>>sum in window &gt;</option>
                                        </select>
                                    </td>
                                    <td><input type="number" class="settings-input" name="value" value="${rule.value!''}" placeholder="amount" step="any" min="0" /></td>
                                    <td>
                                        <span class="rule-period-group">
                                            <input type="number" class="settings-input rule-period-amount-input" name="period-amount" value="${rule.periodAmount!''}" placeholder="—" min="1" step="1" />
                                            <select class="settings-input" name="period-unit">
                                                <#assign pu = (rule.periodUnit!'h')>
                                                <option value="h"<#if pu == 'h'> selected</#if>>hours</option>
                                                <option value="d"<#if pu == 'd'> selected</#if>>days</option>
                                                <option value="w"<#if pu == 'w'> selected</#if>>weeks</option>
                                            </select>
                                        </span>
                                    </td>
                                </tr>
                            </#list>
                            <#list 1..emptySlots as i>
                                <tr class="rule-form-empty-slot">
                                    <td>
                                        <select class="settings-input" name="type">
                                            <option value="value-lt">amount &lt;</option>
                                            <option value="value-gt">amount &gt;</option>
                                            <option value="cumulated-value-lt">sum in window &lt;</option>
                                            <option value="cumulated-value-gt">sum in window &gt;</option>
                                        </select>
                                    </td>
                                    <td><input type="number" class="settings-input" name="value" placeholder="amount" step="any" min="0" /></td>
                                    <td>
                                        <span class="rule-period-group">
                                            <input type="number" class="settings-input rule-period-amount-input" name="period-amount" placeholder="—" min="1" step="1" />
                                            <select class="settings-input" name="period-unit">
                                                <option value="h">hours</option>
                                                <option value="d">days</option>
                                                <option value="w">weeks</option>
                                            </select>
                                        </span>
                                    </td>
                                </tr>
                            </#list>
                        </tbody>
                    </table>
                    <div class="settings-actions" style="margin-top:6px">
                        <button type="submit" class="settings-save-btn rule-save-btn">Save rules</button>
                    </div>
                </form>
            </section>

            <section class="auth-rule-block auth-rule-block-deny" aria-labelledby="auth-deny-0">
                <h4 id="auth-deny-0" class="auth-rule-title">Auto Deny</h4>
                <form method="POST" action="/wallets/${coin.coin!''}/rules">
                    <input type="hidden" name="rule-key" value="auth.auto-deny" />
                    <table class="queue-table auth-rule-table rule-form-table">
                        <thead>
                            <tr>
                                <th>Rule</th>
                                <th>Amount</th>
                                <th>Time window</th>
                            </tr>
                        </thead>
                        <tbody>
                            <#list autoDenyRules as rule>
                                <tr>
                                    <td>
                                        <select class="settings-input" name="type">
                                            <option value="value-lt"<#if (rule.type!'') == 'value-lt'> selected</#if>>amount &lt;</option>
                                            <option value="value-gt"<#if (rule.type!'') == 'value-gt'> selected</#if>>amount &gt;</option>
                                            <option value="cumulated-value-lt"<#if (rule.type!'') == 'cumulated-value-lt'> selected</#if>>sum in window &lt;</option>
                                            <option value="cumulated-value-gt"<#if (rule.type!'') == 'cumulated-value-gt'> selected</#if>>sum in window &gt;</option>
                                        </select>
                                    </td>
                                    <td><input type="number" class="settings-input" name="value" value="${rule.value!''}" placeholder="amount" step="any" min="0" /></td>
                                    <td>
                                        <span class="rule-period-group">
                                            <input type="number" class="settings-input rule-period-amount-input" name="period-amount" value="${rule.periodAmount!''}" placeholder="—" min="1" step="1" />
                                            <select class="settings-input" name="period-unit">
                                                <#assign pu = (rule.periodUnit!'h')>
                                                <option value="h"<#if pu == 'h'> selected</#if>>hours</option>
                                                <option value="d"<#if pu == 'd'> selected</#if>>days</option>
                                                <option value="w"<#if pu == 'w'> selected</#if>>weeks</option>
                                            </select>
                                        </span>
                                    </td>
                                </tr>
                            </#list>
                            <#list 1..emptySlots as i>
                                <tr class="rule-form-empty-slot">
                                    <td>
                                        <select class="settings-input" name="type">
                                            <option value="value-lt">amount &lt;</option>
                                            <option value="value-gt">amount &gt;</option>
                                            <option value="cumulated-value-lt">sum in window &lt;</option>
                                            <option value="cumulated-value-gt">sum in window &gt;</option>
                                        </select>
                                    </td>
                                    <td><input type="number" class="settings-input" name="value" placeholder="amount" step="any" min="0" /></td>
                                    <td>
                                        <span class="rule-period-group">
                                            <input type="number" class="settings-input rule-period-amount-input" name="period-amount" placeholder="—" min="1" step="1" />
                                            <select class="settings-input" name="period-unit">
                                                <option value="h">hours</option>
                                                <option value="d">days</option>
                                                <option value="w">weeks</option>
                                            </select>
                                        </span>
                                    </td>
                                </tr>
                            </#list>
                        </tbody>
                    </table>
                    <div class="settings-actions" style="margin-top:6px">
                        <button type="submit" class="settings-save-btn rule-save-btn">Save rules</button>
                    </div>
                </form>
            </section>
        </div>
        <#if (walletData.ruleFlash!'') != ''>
            <div class="rule-flash-error" id="rule-errors">${walletData.ruleFlash}</div>
        </#if>

        <#if channelWarnings?size gt 0>
            <ul class="auth-warning-list">
                <#list channelWarnings as warning>
                    <li>${warning}</li>
                </#list>
            </ul>
        </#if>
    </section>
</div></main>

<@m.secretToggleScript containerSelectors=".auth-mcp-item, .auth-secret" />
<@m.settingsScript />

<script>
(() => {
    // Copy-to-clipboard for deposit addresses
    const copyButtons = document.querySelectorAll('.deposit-copy-btn[data-copy-target]');
    for (const btn of copyButtons) {
        btn.addEventListener('click', () => {
            const targetId = btn.dataset.copyTarget;
            const textarea = document.getElementById(targetId);
            if (!textarea) return;

            const text = textarea.value;
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(() => {
                    showCopyFeedback(btn);
                }).catch(() => {
                    fallbackCopy(textarea, btn);
                });
            } else {
                fallbackCopy(textarea, btn);
            }
        });
    }

    function fallbackCopy(textarea, btn) {
        textarea.select();
        textarea.setSelectionRange(0, textarea.value.length);
        try {
            document.execCommand('copy');
            showCopyFeedback(btn);
        } catch (_) {}
    }

    function showCopyFeedback(btn) {
        const label = btn.querySelector('.deposit-copy-label');
        if (!label) return;
        const original = label.textContent;
        label.textContent = 'copied!';
        btn.classList.add('deposit-copy-success');
        setTimeout(() => {
            label.textContent = original;
            btn.classList.remove('deposit-copy-success');
        }, 1500);
    }

    // Transaction table paging (10 rows per page)
    const pagingContainers = document.querySelectorAll('.tx-paging[data-table]');
    for (const paging of pagingContainers) {
        const tableId = paging.dataset.table;
        const table = document.getElementById(tableId);
        if (!table) continue;

        const rows = Array.from(table.querySelectorAll('tbody .tx-row'));
        const pageSize = 10;
        const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
        let currentPage = 1;

        const prevBtn = paging.querySelector('.tx-paging-prev');
        const nextBtn = paging.querySelector('.tx-paging-next');
        const info = paging.querySelector('.tx-paging-info');

        function renderPage() {
            const start = (currentPage - 1) * pageSize;
            const end = start + pageSize;
            for (let i = 0; i < rows.length; i++) {
                rows[i].style.display = (i >= start && i < end) ? '' : 'none';
            }
            if (info) info.textContent = 'page ' + currentPage + ' / ' + totalPages;
            if (prevBtn) prevBtn.disabled = currentPage <= 1;
            if (nextBtn) nextBtn.disabled = currentPage >= totalPages;
        }

        if (prevBtn) prevBtn.addEventListener('click', () => {
            if (currentPage > 1) { currentPage--; renderPage(); }
        });
        if (nextBtn) nextBtn.addEventListener('click', () => {
            if (currentPage < totalPages) { currentPage++; renderPage(); }
        });

        renderPage();
    }
})();
</script>

<script>
(() => {
    // Veto channel add/remove
    const vetoList = document.querySelector('.veto-channels-list');
    const vetoAddBtn = document.querySelector('.veto-add-btn');
    const vetoOptions = [<#list (coin.vetoChannelOptions![]) as opt>'${opt}'<#if opt?has_next>,</#if></#list>];
    if (vetoAddBtn && vetoList) {
        vetoAddBtn.addEventListener('click', () => {
            const row = document.createElement('div');
            row.className = 'veto-channel-row';
            const select = document.createElement('select');
            select.className = 'settings-input settings-select veto-channel-input';
            for (const opt of vetoOptions) {
                const o = document.createElement('option');
                o.value = opt;
                o.textContent = opt;
                select.appendChild(o);
            }
            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'rule-remove-btn';
            removeBtn.title = 'Remove';
            removeBtn.innerHTML = '&times;';
            removeBtn.addEventListener('click', () => row.remove());
            row.appendChild(select);
            row.appendChild(removeBtn);
            vetoList.appendChild(row);
        });
        for (const btn of vetoList.querySelectorAll('.rule-remove-btn')) {
            btn.addEventListener('click', () => btn.closest('.veto-channel-row').remove());
        }
    }

    // MCP auth channel add/remove
    const mcpList = document.querySelector('.mcp-auth-channels-list');
    const mcpAddBtn = document.querySelector('.mcp-auth-add-btn');
    const mcpOptions = [<#list (coin.mcpAuthChannelOptions![]) as opt>'${opt}'<#if opt?has_next>,</#if></#list>];
    if (mcpAddBtn && mcpList) {
        mcpAddBtn.addEventListener('click', () => {
            const row = document.createElement('div');
            row.className = 'mcp-auth-channel-row';
            const select = document.createElement('select');
            select.className = 'settings-input settings-select mcp-auth-channel-input';
            for (const opt of mcpOptions) {
                const o = document.createElement('option');
                o.value = opt;
                o.textContent = opt;
                select.appendChild(o);
            }
            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'rule-remove-btn mcp-auth-remove-btn';
            removeBtn.title = 'Remove';
            removeBtn.innerHTML = '&times;';
            removeBtn.addEventListener('click', () => row.remove());
            row.appendChild(select);
            row.appendChild(removeBtn);
            mcpList.appendChild(row);
        });
        for (const btn of mcpList.querySelectorAll('.mcp-auth-remove-btn')) {
            btn.addEventListener('click', () => btn.closest('.mcp-auth-channel-row').remove());
        }
    }

    // Coin settings save: collect veto channels + MCP auth channels + reload on success
    const coinSaveBtn = document.querySelector('.coin-settings-save-btn');
    if (coinSaveBtn) {
        coinSaveBtn.addEventListener('click', async () => {
            const form = coinSaveBtn.closest('.settings-form');
            if (!form) return;
            const endpoint = form.dataset.endpoint;
            const status = form.querySelector('.settings-status');
            const body = {};

            const inputs = form.querySelectorAll('input[name], select[name]');
            for (const input of inputs) {
                const name = input.name;
                if (!name) continue;
                if (input.type === 'checkbox') {
                    body[name] = input.checked;
                } else if (input.type === 'number') {
                    body[name] = input.value === '' ? '' : Number(input.value);
                } else {
                    body[name] = input.value;
                }
            }

            // Collect veto channels
            const vetoInputs = form.querySelectorAll('.veto-channel-input');
            const vetoChannels = [];
            for (const vi of vetoInputs) {
                const v = vi.value.trim();
                if (v) vetoChannels.push(v);
            }
            body['auth.veto-channels'] = vetoChannels;

            // Collect MCP auth channels
            const mcpInputs = form.querySelectorAll('.mcp-auth-channel-input');
            const mcpAuthChannels = [];
            for (const mi of mcpInputs) {
                const v = mi.value.trim();
                if (v && !mcpAuthChannels.includes(v)) mcpAuthChannels.push(v);
            }
            body['auth.mcp-auth-channels'] = mcpAuthChannels;

            coinSaveBtn.disabled = true;
            if (status) { status.textContent = 'saving...'; status.className = 'settings-status'; }

            try {
                const resp = await fetch(endpoint, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const result = await resp.json();
                if (result.success) {
                    if (status) {
                        status.textContent = 'saved — reloading...';
                        status.className = 'settings-status settings-status-ok';
                    }
                    setTimeout(() => location.reload(), 600);
                } else {
                    if (status) { status.textContent = result.errorMessage || 'error'; status.className = 'settings-status settings-status-error'; }
                    coinSaveBtn.disabled = false;
                }
            } catch (err) {
                if (status) { status.textContent = 'network error'; status.className = 'settings-status settings-status-error'; }
                coinSaveBtn.disabled = false;
            }
        });
    }
})();
</script>

<@m.footer />
</div>

<script>
(() => {
    // ── Test Connection (JS enhancement — form POST works without this) ──
    const testButtons = document.querySelectorAll('.wizard-test-btn');
    for (const testBtn of testButtons) {
        testBtn.addEventListener('click', async () => {
            const panel = testBtn.closest('.wizard-panel');
            if (!panel) return;
            const coinId = panel.dataset.coin;
            const resultEl = panel.querySelector('.wizard-test-result');

            const extraWarnings = panel.querySelectorAll('.wizard-test-result.warning');
            for (const w of extraWarnings) {
                if (w !== resultEl) w.remove();
            }

            const body = {};
            const inputs = panel.querySelectorAll('input[name]');
            for (const input of inputs) {
                body[input.name] = input.value;
            }

            testBtn.disabled = true;
            testBtn.textContent = 'Testing...';
            if (resultEl) {
                resultEl.textContent = '';
                resultEl.className = 'wizard-test-result';
            }

            try {
                const resp = await fetch('/wallets/' + encodeURIComponent(coinId) + '/test-connection', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await resp.json();

                if (data.success) {
                    if (resultEl) {
                        resultEl.textContent = data.message || 'Connection successful';
                        resultEl.className = 'wizard-test-result success';
                    }
                    if (data.warnings && data.warnings.length > 0) {
                        const warningDiv = document.createElement('div');
                        warningDiv.className = 'wizard-test-result warning';
                        warningDiv.textContent = data.warnings.join('; ');
                        if (resultEl) resultEl.after(warningDiv);
                    }
                } else {
                    if (resultEl) {
                        resultEl.textContent = data.message || 'Connection failed';
                        resultEl.className = 'wizard-test-result error';
                    }
                }
            } catch (err) {
                if (resultEl) {
                    resultEl.textContent = 'Network error — could not reach server';
                    resultEl.className = 'wizard-test-result error';
                }
            } finally {
                testBtn.disabled = false;
                testBtn.textContent = 'Test Connection';
            }
        });
    }

    // ── Save Connection (progressive enhancement over <form> POST) ──
    const wizardForms = document.querySelectorAll('form.wizard-panel');
    for (const form of wizardForms) {
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const coinId = form.dataset.coin;
            const resultEl = form.querySelector('.wizard-test-result');
            const saveBtn = form.querySelector('.wizard-save-btn');

            const body = {};
            const inputs = form.querySelectorAll('input[name]');
            for (const input of inputs) {
                body[input.name] = input.value;
            }

            if (saveBtn) {
                saveBtn.disabled = true;
                saveBtn.textContent = 'Saving...';
            }

            try {
                const resp = await fetch('/wallets/' + encodeURIComponent(coinId) + '/save-connection', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await resp.json();

                if (data.success) {
                    if (resultEl) {
                        resultEl.textContent = 'Saved successfully — reloading...';
                        resultEl.className = 'wizard-test-result success';
                    }
                    setTimeout(() => {
                        window.location.href = '/wallets/' + encodeURIComponent(coinId);
                    }, 600);
                } else {
                    if (resultEl) {
                        resultEl.textContent = data.message || 'Save failed';
                        resultEl.className = 'wizard-test-result error';
                    }
                    if (saveBtn) {
                        saveBtn.disabled = false;
                        saveBtn.textContent = 'Save & Enable';
                    }
                }
            } catch (err) {
                if (resultEl) {
                    resultEl.textContent = 'Network error — could not reach server';
                    resultEl.className = 'wizard-test-result error';
                }
                if (saveBtn) {
                    saveBtn.disabled = false;
                    saveBtn.textContent = 'Save & Enable';
                }
            }
        });
    }
})();
</script>

</@layout.page>
