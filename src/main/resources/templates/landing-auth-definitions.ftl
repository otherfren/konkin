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
    <input type="checkbox" id="menu-toggle-wallets" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-wallets" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "history"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
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

<main class="main-section"><div class="content auth-definitions-content">
    <h2 class="queue-title">Wallets</h2>
    <p class="auth-definitions-subtitle">Compact wallet overview from config.toml and runtime fallbacks.</p>

    <#assign configuredAuthChannels = (wallets.configuredAuthChannels![])>

    <section class="auth-overview-panel" aria-labelledby="auth-overview-title">
        <h3 id="auth-overview-title" class="auth-section-title">Auth channel configured</h3>
        <div class="auth-chip-row">
            <#list configuredAuthChannels as channel>
                <span class="auth-chip <#if (channel.enabled!false)>auth-chip-on<#else>auth-chip-off</#if>">
                    ${(channel.name!'-')?html}: <strong>${(channel.enabled!false)?string('enabled', 'disabled')}</strong>
                </span>
            </#list>
        </div>
    </section>

    <#assign coins = (wallets.coins![])>
    <#if coins?size == 0>
        <section class="auth-card auth-card-empty">
            <p class="telegram-empty">No wallet configuration available.</p>
        </section>
    <#else>
        <#list coins as coin>
            <#assign channels = (coin.channels!{})>
            <#assign channelWarnings = (coin.channelWarnings![])>
            <#assign autoAcceptRules = (coin.autoAcceptRules![])>
            <#assign autoDenyRules = (coin.autoDenyRules![])>
            <#assign verificationAgents = (coin.verificationAgents![])>

            <section class="auth-card" aria-labelledby="auth-coin-${coin?index}">
                <div class="auth-card-header">
                    <h3 id="auth-coin-${coin?index}" class="auth-coin-name auth-coin-title">
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
                        <span class="mono auth-meta-value auth-secret-value" data-secret-value="${(coin.maskedBalance!'unknown')?html}" data-masked="true">***</span>
                        <button
                            type="button"
                            class="auth-secret-toggle"
                            aria-label="Reveal wallet balance"
                            title="Reveal balance"
                        >
                            <span aria-hidden="true">👁</span>
                        </button>
                    </span>
                </section>

                <div class="deposit-quorum-row">
                    <#if !(coin.disconnected!true)>
                    <section class="deposit-address-panel" aria-label="Deposit address">
                        <h4 class="auth-meta-label">Deposit address</h4>
                        <#assign lastAddr = (coin.lastDepositAddress!'')>
                        <#if lastAddr?has_content>
                            <div class="deposit-address-display">
                                <textarea class="deposit-address-textarea" readonly rows="2" id="deposit-addr-${coin?index}">${lastAddr?html}</textarea>
                                <button
                                    type="button"
                                    class="deposit-copy-btn"
                                    data-copy-target="deposit-addr-${coin?index}"
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
                            <input type="hidden" name="coin" value="${(coin.coin!'')?html}">
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
                        <table class="queue-table incoming-tx-table" id="tx-table-${coin?index}">
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
                                        <td class="mono" title="${(tx.txId!'-')?html}">${(tx.txIdShort!'-')?html}</td>
                                        <td><span class="tx-direction tx-direction-${(tx.direction!'incoming')?html}">${(tx.direction!'incoming')?html}</span></td>
                                        <td class="mono">${(tx.amount!'-')?html}</td>
                                        <td class="mono">${(tx.confirmations!'0')?html}</td>
                                        <td><span class="incoming-tx-status <#if (tx.confirmed!false)>incoming-tx-confirmed-badge<#else>incoming-tx-unconfirmed-badge</#if>">${(tx.confirmed!false)?string('confirmed', 'unconfirmed')}</span></td>
                                        <td class="mono">${(tx.timestamp!'-')?html}</td>
                                    </tr>
                                </#list>
                            </tbody>
                        </table>
                        <div class="tx-paging" data-table="tx-table-${coin?index}" <#if (txList?size lte 10)>style="display:none"</#if>>
                            <button type="button" class="tx-paging-btn tx-paging-prev" disabled>← prev</button>
                            <span class="tx-paging-info mono">page 1 / ${((txList?size + 9) / 10)?int}</span>
                            <button type="button" class="tx-paging-btn tx-paging-next" <#if (txList?size lte 10)>disabled</#if>>next →</button>
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
                                    ${(agent.name!'unknown')?html} @ ${(agent.connectUrl!'unknown')?html}:${(agent.port!'unknown')?html}
                                </span>
                            </#list>
                        </div>
                    </#if>
                </section>

                <div class="auth-rules-grid">
                    <section class="auth-rule-block auth-rule-block-accept" aria-labelledby="auth-accept-${coin?index}">
                        <h4 id="auth-accept-${coin?index}" class="auth-rule-title">Auto Apply</h4>
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
                    </section>

                    <section class="auth-rule-block auth-rule-block-deny" aria-labelledby="auth-deny-${coin?index}">
                        <h4 id="auth-deny-${coin?index}" class="auth-rule-title">Auto Deny</h4>
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
                    </section>
                </div>

                <#if channelWarnings?size gt 0>
                    <ul class="auth-warning-list">
                        <#list channelWarnings as warning>
                            <li>${warning?html}</li>
                        </#list>
                    </ul>
                </#if>
            </section>
        </#list>
    </#if>
</div></main>

<script>
(() => {
    const secretValues = document.querySelectorAll('.auth-secret-value[data-secret-value]');

    for (const valueEl of secretValues) {
        const container = valueEl.closest('.auth-mcp-item, .auth-secret');
        const toggle = container ? container.querySelector('.auth-secret-toggle') : null;
        if (!toggle) {
            continue;
        }

        toggle.addEventListener('click', () => {
            const masked = valueEl.dataset.masked !== 'false';
            if (masked) {
                valueEl.textContent = valueEl.dataset.secretValue || '-';
                valueEl.dataset.masked = 'false';
                const revealLabel = toggle.getAttribute('aria-label') || 'Reveal secret';
                const hideLabel = revealLabel.replace('Reveal', 'Hide');
                const revealTitle = toggle.getAttribute('title') || 'Reveal secret';
                const hideTitle = revealTitle.replace('Reveal', 'Hide');
                toggle.setAttribute('aria-label', hideLabel);
                toggle.setAttribute('title', hideTitle);
                toggle.classList.add('is-revealed');
            } else {
                valueEl.textContent = '***';
                valueEl.dataset.masked = 'true';
                const revealLabel = toggle.getAttribute('aria-label') || 'Reveal secret';
                const hideLabel = revealLabel.replace('Reveal', 'Hide');
                toggle.setAttribute('aria-label', hideLabel.startsWith('Hide') ? hideLabel.replace('Hide', 'Reveal') : 'Reveal secret');
                const revealTitle = toggle.getAttribute('title') || 'Reveal secret';
                const hideTitle = revealTitle.replace('Reveal', 'Hide');
                toggle.setAttribute('title', hideTitle.startsWith('Hide') ? hideTitle.replace('Hide', 'Reveal') : 'Reveal secret');
                toggle.classList.remove('is-revealed');
            }
        });
    }

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

    // ── Transaction table paging (10 rows per page) ──
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

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
