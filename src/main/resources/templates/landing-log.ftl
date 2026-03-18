<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-log" />

<div class="page-body">
<main class="main-section"><div class="content">
    <h2 class="queue-title">History</h2>

    <#assign queueNotice = (queuePage.queueNotice!'')>
    <#assign queueNoticeError = (queuePage.queueNoticeError!false)>
    <#assign queueConfirmRequired = (queuePage.queueConfirmRequired!false)>
    <#assign queueConfirmDecision = (queuePage.queueConfirmDecision!'')>
    <#assign queueConfirmRequestId = (queuePage.queueConfirmRequestId!'')>
    <#assign queueConfirmRequestIdShort = (queuePage.queueConfirmRequestIdShort!'-')>
    <#assign queueConfirmActionPath = (queuePage.queueConfirmActionPath!'')>
    <#assign queueConfirmCoin = (queuePage.queueConfirmCoin!'')>
    <#assign queueConfirmAmountNative = (queuePage.queueConfirmAmountNative!'')>
    <#assign queueConfirmToAddress = (queuePage.queueConfirmToAddress!'')>
    <#assign queueConfirmToolName = (queuePage.queueConfirmToolName!'')>
    <#assign queueConfirmReason = (queuePage.queueConfirmReason!'')>

    <#if queueNotice?has_content>
        <div class="queue-notice<#if queueNoticeError> queue-notice-error</#if>">${queueNotice}</div>
    </#if>

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
                    <input type="hidden" name="_csrf" value="${csrfToken!''}">
                    <input type="hidden" name="request_id" value="${queueConfirmRequestId}">
                    <input type="hidden" name="confirm" value="yes">
                    <button
                        type="submit"
                        class="queue-action-btn queue-action-deny"
                    >confirm ${queueConfirmDecision}</button>
                </form>
                <a
                    href="${auditLogPath}"
                    class="queue-action-btn queue-action-cancel"
                >go back</a>
            </div>
        </section>
    </#if>

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
            <a href="${auditLogPath}/export" class="btn btn-sm btn-export" title="Export successful transactions (CoinTracking CSV)">&#x1F4E5; Export CSV</a>
        </div>

        <form method="get" action="${auditLogPath}" class="log-filter-form">
            <input type="hidden" name="log_queue_sort" value="${lqSort}">
            <input type="hidden" name="log_queue_dir" value="${lqDir}">
            <input type="hidden" name="log_queue_page" value="1">
            <input type="hidden" name="log_queue_page_size" value="${lqPageSize}">

            <select class="log-filter-select" name="log_queue_coin">
                <option value="">all coins</option>
                <#list lqFilterCoins as coin>
                    <option value="${coin}"<#if coin == lqFilterCoin> selected</#if>>${coin}</option>
                </#list>
            </select>

            <select class="log-filter-select" name="log_queue_tool">
                <option value="">all tools</option>
                <#list lqFilterTools as tool>
                    <option value="${tool}"<#if tool == lqFilterTool> selected</#if>>${tool}</option>
                </#list>
            </select>

            <select class="log-filter-select" name="log_queue_state">
                <option value="">all states</option>
                <#list lqFilterStates as state>
                    <option value="${state}"<#if state == lqFilterState> selected</#if>>${state}</option>
                </#list>
            </select>

            <input
                class="log-filter-input"
                type="text"
                name="log_queue_filter"
                value="${lqFilterText}"
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
                            <pre id="details-source-log-${row?index}" class="queue-details-source" hidden>${(row.detailsJson!'{}')}</pre>
                        </td>
                        <td class="action-cell">
                            <#if (row.state!'') == 'QUEUED_FOR_EXECUTION'>
                                <form method="post" action="/queue/cancel" class="queue-decision-form" data-decision="cancel"
                                      data-coin="${(row.coin!'')}" data-amount="${(row.amountNative!'')}"
                                      data-to-address="${(row.toAddress!'')}" data-tool="${(row.toolName!'')}"
                                      data-reason="${(row.reason!'')}">
                                    <input type="hidden" name="_csrf" value="${csrfToken!''}">
                                    <input type="hidden" name="request_id" value="${(row.id!'')}">
                                    <button type="submit" class="queue-action-btn queue-action-deny">cancel</button>
                                </form>
                            </#if>
                        </td>
                    </tr>
                </#list>
            </#if>
            </tbody>
        </table>
    </section>
