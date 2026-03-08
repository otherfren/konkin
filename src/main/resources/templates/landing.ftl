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
    <input type="checkbox" id="menu-toggle-queue" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-queue" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "history"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
        <#assign walletPages = enabledCoins?map(c -> "wallet_" + c)>
        <#assign isWalletSubActive = walletPages?seq_contains(activePage)>
        <#if activePage == "wallets"><span class="menu-active">wallets</span><#else><a href="${walletsPath}"<#if isWalletSubActive> class="menu-group-active"</#if>>wallets</a></#if>
        <#list enabledCoins as ec>
            <#if activePage == "wallet_" + ec><span class="menu-active menu-sub">${ec}</span><#else><a href="/wallets/${ec}" class="menu-sub">${ec}</a></#if>
        </#list>
        <#if activePage == "driver_agent"><span class="menu-active">driver agent</span><#else><a href="${driverAgentPath}">driver agent</a></#if>
        <#assign authChannelSubPages = ["auth_channel_webui"]>
        <#assign isAuthChannelSubActive = authChannelSubPages?seq_contains(activePage)>
        <#if activePage == "auth_channels"><span class="menu-active">auth channels</span><#else><a href="${authChannelsPath}"<#if isAuthChannelSubActive> class="menu-group-active"</#if>>auth channels</a></#if>
        <#if activePage == "auth_channel_webui"><span class="menu-active menu-sub">web ui</span><#else><a href="/auth_channels/web-ui" class="menu-sub">web ui</a></#if>
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
<main class="main-section"><div class="content">
    <h2 class="queue-title">Authorization Queue</h2>

    <#assign qSort = (queuePage.sortBy!'expires_at')>
    <#assign qDir = (queuePage.sortDir!'asc')>
    <#assign qPage = (queuePage.page!1)>
    <#assign qPageSize = (queuePage.pageSize!25)>
    <#assign qTotalRows = (queuePage.totalRows!0)>
    <#assign qTotalPagesRaw = (queuePage.totalPages!0)>
    <#assign qTotalPages = (qTotalPagesRaw < 1)?then(1, qTotalPagesRaw)>
    <#assign queueNotice = (queuePage.queueNotice!'')>
    <#assign queueNoticeError = (queuePage.queueNoticeError!false)>
    <#assign queueConfirmRequired = (queuePage.queueConfirmRequired!false)>
    <#assign queueConfirmDecision = (queuePage.queueConfirmDecision!'approve')>
    <#assign queueConfirmRequestId = (queuePage.queueConfirmRequestId!'')>
    <#assign queueConfirmRequestIdShort = (queuePage.queueConfirmRequestIdShort!'-')>
    <#assign queueConfirmActionPath = (queuePage.queueConfirmActionPath!'/queue/approve')>

    <#if queueNotice?has_content>
        <div class="queue-notice <#if queueNoticeError>queue-notice-error<#else>queue-notice-success</#if>">
            ${queueNotice}
        </div>
    </#if>

    <#assign queueConfirmCoin = (queuePage.queueConfirmCoin!'')>
    <#assign queueConfirmAmountNative = (queuePage.queueConfirmAmountNative!'')>
    <#assign queueConfirmToAddress = (queuePage.queueConfirmToAddress!'')>
    <#assign queueConfirmToolName = (queuePage.queueConfirmToolName!'')>
    <#assign queueConfirmReason = (queuePage.queueConfirmReason!'')>

    <#if queueConfirmRequired>
        <section class="queue-confirm-panel" aria-labelledby="queue-confirm-title">
            <h3 id="queue-confirm-title" class="queue-confirm-title">Confirmation required</h3>
            <p class="queue-confirm-copy">
                Confirm <strong>${queueConfirmDecision}</strong> for request <span class="mono">${queueConfirmRequestIdShort}</span>.
            </p>
            <#if queueConfirmToolName?has_content || queueConfirmCoin?has_content || queueConfirmAmountNative?has_content || queueConfirmToAddress?has_content>
                <div class="queue-confirm-details">
                    <#if queueConfirmToolName?has_content><p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Tool:</span> <span class="mono">${queueConfirmToolName}</span></p></#if>
                    <#if queueConfirmCoin?has_content><p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Coin:</span> <span class="mono">${queueConfirmCoin}</span></p></#if>
                    <#if queueConfirmAmountNative?has_content><p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Amount:</span> <span class="mono">${queueConfirmAmountNative}</span></p></#if>
                    <#if queueConfirmToAddress?has_content><p class="queue-confirm-detail"><span class="queue-confirm-detail-label">To Address:</span> <span class="mono queue-confirm-address">${queueConfirmToAddress}</span></p></#if>
                    <#if queueConfirmReason?has_content><p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Reason:</span> <span class="mono">${queueConfirmReason}</span></p></#if>
                </div>
            </#if>
            <div class="queue-confirm-actions">
                <form method="post" action="${queueConfirmActionPath}" class="queue-confirm-inline-form">
                    <input type="hidden" name="request_id" value="${queueConfirmRequestId}">
                    <input type="hidden" name="confirm" value="yes">
                    <button
                        type="submit"
                        class="queue-action-btn <#if queueConfirmDecision == 'deny'>queue-action-deny<#else>queue-action-approve</#if>"
                    >confirm ${queueConfirmDecision}</button>
                </form>
                <a
                    href="${queuePath}?queue_sort=${qSort}&queue_dir=${qDir}&queue_page=${qPage}&queue_page_size=${qPageSize}"
                    class="queue-action-btn queue-action-cancel"
                >cancel</a>
            </div>
        </section>
    </#if>

    <#macro queuePager extraClass="">
        <div class="pager${extraClass}">
            <#if (queuePage.hasPrev!false)>
                <a class="pager-link" href="${queuePath}?queue_sort=${qSort}&queue_dir=${qDir}&queue_page=${queuePage.prevPage!1}&queue_page_size=${qPageSize}">Prev</a>
            <#else>
                <span class="pager-link disabled">Prev</span>
            </#if>
            <span class="pager-info">Page ${qPage} of ${qTotalPages}</span>
            <#if (queuePage.hasNext!false)>
                <a class="pager-link" href="${queuePath}?queue_sort=${qSort}&queue_dir=${qDir}&queue_page=${queuePage.nextPage!1}&queue_page_size=${qPageSize}">Next</a>
            <#else>
                <span class="pager-link disabled">Next</span>
            </#if>
        </div>
    </#macro>

    <div class="queue-table-shell">
        <div class="table-toolbar">
            <span class="table-meta">rows: ${qTotalRows} · page ${qPage} / ${qTotalPages}</span>
        </div>

        <#if (queueRows?size) gt 8>
            <@queuePager extraClass=" pager-top" />
        </#if>

        <table class="queue-table">
            <thead>
                <tr>
                    <th>Id</th>
                    <th>Coin</th>
                    <th>Tool</th>
                    <th>Amount</th>
                    <th>Quorum Status</th>
                    <th>Requested</th>
                    <th>Expires</th>
                    <th class="action-header"></th>
                    <th class="action-header"></th>
                    <th class="action-header"></th>
                </tr>
            </thead>
            <tbody>
                <#if queueRows?size == 0>
                    <tr>
                        <td colspan="10" class="empty-row">No approval requests found.</td>
                    </tr>
                <#else>
                    <#list queueRows as row>
                        <tr>
                            <td class="queue-id-cell">
                                <span class="mono queue-id-short">${row.idShort!'-'}</span>
                                <#if (row.id!'-') != '-'>
                                    <button
                                        type="button"
                                        class="queue-action-btn queue-copy-btn"
                                        hidden
                                        data-copy-value="${(row.id!'')}"
                                    >copy</button>
                                </#if>
                            </td>
                            <td class="coin-cell">
                                <#if (row.coinIconName!'')?has_content>
                                    <img
                                        class="coin-icon"
                                        src="${assetsPath}/img/${row.coinIconName}.svg?v=${assetsVersion}"
                                        alt="${row.coin!'coin'} icon"
                                        title="${row.coin!'-'}"
                                    >
                                <#else>
                                    <span class="mono queue-small-text" title="${row.coin!'-'}">${row.coin!'-'}</span>
                                </#if>
                            </td>
                            <td class="queue-tool-cell">${row.toolName!'-'}</td>
                            <td class="mono queue-small-text queue-nowrap">${row.amountNative!'-'}</td>
                            <td class="queue-status-cell">
                                <span class="status ${row.statusClass!'pending'}">${row.quorumLabel!'pending 0-of-0'}</span>
                            </td>
                            <td class="mono queue-nowrap queue-small-text">${row.requestedAt!'-'}</td>
                            <td class="mono queue-nowrap queue-small-text">${row.expiresIn!'-'}</td>
                            <td class="action-cell">
                                <form method="post" action="/queue/approve" class="queue-decision-form" data-decision="approve"
                                      data-coin="${(row.coin!'')}" data-amount="${(row.amountNative!'')}"
                                      data-to-address="${(row.toAddress!'')}" data-tool="${(row.toolName!'')}"
                                      data-reason="${(row.reason!'')}">
                                    <input type="hidden" name="request_id" value="${(row.id!'')}">
                                    <button type="submit" class="queue-action-btn queue-action-approve">approve</button>
                                </form>
                            </td>
                            <td class="action-cell">
                                <form method="post" action="/queue/deny" class="queue-decision-form" data-decision="deny"
                                      data-coin="${(row.coin!'')}" data-amount="${(row.amountNative!'')}"
                                      data-to-address="${(row.toAddress!'')}" data-tool="${(row.toolName!'')}"
                                      data-reason="${(row.reason!'')}">
                                    <input type="hidden" name="request_id" value="${(row.id!'')}">
                                    <button type="submit" class="queue-action-btn queue-action-deny">deny</button>
                                </form>
                            </td>
                            <td class="action-cell details-cell">
                                <a
                                    class="queue-action-btn queue-action-details queue-details-trigger"
                                    href="/details?id=${(row.id!'')?url('UTF-8')}"
                                    data-details-source-id="details-source-${row?index}"
                                >details</a>
                                <pre id="details-source-${row?index}" class="queue-details-source" hidden>${(row.detailsJson!'{}')}</pre>
                            </td>
                        </tr>
                    </#list>
                </#if>
            </tbody>
        </table>

        <@queuePager />
    </div>

    <div id="queue-confirm-modal" class="queue-confirm-modal" hidden>
        <div class="queue-confirm-modal-card" role="dialog" aria-modal="true" aria-labelledby="queue-confirm-modal-title">
            <h3 id="queue-confirm-modal-title" class="queue-confirm-modal-title">Confirm queue decision</h3>
            <p id="queue-confirm-modal-message" class="queue-confirm-modal-copy">Proceed with this action?</p>
            <div id="queue-confirm-modal-details" class="queue-confirm-details" hidden></div>
            <div class="queue-confirm-modal-actions">
                <button type="button" id="queue-confirm-modal-cancel" class="queue-action-btn queue-action-cancel">cancel</button>
                <button type="button" id="queue-confirm-modal-submit" class="queue-action-btn queue-action-approve">confirm</button>
            </div>
        </div>
    </div>
