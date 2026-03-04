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
    <input type="checkbox" id="menu-toggle-log" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-log" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "log"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
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

<main class="main-section"><div class="content">
    <h2 class="queue-title">History</h2>

    <#assign lqSort = (logQueuePage.sortBy!'updated_at')>
    <#assign lqDir = (logQueuePage.sortDir!'desc')>
    <#assign lqPage = (logQueuePage.page!1)>
    <#assign lqPageSize = (logQueuePage.pageSize!25)>
    <#assign lqTotalRows = (logQueuePage.totalRows!0)>
    <#assign lqTotalPages = (logQueuePage.totalPages!0)>
    <#assign lqFilterText = (logQueuePage.filterText!(logQueuePage.filterQuery!''))>
    <#assign lqFilterCoin = (logQueuePage.filterCoin!'')>
    <#assign lqFilterTool = (logQueuePage.filterTool!'')>
    <#assign lqFilterState = (logQueuePage.filterState!'')>
    <#assign lqFilterCoins = (logQueuePage.filterCoins![])>
    <#assign lqFilterTools = (logQueuePage.filterTools![])>
    <#assign lqFilterStates = (logQueuePage.filterStates![])>

    <#macro logPager extraClass="">
        <div class="pager${extraClass}">
            <#if (logQueuePage.hasPrev!false)>
                <a class="pager-link" href="${auditLogPath}?log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${logQueuePage.prevPage!1}&log_queue_page_size=${lqPageSize}&log_queue_filter=${lqFilterText?url('UTF-8')}&log_queue_coin=${lqFilterCoin?url('UTF-8')}&log_queue_tool=${lqFilterTool?url('UTF-8')}&log_queue_state=${lqFilterState?url('UTF-8')}">Prev</a>
            <#else>
                <span class="pager-link disabled">Prev</span>
            </#if>
            <span class="pager-info">Page ${lqPage} of ${lqTotalPages}</span>
            <#if (logQueuePage.hasNext!false)>
                <a class="pager-link" href="${auditLogPath}?log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${logQueuePage.nextPage!1}&log_queue_page_size=${lqPageSize}&log_queue_filter=${lqFilterText?url('UTF-8')}&log_queue_coin=${lqFilterCoin?url('UTF-8')}&log_queue_tool=${lqFilterTool?url('UTF-8')}&log_queue_state=${lqFilterState?url('UTF-8')}">Next</a>
            <#else>
                <span class="pager-link disabled">Next</span>
            </#if>
        </div>
    </#macro>

    <#macro logSortHeader sortKey label>
        <#assign nextDir = "asc">
        <#if lqSort == sortKey && lqDir == "asc">
            <#assign nextDir = "desc">
        </#if>
        <th>
            <a
                class="sort-link<#if lqSort == sortKey> active</#if>"
                href="${auditLogPath}?log_queue_sort=${sortKey}&log_queue_dir=${nextDir}&log_queue_page=1&log_queue_page_size=${lqPageSize}&log_queue_filter=${lqFilterText?url('UTF-8')}&log_queue_coin=${lqFilterCoin?url('UTF-8')}&log_queue_tool=${lqFilterTool?url('UTF-8')}&log_queue_state=${lqFilterState?url('UTF-8')}"
            >
                ${label}
                <#if lqSort == sortKey>
                    <span class="sort-indicator"><#if lqDir == "asc">↑<#else>↓</#if></span>
                </#if>
            </a>
        </th>
    </#macro>

    <section class="queue-table-shell queue-log-section">
        <h3 class="queue-subtitle">Resolved / Processed Requests</h3>

        <div class="table-toolbar">
            <span class="table-meta">rows: ${lqTotalRows} · page ${lqPage} / ${lqTotalPages}</span>
        </div>

        <form method="get" action="${auditLogPath}" class="log-filter-form">
            <input type="hidden" name="log_queue_sort" value="${lqSort}">
            <input type="hidden" name="log_queue_dir" value="${lqDir}">
            <input type="hidden" name="log_queue_page" value="1">
            <input type="hidden" name="log_queue_page_size" value="${lqPageSize}">

            <select class="log-filter-select" name="log_queue_coin">
                <option value="">all coins</option>
                <#list lqFilterCoins as coin>
                    <option value="${coin?html}"<#if coin == lqFilterCoin> selected</#if>>${coin}</option>
                </#list>
            </select>

            <select class="log-filter-select" name="log_queue_tool">
                <option value="">all tools</option>
                <#list lqFilterTools as tool>
                    <option value="${tool?html}"<#if tool == lqFilterTool> selected</#if>>${tool}</option>
                </#list>
            </select>

            <select class="log-filter-select" name="log_queue_state">
                <option value="">all states</option>
                <#list lqFilterStates as state>
                    <option value="${state?html}"<#if state == lqFilterState> selected</#if>>${state}</option>
                </#list>
            </select>

            <input
                class="log-filter-input"
                type="text"
                name="log_queue_filter"
                value="${lqFilterText?html}"
                placeholder="Filter by id or decider"
            >
            <button type="submit" class="queue-action-btn log-filter-btn">filter</button>
            <#if lqFilterText?has_content || lqFilterCoin?has_content || lqFilterTool?has_content || lqFilterState?has_content>
                <a class="queue-action-btn log-filter-btn" href="${auditLogPath}?log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=1&log_queue_page_size=${lqPageSize}">clear</a>
            </#if>
        </form>

        <@logPager extraClass=" pager-top" />

        <table class="queue-table">
            <thead>
            <tr>
                <@logSortHeader sortKey="id" label="Id" />
                <@logSortHeader sortKey="coin" label="Coin" />
                <@logSortHeader sortKey="tool_name" label="Tool" />
                <@logSortHeader sortKey="state" label="State" />
                <@logSortHeader sortKey="updated_at" label="Last Action" />
                <th>Decider(s)</th>
                <th class="action-header"></th>
                <th class="action-header"></th>
            </tr>
            </thead>
            <tbody>
            <#if logQueueRows?size == 0>
                <tr>
                    <td colspan="8" class="empty-row">No resolved/processed requests found.</td>
                </tr>
            <#else>
                <#list logQueueRows as row>
                    <tr>
                        <td class="queue-id-cell">
                            <span class="mono queue-id-short">${row.idFirst5!'-'}</span>
                            <#if (row.id!'-') != '-'>
                                <button
                                    type="button"
                                    class="queue-action-btn queue-copy-btn"
                                    hidden
                                    data-copy-value="${(row.id!'')?html}"
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
                        <td class="queue-status-cell">
                            <span class="status ${row.statusClass!'pending'}">${row.state!'UNKNOWN'}</span>
                        </td>
                        <td class="mono queue-nowrap queue-small-text">${row.lastActionAt!'-'}</td>
                        <td class="queue-small-text">${row.deciders!'-'}</td>
                        <td class="action-cell details-cell">
                            <a
                                class="queue-action-btn queue-action-details queue-details-trigger"
                                href="/details?id=${(row.id!'')?url('UTF-8')}"
                                data-details-source-id="details-source-log-${row?index}"
                            >details</a>
                            <pre id="details-source-log-${row?index}" class="queue-details-source" hidden>${(row.detailsJson!'{}')?html}</pre>
                        </td>
                        <td class="action-cell"></td>
                    </tr>
                </#list>
            </#if>
            </tbody>
        </table>
    </section>
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
            detailsCell.colSpan = parentRow.children.length || 8;

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

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
