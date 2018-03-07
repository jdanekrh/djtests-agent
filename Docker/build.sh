#!/usr/bin/env bash

DIR=$(dirname "$(readlink -f "$0")")
SRCDIR=${DIR}/..

pushd ${SRCDIR}
    ./gradlew generateProto generateGrammarSource build
    docker build -t djtests-broker -f Docker/broker/Dockerfile ${SRCDIR}
    docker build -t djtests-clients -f Docker/clients/Dockerfile ${SRCDIR}
popd