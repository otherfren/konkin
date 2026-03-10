<#macro page title assetsPath assetsVersion bodyClass="" iconType="image/png" iconHref="">
<!doctype html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title}</title>
    <#if iconHref?has_content>
        <link rel="icon" type="${iconType}" href="${iconHref}?v=${assetsVersion}">
    </#if>
    <link rel="stylesheet" href="${assetsPath}/css/landing.css?v=${assetsVersion}">
</head>
<body<#if bodyClass?has_content> class="${bodyClass}"</#if>>
    <#if (pendingRestartFields![])?has_content>
    <div class="restart-banner">
        Settings changed — restart required for: ${pendingRestartFields?join(", ")}
    </div>
    </#if>
    <#nested>
</body>
</html>
</#macro>
