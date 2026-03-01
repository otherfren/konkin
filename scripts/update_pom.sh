#!/bin/bash

mvn versions:update-properties \
    versions:use-latest-versions \
    versions:use-latest-releases \
    -DallowMajorUpdates=false \               # ← verhindert Breaking Changes (meistens gewünscht)
    -Dmaven.version.rules="file://$(pwd)/version-rules.xml" \
    -DgenerateBackupPoms=false