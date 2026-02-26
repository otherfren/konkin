<!doctype html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title}</title>
    <link rel="icon" type="image/png" href="${assetsPath}/img/logo.png?v=${assetsVersion}">
    <link rel="stylesheet" href="${assetsPath}/css/landing.css?v=${assetsVersion}">
</head>
<body>
<header class="topbar">
    <a href="/" class="brand">
        <img src="${assetsPath}/img/logo.png?v=${assetsVersion}" alt="KONKIN logo" class="brand-logo">
        <span class="brand-name">${title}</span>
    </a>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "audit"><span class="menu-active">audit</span><#else><a href="/audit">audit</a></#if>
        <a href="${githubPath}">github</a>
        <#if showLogout>
            <form method="post" action="/logout" class="logout-form">
                <button type="submit" class="logout-btn">logout</button>
            </form>
        </#if>
    </nav>
</header>

<main class="main-section"><div class="content">
    <h2 class="queue-title">Auth Queue</h2>
    <table class="queue-table">
        <thead>
            <tr>
                <th>ID</th>
                <th>Client</th>
                <th>IP</th>
                <th>Requested</th>
                <th>Scope</th>
                <th>Action</th>
            </tr>
        </thead>
        <tbody>
            <tr>
                <td class="mono">a3f9c1</td>
                <td>cursor/0.44.2</td>
                <td class="mono">192.168.1.42</td>
                <td>2025-02-26 08:14</td>
                <td>read, write</td>
                <td class="actions">
                    <button class="btn-approve">approve</button>
                    <button class="btn-cancel">cancel</button>
                </td>
            </tr>
            <tr>
                <td class="mono">b7d2e5</td>
                <td>claude-code/1.2.0</td>
                <td class="mono">10.0.0.7</td>
                <td>2025-02-26 09:01</td>
                <td>read</td>
                <td class="actions">
                    <span class="status approved">approved</span>
                </td>
            </tr>
            <tr>
                <td class="mono">c1a8f3</td>
                <td>vscode/1.87.0</td>
                <td class="mono">172.16.4.11</td>
                <td>2025-02-26 09:33</td>
                <td>read, write, admin</td>
                <td class="actions">
                    <span class="status cancelled">cancelled</span>
                </td>
            </tr>
            <tr>
                <td class="mono">d4e6b9</td>
                <td>cursor/0.44.2</td>
                <td class="mono">192.168.1.55</td>
                <td>2025-02-26 10:02</td>
                <td>read</td>
                <td class="actions">
                    <button class="btn-approve">approve</button>
                    <button class="btn-cancel">cancel</button>
                </td>
            </tr>
            <tr>
                <td class="mono">e9c3d7</td>
                <td>claude-code/1.1.9</td>
                <td class="mono">10.0.0.3</td>
                <td>2025-02-26 10:48</td>
                <td>read, write</td>
                <td class="actions">
                    <span class="status cancelled">cancelled</span>
                </td>
            </tr>
            <tr>
                <td class="mono">f2b5a1</td>
                <td>zed/0.139.0</td>
                <td class="mono">192.168.1.99</td>
                <td>2025-02-26 11:15</td>
                <td>read</td>
                <td class="actions">
                    <button class="btn-approve">approve</button>
                    <button class="btn-cancel">cancel</button>
                </td>
            </tr>
            <tr>
                <td class="mono">g8h4k2</td>
                <td>vscode/1.87.0</td>
                <td class="mono">172.16.4.22</td>
                <td>2025-02-26 11:44</td>
                <td>read, write</td>
                <td class="actions">
                    <span class="status approved">approved</span>
                </td>
            </tr>
        </tbody>
    </table>
</div></main>
</body>
</html>
