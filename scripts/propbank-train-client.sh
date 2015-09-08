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

# Will send an average to the param server every this many seconds
if [[ $SAVE_INTERVAL == "" ]]; then
  SAVE_INTERVAL=120
fi

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
  echo "NOTE: You can run with RUN_WITH_MAVEN=true (which ignores classpath) or just leave it off (for java+classpath)"
  exit -1
fi

WORKING_DIR=$1
PARSE_SERVER=$2
PARAM_SERVER_HOST=$3
PARAM_SERVER_PORT=$4
i=$5
NUM_SHARD=$6
CP=$7
FEATURES=$8
FEATURE_MODE=$9

COMMAND="-Ddata.wordnet=toydata/wordnet/dict \
  -Ddata.embeddings=data/embeddings \
  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
  -DdisallowConcreteStanford=false \
  -DworkingDir=${WORKING_DIR} \
  -DparallelLearnDebug=true \
  -DnumClientsForParamAvg=${NUM_SHARD} \
  -DparamServerHost=${PARAM_SERVER_HOST} \
  -DparamServerPort=${PARAM_SERVER_PORT} \
  -DisParamServer=false \
  -DnumShards=${NUM_SHARD} \
  -Dshard=${i} \
  -DparamAvgSecBetweenSaves=${SAVE_INTERVAL} \
  -Dredis.host.propbankParses=${PARSE_SERVER} \
  -Dredis.port.propbankParses=6379 \
  -Dredis.db.propbankParses=0 \
  -DaddStanfordParses=false \
  -DrealTestSet=false \
  -Dpropbank=true \
  -Dl2Penalty=1e-8 \
  -DglobalL2Penalty=1e-7 \
  -DsecsBetweenShowingWeights=180 \
  -DtrainTimeLimit=360 \
  -DfeatureSetFile=${FEATURES} \
  -DfeatureMode=${FEATURE_MODE} \
  -DfeatCoversFrames=false"

if [[ $RUN_WITH_MAVEN == "true" ]]; then
  echo "Running with maven, no classpath needed..."
  COMMAND="mvn compile exec:java \
    -Dexec.mainClass=edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
    -Dexec.args=workerJob$i \
    $COMMAND"
  MAVEN_OPTS="-XX:+UseSerialGC -Xmx7G -ea -server" $COMMAND
else
  echo "Running with java, classpath: $CP"
  COMMAND="java $COMMAND -XX:+UseSerialGC -Xmx12G -ea -server -cp ${CP} \
    edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \"workerJob$i\""
  exec $COMMAND
fi

