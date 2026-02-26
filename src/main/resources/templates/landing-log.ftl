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
        <a href="${githubPath}">github</a>
        <#if showLogout>
            <form method="post" action="/logout" class="logout-form">
                <button type="submit" class="logout-btn">logout</button>
            </form>
        </#if>
    </nav>
</header>

<main class="main-section"><div class="content">
    <h2 class="queue-title">Audit Log</h2>
    <table class="queue-table">
        <thead>
            <tr>
                <th>Timestamp</th>
                <th>Actor</th>
                <th>Event</th>
                <th>Target</th>
                <th>Result</th>
            </tr>
        </thead>
        <tbody>
            <tr>
                <td class="mono">2026-02-26 11:05:09Z</td>
                <td>admin@local</td>
                <td>AUTH_REQUEST_APPROVED</td>
                <td class="mono">req-a3f9c1</td>
                <td><span class="status approved">success</span></td>
            </tr>
            <tr>
                <td class="mono">2026-02-26 11:06:41Z</td>
                <td>admin@local</td>
                <td>AUTH_REQUEST_CANCELLED</td>
                <td class="mono">req-c1a8f3</td>
                <td><span class="status cancelled">cancelled</span></td>
            </tr>
            <tr>
                <td class="mono">2026-02-26 11:07:33Z</td>
                <td>system</td>
                <td>SESSION_CREATED</td>
                <td class="mono">konkin_landing_session</td>
                <td><span class="status approved">issued</span></td>
            </tr>
            <tr>
                <td class="mono">2026-02-26 11:09:12Z</td>
                <td>admin@local</td>
                <td>AUTH_REQUEST_APPROVED</td>
                <td class="mono">req-g8h4k2</td>
                <td><span class="status approved">success</span></td>
            </tr>
            <tr>
                <td class="mono">2026-02-26 11:10:55Z</td>
                <td>system</td>
                <td>SESSION_TERMINATED</td>
                <td class="mono">konkin_landing_session</td>
                <td><span class="status cancelled">expired</span></td>
            </tr>
        </tbody>
    </table>
</div></main>
</@layout.page>
