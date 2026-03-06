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
        <#if activePage == "history"><span class="menu-active">history</span><#else><a href="${auditLogPath}">history</a></#if>
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

    <#assign telegramConfirmRequiredValue = (telegramConfirmRequired!false)>
    <#assign telegramConfirmModeValue = (telegramConfirmMode!'')>
    <#assign telegramConfirmChatIdValue = (telegramConfirmChatId!'')>
    <#assign telegramConfirmChatIdShortValue = (telegramConfirmChatIdShort!'-')>
    <#assign telegramConfirmActionPathValue = (telegramConfirmActionPath!'/telegram/unapprove')>

    <#if telegramNotice?has_content>
        <div class="telegram-notice <#if telegramNoticeError>telegram-notice-error<#else>telegram-notice-success</#if>">
            ${telegramNotice}
        </div>
    </#if>

    <#if telegramConfirmRequiredValue>
        <section class="queue-confirm-panel" aria-labelledby="telegram-confirm-title">
            <h3 id="telegram-confirm-title" class="queue-confirm-title">Confirmation required</h3>
            <p class="queue-confirm-copy">
                <#if telegramConfirmModeValue == "reset">
                    Confirm reset of all approved Telegram chats.
                <#else>
                    Confirm <strong>unapprove</strong> for chat <span class="mono">${telegramConfirmChatIdShortValue}</span>.
                </#if>
            </p>
            <div class="queue-confirm-actions">
                <form method="post" action="${telegramConfirmActionPathValue}" class="queue-confirm-inline-form">
                    <#if telegramConfirmModeValue != "reset">
                        <input type="hidden" name="chat_id" value="${telegramConfirmChatIdValue}">
                    </#if>
                    <input type="hidden" name="confirm" value="yes">
                    <button type="submit" class="queue-action-btn queue-action-deny">
                        <#if telegramConfirmModeValue == "reset">confirm reset<#else>confirm unapprove</#if>
                    </button>
                </form>
                <a href="${telegramPath}" class="queue-action-btn queue-action-cancel">cancel</a>
            </div>
        </section>
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
                        <th>Username</th>
                        <th>Action</th>
                    </tr>
                </thead>
                <tbody>
                    <#list telegramChatRequests as chat>
                        <tr>
                            <td class="mono">${chat.chatId}</td>
                            <td>${chat.chatType}</td>
                            <td>${chat.chatTitle}</td>
                            <td><#if chat.chatUsername?has_content>@${chat.chatUsername}<#else>—</#if></td>
                            <td>
                                <form method="post" action="/telegram/approve" class="telegram-inline-form">
                                    <input type="hidden" name="chat_id" value="${chat.chatId}">
                                    <input type="hidden" name="chat_type" value="${chat.chatType}">
                                    <input type="hidden" name="chat_title" value="${chat.chatTitle}">
                                    <input type="hidden" name="chat_username" value="${chat.chatUsername}">
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
                        <th>Name</th>
                        <th>Type</th>
                        <th>Username</th>
                        <th>Action</th>
                    </tr>
                </thead>
                <tbody>
                    <#list telegramApprovedChats as chat>
                        <tr>
                            <td class="mono">${chat.chatId}</td>
                            <td>${chat.chatDisplayName}</td>
                            <td>${chat.chatType}</td>
                            <td><#if chat.chatUsername?has_content>@${chat.chatUsername}<#else>—</#if></td>
                            <td>
                                <form method="post" action="/telegram/unapprove" class="telegram-inline-form telegram-decision-form" data-telegram-decision="unapprove">
                                    <input type="hidden" name="chat_id" value="${chat.chatId}">
                                    <button type="submit" class="queue-action-btn queue-action-deny">unapprove</button>
                                </form>
                            </td>
                        </tr>
                    </#list>
                </tbody>
            </table>

            <div class="queue-confirm-actions" style="margin-top:12px;">
                <form method="post" action="/telegram/reset" class="telegram-inline-form telegram-decision-form" data-telegram-decision="reset">
                    <button type="submit" class="queue-action-btn queue-action-deny">reset approved list</button>
                </form>
            </div>
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
            >${telegramDraft}</textarea>
            <button type="submit" class="telegram-button">Send via Telegram to all bot subscribers</button>
        </form>
    </section>

    <div id="telegram-confirm-modal" class="queue-confirm-modal" hidden>
        <div class="queue-confirm-modal-card" role="dialog" aria-modal="true" aria-labelledby="telegram-confirm-modal-title">
            <h3 id="telegram-confirm-modal-title" class="queue-confirm-modal-title">Confirm telegram change</h3>
            <p id="telegram-confirm-modal-message" class="queue-confirm-modal-copy">Proceed with this action?</p>
            <div class="queue-confirm-modal-actions">
                <button type="button" id="telegram-confirm-modal-cancel" class="queue-action-btn queue-action-cancel">cancel</button>
                <button type="button" id="telegram-confirm-modal-submit" class="queue-action-btn queue-action-deny">confirm</button>
            </div>
        </div>
    </div>