</div></main>

<@m.copyButtonScript />
<@m.detailsExpandScript defaultColSpan=8 />

<div id="queue-confirm-modal" class="queue-confirm-modal" hidden>
    <div class="queue-confirm-modal-card" role="dialog" aria-modal="true" aria-labelledby="queue-confirm-modal-title">
        <h3 id="queue-confirm-modal-title" class="queue-confirm-modal-title">Confirm cancellation</h3>
        <p id="queue-confirm-modal-message" class="queue-confirm-modal-copy">Cancel this queued transaction?</p>
        <div id="queue-confirm-modal-details" class="queue-confirm-details" hidden></div>
        <div class="queue-confirm-modal-actions">
            <button type="button" id="queue-confirm-modal-cancel" class="queue-action-btn queue-action-cancel">go back</button>
            <button type="button" id="queue-confirm-modal-submit" class="queue-action-btn queue-action-deny">confirm cancel</button>
        </div>
    </div>
</div>

<script>
(() => {
    const decisionForms = document.querySelectorAll('.queue-decision-form[data-decision]');
    const confirmModal = document.getElementById('queue-confirm-modal');
    const confirmMessage = document.getElementById('queue-confirm-modal-message');
    const confirmDetails = document.getElementById('queue-confirm-modal-details');
    const confirmCancel = document.getElementById('queue-confirm-modal-cancel');
    const confirmSubmit = document.getElementById('queue-confirm-modal-submit');
    let pendingDecisionForm = null;

    const closeConfirmModal = () => {
        if (confirmModal) confirmModal.hidden = true;
        if (confirmDetails) { confirmDetails.innerHTML = ''; confirmDetails.hidden = true; }
        pendingDecisionForm = null;
    };

    const esc = s => (s || '').replace(/</g, '&lt;');

    const openConfirmModal = form => {
        if (!confirmModal || !confirmMessage || !confirmSubmit) return false;
        pendingDecisionForm = form;
        confirmMessage.textContent = 'Cancel this queued transaction?';

        const tool = form.getAttribute('data-tool') || '';
        const coin = form.getAttribute('data-coin') || '';
        const amount = form.getAttribute('data-amount') || '';
        const toAddr = form.getAttribute('data-to-address') || '';
        const reason = form.getAttribute('data-reason') || '';
        let html = '';
        if (tool) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Tool:</span> <span class="mono">' + esc(tool) + '</span></p>';
        if (coin) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Coin:</span> <span class="mono">' + esc(coin) + '</span></p>';
        if (amount) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Amount:</span> <span class="mono">' + esc(amount) + '</span></p>';
        if (toAddr) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">To Address:</span> <span class="mono queue-confirm-address">' + esc(toAddr) + '</span></p>';
        if (reason) html += '<p class="queue-confirm-detail"><span class="queue-confirm-detail-label">Reason:</span> <span class="mono">' + esc(reason) + '</span></p>';
        if (html && confirmDetails) {
            confirmDetails.innerHTML = html;
            confirmDetails.hidden = false;
        }

        confirmModal.hidden = false;
        confirmSubmit.focus();
        return true;
    };

    decisionForms.forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            if (!openConfirmModal(form)) form.submit();
        });
    });

    if (confirmCancel) confirmCancel.addEventListener('click', closeConfirmModal);
    if (confirmSubmit) confirmSubmit.addEventListener('click', () => {
        if (pendingDecisionForm) {
            const hiddenConfirm = document.createElement('input');
            hiddenConfirm.type = 'hidden';
            hiddenConfirm.name = 'confirm';
            hiddenConfirm.value = 'yes';
            pendingDecisionForm.appendChild(hiddenConfirm);
            pendingDecisionForm.submit();
        }
    });

    if (confirmModal) {
        confirmModal.addEventListener('click', e => { if (e.target === confirmModal) closeConfirmModal(); });
        document.addEventListener('keydown', e => { if (e.key === 'Escape' && !confirmModal.hidden) closeConfirmModal(); });
    }
})();
</script>

<@m.footer />
</div>
</@layout.page>
