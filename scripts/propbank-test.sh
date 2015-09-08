#!/bin/bash

# For every (FS_MODE, FS_SIZE),
# 1) compute error on the dev set
# 2) compute error on the test set
# Does this by qsubbing two jobs which dump into a log file (to be grepped out later).

# NOTE: The reason that I'm going this separately (as opposed to the dev set evaluation
# that is done already in train-client) is because each of the clients will not get to
# the prediction stage for a long time.

set -e

if [[ $# != 7 ]]; then
  echo "Please provide:"
  echo "1) a feature set mode, i.e. \"LOCAL\", \"ARG-LOCATION\", \"NUM-ARGS\", \"ROLE-COOC\", \"FULL\""
  echo "2) a feature set size, e.g. 30"
  echo "3) a working directory for output"
  echo "4) a parse (redis) server"
  echo "5) a jar file with all dependencies"
  echo "6) a base directory where training occurred, e.g. experiments/final-results/propbank/sep2a"
  echo "7) where to run the job (i.e. \"grid\" for qsub or \"local\" the current machine)"
  exit -1
fi

FS_MODE=$1
FS_SIZE=$2
WD=$3
PARSE_SERVER=$4
JAR=$5
BASE=$6
MODE=$7

if [[ $MODE != "grid" && $MODE != "local" ]]; then
  echo "Illegal mode: $MODE"
  exit -2
fi

ID=`echo "$FS_MODE-$FS_SIZE" | shasum | awk '{print substr($1, 0, 8)}'`

RESULTS_DIR=${BASE}/wd-${FS_MODE}-${FS_SIZE}

if [[ ! -d $RESULTS_DIR ]]; then
  echo "Could not find results directory: $RESULTS_DIR"
  exit 1
else
  echo "Using results from: $RESULTS_DIR"
fi

if [[ ! -f $WD ]]; then
  echo "couldn't find working directory: $WD"
  exit 5
fi

JAR_STABLE=$WD/fnparse.jar
echo "Copying jar to a safe place"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE
CP=$JAR_STABLE


PARAMS_FILE=`find $RESULTS_DIR/server/paramAverages -type f | tail -n 1`

if [[ ! -f $PARAMS_FILE ]]; then
  echo "Could not find params file: $PARAMS_FILE"
  exit 2
else
  echo "Using param file: $PARAMS_FILE"
fi

FEATURES=experiments/feature-information-gain/feature-sets/fs-$FS_SIZE.txt

if [[ ! -f $FEATURES ]]; then
  echo "Could not find feature file: $FEATURES"
  exit 3
else
  echo "Using feature file: $FEATURES"
fi


# If I provide a model training will not occur. So just do realTestSet=false then realTestSet=true.

### DEV ###
echo "Launching dev set performance job..."
DEV_WD=$WD/dev-perf
mkdir -p $DEV_WD
COMMAND="java \
    -Ddata.wordnet=toydata/wordnet/dict \
    -Ddata.embeddings=data/embeddings \
    -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
    -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
    -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
    -DdisallowConcreteStanford=false \
    -XX:+UseSerialGC -Xmx12G -ea -server -cp ${CP} \
    edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
    \"devPerf-$ID\" \
    workingDir ${DEV_WD} \
    redis.host.propbankParses ${PARSE_SERVER} \
    redis.port.propbankParses 6379 \
    redis.db.propbankParses 0 \
    addStanfordParses false \
    realTestSet false \
    propbank true \
    featureSetFile ${FEATURES} \
    featureMode ${FS_MODE} \
    featCoversFrames false \
    loadParamsFromFile ${PARAMS_FILE}"
if [[ $MODE == "grid" ]]; then
  COMMAND="qsub -N \"devPerf-$ID\" -q all.q \
    -cwd -j y -b y -V -l \"num_proc=1,mem_free=13G,h_rt=72:00:00\" \
    -o $DEV_WD \
    $COMMAND"
else
  COMMAND="$COMMAND 2>&1 | tee $DEV_WD/log.txt"
fi
eval $COMMAND || exit 1


### TEST ###
echo "Launching test set performance job..."
TEST_WD=$WD/test-perf
mkdir -p $TEST_WD
COMMAND="java \
    -Ddata.wordnet=toydata/wordnet/dict \
    -Ddata.embeddings=data/embeddings \
    -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
    -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
    -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
    -DdisallowConcreteStanford=false \
    -XX:+UseSerialGC -Xmx12G -ea -server -cp ${CP} \
    edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
    \"testPerf-$ID\" \
    workingDir ${TEST_WD} \
    redis.host.propbankParses ${PARSE_SERVER} \
    redis.port.propbankParses 6379 \
    redis.db.propbankParses 0 \
    addStanfordParses false \
    realTestSet true \
    propbank true \
    featureSetFile ${FEATURES} \
    featureMode ${FS_MODE} \
    featCoversFrames false \
    loadParamsFromFile ${PARAMS_FILE}"
if [[ $MODE == "grid" ]]; then
  COMMAND="qsub -N \"testPerf-$ID\" -q all.q \
    -cwd -j y -b y -V -l \"num_proc=1,mem_free=13G,h_rt=72:00:00\" \
    -o $TEST_WD \
    $COMMAND"
else
  COMMAND="$COMMAND 2>&1 | tee $TEST_WD/log.txt"
fi
eval $COMMAND || exit 2



