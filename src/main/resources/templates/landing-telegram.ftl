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
    <input type="checkbox" id="menu-toggle-telegram" class="menu-toggle" aria-hidden="true">
    <label for="menu-toggle-telegram" class="menu-toggle-btn" aria-label="Toggle navigation" title="menu">
        <span></span><span></span><span></span>
    </label>
    <nav class="menu" aria-label="Main">
        <#if activePage == "queue"><span class="menu-active">queue</span><#else><a href="${queuePath}">queue</a></#if>
        <#if activePage == "log"><span class="menu-active">audit</span><#else><a href="${auditLogPath}">audit</a></#if>
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

<main class="main-section"><div class="content telegram-content">
    <h2 class="queue-title">Telegram</h2>
    <p class="telegram-subtitle">Approve discovered chat requests and send a manual notification to approved chats. A chat appears here only after it has sent at least one message to your bot.</p>

    <#if telegramNotice?has_content>
        <div class="telegram-notice <#if telegramNoticeError>telegram-notice-error<#else>telegram-notice-success</#if>">
            ${telegramNotice?html}
        </div>
    </#if>

    <section class="telegram-panel" aria-labelledby="telegram-requests-title">
        <h3 id="telegram-requests-title" class="telegram-title">Chat Requests</h3>
        <#if telegramChatRequests?size == 0>
            <p class="telegram-empty">No pending chat requests found. Send at least one message (for example /start) to your bot from a target chat, then refresh this page.</p>
        <#else>
            <table class="queue-table telegram-table">
                <thead>
                    <tr>
                        <th>Chat ID</th>
                        <th>Type</th>
                        <th>Title</th>
                        <th>Action</th>
                    </tr>
                </thead>
                <tbody>
                    <#list telegramChatRequests as chat>
                        <tr>
                            <td class="mono">${chat.chatId?html}</td>
                            <td>${chat.chatType?html}</td>
                            <td>${chat.chatTitle?html}</td>
                            <td>
                                <form method="post" action="/telegram/approve" class="telegram-inline-form">
                                    <input type="hidden" name="chat_id" value="${chat.chatId?html}">
                                    <button type="submit" class="btn-approve">approve</button>
                                </form>
                            </td>
                        </tr>
                    </#list>
                </tbody>
            </table>
        </#if>
    </section>

    <section class="telegram-panel" aria-labelledby="telegram-approved-title">
        <h3 id="telegram-approved-title" class="telegram-title">Approved Chats</h3>
        <#if telegramApprovedChats?size == 0>
            <p class="telegram-empty">No approved chat-ids yet.</p>
        <#else>
            <table class="queue-table telegram-table">
                <thead>
                    <tr>
                        <th>Chat ID</th>
                        <th>Username</th>
                    </tr>
                </thead>
                <tbody>
                    <#list telegramApprovedChats as chat>
                        <tr>
                            <td class="mono">${chat.chatId?html}</td>
                            <td><#if chat.chatUsername?has_content>@${chat.chatUsername?html}<#else>—</#if></td>
                        </tr>
                    </#list>
                </tbody>
            </table>
        </#if>
    </section>

    <section class="telegram-panel" aria-labelledby="telegram-send-title">
        <h3 id="telegram-send-title" class="telegram-title">Telegram Broadcast</h3>
        <p class="telegram-empty">This is a test broadcast to currently approved chats, so you can verify bot delivery.</p>
        <form method="post" action="/telegram/send" class="telegram-form">
            <label for="telegram_message" class="telegram-label">Message</label>
            <textarea
                id="telegram_message"
                name="telegram_message"
                class="telegram-input"
                rows="4"
                maxlength="4096"
                placeholder="Type your message..."
            >${telegramDraft?html}</textarea>
            <button type="submit" class="telegram-button">Send via Telegram to all bot subscribers</button>
        </form>
    </section>
</div></main>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
