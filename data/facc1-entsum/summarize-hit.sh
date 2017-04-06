#!/bin/bash

set -eu

ENTITY_DIR_PARENT=$1
JAR=$2

for SUM in `find $ENTITY_DIR_PARENT -type d -name 'summary'`; do
ENTITY_DIR=`dirname $SUM`
for SUM_N in `find $SUM -type d -name 'w*'`; do
echo "$SUM_N $ENTITY_DIR"
java -cp $JAR -ea -server -Xmx4G \
  edu.jhu.hlt.entsum.Summary \
    entityDir $ENTITY_DIR \
    wTag `basename $SUM_N`
done
done


