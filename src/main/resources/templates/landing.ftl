<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-queue" />

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
                        <tr<#if row.votedByWebUi!false> class="queue-row-voted" title="You voted: ${row.webUiVoteDecision!''}"</#if>>
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
                                <#if row.votedByWebUi!false>
                                    <button type="button" class="queue-action-btn queue-action-approve" disabled title="You already voted: ${row.webUiVoteDecision!''}">approve</button>
                                <#else>
                                    <form method="post" action="/queue/approve" class="queue-decision-form" data-decision="approve"
                                          data-coin="${(row.coin!'')}" data-amount="${(row.amountNative!'')}"
                                          data-to-address="${(row.toAddress!'')}" data-tool="${(row.toolName!'')}"
                                          data-reason="${(row.reason!'')}">
                                        <input type="hidden" name="request_id" value="${(row.id!'')}">
                                        <button type="submit" class="queue-action-btn queue-action-approve">approve</button>
                                    </form>
                                </#if>
                            </td>
                            <td class="action-cell">
                                <#if row.votedByWebUi!false>
                                    <button type="button" class="queue-action-btn queue-action-deny" disabled title="You already voted: ${row.webUiVoteDecision!''}">deny</button>
                                <#else>
                                    <form method="post" action="/queue/deny" class="queue-decision-form" data-decision="deny"
                                          data-coin="${(row.coin!'')}" data-amount="${(row.amountNative!'')}"
                                          data-to-address="${(row.toAddress!'')}" data-tool="${(row.toolName!'')}"
                                          data-reason="${(row.reason!'')}">
                                        <input type="hidden" name="request_id" value="${(row.id!'')}">
                                        <button type="submit" class="queue-action-btn queue-action-deny">deny</button>
                                    </form>
                                </#if>
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

<@m.copyButtonScript />
<@m.detailsExpandScript defaultColSpan=9 />

<script>
(() => {
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

<@m.footer />
</div>
</@layout.page>
