#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=13G
#$ -l num_proc=1
#$ -o logging/propbank
#$ -S /bin/bash

echo "running on $HOSTNAME"
echo "java: `java -version 2>&1`"
echo "java versions: `which -a java`"
echo "arguments: $@"

set -euo pipefail

CP=`find target/ -name '*.jar' | tr '\n' ':'`

NUM_SHARD=10
SAVE_INTERVAL=600

if [[ $# != 4 ]]; then
  echo "please provide"
  echo "1) a working directory"
  echo "2) redis server"
  echo "3) parameter server"
  echo "4) a job index between 0 and numShards-1"
  exit -1
fi

WORKING_DIR=$1
REDIS_SERVER=$2
PARAM_SERVER=$3
i=$4

echo "starting with CP=$CP"

java -Xmx12G -ea -server -cp ${CP} \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  "workerJob$i" \
  workingDir ${WORKING_DIR} \
  parallelLearnDebug true \
  numClientsForParamAvg ${NUM_SHARD} \
  paramServerHost ${PARAM_SERVER} \
  isParamServer false \
  numShards ${NUM_SHARD} \
  shard ${i} \
  paramAvgSecBetweenSaves ${SAVE_INTERVAL} \
  propbankParseRedisHost ${REDIS_SERVER} \
  propbankParseRedisPort 6379 \
  propbankParseRedisDb 0 \
  addStanfordParses false \
  realTestSet false \
  propbank true \
  laptop false \
  nTrain 5000 \
  l2Penalty 1e-8 \
  globalL2Penalty 1e-7 \
  secsBetweenShowingWeights 180 \
  trainTimeLimit 360 \
  featCoversFrames false \
  useGlobalFeatures True \
  globalFeatArgLocSimple True \
  globalFeatNumArgs True \
  globalFeatRoleCoocSimple True \
  globalFeatArgOverlap True \
  globalFeatSpanBoundary True

