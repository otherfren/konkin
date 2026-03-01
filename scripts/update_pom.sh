#!/bin/bash

mvn versions:update-properties \
    versions:use-latest-versions \
    -DallowMajorUpdates=false \
    -DgenerateBackupPoms=false