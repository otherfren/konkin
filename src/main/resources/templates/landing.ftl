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

    <div class="table-toolbar">
        <span class="table-meta">rows: ${qTotalRows} · page ${qPage} / ${qTotalPages}</span>
    </div>

    <table class="queue-table">
        <thead>
            <tr>
                <th>
                    <a class="sort-link <#if qSort == 'id'>active</#if>" href="${queuePath}?queue_sort=id&queue_dir=<#if qSort == 'id' && qDir == 'asc'>desc<#else>asc</#if>&queue_page=1&queue_page_size=${qPageSize}">
                        ID<#if qSort == 'id'><span class="sort-indicator"><#if qDir == 'asc'>↑<#else>↓</#if></span></#if>
                    </a>
                </th>
                <th>
                    <a class="sort-link <#if qSort == 'coin'>active</#if>" href="${queuePath}?queue_sort=coin&queue_dir=<#if qSort == 'coin' && qDir == 'asc'>desc<#else>asc</#if>&queue_page=1&queue_page_size=${qPageSize}">
                        Coin<#if qSort == 'coin'><span class="sort-indicator"><#if qDir == 'asc'>↑<#else>↓</#if></span></#if>
                    </a>
                </th>
                <th>
                    <a class="sort-link <#if qSort == 'tool_name'>active</#if>" href="${queuePath}?queue_sort=tool_name&queue_dir=<#if qSort == 'tool_name' && qDir == 'asc'>desc<#else>asc</#if>&queue_page=1&queue_page_size=${qPageSize}">
                        Tool<#if qSort == 'tool_name'><span class="sort-indicator"><#if qDir == 'asc'>↑<#else>↓</#if></span></#if>
                    </a>
                </th>
                <th>
                    <a class="sort-link <#if qSort == 'state'>active</#if>" href="${queuePath}?queue_sort=state&queue_dir=<#if qSort == 'state' && qDir == 'asc'>desc<#else>asc</#if>&queue_page=1&queue_page_size=${qPageSize}">
                        State<#if qSort == 'state'><span class="sort-indicator"><#if qDir == 'asc'>↑<#else>↓</#if></span></#if>
                    </a>
                </th>
                <th>
                    <a class="sort-link <#if qSort == 'requested_at'>active</#if>" href="${queuePath}?queue_sort=requested_at&queue_dir=<#if qSort == 'requested_at' && qDir == 'asc'>desc<#else>asc</#if>&queue_page=1&queue_page_size=${qPageSize}">
                        Requested<#if qSort == 'requested_at'><span class="sort-indicator"><#if qDir == 'asc'>↑<#else>↓</#if></span></#if>
                    </a>
                </th>
                <th>
                    <a class="sort-link <#if qSort == 'expires_at'>active</#if>" href="${queuePath}?queue_sort=expires_at&queue_dir=<#if qSort == 'expires_at' && qDir == 'asc'>desc<#else>asc</#if>&queue_page=1&queue_page_size=${qPageSize}">
                        Expires<#if qSort == 'expires_at'><span class="sort-indicator"><#if qDir == 'asc'>↑<#else>↓</#if></span></#if>
                    </a>
                </th>
                <th>Approvals</th>
                <th>Session</th>
            </tr>
        </thead>
        <tbody>
            <#if queueRows?size == 0>
                <tr>
                    <td colspan="8" class="empty-row">No approval requests found.</td>
                </tr>
            <#else>
                <#list queueRows as row>
                    <#assign stateLower = (row.state!'unknown')?lower_case>
                    <#assign stateClass = 'pending'>
                    <#if stateLower == 'completed' || stateLower == 'approved'>
                        <#assign stateClass = 'approved'>
                    <#elseif stateLower == 'failed' || stateLower == 'denied' || stateLower == 'cancelled' || stateLower == 'timed_out' || stateLower == 'rejected' || stateLower == 'expired'>
                        <#assign stateClass = 'cancelled'>
                    </#if>
                    <tr>
                        <td class="mono">${row.id!'-'}</td>
                        <td>${row.coin!'-'}</td>
                        <td>${row.toolName!'-'}</td>
                        <td><span class="status ${stateClass}">${row.state!'-'}</span></td>
                        <td class="mono">${row.requestedAt!'-'}</td>
                        <td class="mono">${row.expiresAt!'-'}</td>
                        <td>${row.approvalsGranted!0} / ${row.minApprovalsRequired!0} (${row.approvalsDenied!0} denied)</td>
                        <td class="mono">${row.requestSessionId!'-'}</td>
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
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
