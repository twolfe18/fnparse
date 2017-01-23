#!/bin/bash

# Count the features associated with situations chosen by
# DependencySyntaxEvents.java

# NOTE: This used to be done via redis, and the old script name reflected that.

set -eu

#HOST=$1
#PORT=$2
#JAR=$3
JAR=$1

#WD=/export/projects/twolfe/sit-search/situation-feature-counts/redis-v2
#WD=/export/projects/twolfe/sit-search/situation-feature-counts/count-min-sketch-v2
WD=/export/projects/twolfe/sit-search/situation-feature-counts/count-min-sketch-v3
mkdir -p $WD

JAR_STABLE=$WD/fnparse.jar

#REDIS_BIN=/home/hltcoe/twolfe/redis/redis-3.0.2/src
#echo "Make sure you start a redis server first:"
#echo "from $HOST:"
#echo "$REDIS_BIN/redis-server --port $PORT --dir $WD"
#echo
echo "Copying JAR into stable place:"
echo "$JAR  ==>  $JAR_STABLE"
cp $JAR $JAR_STABLE
echo

#qsub -N 'sit-feat-freq-CAG' -b y -j y -o $WD \
#  -l "mem_free=4G,num_proc=1,h_rt=72:00:00" \
#  java -ea -cp $JAR_STABLE \
#    edu.jhu.hlt.ikbp.tac.DependencySyntaxEvents \
#      redis.host $HOST \
#      redis.port $PORT \
#      dataProvider simpleAccumulo:twolfe-cag1
#
#qsub -N 'sit-feat-freq-CAWiki' -b y -j y -o $WD \
#  -l "mem_free=4G,num_proc=1,h_rt=72:00:00" \
#  java -ea -cp $JAR_STABLE \
#    edu.jhu.hlt.ikbp.tac.DependencySyntaxEvents \
#      redis.host $HOST \
#      redis.port $PORT \
#      dataProvider simpleAccumulo:twolfe-cawiki-en1

qsub -cwd -N 'sit-feat-freq-CAG' -b y -j y -o $WD \
  -l "mem_free=4G,num_proc=1,h_rt=72:00:00" \
  java -ea -cp $JAR_STABLE \
    edu.jhu.hlt.ikbp.tac.DependencySyntaxEvents \
      dataProvider simpleAccumulo:twolfe-cag1 \
      output $WD/cag-cms.jser \
      modelFile $WD/cag-srl.jser

qsub -cwd -N 'sit-feat-freq-CAWiki' -b y -j y -o $WD \
  -l "mem_free=4G,num_proc=1,h_rt=72:00:00" \
  java -ea -cp $JAR_STABLE \
    edu.jhu.hlt.ikbp.tac.DependencySyntaxEvents \
      dataProvider simpleAccumulo:twolfe-cawiki-en1 \
      output $WD/cawiki-cms.jser \
      modelFile $WD/cawiki-srl.jser

