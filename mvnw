#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
#
# Optional ENV vars
#   MVNW_REPOURL - repo url base for downloading maven distribution
#   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
#   MVNW_VERBOSE - true: enable verbose log; others: silence the output
# ----------------------------------------------------------------------------

set -euf
[ "${MVNW_VERBOSE-}" != true ] || set -x

# OS specific support.
native_path() { printf %s\\n "$1"; }
case "$(uname)" in
CYGWIN* | MINGW*)
  [ -z "${JAVA_HOME-}" ] || JAVA_HOME="$(cygpath --unix "$JAVA_HOME")"
  native_path() { cygpath --path --windows "$1"; }
  ;;
esac

# set JAVA_HOME
if [ -z "${JAVA_HOME-}" ]; then
  if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home)" || :
  fi
fi

if [ -n "${JAVA_HOME-}" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then
    # IBM's JDK on AIX uses strange locations for the executables
    JAVACMD="$JAVA_HOME/jre/sh/java"
  else
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  if [ ! -x "$JAVACMD" ]; then
    die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
  fi
else
  JAVACMD="$(
    'set' +e
    'unset' -f command 2>/dev/null
    'command' -v java
  )" || :
  if [ ! -x "${JAVACMD-}" ]; then
    die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
  fi
fi

# hash://
find_maven_basedir() {
  if [ -z "${1-}" ]; then
    printf '%s' "$(pwd)"
  elif [ -d "$1" ]; then
    (cd -- "$1" && pwd)
  elif [ -f "$1" ]; then
    case "$1" in
    /*)  printf '%s\n' "$(dirname -- "$1")" ;;
    *) printf '%s\n' "$(dirname -- "$(pwd)/$1")" ;;
    esac
  fi
}

find_file_argument_basedir() {
  basedir="$(find_maven_basedir "$(pwd)")"
  wdir="$basedir"
  while [ "$wdir" != '/' ]; do
    if [ -d "$wdir"/.mvn ]; then
      basedir="$wdir"
      break
    fi
    wdir="$(dirname -- "$wdir")"
  done
  printf '%s' "$basedir"
}

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(find_file_argument_basedir)}"
MAVEN_OPTS="$(concat_lines "$MAVEN_PROJECTBASEDIR/.mvn/jvm.config") $MAVEN_OPTS"

# --- Wrapper Properties ---
MAVEN_WRAPPER_PROPERTIES="${MAVEN_PROJECTBASEDIR}/.mvn/wrapper/maven-wrapper.properties"
if [ -r "$MAVEN_WRAPPER_PROPERTIES" ]; then
  distributionUrl="$(sed -n 's/distributionUrl=//p' "$MAVEN_WRAPPER_PROPERTIES" | tr -d '\r')"
  distributionType="$(sed -n 's/distributionType=//p' "$MAVEN_WRAPPER_PROPERTIES" | tr -d '\r')"
  wrapperVersion="$(sed -n 's/wrapperVersion=//p' "$MAVEN_WRAPPER_PROPERTIES" | tr -d '\r')"
  wrapperUrl="$(sed -n 's/wrapperUrl=//p' "$MAVEN_WRAPPER_PROPERTIES" | tr -d '\r')"
  wrapperSha256Sum="$(sed -n 's/wrapperSha256Sum=//p' "$MAVEN_WRAPPER_PROPERTIES" | tr -d '\r')"
fi

# --- script mode ---
if [ "${distributionType-}" = "only-script" ]; then
  MAVEN_HOME="${HOME}/.m2/wrapper/dists/apache-maven-3.9.9-bin"

  if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
    verbose "Downloading Maven from: $distributionUrl"

    DOWNLOAD_DIR="$(mktemp -d)"
    DOWNLOAD_FILE="$DOWNLOAD_DIR/apache-maven-3.9.9-bin.zip"

    if command -v wget > /dev/null; then
      if [ "${MVNW_USERNAME-}" ] && [ "${MVNW_PASSWORD-}" ]; then
        wget --http-user="$MVNW_USERNAME" --http-password="$MVNW_PASSWORD" "$distributionUrl" -O "$DOWNLOAD_FILE" || die "failed to download $distributionUrl"
      else
        wget "$distributionUrl" -O "$DOWNLOAD_FILE" || die "failed to download $distributionUrl"
      fi
    elif command -v curl > /dev/null; then
      if [ "${MVNW_USERNAME-}" ] && [ "${MVNW_PASSWORD-}" ]; then
        curl -fsSL --user "$MVNW_USERNAME:$MVNW_PASSWORD" -o "$DOWNLOAD_FILE" "$distributionUrl" || die "failed to download $distributionUrl"
      else
        curl -fsSL -o "$DOWNLOAD_FILE" "$distributionUrl" || die "failed to download $distributionUrl"
      fi
    else
      die "ERROR: Neither wget nor curl found. Cannot download Maven distribution."
    fi

    mkdir -p "${HOME}/.m2/wrapper/dists"
    unzip -o -q "$DOWNLOAD_FILE" -d "${HOME}/.m2/wrapper/dists" || die "failed to unzip Maven distribution"

    # Rename extracted folder if needed
    for d in "${HOME}/.m2/wrapper/dists/apache-maven-"*; do
      if [ "$d" != "$MAVEN_HOME" ] && [ -d "$d" ]; then
        mv "$d" "$MAVEN_HOME"
        break
      fi
    done

    rm -rf "$DOWNLOAD_DIR"
    chmod +x "$MAVEN_HOME/bin/mvn"
  fi

  exec "$MAVEN_HOME/bin/mvn" "$@"
fi

# --- wrapper jar mode ---
wrapperJar="${MAVEN_PROJECTBASEDIR}/.mvn/wrapper/maven-wrapper.jar"

if [ -r "$wrapperJar" ]; then
  foundJavaHome="${JAVA_HOME-}"
  [ -z "$foundJavaHome" ] && foundJavaHome="$(java -XshowSettings:property -version 2>&1 | sed -n 's/.*java.home = //p')"
  exec "$JAVACMD" \
    $JVM_CONFIG_MAVEN_PROPS \
    ${MAVEN_OPTS-} \
    "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
    -classpath "$wrapperJar" \
    "-Dmaven.home=${foundJavaHome-}" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
else
  # Fallback: download wrapper jar
  if [ -z "${wrapperUrl-}" ]; then
    wrapperUrl="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/${wrapperVersion:-3.3.2}/maven-wrapper-${wrapperVersion:-3.3.2}.jar"
  fi

  verbose "Downloading from: $wrapperUrl"

  if command -v wget > /dev/null; then
    [ "${MVNW_VERBOSE-}" = true ] && QUIET="" || QUIET="--quiet"
    if [ "${MVNW_USERNAME-}" ] && [ "${MVNW_PASSWORD-}" ]; then
      wget $QUIET --http-user="$MVNW_USERNAME" --http-password="$MVNW_PASSWORD" "$wrapperUrl" -O "$wrapperJar" || die "failed to download $wrapperUrl"
    else
      wget $QUIET "$wrapperUrl" -O "$wrapperJar" || die "failed to download $wrapperUrl"
    fi
  elif command -v curl > /dev/null; then
    [ "${MVNW_VERBOSE-}" = true ] && QUIET="" || QUIET="--silent"
    if [ "${MVNW_USERNAME-}" ] && [ "${MVNW_PASSWORD-}" ]; then
      curl $QUIET --user "$MVNW_USERNAME:$MVNW_PASSWORD" -o "$wrapperJar" "$wrapperUrl" -f -L || die "failed to download $wrapperUrl"
    else
      curl $QUIET -o "$wrapperJar" "$wrapperUrl" -f -L || die "failed to download $wrapperUrl"
    fi
  else
    die "ERROR: could not find curl or wget to download Maven Wrapper"
  fi

  exec "$JAVACMD" \
    $JVM_CONFIG_MAVEN_PROPS \
    ${MAVEN_OPTS-} \
    "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
    -classpath "$wrapperJar" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
fi

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

verbose() {
  [ "${MVNW_VERBOSE-}" != true ] || printf '%s\n' "$1"
}

concat_lines() {
  if [ -f "$1" ]; then
    tr -s '\r\n' '  ' < "$1"
  fi
}
