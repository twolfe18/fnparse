#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=3G
#$ -l num_proc=1
#$ -S /bin/bash

echo "running on $HOSTNAME"
echo "java: `java -version 2>&1`"
echo "java versions: `which -a java`"
echo "arguments: $@"

# Will save a model to disk every this many seconds
if [[ $SAVE_INTERVAL == "" ]]; then
  SAVE_INTERVAL=600
fi

if [[ $# != 3 ]]; then
  echo "please provide:"
  echo "1) a working directory"
  echo "2) a classpath with all dependencies"
  echo "3) a port to listen on"
  echo "NOTE: You can run with RUN_WITH_MAVEN=true (which ignores classpath) or just leave it off (for java+classpath)"
  exit -1
fi

WORKING_DIR=$1
CP=$2
PORT=$3

COMMAND="-DworkingDir=${WORKING_DIR} \
  -DparallelLearnDebug=true \
  -DparamServerHost=localhost \
  -DparamServerPort=${PORT} \
  -DisParamServer=true \
  -DparamAvgSecBetweenSaves=${SAVE_INTERVAL} \
  -DaddStanfordParses=false \
  -DrealTestSet=false \
  -Dpropbank=true \
  -Dl2Penalty=1e-8 \
  -DglobalL2Penalty=1e-7 \
  -DsecsBetweenShowingWeights=180"

if [[ $RUN_WITH_MAVEN == "true" ]]; then
  echo "Running with maven, no classpath needed..."
  COMMAND="mvn compile exec:java \
    -Dexec.mainClass=edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer \
    -Dexec.args=parameterServerJob \
    $COMMAND"
  MAVEN_OPTS="-XX:+UseSerialGC -Xmx3G -ea -server" $COMMAND
else
  echo "Running with java, classpath: $CP"
  COMMAND="java $COMMAND -XX:+UseSerialGC -Xmx5G -ea -server -cp ${CP} \
    edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer parameterServerJob"
  exec $COMMAND
fi

