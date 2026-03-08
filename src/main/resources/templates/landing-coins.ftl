<#import "layout.ftl" as layout>
<#import "macros.ftl" as m>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    iconType="image/png"
    iconHref=(assetsPath + "/img/logo_v2_small_trans.png")
>
<@m.sidebar menuToggleId="menu-toggle-coins" />

<div class="page-body">
<main class="main-section"><div class="content coins-content">
    <h2 class="queue-title">Coins</h2>
    <p class="coins-subtitle">Wallet status overview for coins configured in config.toml.</p>

    <#assign coins = (coinsData.coins![])>
    <#if coins?size == 0>
        <section class="coins-card coins-card-empty">
            <p class="telegram-empty">No coins configured.</p>
        </section>
    <#else>
        <div class="coins-grid">
            <#list coins as coin>
                <section class="coins-card" aria-labelledby="coins-card-${coin?index}">
                    <div class="coins-card-header">
                        <h3 id="coins-card-${coin?index}" class="coins-coin-title">
                            <#if (coin.coinIconName!'')?has_content>
                                <img
                                    class="coin-icon coins-coin-icon"
                                    src="${assetsPath}/img/${coin.coinIconName}.svg?v=${assetsVersion}"
                                    alt="${coin.coin!'coin'} icon"
                                    title="${coin.coin!'-'}"
                                >
                            </#if>
                            <span>${(coin.coin!'-')?upper_case}</span>
                        </h3>
                        <span class="coins-enabled-chip <#if (coin.enabled!false)>coins-enabled-on<#else>coins-enabled-off</#if>">
                            coin ${(coin.enabled!false)?string('enabled', 'disabled')}
                        </span>
                    </div>

                    <div class="coins-status-grid">
                        <div class="coins-status-item">
                            <span class="coins-status-label">connected</span>
                            <span class="coins-state <#if (coin.connected!false)>coins-state-on<#else>coins-state-off</#if>">${(coin.connected!false)?string('yes', 'no')}</span>
                        </div>
                        <div class="coins-status-item">
                            <span class="coins-status-label">read</span>
                            <span class="coins-state <#if (coin.readable!false)>coins-state-on<#else>coins-state-off</#if>">${(coin.readable!false)?string('yes', 'no')}</span>
                        </div>
                        <div class="coins-status-item">
                            <span class="coins-status-label">write</span>
                            <span class="coins-state <#if (coin.writable!false)>coins-state-on<#else>coins-state-off</#if>">${(coin.writable!false)?string('yes', 'no')}</span>
                        </div>
                        <div class="coins-status-item coins-balance-item">
                            <span class="coins-status-label">balance</span>
                            <#assign balanceValue = (coin.balanceValue!'-')>
                            <#if balanceValue == "-">
                                <span class="mono coins-balance-value">-</span>
                            <#else>
                                <span class="mono coins-balance-value" data-balance-value="${balanceValue}" data-masked="true">***</span>
                                <button
                                    type="button"
                                    class="coins-balance-toggle"
                                    aria-label="Reveal balance"
                                    title="Reveal balance"
                                >
                                    <span aria-hidden="true">👁</span>
                                </button>
                            </#if>
                        </div>
                    </div>
                </section>
            </#list>
        </div>
    </#if>
</div></main>

<script>
(() => {
    const balanceValues = document.querySelectorAll('.coins-balance-value[data-balance-value]');

    for (const valueEl of balanceValues) {
        const container = valueEl.closest('.coins-balance-item');
        const toggle = container ? container.querySelector('.coins-balance-toggle') : null;
        if (!toggle) {
            continue;
        }

        toggle.addEventListener('click', () => {
            const masked = valueEl.dataset.masked !== 'false';
            if (masked) {
                valueEl.textContent = valueEl.dataset.balanceValue || '-';
                valueEl.dataset.masked = 'false';
                toggle.setAttribute('aria-label', 'Hide balance');
                toggle.setAttribute('title', 'Hide balance');
                toggle.classList.add('is-revealed');
            } else {
                valueEl.textContent = '***';
                valueEl.dataset.masked = 'true';
                toggle.setAttribute('aria-label', 'Reveal balance');
                toggle.setAttribute('title', 'Reveal balance');
                toggle.classList.remove('is-revealed');
            }
        });
    }
})();
</script>

<@m.footer />
</div>
</@layout.page>
