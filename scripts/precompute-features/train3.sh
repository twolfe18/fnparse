#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=31G
#$ -l num_proc=8
#$ -S /bin/bash
#$ -q intel.q

set -eu

echo "starting at `date` on $HOSTNAME in `pwd`"
echo "args: $@"
CPU=`grep -m 1 '^model name' /proc/cpuinfo | cut -d':' -f2 | xargs`
echo "running on an $CPU"

if [[ $# -lt 5 ]]; then
  echo "1) a working directory (WD) for output"
  echo "2) a data directory (DD) which contains (train|dev|test).jser.gz"
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

echo "WD=$WD"
echo "DD=$DD"
echo "DATASET=$DATASET"
echo "MEM=$MEM"
echo "JAR=$JAR"

EVAL_DIR=$WD/evaluation
mkdir -p $EVAL_DIR

# TODO Remove and test this. This should be automatically
# derived inside CachedFeatures now.
if [[ $DATASET == "propbank" ]]; then
  NR=30
  PROPBANK=true
  NUM_INST=115000
elif [[ $DATASET == "framenet" ]]; then
  NR=60
  PROPBANK=false
  NUM_INST=3500
else
  echo "unknown dataset: $DATASET"
  exit 2
fi

DATA_HOME=/export/projects/twolfe/fnparse-data
K_PERC_AVG=`echo "3 * $NUM_INST" | bc`
echo "K_PERC_AVG=$K_PERC_AVG"

java -XX:+UseNUMA -XX:+UseSerialGC -Xmx$MEM -server -cp $JAR \
  edu.jhu.hlt.fnparse.rl.rerank.ShimModel \
  primesFile ${DATA_HOME}/primes/primes1.byLine.txt.gz \
  propbank $PROPBANK \
  threads 1 \
  noApproxAfterEpoch 0 \
  maxEpoch 10 \
  evalOutputDir $EVAL_DIR \
  conll2005srlEval /export/projects/twolfe/fnparse-data/srl-eval.pl \
  semaforEval.scriptDir /export/projects/twolfe/fnparse-data \
  data.framenet.root ${DATA_HOME} \
  data.wordnet ${DATA_HOME}/wordnet/dict \
  data.embeddings ${DATA_HOME}/embeddings \
  data.ontonotes5 /export/common/data/corpora/LDC/LDC2013T19/data/files/data/english/annotations \
  data.propbank.conll ${DATA_HOME}/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  data.propbank.frames ${DATA_HOME}/ontonotes-release-5.0-fixed-frames/frames \
  trainData $DD/train.jser.gz \
  devData $DD/dev.jser.gz \
  testData $DD/test.jser.gz \
  $@ 2>&1 | tee $LOG


echo "ret code: $?"
echo "done at `date`"

