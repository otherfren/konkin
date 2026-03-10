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
        </div>
        <br/>
        <section class="auth-meta-item auth-compact-block" aria-label="Connection and secrets">
            <h4 class="auth-meta-label">Connection</h4>
            <span class="mono auth-meta-value auth-inline-meta">${coin.connectionStatus!'unknown'} · last life-sign: ${coin.lastLifeSign!'unknown'}</span>
            <h4 class="auth-meta-label">Secrets</h4>
            <span class="mono auth-meta-value auth-inline-meta">daemon: ${coin.daemonSecretFile!'unknown'} · wallet: ${coin.walletSecretFile!'unknown'}</span>
            <h4 class="auth-meta-label">Balance</h4>
            <span class="auth-secret">
                <span class="mono auth-meta-value auth-secret-value" data-secret-value="${(coin.maskedBalance!'unknown')}" data-masked="true">***</span>
                <button
                    type="button"
                    class="auth-secret-toggle"
                    aria-label="Reveal wallet balance"
                    title="Reveal balance"
                >
                    <span aria-hidden="true">&#x1F441;</span>
                </button>
            </span>
        </section>

        <section class="auth-card settings-section" data-section="coin-settings" style="margin-top:1rem">
            <div class="auth-card-header settings-card-header" role="button" tabindex="0" aria-expanded="false">
                <h3 class="auth-coin-name">Settings</h3>
                <span class="settings-toggle-icon">&#9654;</span>
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
                        <span class="auth-chip <#if (agent.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                            ${(agent.name!'unknown')} @ ${(agent.connectUrl!'unknown')}:${(agent.port!'unknown')}
                        </span>
                    </#list>
                </div>
            </#if>
        </section>

        <div class="auth-rules-grid">
            <section class="auth-rule-block auth-rule-block-accept" aria-labelledby="auth-accept-0">
                <h4 id="auth-accept-0" class="auth-rule-title">Auto Apply</h4>
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
                <div class="rule-editor" data-coin="${coin.coin!''}" data-rule-key="auth.auto-accept">
                    <div class="rule-editor-toggle" role="button" tabindex="0" aria-expanded="false">&#9654; edit rules</div>
                    <div class="rule-editor-body" hidden>
                        <div class="rule-editor-list">
                            <#list autoAcceptRules as rule>
                            <div class="rule-editor-row">
                                <select class="settings-input rule-type-select" name="type">
                                    <option value="value-lt"<#if (rule.type!'') == 'value-lt'> selected</#if>>amount &lt;</option>
                                    <option value="value-gt"<#if (rule.type!'') == 'value-gt'> selected</#if>>amount &gt;</option>
                                    <option value="cumulated-value-lt"<#if (rule.type!'') == 'cumulated-value-lt'> selected</#if>>sum in window &lt;</option>
                                    <option value="cumulated-value-gt"<#if (rule.type!'') == 'cumulated-value-gt'> selected</#if>>sum in window &gt;</option>
                                </select>
                                <input type="number" class="settings-input rule-value-input" name="value" value="${rule.value!''}" placeholder="amount" step="any" min="0" />
                                <input type="text" class="settings-input rule-period-input" name="period" value="<#if rule.period != '-'>${rule.period!''}</#if>" placeholder="e.g. 24h" />
                                <button type="button" class="rule-remove-btn" title="Remove rule">&times;</button>
                            </div>
                            </#list>
                        </div>
                        <button type="button" class="rule-add-btn">+ add rule</button>
                        <div class="settings-actions">
                            <button type="button" class="settings-save-btn rule-save-btn">Save</button>
                            <span class="settings-status"></span>
                        </div>
                    </div>
                </div>
            </section>

            <section class="auth-rule-block auth-rule-block-deny" aria-labelledby="auth-deny-0">
                <h4 id="auth-deny-0" class="auth-rule-title">Auto Deny</h4>
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
                <div class="rule-editor" data-coin="${coin.coin!''}" data-rule-key="auth.auto-deny">
                    <div class="rule-editor-toggle" role="button" tabindex="0" aria-expanded="false">&#9654; edit rules</div>
                    <div class="rule-editor-body" hidden>
                        <div class="rule-editor-list">
                            <#list autoDenyRules as rule>
                            <div class="rule-editor-row">
                                <select class="settings-input rule-type-select" name="type">
                                    <option value="value-lt"<#if (rule.type!'') == 'value-lt'> selected</#if>>amount &lt;</option>
                                    <option value="value-gt"<#if (rule.type!'') == 'value-gt'> selected</#if>>amount &gt;</option>
                                    <option value="cumulated-value-lt"<#if (rule.type!'') == 'cumulated-value-lt'> selected</#if>>sum in window &lt;</option>
                                    <option value="cumulated-value-gt"<#if (rule.type!'') == 'cumulated-value-gt'> selected</#if>>sum in window &gt;</option>
                                </select>
                                <input type="number" class="settings-input rule-value-input" name="value" value="${rule.value!''}" placeholder="amount" step="any" min="0" />
                                <input type="text" class="settings-input rule-period-input" name="period" value="<#if rule.period != '-'>${rule.period!''}</#if>" placeholder="e.g. 24h" />
                                <button type="button" class="rule-remove-btn" title="Remove rule">&times;</button>
                            </div>
                            </#list>
                        </div>
                        <button type="button" class="rule-add-btn">+ add rule</button>
                        <div class="settings-actions">
                            <button type="button" class="settings-save-btn rule-save-btn">Save</button>
                            <span class="settings-status"></span>
                        </div>
                    </div>
                </div>
            </section>
        </div>

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
<@m.ruleEditorScript />

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

    // Coin settings save: collect veto channels + reload on success
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
</@layout.page>
