#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=31G
#$ -l num_proc=3
#$ -S /bin/bash

set -eu

echo "starting at `date` on $HOSTNAME in `pwd`"
echo "args: $@"

if [[ $# != 13 ]]; then
  echo "1) a working directory (WD)"
  echo "2) a data directory (DD)"
  echo "3) propbank, i.e. either \"true\" or \"false\""
  echo "4) a dimension in [10, 20, 40, 80, 160, 320, 640]"
  echo "5) an oracle mode in [MIN, RAND_MIN, RAND_MAX, MAX]"
  echo "6) a beam size, e.g. [1, 4, 16, 64]"
  echo "7) force left right inference, i.e. either \"true\" or \"false\""
  echo "8) perceptron, i.e. either \"true\" or \"false\""
  echo "9) nTrain limit (0 means no limit)"
  echo "10) feature file"
  echo "11) feature mode"
  echo "12) what to set -Xmx in gigabytes, e.g. \"22\" -- you must set mem_free from above"
  echo "13) a jar file in a stable location"
  exit 1
fi

# WD is for output (should not be same level as DD)
# DD contains coherent-shards-filtered
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
NTRAIN=$9
FEATURES=${10}
FEATURE_MODE=${11}
MEM=${12}
JAR=${13}

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

#-DnumShards=1
#-Dshard=0

#-DcachedFeatures.bialph=$DD/coherent-shards-filtered/alphabet.txt.gz

#mvn compile exec:java -Dexec.mainClass=edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
java -Xmx${MEM}G -XX:+UseSerialGC -ea -server -cp $JAR \
  -DworkingDir=$WD \
  -DuseCachedFeatures=true \
  -DallowDynamicStopping=true \
  -Dperceptron=$PERCEPTRON \
  -DforceLeftRightInference=$FORCE_LEFT_RIGHT \
  -DoracleMode=$ORACLE_MODE \
  -Dpropbank=$PROPBANK \
  -DfeatureSetFile=${FEATURES} \
  -DfeatureMode=${FEATURE_MODE} \
  -DbeamSize=$BEAM_SIZE \
  -DtemplateCardinalityBug=true \
  -DcachedFeatures.numRoles=$NR \
  -DcachedFeatures.bialph=$DD/coherent-shards-filtered/alphabet.onlyTemplatesInFs.txt \
  -DcachedFeatures.bialph.lineMode=ALPH \
  -DcachedFeatures.featuresParent=$DD/coherent-shards-filtered/features \
  -DcachedFeatures.featuresGlob="glob:**/*" \
  -DcachedFeatures.numDataLoadThreads=2 \
  -DcachedFeatures.hashingTrickDim=`echo "2 * 1024 * 1024" | bc` \
  -DpretrainBatchSize=8 \
  -DtrainBatchSize=8 \
  -Dthreads=2 \
  -DnTrain=$NTRAIN \
  -DtemplatedFeatureParams.throwExceptionOnComputingFeatures=true \
  -DgradientBugfix=true \
  -DignoreNoNullSpanFeatures=true \
  -Ddata.wordnet=toydata/wordnet/dict \
  -Ddata.embeddings=data/embeddings \
  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=false \
  -DaddStanfordParses=false \
  -DrealTestSet=true \
  -Ddropout=false \
  -DlrBatchScale=2048 \
  -DlrType=constant \
  -Dl2Penalty=1e-8 \
  -DglobalL2Penalty=1e-7 \
  -DsecsBetweenShowingWeights=60 \
  -DtrainTimeLimit=`echo "2 * 15" | bc` \
  -DestimateLearningRateFreq=0 \
  -DfeatCoversFrames=false \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  dummyJobName \
  2>&1 | tee $LOG

echo "ret code: $?"
echo "done at `date`"


