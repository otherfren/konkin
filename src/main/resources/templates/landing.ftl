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
    <h2 class="queue-title">Auth Queue</h2>

    <#assign qSort = (queuePage.sortBy!'requested_at')>
    <#assign qDir = (queuePage.sortDir!'desc')>
    <#assign qPage = (queuePage.page!1)>
    <#assign qPageSize = (queuePage.pageSize!25)>
    <#assign qTotalRows = (queuePage.totalRows!0)>
    <#assign qTotalPages = (queuePage.totalPages!0)>

    <div class="queue-table-shell">
        <div class="table-toolbar">
            <span class="table-meta">rows: ${qTotalRows} · page ${qPage} / ${qTotalPages}</span>
        </div>

        <table class="queue-table">
            <thead>
                <tr>
                    <th>Id</th>
                    <th>Coin</th>
                    <th>Tool</th>
                    <th>Quorum Status</th>
                    <th>Requested</th>
                    <th>Expires</th>
                    <th>approve-button</th>
                    <th>deny-button</th>
                    <th>details-button</th>
                </tr>
            </thead>
            <tbody>
                <#if queueRows?size == 0>
                    <tr>
                        <td colspan="9" class="empty-row">No approval requests found.</td>
                    </tr>
                <#else>
                    <#list queueRows as row>
                        <#assign approvalsGranted = row.approvalsGranted!0>
                        <#assign minApprovalsRequired = row.minApprovalsRequired!0>
                        <#assign approvalsDenied = row.approvalsDenied!0>
                        <#assign quorumMet = approvalsGranted gte minApprovalsRequired>
                        <#assign quorumClass = 'pending'>
                        <#assign quorumLabel = 'pending'>
                        <#if quorumMet>
                            <#assign quorumClass = 'approved'>
                            <#assign quorumLabel = 'quorum met'>
                        </#if>
                        <tr>
                            <td class="mono">${row.id!'-'}</td>
                            <td>${row.coin!'-'}</td>
                            <td>${row.toolName!'-'}</td>
                            <td>
                                <span class="status ${quorumClass}">${quorumLabel}</span>
                                <span class="quorum-meta">${approvalsGranted} / ${minApprovalsRequired} · denied ${approvalsDenied}</span>
                            </td>
                            <td class="mono">${row.requestedAt!'-'}</td>
                            <td class="mono">${row.expiresAt!'-'}</td>
                            <td class="action-cell">
                                <button type="button" class="queue-action-btn queue-action-approve" disabled>approve</button>
                            </td>
                            <td class="action-cell">
                                <button type="button" class="queue-action-btn queue-action-deny" disabled>deny</button>
                            </td>
                            <td class="action-cell">
                                <a class="queue-action-btn queue-action-details" href="${auditLogPath}">details</a>
                            </td>
                        </tr>
                    </#list>
                </#if>
            </tbody>
        </table>

        <div class="pager">
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
    </div>
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
