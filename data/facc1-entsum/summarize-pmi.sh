#!/bin/bash

set -eu

ENTITY_DIR_PARENT=$1
PMI_FEAT_PARENT=$2
JAR=$3

#export GUROBI_HOME=/home/hltcoe/twolfe/gurobi/gurobi702/linux64
#export LD_LIBRARY_PATH=/home/hltcoe/twolfe/gurobi/gurobi702/linux64/lib
#export GRB_LICENSE_FILE=/home/hltcoe/twolfe/gurobi/keys/gurobi.lic
#export HOSTNAME="caligula"

# 8 summary types * 4 summary lengths * (100+100) dev+test entities = 3200 jobs * 1 min/job = 53 hours
for PARSE in `find $ENTITY_DIR_PARENT -name 'parse.conll'`; do
ENT_DIR=`dirname $PARSE`
mkdir -p $ENT_DIR/summary

for E in "true" "false"; do
for S in "true" "false"; do
for W in "none" "data/facc1-entsum/code-testing-data/bigram-counts.nhash11-logb22.jser"; do

if [[ $E == "false" && $S == "false" && $W == "none" ]]; then
continue;
fi

java -ea -server -cp $JAR edu.jhu.hlt.entsum.SlotsAsConcepts \
  mode summarize \
  pmiFiles "$PMI_FEAT_PARENT/**/mi-withNeg-entityInst-shard*-of16.txt" \
  entityDir $ENT_DIR \
  outputDir $ENT_DIR/summary \
  entities $E \
  slots $S \
  ngrams $W \
  numWords "40,80,160,320"
done
done
done
done