</div></main>

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
            detailsCell.colSpan = parentRow.children.length || 9;

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

    const decisionForms = document.querySelectorAll('.queue-decision-form[data-decision]');
    const confirmModal = document.getElementById('queue-confirm-modal');
    const confirmMessage = document.getElementById('queue-confirm-modal-message');
    const confirmCancel = document.getElementById('queue-confirm-modal-cancel');
    const confirmSubmit = document.getElementById('queue-confirm-modal-submit');
    let pendingDecisionForm = null;

    const closeConfirmModal = () => {
        if (!confirmModal) {
            return;
        }
        confirmModal.classList.remove('is-open');
        confirmModal.hidden = true;
        pendingDecisionForm = null;
    };

    const confirmDetails = document.getElementById('queue-confirm-modal-details');

    const openConfirmModal = form => {
        if (!confirmModal || !confirmMessage || !confirmSubmit) {
            return;
        }

        pendingDecisionForm = form;
        const decision = form.getAttribute('data-decision') === 'deny' ? 'deny' : 'approve';
        const requestField = form.querySelector('input[name="request_id"]');
        const requestId = requestField ? requestField.value.trim() : '';
        const requestIdShort = requestId.length > 5 ? (requestId.slice(0, 5) + '...') : (requestId || 'unknown');

        confirmMessage.textContent = 'Confirm ' + decision + ' for request ' + requestIdShort + '?';
        confirmSubmit.textContent = decision === 'deny' ? 'confirm deny' : 'confirm approve';
        confirmSubmit.classList.remove('queue-action-approve', 'queue-action-deny');
        confirmSubmit.classList.add(decision === 'deny' ? 'queue-action-deny' : 'queue-action-approve');

        if (confirmDetails) {
            const tool = form.getAttribute('data-tool') || '';
            const coin = form.getAttribute('data-coin') || '';
            const amount = form.getAttribute('data-amount') || '';
            const toAddr = form.getAttribute('data-to-address') || '';
            const reason = form.getAttribute('data-reason') || '';
            let html = '';
            if (tool) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Tool:</span> <span class="mono">' + tool.replace(/</g, '&lt;') + '</span></p>';
            if (coin) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Coin:</span> <span class="mono">' + coin.replace(/</g, '&lt;') + '</span></p>';
            if (amount) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Amount:</span> <span class="mono">' + amount.replace(/</g, '&lt;') + '</span></p>';
            if (toAddr) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">To Address:</span> <span class="mono queue-confirm-address">' + toAddr.replace(/</g, '&lt;') + '</span></p>';
            if (reason) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Reason:</span> <span class="mono">' + reason.replace(/</g, '&lt;') + '</span></p>';
            if (html) {
                confirmDetails.innerHTML = html;
                confirmDetails.hidden = false;
            } else {
                confirmDetails.innerHTML = '';
                confirmDetails.hidden = true;
            }
        }

        confirmModal.hidden = false;
        window.requestAnimationFrame(() => confirmModal.classList.add('is-open'));
    };

    if (confirmModal && confirmMessage && confirmCancel && confirmSubmit) {
        for (const form of decisionForms) {
            form.addEventListener('submit', event => {
                if (form.dataset.confirmed === 'yes') {
                    return;
                }
                event.preventDefault();
                openConfirmModal(form);
            });
        }

        confirmCancel.addEventListener('click', () => {
            closeConfirmModal();
        });

        confirmModal.addEventListener('click', event => {
            if (event.target === confirmModal) {
                closeConfirmModal();
            }
        });

        document.addEventListener('keydown', event => {
            if (event.key === 'Escape' && confirmModal.classList.contains('is-open')) {
                closeConfirmModal();
            }
        });

        confirmSubmit.addEventListener('click', () => {
            if (!pendingDecisionForm) {
                return;
            }

            let confirmInput = pendingDecisionForm.querySelector('input[name="confirm"]');
            if (!confirmInput) {
                confirmInput = document.createElement('input');
                confirmInput.type = 'hidden';
                confirmInput.name = 'confirm';
                pendingDecisionForm.appendChild(confirmInput);
            }
            confirmInput.value = 'yes';
            pendingDecisionForm.dataset.confirmed = 'yes';
            pendingDecisionForm.submit();
        });
    }
})();
</script>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</div>
</@layout.page>
