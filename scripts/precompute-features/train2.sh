#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=31G
#$ -l num_proc=1
#$ -S /bin/bash

set -eu

echo "starting at `date` on $HOSTNAME in `pwd`"
echo "args: $@"
CPU=`grep -m 1 '^model name' /proc/cpuinfo | cut -d':' -f2 | xargs`
echo "running on an $CPU"

if [[ $# -lt 5 ]]; then
  echo "1) a working directory (WD) for output"
  echo "2) a data directory (DD) which contains coherent-shards-filtered-small"
  echo "3) dataset, i.e. either \"propbank\" or \"framenet\""
  echo "4) what to set -Xmx to, e.g. \"22G\" -- you must set mem_free from above"
  echo "5) a jar file in a stable location"
  echo "6+) any other ExperimentProperties key-value config pairs"
  exit 1
fi

WD=$1
DD=$2
DATASET=$3
MEM=$4
JAR=$5

shift 5

LOG=$WD/log.txt

# TODO Make this smaller for framenet?
NUM_MODEL_SHARDS=10

# TODO Remove and test this. This should be automatically
# derived inside CachedFeatures now.
if [[ $DATASET == "propbank" ]]; then
  NR=30
  PROPBANK=true
  MAX_EPOCH=2
  NUM_INST=115000
elif [[ $DATASET == "framenet" ]]; then
  NR=60
  PROPBANK=false
  MAX_EPOCH=20
  NUM_INST=3500
else
  echo "unknown dataset: $DATASET"
  exit 2
fi

DATA_HOME=/export/projects/twolfe/fnparse-data
TIME_LIMIT_MINS=`echo "36 * 60" | bc`
#TIME_LIMIT_MINS=50

NUM_IO_THREADS=2
NUM_LEARN_THREADS=$NUM_MODEL_SHARDS
NUM_THREAD=`echo "$NUM_IO_THREADS + $NUM_LEARN_THREADS" | bc`

#COMBINE_EVERY=`echo "$NUM_INST / $NUM_MODEL_SHARDS" | bc`   # Combine every epoch, conservative, what previous work does
COMBINE_EVERY=1000      # If network is not an issue (i.e. one machine), why not combine more often?
#COMBINE_EVERY=200      # TODO for debugging, remove
echo "COMBINE_EVERY=$COMBINE_EVERY"

#mvn compile exec:java -Dexec.mainClass=edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
java -Xmx${MEM} -XX:+UseSerialGC -ea -server -cp $JAR \
  -DworkingDir=$WD \
  -DuseCachedFeatures=true \
  -DallowDynamicStopping=false \
  -Dpropbank=$PROPBANK \
  -DmaxEpoch=$MAX_EPOCH \
  -DcachedFeatures.numRoles=$NR \
  -DcachedFeatures.bialph.lineMode=ALPH \
  -DcachedFeatures.bialph=$DD/coherent-shards-filtered-small/alphabet.txt \
  -DcachedFeatures.featuresParent=$DD/coherent-shards-filtered-small/features \
  -DcachedFeatures.featuresGlob="glob:**/*" \
  -DcachedFeatures.numDataLoadThreads=$NUM_IO_THREADS \
  -DstoppingScript=/home/hltcoe/twolfe/fnparse-build/fnparse/scripts/stop.sh \
  -DstoppingConditionFrequency=4 \
  -DpretrainBatchSize=$NUM_LEARN_THREADS \
  -DtrainBatchSize=$NUM_LEARN_THREADS \
  -Dthreads=$NUM_LEARN_THREADS \
  -DperceptronShards=$NUM_LEARN_THREADS \
  -DdistributedPerceptron.combineEvery=$COMBINE_EVERY \
  -DuseFModel=true \
  -DprimesFile=${DATA_HOME}/primes/primes1.byLine.txt.gz \
  -DtemplatedFeatureParams.throwExceptionOnComputingFeatures=true \
  -DignoreNoNullSpanFeatures=true \
  -Ddata.framenet.root=${DATA_HOME} \
  -Ddata.wordnet=${DATA_HOME}/wordnet/dict \
  -Ddata.embeddings=${DATA_HOME}/embeddings \
  -Ddata.ontonotes5=/export/common/data/corpora/LDC/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=${DATA_HOME}/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=${DATA_HOME}/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=false \
  -DaddStanfordParses=false \
  -DrealTestSet=true \
  -Ddropout=false \
  -DlrBatchScale=2048 \
  -DlrType=constant \
  -DsecsBetweenShowingWeights=120 \
  -DtrainTimeLimit=${TIME_LIMIT_MINS} \
  -DestimateLearningRateFreq=0 \
  -DfeatCoversFrames=false \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  dummyJobName $@

echo "ret code: $?"
echo "done at `date`"


