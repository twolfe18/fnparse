#!/bin/bash

# Scans the t2f table to estimate the tokenization and communication
# frequency for every triage (entity) feature.

set -eu

JAR=$1

#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate
#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_mostFreq
#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_mostFreq_2M
#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_maxMin
#WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_maxMin_stable
WD=/export/projects/twolfe/sit-search/feature-cardinality-estimate_byNerType
mkdir -p $WD

JAR_STABLE=$WD/fnparse.jar
cp $JAR $JAR_STABLE

# 25M string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 1GB
# 2M string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 80MB
# 1M string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 40MB
# 250K string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 10MB
# 100K string * 10 chars * 2 bytes/char * 2 (hashmap,est) = 4MB

#for MF in 100000 1000000; do
#for NHASH in 8 12; do
#for LOGB in 16 20; do
#qsub -N "fce-$MF-$NHASH-$LOGB" -cwd -l "num_proc=1,mem_free=5G,h_rt=96:00:00" -b y -j y -o $WD \
#  java -server -ea -cp $JAR_STABLE edu.jhu.hlt.ikbp.tac.FeatureCardinalityEstimator \
#    output $WD/fce-mostFreq${MF}-nhash${NHASH}-logb${LOGB}.jser \
#    interval 240 \
#    logBuckets $LOGB \
#    numHash $NHASH \
#    nMostFrequent $MF
#done
#done
#done

for NS in twolfe-cag1 twolfe-cawiki-en1; do
NHASH=8
LOGB=16
WDD=$WD/nhash$NHASH-logb$LOGB-$NS
mkdir -p $WDD
qsub -N "fce-$NHASH-$LOGB" -cwd -l "num_proc=1,mem_free=7G,h_rt=96:00:00" -b y -j y -o $WDD \
  java -Xmx6G -server -ea -cp $JAR_STABLE edu.jhu.hlt.ikbp.tac.FeatureCardinalityEstimator \
    namespace $NS outputDir $WDD logBuckets $LOGB numHash $NHASH

NHASH=10
LOGB=19
WDD=$WD/nhash$NHASH-logb$LOGB-$NS
mkdir -p $WDD
qsub -N "fce-$NHASH-$LOGB" -cwd -l "num_proc=1,mem_free=7G,h_rt=96:00:00" -b y -j y -o $WDD \
  java -Xmx6G -server -ea -cp $JAR_STABLE edu.jhu.hlt.ikbp.tac.FeatureCardinalityEstimator \
    namespace $NS outputDir $WDD logBuckets $LOGB numHash $NHASH

NHASH=12
LOGB=20
WDD=$WD/nhash$NHASH-logb$LOGB-$NS
mkdir -p $WDD
qsub -N "fce-$NHASH-$LOGB" -cwd -l "num_proc=1,mem_free=7G,h_rt=96:00:00" -b y -j y -o $WDD \
  java -Xmx6G -server -ea -cp $JAR_STABLE edu.jhu.hlt.ikbp.tac.FeatureCardinalityEstimator \
    namespace $NS outputDir $WDD logBuckets $LOGB numHash $NHASH

done

