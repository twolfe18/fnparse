#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=31G
#$ -l num_proc=5
#$ -S /bin/bash

set -eu

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

if [[ $# != 7 ]]; then
  echo "please provide:"
  echo "1) a data directory which contains coherent-shards/, e.g. /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b"
  echo "2) propbank, i.e. either \"true\" or \"false\""
  echo "3) a working directory"
  echo "4) a feature file"
  echo "5) a feature mode, i.e. \"LOCAL\", \"ARG-LOCATION\", \"NUM-ARGS\", \"ROLE-COOC\", \"FULL\""
  echo "6) a jar file"
  echo "7) what to set -Xmx to, e.g. \"22G\" -- you must set mem_free from above"
  exit 1
fi

# Data directory
#DD=/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b
DD=$1
PROPBANK=$2

#FEATURES=scripts/having-a-laugh/propbank-20.fs
#FEATURE_MODE="LOCAL"  #"FULL"
FEATURES=$3
FEATURE_MODE=$4
#WD=/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b
WD=$5
JAR=$6
MEM=$7
#LOG=/state/partition1/rt.log
LOG=$WD/log.txt

ORACLE_MODE="RAND_MIN"

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
  -DoracleMode=$ORACLE_MODE \
  -DcachedFeatures.numRoles=$NR \
  -DcachedFeatures.bialph=$DD/coherent-shards/alphabet.txt.gz \
  -DcachedFeatures.bialph.lineMode=ALPH \
  -DcachedFeatures.featuresParent=$DD/coherent-shards/features \
  -DcachedFeatures.featuresGlob="glob:**/*" \
  -DcachedFeatures.numDataLoadThreads=2 \
  -DpretrainBatchSize=8 \
  -DtrainBatchSize=8 \
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
  -DrealTestSet=false \
  -Dpropbank=$PROPBANK \
  -Dl2Penalty=1e-8 \
  -DglobalL2Penalty=1e-7 \
  -DsecsBetweenShowingWeights=60 \
  -DtrainTimeLimit=600 \
  -DestimateLearningRateFreq=0 \
  -DlrType=constant \
  -DfeatureSetFile=${FEATURES} \
  -DfeatureMode=${FEATURE_MODE} \
  -DfeatCoversFrames=false \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  dummyJobName \
  2>&1 | tee $LOG

echo "ret code: $?"
echo "done at `date`"


