#!/bin/sh
# Maven Wrapper startup script
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

if [ -z "$JAVA_HOME" ] ; then
  JAVACMD=java
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-}"
if [ -z "$MAVEN_PROJECTBASEDIR" ]; then
  MAVEN_PROJECTBASEDIR=$(cd "$(dirname "$0")" && pwd)
fi

MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ -r "$MAVEN_WRAPPER_PROPERTIES" ]; then
  DISTRIBUTION_URL=$(grep "^distributionUrl" "$MAVEN_WRAPPER_PROPERTIES" | sed 's/distributionUrl=//')
  MAVEN_VERSION=$(echo "$DISTRIBUTION_URL" | sed 's/.*apache-maven-\([0-9.]*\)-.*/\1/')
fi

MAVEN_VERSION="${MAVEN_VERSION:-3.9.9}"
MAVEN_DIST_DIR="$HOME/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"

if [ ! -f "$MAVEN_DIST_DIR/bin/mvn" ]; then
  echo "Downloading Apache Maven ${MAVEN_VERSION} ..."
  DOWNLOAD_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  mkdir -p "$MAVEN_DIST_DIR"
  if command -v curl > /dev/null; then
    curl -fsSL "$DOWNLOAD_URL" | tar -xz -C "$MAVEN_DIST_DIR" --strip-components=1
  elif command -v wget > /dev/null; then
    wget -q -O - "$DOWNLOAD_URL" | tar -xz -C "$MAVEN_DIST_DIR" --strip-components=1
  else
    echo "ERROR: curl or wget required to download Maven." >&2
    exit 1
  fi
  echo "Maven ${MAVEN_VERSION} installed to $MAVEN_DIST_DIR"
fi

MAVEN_HOME="$MAVEN_DIST_DIR"
export MAVEN_HOME

exec "$MAVEN_HOME/bin/mvn" "$@"
