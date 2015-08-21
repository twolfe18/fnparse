#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=3G
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

if [[ $# != 1 ]]; then
  echo "please provide a working directory"
  exit -1
fi

WORKING_DIR=$1

java -XX:+UseSerialGC -Xmx3G -ea -server -cp ${CP} \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  parameterServerJob \
  workingDir ${WORKING_DIR} \
  parallelLearnDebug true \
  numClientsForParamAvg ${NUM_SHARD} \
  paramServerHost localhost \
  isParamServer true \
  paramAvgSecBetweenSaves ${SAVE_INTERVAL} \
  addStanfordParses false \
  realTestSet false \
  propbank true \
  laptop false \
  nTrain 8000 \
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