</div></main>

<script>
(() => {
    const decisionForms = document.querySelectorAll('.telegram-decision-form[data-telegram-decision]');
    const confirmModal = document.getElementById('telegram-confirm-modal');
    const confirmMessage = document.getElementById('telegram-confirm-modal-message');
    const confirmCancel = document.getElementById('telegram-confirm-modal-cancel');
    const confirmSubmit = document.getElementById('telegram-confirm-modal-submit');
    let pendingForm = null;

    if (!confirmModal || !confirmMessage || !confirmCancel || !confirmSubmit || decisionForms.length === 0) {
        return;
    }

    const closeModal = () => {
        confirmModal.classList.remove('is-open');
        confirmModal.hidden = true;
        pendingForm = null;
    };

    const openModal = form => {
        pendingForm = form;

        const decision = form.getAttribute('data-telegram-decision') === 'reset' ? 'reset' : 'unapprove';
        if (decision === 'reset') {
            confirmMessage.textContent = 'Confirm reset of all approved Telegram chats?';
            confirmSubmit.textContent = 'confirm reset';
        } else {
            const chatField = form.querySelector('input[name="chat_id"]');
            const chatId = chatField ? chatField.value.trim() : '';
            const chatIdShort = chatId.length > 5 ? (chatId.slice(0, 5) + '...') : (chatId || 'unknown');
            confirmMessage.textContent = 'Confirm unapprove for chat ' + chatIdShort + '?';
            confirmSubmit.textContent = 'confirm unapprove';
        }

        confirmModal.hidden = false;
        window.requestAnimationFrame(() => confirmModal.classList.add('is-open'));
    };

    for (const form of decisionForms) {
        form.addEventListener('submit', event => {
            if (form.dataset.confirmed === 'yes') {
                return;
            }
            event.preventDefault();
            openModal(form);
        });
    }

    confirmCancel.addEventListener('click', () => {
        closeModal();
    });

    confirmModal.addEventListener('click', event => {
        if (event.target === confirmModal) {
            closeModal();
        }
    });

    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && confirmModal.classList.contains('is-open')) {
            closeModal();
        }
    });

    confirmSubmit.addEventListener('click', () => {
        if (!pendingForm) {
            return;
        }

        let confirmInput = pendingForm.querySelector('input[name="confirm"]');
        if (!confirmInput) {
            confirmInput = document.createElement('input');
            confirmInput.type = 'hidden';
            confirmInput.name = 'confirm';
            pendingForm.appendChild(confirmInput);
        }

        confirmInput.value = 'yes';
        pendingForm.dataset.confirmed = 'yes';
        const formToSubmit = pendingForm;
        closeModal();
        formToSubmit.submit();
    });
})();
</script>

<footer class="site-footer">
    <div class="site-footer-inner">
        <span class="site-footer-copy">KONKIN control panel</span>
        <a href="${githubPath}" class="footer-link">View on GitHub</a>
    </div>
</footer>
</@layout.page>
