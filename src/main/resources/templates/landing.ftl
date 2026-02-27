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
    <input type="checkbox" id="menu-toggle-queue" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-queue" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "log"><span class="menu-active">audit</span><#else><a href="${auditLogPath}">audit</a></#if>
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
    <h2 class="queue-title">Authorization Queue</h2>

    <#assign qSort = (queuePage.sortBy!'expires_at')>
    <#assign qDir = (queuePage.sortDir!'asc')>
    <#assign qPage = (queuePage.page!1)>
    <#assign qPageSize = (queuePage.pageSize!25)>
    <#assign qTotalRows = (queuePage.totalRows!0)>
    <#assign qTotalPages = (queuePage.totalPages!0)>

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
                        <td colspan="9" class="empty-row">No approval requests found.</td>
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
                                <span class="status ${row.statusClass!'pending'}">${row.quorumLabel!'pending 0-of-0'}</span>
                            </td>
                            <td class="mono queue-nowrap queue-small-text">${row.requestedAt!'-'}</td>
                            <td class="mono queue-nowrap queue-small-text">${row.expiresIn!'-'}</td>
                            <td class="action-cell">
                                <button type="button" class="queue-action-btn queue-action-approve" disabled>approve</button>
                            </td>
                            <td class="action-cell">
                                <button type="button" class="queue-action-btn queue-action-deny" disabled>deny</button>
                            </td>
                            <td class="action-cell details-cell">
                                <a
                                    class="queue-action-btn queue-action-details queue-details-trigger"
                                    href="/details?id=${(row.id!'')?url('UTF-8')}"
                                    data-details-source-id="details-source-${row?index}"
                                >details</a>
                                <pre id="details-source-${row?index}" class="queue-details-source" hidden>${(row.detailsJson!'{}')?html}</pre>
                            </td>
                        </tr>
                    </#list>
                </#if>
            </tbody>
        </table>

        <@queuePager />
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

            const detailsPre = document.createElement('pre');
            detailsPre.textContent = sourceElement.textContent || '{}';

            detailsBox.appendChild(detailsPre);
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
