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
    <h2 class="queue-title">Audit Log</h2>

    <#assign lqSort = (logQueuePage.sortBy!'updated_at')>
    <#assign lqDir = (logQueuePage.sortDir!'desc')>
    <#assign lqPage = (logQueuePage.page!1)>
    <#assign lqPageSize = (logQueuePage.pageSize!25)>
    <#assign lqTotalRows = (logQueuePage.totalRows!0)>
    <#assign lqTotalPages = (logQueuePage.totalPages!0)>

    <#assign aSort = (auditPage.sortBy!'created_at')>
    <#assign aDir = (auditPage.sortDir!'desc')>
    <#assign aPage = (auditPage.page!1)>
    <#assign aPageSize = (auditPage.pageSize!25)>
    <#assign aTotalRows = (auditPage.totalRows!0)>
    <#assign aTotalPages = (auditPage.totalPages!0)>

    <section class="queue-table-shell queue-log-section">
        <h3 class="queue-subtitle">Non-pending Requests</h3>
        <div class="table-toolbar">
            <span class="table-meta">rows: ${lqTotalRows} · page ${lqPage} / ${lqTotalPages}</span>
        </div>

        <table class="queue-table">
            <thead>
            <tr>
                <th>Id</th>
                <th>Coin</th>
                <th>Tool</th>
                <th>State</th>
                <th>Requested</th>
                <th>Expires</th>
                <th class="action-header"></th>
            </tr>
            </thead>
            <tbody>
            <#if logQueueRows?size == 0>
                <tr>
                    <td colspan="7" class="empty-row">No non-pending requests found.</td>
                </tr>
            <#else>
                <#list logQueueRows as row>
                    <tr>
                        <td class="queue-id-cell">
                            <span class="mono queue-id-short">${row.idShort!'-'}</span>
                        </td>
                        <td class="coin-cell">
                            <#if (row.coinIconName!'')?has_content>
                                <img
                                    class="coin-icon"
                                    src="${assetsPath}/img/${row.coinIconName}.svg?v=${assetsVersion}"
                                    alt="${row.coin!'coin'} icon"
                                >
                            <#else>
                                <span class="mono queue-small-text">${row.coin!'-'}</span>
                            </#if>
                        </td>
                        <td>${row.toolName!'-'}</td>
                        <td>
                            <span class="status ${row.statusClass!'pending'}">${row.state!'UNKNOWN'}</span>
                        </td>
                        <td class="mono queue-nowrap queue-small-text">${row.requestedAt!'-'}</td>
                        <td class="mono queue-nowrap queue-small-text">${row.expiresIn!'-'}</td>
                        <td class="action-cell details-cell">
                            <details class="queue-details">
                                <summary class="queue-action-btn queue-action-details">details</summary>
                                <div class="details-box">
                                    <pre>${(row.detailsJson!'{}')?html}</pre>
                                </div>
                            </details>
                        </td>
                    </tr>
                </#list>
            </#if>
            </tbody>
        </table>

        <div class="pager">
            <#if (logQueuePage.hasPrev!false)>
                <a class="pager-link" href="${auditLogPath}?log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${logQueuePage.prevPage!1}&log_queue_page_size=${lqPageSize}&audit_sort=${aSort}&audit_dir=${aDir}&audit_page=${aPage}&audit_page_size=${aPageSize}">Prev</a>
            <#else>
                <span class="pager-link disabled">Prev</span>
            </#if>
            <span class="pager-info">Page ${lqPage} of ${lqTotalPages}</span>
            <#if (logQueuePage.hasNext!false)>
                <a class="pager-link" href="${auditLogPath}?log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${logQueuePage.nextPage!1}&log_queue_page_size=${lqPageSize}&audit_sort=${aSort}&audit_dir=${aDir}&audit_page=${aPage}&audit_page_size=${aPageSize}">Next</a>
            <#else>
                <span class="pager-link disabled">Next</span>
            </#if>
        </div>
    </section>

    <section class="queue-table-shell queue-log-section">
        <h3 class="queue-subtitle">State Transitions</h3>
        <div class="table-toolbar">
            <span class="table-meta">rows: ${aTotalRows} · page ${aPage} / ${aTotalPages}</span>
        </div>

        <table class="queue-table">
            <thead>
                <tr>
                    <th>
                        <a class="sort-link <#if aSort == 'created_at'>active</#if>" href="${auditLogPath}?audit_sort=created_at&audit_dir=<#if aSort == 'created_at' && aDir == 'asc'>desc<#else>asc</#if>&audit_page=1&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">
                            Timestamp<#if aSort == 'created_at'><span class="sort-indicator"><#if aDir == 'asc'>↑<#else>↓</#if></span></#if>
                        </a>
                    </th>
                    <th>
                        <a class="sort-link <#if aSort == 'request_id'>active</#if>" href="${auditLogPath}?audit_sort=request_id&audit_dir=<#if aSort == 'request_id' && aDir == 'asc'>desc<#else>asc</#if>&audit_page=1&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">
                            Request<#if aSort == 'request_id'><span class="sort-indicator"><#if aDir == 'asc'>↑<#else>↓</#if></span></#if>
                        </a>
                    </th>
                    <th>
                        <a class="sort-link <#if aSort == 'to_state'>active</#if>" href="${auditLogPath}?audit_sort=to_state&audit_dir=<#if aSort == 'to_state' && aDir == 'asc'>desc<#else>asc</#if>&audit_page=1&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">
                            To State<#if aSort == 'to_state'><span class="sort-indicator"><#if aDir == 'asc'>↑<#else>↓</#if></span></#if>
                        </a>
                    </th>
                    <th>
                        <a class="sort-link <#if aSort == 'from_state'>active</#if>" href="${auditLogPath}?audit_sort=from_state&audit_dir=<#if aSort == 'from_state' && aDir == 'asc'>desc<#else>asc</#if>&audit_page=1&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">
                            From State<#if aSort == 'from_state'><span class="sort-indicator"><#if aDir == 'asc'>↑<#else>↓</#if></span></#if>
                        </a>
                    </th>
                    <th>
                        <a class="sort-link <#if aSort == 'actor_type'>active</#if>" href="${auditLogPath}?audit_sort=actor_type&audit_dir=<#if aSort == 'actor_type' && aDir == 'asc'>desc<#else>asc</#if>&audit_page=1&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">
                            Actor<#if aSort == 'actor_type'><span class="sort-indicator"><#if aDir == 'asc'>↑<#else>↓</#if></span></#if>
                        </a>
                    </th>
                    <th>
                        <a class="sort-link <#if aSort == 'reason_code'>active</#if>" href="${auditLogPath}?audit_sort=reason_code&audit_dir=<#if aSort == 'reason_code' && aDir == 'asc'>desc<#else>asc</#if>&audit_page=1&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">
                            Reason<#if aSort == 'reason_code'><span class="sort-indicator"><#if aDir == 'asc'>↑<#else>↓</#if></span></#if>
                        </a>
                    </th>
                </tr>
            </thead>
            <tbody>
                <#if auditRows?size == 0>
                    <tr>
                        <td colspan="6" class="empty-row">No audit transitions found.</td>
                    </tr>
                <#else>
                    <#list auditRows as row>
                        <#assign toStateLower = (row.toState!'unknown')?lower_case>
                        <#assign statusClass = 'pending'>
                        <#if toStateLower == 'completed' || toStateLower == 'approved'>
                            <#assign statusClass = 'approved'>
                        <#elseif toStateLower == 'failed' || toStateLower == 'denied' || toStateLower == 'cancelled' || toStateLower == 'timed_out' || toStateLower == 'rejected' || toStateLower == 'expired'>
                            <#assign statusClass = 'cancelled'>
                        </#if>
                        <tr>
                            <td class="mono queue-nowrap">${row.createdAt!'-'}</td>
                            <td class="mono queue-nowrap">${row.requestId!'-'}</td>
                            <td><span class="status ${statusClass}">${row.toState!'-'}</span></td>
                            <td><span class="mono queue-nowrap">${row.fromState!'-'}</span></td>
                            <td>${row.actorType!'-'} <#if (row.actorId!'-') != '-'><span class="mono">(${row.actorId})</span></#if></td>
                            <td>${row.reasonCode!'-'}</td>
                        </tr>
                    </#list>
                </#if>
            </tbody>
        </table>

        <div class="pager">
            <#if (auditPage.hasPrev!false)>
                <a class="pager-link" href="${auditLogPath}?audit_sort=${aSort}&audit_dir=${aDir}&audit_page=${auditPage.prevPage!1}&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">Prev</a>
            <#else>
                <span class="pager-link disabled">Prev</span>
            </#if>
            <span class="pager-info">Page ${aPage} of ${aTotalPages}</span>
            <#if (auditPage.hasNext!false)>
                <a class="pager-link" href="${auditLogPath}?audit_sort=${aSort}&audit_dir=${aDir}&audit_page=${auditPage.nextPage!1}&audit_page_size=${aPageSize}&log_queue_sort=${lqSort}&log_queue_dir=${lqDir}&log_queue_page=${lqPage}&log_queue_page_size=${lqPageSize}">Next</a>
            <#else>
                <span class="pager-link disabled">Next</span>
            </#if>
        </div>
    </section>
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
