<#-- Shared macros to eliminate template duplication -->

<#-- Footer macro - used by all sidebar-bearing pages -->
<#macro footer>
<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN - Keeper Of Nodes, Keys, and Independent Networks</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</#macro>

<#-- Full sidebar navigation macro - used by most pages -->
<#macro sidebar menuToggleId>
<aside class="sidebar">
    <a href="/" class="brand">
        <img src="${assetsPath}/img/logo_v2_small_trans.png?v=${assetsVersion}" alt="KONKIN logo" class="brand-logo">
    </a>
    <input type="checkbox" id="${menuToggleId}" class="menu-toggle" aria-hidden="true">
    <label for="${menuToggleId}" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "history"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
        <#if activePage == "driver_agent"><span class="menu-active">driver agent<#if (driverAgentWarn!false)> <span class="menu-warn">&#9888;</span></#if></span><#else><a href="${driverAgentPath}">driver agent<#if (driverAgentWarn!false)> <span class="menu-warn">&#9888;</span></#if></a></#if>
        <#assign walletPages = enabledCoins?map(c -> "wallet_" + c)>
        <#assign isWalletSubActive = walletPages?seq_contains(activePage)>
        <#if activePage == "wallets"><span class="menu-active">wallets<#if (walletsWarn!false)> <span class="menu-warn">&#9888;</span></#if></span><#else><a href="${walletsPath}"<#if isWalletSubActive> class="menu-group-active"</#if>>wallets<#if (walletsWarn!false)> <span class="menu-warn">&#9888;</span></#if></a></#if>
        <#list enabledCoins as ec>
            <#assign coinDisconnected = (disconnectedWallets[ec])!false>
            <#if activePage == "wallet_" + ec><span class="menu-active menu-sub">${ec}<#if coinDisconnected> <span class="menu-warn">&#9888;</span></#if></span><#else><a href="/wallets/${ec}" class="menu-sub">${ec}<#if coinDisconnected> <span class="menu-warn">&#9888;</span></#if></a></#if>
        </#list>
        <#assign authChannelSubPages = ["auth_channel_webui", "auth_channel_api_keys", "auth_channel_telegram"]>
        <#assign isAuthChannelSubActive = authChannelSubPages?seq_contains(activePage)>
        <#if activePage == "auth_channels"><span class="menu-active">auth channels</span><#else><a href="${authChannelsPath}"<#if isAuthChannelSubActive> class="menu-group-active"</#if>>auth channels</a></#if>
        <#if activePage == "auth_channel_webui"><span class="menu-active menu-sub">web ui</span><#else><a href="/auth_channels/web-ui" class="menu-sub">web ui</a></#if>
        <#if activePage == "auth_channel_api_keys"><span class="menu-active menu-sub">api keys<#if restApiKeyMissing> <span class="menu-warn">&#9888;</span></#if></span><#else><a href="${apiKeysPath}" class="menu-sub">api keys<#if restApiKeyMissing> <span class="menu-warn">&#9888;</span></#if></a></#if>
        <#if telegramPageAvailable>
            <#if activePage == "auth_channel_telegram"><span class="menu-active menu-sub">telegram</span><#else><a href="${telegramPath}" class="menu-sub">telegram</a></#if>
        </#if>
        <#if showLogout>
            <form method="post" action="/logout" class="logout-form">
                <button type="submit" class="logout-btn">logout</button>
            </form>
        </#if>
        <span class="app-version">${appVersion}</span>
    </nav>
</aside>
</#macro>

<#-- Secret toggle JavaScript - used by wallet and auth-channels pages -->
<#macro secretToggleScript containerSelectors=".auth-channel-secret-wrap, .auth-mcp-item, .auth-secret">
<script>
(() => {
    const secretValues = document.querySelectorAll('.auth-secret-value[data-secret-value]');

    for (const valueEl of secretValues) {
        const container = valueEl.closest('${containerSelectors}');
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
                const currentLabel = toggle.getAttribute('aria-label') || '';
                toggle.setAttribute('aria-label', currentLabel.replace('Hide', 'Reveal') || 'Reveal secret');
                const currentTitle = toggle.getAttribute('title') || '';
                toggle.setAttribute('title', currentTitle.replace('Hide', 'Reveal') || 'Reveal secret');
                toggle.classList.remove('is-revealed');
            }
        });
    }
})();
</script>
</#macro>

