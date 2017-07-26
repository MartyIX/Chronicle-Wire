#!/bin/bash

echo "Build Chronicle-Bytes"
(
    cd ../Chronicle-Bytes
    ./build9.sh
)

echo "Build Chronicle-Wire"

# Linux
export JAVA_HOME=/home/marty/work/programs/jdk-9-ea
export PATH=${JAVA_HOME}/bin:/home/marty/work/programs/apache-maven-3.5.0/bin:$PATH

# Win
#export JAVA_HOME=/c/Work/Programs/jdk-9.178
#export PATH=${JAVA_HOME}/bin:/c/Work/Programs/apache-maven-3.5.0/bin:$PATH

java -version

#mvn -Dmaven.compiler.fork=true -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.wire.map.MapWireTest clean install
#mvn -Dmaven.compiler.fork=false -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.wire.BinaryWireTest clean install
mvn -Dmaven.compiler.fork=true -Dmaven.javadoc.skip=true -Dsurefire.useFile=false -DtrimStackTrace=false clean install

