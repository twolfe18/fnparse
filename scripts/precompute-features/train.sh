#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=31G
#$ -l num_proc=4
#$ -S /bin/bash

set -eu

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

if [[ $# != 12 ]]; then
  echo "1) a working directory (WD)"
  echo "2) a data directory (DD)"
  echo "3) propbank, i.e. either \"true\" or \"false\""
  echo "4) a dimension in [10, 20, 40, 80, 160, 320, 640]"
  echo "5) an oracle mode in [MIN, RAND_MIN, RAND_MAX, MAX]"
  echo "6) a beam size, e.g. [1, 4, 16, 64]"
  echo "7) force left right inference, i.e. either \"true\" or \"false\""
  echo "8) perceptron, i.e. either \"true\" or \"false\""
  echo "9) feature file"
  echo "10) feature mode"
  echo "11 what to set -Xmx in gigabytes, e.g. \"22\" -- you must set mem_free from above"
  echo "12) a jar file in a stable location"
  exit 1
fi

# WD is for output (should not be same level as DD)
# DD contains coherent-shards
#FEATURES=scripts/having-a-laugh/propbank-20.fs
#FEATURE_MODE="LOCAL"  #"FULL"

WD=$1
DD=$2
PROPBANK=$3
DIM=$4            # Not actually needed
ORACLE_MODE=$5
BEAM_SIZE=$6
FORCE_LEFT_RIGHT=$7
PERCEPTRON=$8
FEATURES=$9
FEATURE_MODE=${10}
MEM=${11}
JAR=${12}

LOG=$WD/log.txt

# TODO Remove and test this. This should be automatically
# derived inside CachedFeatures now.
if [[ $PROPBANK == "true" ]]; then
  NR=30
elif [[ $PROPBANK == "false" ]]; then
  NR=60
else
  echo "need boolean: $PROPBANK"
  exit 2
fi

#mvn compile exec:java -Dexec.mainClass=edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
java -Xmx${MEM}G -XX:+UseSerialGC -ea -server -cp $JAR \
  -DworkingDir=$WD \
  -DuseCachedFeatures=true \
  -DallowDynamicStopping=false \
  -Dperceptron=$PERCEPTRON \
  -DforceLeftRightInference=$FORCE_LEFT_RIGHT \
  -DoracleMode=$ORACLE_MODE \
  -Dpropbank=$PROPBANK \
  -DfeatureSetFile=${FEATURES} \
  -DfeatureMode=${FEATURE_MODE} \
  -DbeamSize=$BEAM_SIZE \
  -DcachedFeatures.numRoles=$NR \
  -DcachedFeatures.bialph=$DD/coherent-shards/alphabet.txt.gz \
  -DcachedFeatures.bialph.lineMode=ALPH \
  -DcachedFeatures.featuresParent=$DD/coherent-shards/features \
  -DcachedFeatures.featuresGlob="glob:**/*" \
  -DcachedFeatures.numDataLoadThreads=1 \
  -DpretrainBatchSize=16 \
  -DtrainBatchSize=16 \
  -Dthreads=4 \
  -DtemplatedFeatureParams.throwExceptionOnComputingFeatures=true \
  -DgradientBugfix=true \
  -DignoreNoNullSpanFeatures=true \
  -Ddata.wordnet=toydata/wordnet/dict \
  -Ddata.embeddings=data/embeddings \
  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=true \
  -DnumShards=1 \
  -Dshard=0 \
  -DaddStanfordParses=false \
  -DrealTestSet=true \
  -DlrBatchScale=2048 \
  -DlrType=constant \
  -Dl2Penalty=1e-8 \
  -DglobalL2Penalty=1e-7 \
  -DsecsBetweenShowingWeights=60 \
  -DtrainTimeLimit=`echo "52 * 60" | bc` \
  -DestimateLearningRateFreq=0 \
  -DfeatCoversFrames=false \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  dummyJobName \
  2>&1 | tee $LOG

echo "ret code: $?"
echo "done at `date`"


