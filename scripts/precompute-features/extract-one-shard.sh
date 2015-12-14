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

set -e

if [[ $# != 7 ]]; then
  echo "please provide:"
  echo "1) a working directory for output"
  echo "2) a jar file in a stable location"
  echo "3) what shard to work on"
  echo "4) how many shards"
  echo "5) a redis parse server"
  echo "6) a dataset, e.g. \"propbank\" or \"framenet\""
  echo "7) a file suffix to control compression, e.g. \".gz\" or \".bz2\""
  echo "   script: $0"
  echo "   args: $@"
  exit 1
fi

WORKING_DIR=$1
JAR=$2
SHARD=$3
NUM_SHARDS=$4
PARSE_REDIS_SERVER=$5
DATASET=$6
SUF=$7

FNPARSE_DATA=/export/projects/twolfe/fnparse-data/

echo "launching from `pwd` on $HOSTNAME at `date`"

java -Xmx14G -ea -server -cp $JAR \
  -Dredis.host.propbankParses=$PARSE_REDIS_SERVER \
  -Dredis.port.propbankParses=6379 \
  -Dredis.db.propbankParses=0 \
  -DworkingDir=$WORKING_DIR \
  -Dshard=$SHARD \
  -DnumShards=$NUM_SHARDS \
  -Ddataset=$DATASET \
  -Dsuffix=$SUF \
  -Ddata.framenet.root=$FNPARSE_DATA \
  -Ddata.wordnet=$FNPARSE_DATA/wordnet/dict \
  -Ddata.embeddings=$FNPARSE_DATA/embeddings \
  -Ddata.ontonotes5=/export/common/data/corpora/LDC/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=$FNPARSE_DATA/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=$FNPARSE_DATA/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=false \
  edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation

