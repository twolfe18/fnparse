#!/bin/bash

set -eu

JAR=$1

#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate
#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_mostFreq
WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_mostFreq_2M
mkdir -p $WD

JAR_STABLE=$WD/fnparse.jar
cp $JAR $JAR_STABLE

# 25M string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 1GB
# 2M string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 80MB

for NB in 18 20 22 24; do
qsub -N "fce$NB" -cwd -l "num_proc=1,mem_free=5G,h_rt=96:00:00" -b y -j y -o $WD \
  java -server -ea -cp $JAR_STABLE edu.jhu.hlt.ikbp.tac.FeatureCardinalityEstimator \
    nBits $NB \
    output $WD/fce-${NB}bitSig.jser \
    interval 120 \
    nMostFrequent 2000000
done

