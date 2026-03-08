<#import "layout.ftl" as layout>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    bodyClass="login-page"
    iconType="image/svg+xml"
    iconHref=(assetsPath + "/favicon.svg")
>
<main class="setup-card">
    <#if wizardStep == "create">
        <h1 class="login-title">KONKIN Setup</h1>
        <p class="login-subtitle">No password has been configured yet for the web interface. Click below to generate a secure password.</p>
        <p class="login-subtitle">The password hash will be stored in <code>secrets/web-ui.password</code>. To rotate, delete that file and restart.</p>
        <form class="login-form" method="post" action="/setup">
            <button class="login-button" type="submit">Create Password</button>
        </form>
    <#elseif wizardStep == "reveal">
        <h1 class="login-title">Password Created</h1>
        <p class="login-subtitle">Your password has been generated and its hash saved to <code>secrets/web-ui.password</code>. Save the password now &mdash; it will not be shown again.</p>
        <div class="setup-password-row">
            <code class="setup-password-display" id="setup-password">${password}</code>
            <button class="setup-copy-button" type="button" onclick="navigator.clipboard.writeText(document.getElementById('setup-password').textContent).then(function(){var b=document.querySelector('.setup-copy-button');b.textContent='Copied';setTimeout(function(){b.textContent='Copy'},1500)})">Copy</button>
        </div>
        <form class="login-form" method="get" action="/login">
            <button class="login-button" type="submit">Continue to Login</button>
        </form>
    </#if>
</main>
</@layout.page>
