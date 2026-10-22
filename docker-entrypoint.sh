#!/bin/bash
set -Ee

# If the environment variable `USE_SYSTEM_CA_CERTS` is set, the Docker container will automatically import
# mounted private or self-signed certificates during startup.
# The value of this variable can be any character or word. For example: 1, yes, true, YES, TRUE.
# Private or self-signed certificates must be mounted into the container in the /certificates/ directory.
if [ -x /__cacert_entrypoint.sh ]; then
  (/__cacert_entrypoint.sh)
fi

# Check if DEBUG_OPTS is set, if not, set it to an empty string
DEBUG_OPTS=${DEBUG_OPTS:-}

# Check if JAVA_OPTS is set, if not, set it to an empty string
JAVA_OPTS=${JAVA_OPTS:-}

# JVM flags required by Arrow's off-heap allocator on JDK 24+ (see docs/configuration.md):
#  --add-opens             reflective access to java.nio for Arrow's allocator
#  --sun-misc-unsafe...    legacy Unsafe memory access (JEP 498)
#  --enable-native-access  Netty's native-library loader (JEP 472); avoids the startup warning
REQUIRED_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED"

# Execute the Java application with the provided options, passing through any container CMD arguments
exec java $REQUIRED_OPTS $DEBUG_OPTS $JAVA_OPTS -jar app.jar "$@"
