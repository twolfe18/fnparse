#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=15G
#$ -l num_proc=1
#$ -S /bin/bash

# Compute all of the (target,span) features for all data, to be later producted with role/frame-roles at runtime.
# Produces a features file and an alphabet file.
# Wrapper around FeaturePrecomputation.java
# Designed for Propbank data.

set -eu

if [[ $# != 9 ]]; then
  echo "please provide:"
  echo "1) a working directory for output"
  echo "2) a jar file in a stable location"
  echo "3) what shard to work on"
  echo "4) how many shards"
  echo "5) a redis parse server hostname"
  echo "6) a redis parse server port"
  echo "7) a dataset, e.g. \"propbank\" or \"framenet\""
  echo "8) either \"true\" for role id features or \"false\" for frame id features"
  echo "9) a file suffix to control compression, e.g. \".gz\" or \".bz2\""
  echo "   script: $0"
  echo "   args: $@"
  exit 1
fi

WORKING_DIR=$1
JAR=$2
SHARD=$3
NUM_SHARDS=$4
PARSE_REDIS_SERVER=$5
PORT=$6
DATASET=$7
ROLE_MODE=$8
SUF=$9

# Must be set in environment
#FNPARSE_DATA=/export/projects/twolfe/fnparse-data/

echo "launching from `pwd` on $HOSTNAME at `date`"

maybe-module-load java/JDK_1.8.0_45

java -Xmx14G -ea -server -cp $JAR \
  -Dredis.host.propbankParses=$PARSE_REDIS_SERVER \
  -Dredis.port.propbankParses=$PORT \
  -Dredis.db.propbankParses=0 \
  -DworkingDir=$WORKING_DIR \
  -Dshard=$SHARD \
  -DnumShards=$NUM_SHARDS \
  -Ddataset=$DATASET \
  -Dsuffix=$SUF \
  -Ddata.framenet.root=$FNPARSE_DATA \
  -Ddata.wordnet=$FNPARSE_DATA/wordnet/dict \
  -Ddata.embeddings=$FNPARSE_DATA/embeddings \
  -Ddata.ontonotes5=$FNPARSE_DATA/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=$FNPARSE_DATA/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=$FNPARSE_DATA/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=false \
  edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation

