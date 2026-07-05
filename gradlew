#!/usr/bin/env sh

# Ensure we have a working dir
APP_BASE_NAME=`basename "$0"`
APP_HOME=`dirname "$0"`
APP_HOME=`cd "$APP_HOME" && pwd`

# Add standard Gradle execution paths
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec java -Xmx2048m -Dfile.encoding=UTF-8 -jar "$CLASSPATH" "$
@"
