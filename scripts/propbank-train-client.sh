#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=13G
#$ -l num_proc=1
#$ -S /bin/bash

echo "running on $HOSTNAME"
echo "java: `java -version 2>&1`"
echo "java versions: `which -a java`"
echo "arguments: $@"

set -euo pipefail

# Will send an average to the param server every this many seconds
SAVE_INTERVAL=300

if [[ $# != 9 ]]; then
  echo "please provide"
  echo "1) a working directory"
  echo "2) redis server"
  echo "3) parameter server host"
  echo "4) parameter server port"
  echo "5) a job index between 0 and numShards-1"
  echo "6) numShards"
  #echo "7) a jar file"
  echo "7) a classpath with all dependencies"
  echo "8) a file containing the feature set"
  echo "9) feature mode, i.e. \"LOCAL\", \"ARG-LOCATION\", \"NUM-ARGS\", \"ROLE-COOC\", \"FULL\""
  exit -1
fi

WORKING_DIR=$1
PARSE_SERVER=$2
PARAM_SERVER_HOST=$3
PARAM_SERVER_PORT=$4
i=$5
NUM_SHARD=$6
#JAR=$7
CP=$7
FEATURES=$8
FEATURE_MODE=$9

#echo "copying jar file to the working directory:"
#echo "$JAR  =>  $WORKING_DIR/fnparse.jar"
#cp $JAR $WORKING_DIR/fnparse.jar
#cp $FEATURES $WORKING_DIR/features.txt

#CP=`find target/ -name '*.jar' | tr '\n' ':'`
#CP=$WORKING_DIR/fnparse.jar
echo "starting with CP=$CP"

java \
  -Ddata.wordnet=toydata/wordnet/dict \
  -Ddata.embeddings=data/embeddings \
  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=false \
  -XX:+UseSerialGC -Xmx12G -ea -server -cp ${CP} \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
  "workerJob$i" \
  workingDir ${WORKING_DIR} \
  parallelLearnDebug true \
  numClientsForParamAvg ${NUM_SHARD} \
  paramServerHost ${PARAM_SERVER_HOST} \
  paramServerPort ${PARAM_SERVER_PORT} \
  isParamServer false \
  numShards ${NUM_SHARD} \
  shard ${i} \
  paramAvgSecBetweenSaves ${SAVE_INTERVAL} \
  redis.host.propbankParses ${PARSE_SERVER} \
  redis.port.propbankParses 6379 \
  redis.db.propbankParses 0 \
  addStanfordParses false \
  realTestSet false \
  propbank true \
  l2Penalty 1e-8 \
  globalL2Penalty 1e-7 \
  secsBetweenShowingWeights 180 \
  trainTimeLimit 360 \
  featureSetFile ${FEATURES} \
  featureMode ${FEATURE_MODE} \
  featCoversFrames false

