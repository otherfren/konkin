<#import "layout.ftl" as layout>

<@layout.page
    title=title
    assetsPath=assetsPath
    assetsVersion=assetsVersion
    bodyClass="login-page"
    iconType="image/svg+xml"
    iconHref=(assetsPath + "/favicon.svg")
>
<main class="login-card">
    <h1 class="login-title">KONKIN Landing Login</h1>
    <p class="login-subtitle">Enter your landing password to continue.</p>

    <#if invalidPassword>
        <p class="login-error">Invalid password. Please try again.</p>
    </#if>

    <form class="login-form" method="post" action="/login">
        <label class="login-label" for="password">Password</label>
        <input class="login-input" id="password" name="password" type="password" autocomplete="current-password" required>
        <button class="login-button" type="submit">Login</button>
    </form>
</main>
</@layout.page>
