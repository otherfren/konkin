#!/bin/bash
# Skript zum Prüfen auf neuere Versionen der Abhängigkeiten in pom.xml
# Verwendet das versions-maven-plugin, ignoriert dabei Beta/RC/Alpha-Versionen.

mvn versions:display-dependency-updates \
    versions:display-plugin-updates \
    versions:display-property-updates \
    -Dmaven.version.ignore=".*-alpha.*|.*-beta.*|.*-rc.*|.*-m.*|.*-cr.*"
