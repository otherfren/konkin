<!doctype html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title}</title>
    <link rel="icon" type="image/svg+xml" href="${assetsPath}/favicon.svg?v=${assetsVersion}">
    <link rel="stylesheet" href="${assetsPath}/css/landing.css?v=${assetsVersion}">
</head>
<body class="login-page">
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
</body>
</html>