<#-- Copy button JavaScript - used by queue and history pages -->
<#macro copyButtonScript>
<script>
(() => {
    const copyButtons = document.querySelectorAll('.queue-copy-btn[data-copy-value]');

    const setMessage = (button, text) => {
        const original = button.dataset.originalLabel || 'copy';
        button.textContent = text;
        window.setTimeout(() => {
            button.textContent = original;
        }, 1200);
    };

    for (const button of copyButtons) {
        button.dataset.originalLabel = button.textContent || 'copy';

        if (!navigator.clipboard || !navigator.clipboard.writeText) {
            continue;
        }

        button.hidden = false;
        button.addEventListener('click', async () => {
            const value = button.getAttribute('data-copy-value') || '';
            try {
                await navigator.clipboard.writeText(value);
                setMessage(button, 'copied');
            } catch (err) {
                setMessage(button, 'failed');
            }
        });
    }
})();
</script>
</#macro>

<#-- Inline details expand/collapse JavaScript - used by queue and history pages -->
<#macro detailsExpandScript defaultColSpan=9>
<script>
(() => {
    const esc = s => {
        if (s == null) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    };

    const buildDetailsHtml = raw => {
        let data;
        try { data = JSON.parse(raw); } catch(e) { return '<pre>' + esc(raw) + '</pre>'; }

        const req = data.request || {};
        const history = data.history || [];
        const deps = data.dependencies || {};
        const channels = deps.channels || [];
        const votes = deps.votes || [];
        const executions = deps.executionAttempts || [];

        let html = '';

        // summary bar
        html += '<div class="details-summary">';
        html += '<span class="details-summary-item"><span class="details-summary-label">id: </span><span class="details-summary-value">' + esc(req.id) + '</span></span>';
        html += '<span class="details-summary-item"><span class="details-summary-label">coin: </span><span class="details-summary-value">' + esc(req.coin) + '</span></span>';
        html += '<span class="details-summary-item"><span class="details-summary-label">tool: </span><span class="details-summary-value">' + esc(req.toolName) + '</span></span>';
        html += '<span class="details-summary-item"><span class="details-summary-label">amount: </span><span class="details-summary-value">' + esc(req.amountNative) + '</span></span>';
        html += '<span class="details-summary-item"><span class="details-summary-label">to: </span><span class="details-summary-value">' + esc(req.toAddress) + '</span></span>';
        html += '<span class="details-summary-item"><span class="details-summary-label">state: </span><span class="details-summary-value">' + esc(req.state) + '</span></span>';
        if (req.feePolicy) html += '<span class="details-summary-item"><span class="details-summary-label">fee: </span><span class="details-summary-value">' + esc(req.feePolicy) + (req.feeCapNative ? ' (cap ' + esc(req.feeCapNative) + ')' : '') + '</span></span>';
        html += '<span class="details-summary-item"><span class="details-summary-label">quorum: </span><span class="details-summary-value">' + esc(req.approvalsGranted) + '-of-' + esc(req.minApprovalsRequired) + '</span></span>';
        if (req.memo) html += '<span class="details-summary-item"><span class="details-summary-label">memo: </span><span class="details-summary-value">' + esc(req.memo) + '</span></span>';
        if (req.reason) html += '<span class="details-summary-item"><span class="details-summary-label">reason: </span><span class="details-summary-value">' + esc(req.reason) + '</span></span>';
        html += '</div>';

        // build combined timeline events
        const events = [];

        // request creation
        if (req.requestedAt) {
            events.push({ time: req.requestedAt, cls: 'tl-created', text: 'request created', meta: req.policyActionAtCreation ? 'policy: ' + req.policyActionAtCreation : '' });
        }

        // state transitions
        for (const t of history) {
            let text = (t.fromState || '(new)') + ' \u2192 ' + (t.toState || '?');
            let cls = 'tl-created';
            const toLower = (t.toState || '').toLowerCase();
            if (toLower === 'approved') cls = 'tl-approved';
            else if (toLower === 'denied') cls = 'tl-denied';
            else if (toLower === 'expired') cls = 'tl-expired';
            else if (toLower === 'cancelled') cls = 'tl-cancelled';
            else if (toLower === 'executed') cls = 'tl-executed';
            else if (toLower === 'failed' || toLower === 'execution_failed') cls = 'tl-failed';

            let meta = '';
            if (t.actorType) meta += t.actorType;
            if (t.actorId) meta += ':' + t.actorId;
            if (t.reasonCode) meta += ' \u2014 ' + t.reasonCode;
            if (t.reasonText) meta += ': ' + t.reasonText;

            events.push({ time: t.createdAt || '', cls, text, meta });
        }

        // channel deliveries
        for (const ch of channels) {
            let cls = 'tl-delivered';
            let text = 'notification \u2192 ' + (ch.channelId || '?') + ' (' + (ch.deliveryState || '?') + ')';
            let meta = '';
            if (ch.attemptCount > 1) meta += ch.attemptCount + ' attempts';
            if (ch.lastError) meta += (meta ? ' \u2014 ' : '') + ch.lastError;
            const time = ch.firstSentAt || ch.createdAt || '';
            if ((ch.deliveryState || '').toLowerCase() === 'failed') cls = 'tl-failed';
            events.push({ time, cls, text, meta });
        }

        // votes
        for (const v of votes) {
            const decision = (v.decision || '?').toLowerCase();
            const cls = decision === 'approve' ? 'tl-vote-approve' : 'tl-vote-deny';
            let text = 'vote: ' + decision;
            if (v.decidedBy) text += ' by ' + v.decidedBy;
            if (v.channelId) text += ' via ' + v.channelId;
            let meta = v.decisionReason || '';
            events.push({ time: v.decidedAt || '', cls, text, meta });
        }

        // execution attempts
        for (const ex of executions) {
            const result = (ex.result || '').toLowerCase();
            const cls = result === 'success' ? 'tl-executed' : 'tl-failed';
            let text = 'execution #' + (ex.attemptNo || '?') + ': ' + (ex.result || '?');
            let meta = '';
            if (ex.txid) meta += 'txid: ' + ex.txid;
            if (ex.daemonFeeNative) meta += (meta ? ' \u2014 ' : '') + 'fee: ' + ex.daemonFeeNative;
            if (ex.errorClass) meta += (meta ? ' \u2014 ' : '') + ex.errorClass;
            if (ex.errorMessage) meta += ': ' + ex.errorMessage;
            events.push({ time: ex.startedAt || '', cls, text, meta });
        }

        // sort by time
        events.sort((a, b) => (a.time || '').localeCompare(b.time || ''));

        // render timeline
        html += '<div class="details-section-title">history</div>';
        if (events.length === 0) {
            html += '<div class="details-no-history">no history available</div>';
        } else {
            html += '<ul class="details-timeline">';
            for (const ev of events) {
                html += '<li class="details-timeline-item ' + esc(ev.cls) + '">';
                html += '<span class="details-timeline-time">' + esc(ev.time) + '</span>';
                html += '<span class="details-timeline-event">' + esc(ev.text) + '</span>';
                if (ev.meta) html += '<div class="details-timeline-meta">' + esc(ev.meta) + '</div>';
                html += '</li>';
            }
            html += '</ul>';
        }

        // waiting-for explanation
        const stateLower = (req.state || '').toLowerCase();
        if (stateLower === 'queued' || stateLower === 'pending') {
            html += '<div class="details-section-title">waiting for</div>';
            const needed = (req.minApprovalsRequired || 0) - (req.approvalsGranted || 0);
            let waitHtml = '';
            if (needed > 0) {
                waitHtml += '<span class="details-timeline-event">' + needed + ' more approval' + (needed > 1 ? 's' : '') + ' needed</span>';
            }
            if (channels.length > 0) {
                const pendingChannels = channels.filter(ch => (ch.deliveryState || '').toLowerCase() !== 'delivered' && (ch.deliveryState || '').toLowerCase() !== 'sent');
                if (pendingChannels.length > 0) {
                    waitHtml += '<div class="details-timeline-meta">notifications pending delivery on: ' + esc(pendingChannels.map(ch => ch.channelId).join(', ')) + '</div>';
                }
            }
            if (req.expiresAt) {
                waitHtml += '<div class="details-timeline-meta">expires at: ' + esc(req.expiresAt) + '</div>';
            }
            if (!waitHtml) waitHtml = '<span class="details-timeline-event">waiting for system processing</span>';
            html += waitHtml;
        }

        // raw json toggle
        html += '<button class="details-raw-toggle" onclick="this.nextElementSibling.classList.toggle(\'is-visible\');this.textContent=this.nextElementSibling.classList.contains(\'is-visible\')?\'hide raw json\':\'show raw json\'">show raw json</button>';
        html += '<div class="details-raw-section"><pre>' + esc(JSON.stringify(data, null, 2)) + '</pre></div>';

        return html;
    };

    const detailTriggers = document.querySelectorAll('.queue-details-trigger[data-details-source-id]');
    let expandedRow = null;
    let expandedTrigger = null;

    const collapseExpanded = () => {
        if (expandedRow) {
            expandedRow.remove();
            expandedRow = null;
        }
        if (expandedTrigger) {
            expandedTrigger.classList.remove('is-open');
            expandedTrigger = null;
        }
    };

    for (const trigger of detailTriggers) {
        trigger.addEventListener('click', event => {
            event.preventDefault();

            if (expandedTrigger === trigger) {
                collapseExpanded();
                return;
            }

            const sourceId = trigger.getAttribute('data-details-source-id');
            const sourceElement = sourceId ? document.getElementById(sourceId) : null;
            if (!sourceElement) {
                window.location.href = trigger.getAttribute('href') || '/';
                return;
            }

            const parentRow = trigger.closest('tr');
            if (!parentRow) {
                return;
            }

            collapseExpanded();

            const detailsRow = document.createElement('tr');
            detailsRow.className = 'details-expanded-row';

            const detailsCell = document.createElement('td');
            detailsCell.colSpan = parentRow.children.length || ${defaultColSpan};

            const detailsBox = document.createElement('div');
            detailsBox.className = 'details-box details-box-expanded';

            const raw = sourceElement.textContent || '{}';
            detailsBox.innerHTML = buildDetailsHtml(raw);

            detailsCell.appendChild(detailsBox);
            detailsRow.appendChild(detailsCell);
            parentRow.insertAdjacentElement('afterend', detailsRow);

            expandedRow = detailsRow;
            expandedTrigger = trigger;
            expandedTrigger.classList.add('is-open');
        });
    }
})();
</script>
</#macro>

<#-- Agent select JavaScript - used by driver-agent and auth-channels pages -->
<#macro agentSelectScript selectSelector=".auth-agent-mcp-select" commandsSelector=".auth-agent-mcp-commands" indexAttr="data-reg-index">
<script>
(() => {
    const selects = document.querySelectorAll('${selectSelector}');
    for (const sel of selects) {
        sel.addEventListener('change', () => {
            <#if indexAttr?has_content>
            const regIndex = sel.dataset.regIndex;
            const blocks = document.querySelectorAll('${commandsSelector}[${indexAttr}="' + regIndex + '"]');
            <#else>
            const blocks = document.querySelectorAll('${commandsSelector}');
            </#if>
            for (const block of blocks) {
                block.style.display = block.getAttribute('data-agent-id') === sel.value ? '' : 'none';
            }
        });
    }
})();
</script>
</#macro>
